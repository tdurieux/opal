/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2017
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.opalj
package fpcf
package par

import java.lang.System.identityHashCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReferenceArray

import scala.reflect.runtime.universe.Type
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.AnyRefMap
import scala.collection.mutable
import scala.collection.{Map ⇒ SomeMap}

import org.opalj.graphs
import org.opalj.collection.mutable.AnyRefArrayStack
import org.opalj.log.LogContext
import org.opalj.log.OPALLogger.info
import org.opalj.log.OPALLogger.{debug ⇒ trace}
import org.opalj.log.OPALLogger.error
import org.opalj.fpcf.PropertyKey.fallbackPropertyBasedOnPkId
import org.opalj.fpcf.PropertyKey.fastTrackPropertyBasedOnPkId
import org.opalj.fpcf.PropertyKind.SupportedPropertyKinds
import org.opalj.concurrent.NumberOfThreadsForCPUBoundTasks
import org.opalj.log.GlobalLogContext

/**
 * A concurrent implementation of the property store which parallels the execution of the scheduled
 * computations.
 *
 * Entities are stored after computation.
 *
 * ==Implementation==
 * The idea is to use one specific thread (the `store updates thread) for processing updates
 * to the store. This enables us to avoid any synchronization w.r.t. updating the
 * depender/dependee relations.
 *
 * For processing the scheduled computations we use
 * `NumberOfThreadsForProcessingPropertyComputations` threads.
 *
 * @author Michael Eichberg
 */
final class PKEParallelTasksPropertyStore private (
        val ctx:                                              Map[Type, AnyRef],
        val NumberOfThreadsForProcessingPropertyComputations: Int,
        val tracer:                                           Option[PropertyStoreTracer]
)(
        implicit
        val logContext: LogContext
) extends PropertyStore {
    store ⇒

    // --------------------------------------------------------------------------------------------
    //
    // CAPABILITIES
    //
    // --------------------------------------------------------------------------------------------

    final def supportsFastTrackPropertyComputations: Boolean = true

    // --------------------------------------------------------------------------------------------
    //
    // STATISTICS
    //
    // --------------------------------------------------------------------------------------------

    // Tasks are allowed to schedule further tasks... therefore, the scheduled tasks counter
    // has to be thread-safe.
    private[this] val scheduledTasksCounter: AtomicInteger = new AtomicInteger(0)
    def scheduledTasksCount: Int = scheduledTasksCounter.get

    private[this] val directInTaskThreadPropertyComputationsCounter: AtomicInteger = new AtomicInteger(0)
    def directInTaskThreadPropertyComputationsCount: Int = directInTaskThreadPropertyComputationsCounter.get

    // Fast-track properties are eagerly computed in the thread requiring the values
    // and are stored using idempotent results
    private[this] val fastTrackPropertiesCounter: AtomicInteger = new AtomicInteger(0)
    def fastTrackPropertiesCount: Int = fastTrackPropertiesCounter.get

    @volatile private[this] var redundantIdempotentResultsCounter = 0
    def redundantIdempotentResultsCount: Int = redundantIdempotentResultsCounter

    @volatile private[this] var uselessPartialResultComputationCounter = 0
    def uselessPartialResultComputationCount: Int = uselessPartialResultComputationCounter

    private[this] var scheduledLazyTasksCounter = 0
    def scheduledLazyTasksCount: Int = scheduledLazyTasksCounter

    private[this] val fallbacksUsedCounter: AtomicInteger = new AtomicInteger(0)
    def fallbacksUsedCount: Int = fallbacksUsedCounter.get

    @volatile private[this] var scheduledOnUpdateComputationsCounter = 0
    def scheduledOnUpdateComputationsCount: Int = scheduledOnUpdateComputationsCounter

    @volatile private[this] var scheduledDependeeUpdatesCounter = 0
    /** Computations of dependees which are scheduled immediately. */
    def scheduledDependeeUpdatesCount: Int = scheduledDependeeUpdatesCounter

    @volatile private[this] var directDependerOnUpdateComputationsCounter = 0
    /** Computations which are executed immediately and which are not scheduled. */
    def directDependerOnUpdateComputationsCount: Int = directDependerOnUpdateComputationsCounter

    @volatile private[this] var directDependeeUpdatesCounter = 0
    def directDependeeUpdatesCount: Int = directDependeeUpdatesCounter

    def immediateOnUpdateComputationsCount: Int = {
        directDependeeUpdatesCounter + scheduledDependeeUpdatesCounter
    }

    private[this] val maxTasksQueueSize: AtomicInteger = new AtomicInteger(-1)

    private[this] var updatesCounter = 0
    private[this] var oneStepFinalUpdatesCounter = 0

    @volatile private[this] var resolvedCSCCsCounter = 0
    def resolvedCSCCsCount: Int = resolvedCSCCsCounter

    @volatile private[this] var quiescenceCounter = 0
    def quiescenceCount: Int = quiescenceCounter

    def statistics: SomeMap[String, Int] = {
        mutable.LinkedHashMap(
            "scheduled tasks" -> scheduledTasksCount,
            "scheduled lazy tasks (fast track computations of lazy properties are not counted)" -> scheduledLazyTasksCount,
            "max tasks queue size" -> maxTasksQueueSize.get,
            "fast-track properties computations" -> fastTrackPropertiesCount,
            "computations of fallback properties (queried but not computed properties)" -> fallbacksUsedCount,
            "property store updates" -> updatesCounter,
            "computations which in one step computed a final result" -> oneStepFinalUpdatesCounter,
            "redundant fast-track/fallback property computations" -> redundantIdempotentResultsCount,
            "useless partial result computations" -> uselessPartialResultComputationCount,

            "scheduled reevaluation of dependees due to updated dependers" -> scheduledDependeeUpdatesCount,

            "direct in task thread property computations (cheap property computation or tasks queue is full enough)" -> directInTaskThreadPropertyComputationsCount,
            "direct evaluation of dependers (cheap property computation)" -> directDependerOnUpdateComputationsCount,
            "direct reevaluations of dependee due to updated dependers (cheap property computation)" -> directDependeeUpdatesCount,

            "number of times the store reached quiescence" -> quiescenceCount,
            "resolved cSCCs" -> resolvedCSCCsCount
        )
    }

    // --------------------------------------------------------------------------------------------
    //
    // CORE DATA STRUCTURES
    //
    // Please note, that all data-structures are organized based on the property kind first; i.e.,
    // the property kind id is the index in the underlying array.
    //
    // --------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // The following  data-structures are potentially read concurrently.
    //

    // Those computations that will only be scheduled if the property is required.
    private[this] var lazyComputations: AtomicReferenceArray[SomePropertyComputation] = {
        new AtomicReferenceArray(SupportedPropertyKinds)
    }

    // Read by many threads, updated only by the store updates thread.
    // ONLY contains `true` intermediate and final properties; i.e., the value is never null.
    private[this] val properties: Array[ConcurrentHashMap[Entity, SomeEPS]] = {
        Array.fill(SupportedPropertyKinds) { new ConcurrentHashMap() }
    }
    // Contains all those EPKs that should be computed until we have a final result.
    // When we have computed a final result, the epk will be deleted.
    private[this] val forcedComputations: Array[ConcurrentHashMap[Entity, Entity] /*...a set*/ ] = {
        Array.fill(SupportedPropertyKinds) { new ConcurrentHashMap() }
    }

    @volatile private[this] var computedPropertyKinds: Array[Boolean] = _ /*null*/

    @volatile private[this] var delayedPropertyKinds: Array[Boolean] = _ /*null*/

    // ---------------------------------------------------------------------------------------------
    // The following three data-structures are never read or updated concurrently;
    // they are only read/updated by the store updates thread.
    //
    private[this] val dependers: Array[AnyRefMap[Entity, AnyRefMap[SomeEPK, (OnUpdateContinuation, PropertyComputationHint)]]] = {
        Array.fill(SupportedPropertyKinds) { AnyRefMap.empty }
    }
    private[this] val dependees: Array[AnyRefMap[Entity, Traversable[SomeEOptionP]]] = {
        Array.fill(SupportedPropertyKinds) { AnyRefMap.empty }
    }
    private[this] val triggeredLazyComputations: Array[mutable.Set[Entity]] = {
        Array.fill(SupportedPropertyKinds) { mutable.HashSet.empty }
    }

    // ---------------------------------------------------------------------------------------------
    // Helper data-structure to control the overall progress.
    //

    // The latch is initialized whenever the number of jobs goes from "0" to "1".
    // Note that the setup thread will always be responsible to -- at least -- schedule an initial
    // task/force an initial evaluation.
    @volatile private[this] var latch: CountDownLatch = _

    /**
     * The number of property computations and results which have not been completely processed.
     */
    private[this] val openJobs = new AtomicInteger(0)
    /**
     * MUST BE CALLED AFTER the respective task was processed.
     */
    private[this] def decOpenJobs(): Unit = {
        val v = openJobs.decrementAndGet()
        if (v == 0) { latch.countDown() }
    }
    /**
     * MUST BE CALLED BEFORE the job is actually processed.
     */
    private[this] def incOpenJobs(): Unit = {
        val v = openJobs.getAndIncrement()
        if (v == 0) { latch = new CountDownLatch(0) }
    }

    // --------------------------------------------------------------------------------------------
    //
    // Handling property computations.
    //
    // --------------------------------------------------------------------------------------------

    private[this] val propertyStoreThreads: ThreadGroup = {
        new ThreadGroup(s"OPAL - Property Store ${store.hashCode().toHexString} Threads")
    }

    /**
     * The list of scheduled (on update) property computations -
     * they will be processed in parallel.
     */
    private[this] val tasks = new ConcurrentLinkedDeque[QualifiedTask[_ <: Entity]]()
    private[this] val tasksSemaphore = new Semaphore(0)

    private[this] def prependTask(task: QualifiedTask[_ <: Entity]): Unit = {
        incOpenJobs()
        tasks.offerFirst(task)
        tasksSemaphore.release()
    }

    private[this] def appendTask(task: QualifiedTask[_ <: Entity]): Unit = {
        incOpenJobs()
        tasks.offerLast(task)
        tasksSemaphore.release()
    }

    @volatile private[this] var tasksProcessors: ThreadGroup = {

        @inline def processTask(): Unit = {
            var currentMaxTasksQueueSize = maxTasksQueueSize.get
            var newMaxTasksQueueSize = Math.max(maxTasksQueueSize.get, tasksSemaphore.availablePermits())
            while (currentMaxTasksQueueSize < newMaxTasksQueueSize &&
                !maxTasksQueueSize.compareAndSet(currentMaxTasksQueueSize, newMaxTasksQueueSize)) {
                currentMaxTasksQueueSize = maxTasksQueueSize.get
                newMaxTasksQueueSize = Math.max(maxTasksQueueSize.get, tasksSemaphore.availablePermits())
            }

            tasksSemaphore.acquire()
            val task = tasks.pollFirst()
            try {
                // As a sideeffect of processing a task, we may have (implicit) calls
                // to schedule and also implicit handleResult calls; both will
                // increase openJobs.
                if (task.isInitialTask) {
                    task.apply()
                } else {
                    // TODO check if required; i.e., if we are forced or have dependees.
                    task.apply()
                }
            } finally {
                decOpenJobs()
            }
        }

        val tg = new ThreadGroup(propertyStoreThreads, "OPAL - Property Computations Processors")
        for { i ← 1 to NumberOfThreadsForProcessingPropertyComputations } {
            val t = new Thread(tg, s"OPAL - Property Computations Processor $i") {
                override def run(): Unit = gatherExceptions {
                    do {
                        while (!store.isSuspended()) {
                            processTask()
                            if (exception != null)
                                return ;
                        }
                        // The store is suspended; hence, we want to keep the thread alive.
                        Thread.sleep(1000)
                    } while (exception == null)
                }
            }
            t.setDaemon(true)
            t.start()
        }
        tg
    }

    // --------------------------------------------------------------------------------------------
    //
    // Handling PropertyStore updates.
    //
    // --------------------------------------------------------------------------------------------

    private[this] sealed trait StoreUpdate

    private[this] case class PropertyUpdate(
            pcr:                       PropertyComputationResult,
            forceEvaluation:           Boolean,
            forceDependerNotification: Boolean
    ) extends StoreUpdate

    private[this] case class TriggeredLazyComputation[E <: Entity](
            e:    E,
            pkId: Int,
            pc:   PropertyComputation[E]
    ) extends StoreUpdate

    /**
     * The jobs which update the store.
     */
    private[this] val storeUpdates = new ConcurrentLinkedDeque[StoreUpdate]()
    private[this] val storeUpdatesSemaphore = new Semaphore(0)

    private[this] def prependStoreUpdate(update: StoreUpdate): Unit = {
        incOpenJobs()
        storeUpdates.offerFirst(update)
        storeUpdatesSemaphore.release()
    }

    private[this] def appendStoreUpdate(update: StoreUpdate): Unit = {
        incOpenJobs()
        storeUpdates.offerLast(update)
        storeUpdatesSemaphore.release()
    }

    @volatile private[this] var storeUpdatesProcessor: Thread = {

        @inline def processUpdate(): Unit = {
            storeUpdatesSemaphore.acquire()
            val update = storeUpdates.pollFirst()
            try {
                update match {
                    case PropertyUpdate(r, forceEvaluation, forceDependerNotification) ⇒
                        doHandleResult(r, forceEvaluation, forceDependerNotification)

                    case TriggeredLazyComputation(e, pkId, lc) ⇒
                        // Recall, that -- once we have a final result -- all meta data
                        // is deleted; in particular information about triggeredLazyComputations.
                        val currentP = properties(pkId).get(e)
                        if (currentP == null && triggeredLazyComputations(pkId).add(e)) {
                            if (tracer.isDefined)
                                tracer.get.schedulingLazyComputation(e, pkId)

                            scheduledLazyTasksCounter += 1
                            appendTask(new PropertyComputationTask[Entity](store, e, pkId, lc))
                        }
                }
            } finally {
                decOpenJobs()
            }
        }

        val t = new Thread(propertyStoreThreads, "OPAL - Property Updates Processor") {
            override def run(): Unit = gatherExceptions {
                do {
                    while (!store.isSuspended()) {
                        if (exception != null)
                            return ;
                        processUpdate()
                    }
                    // The store is suspended; hence, we want to keep the thread alive.
                    Thread.sleep(1000)
                } while (exception == null)
            }
        }
        t.setDaemon(true)
        t.setPriority(8)
        t.start()
        t
    }

    // --------------------------------------------------------------------------------------------
    //
    // Shutdown/Failure handling
    //
    // --------------------------------------------------------------------------------------------

    @inline protected[this] def gatherExceptions(f: ⇒ Unit): Unit = {
        try {
            f
        } catch {
            case t: Throwable ⇒ collectException(t)
        }
    }

    def shutdown(): Unit = this.synchronized {
        if (storeUpdatesProcessor == null)
            return ;

        // We use the "Thread"s' interrupt method to finally abort the threads...
        storeUpdatesProcessor.interrupt()
        storeUpdatesProcessor = null
        tasksProcessors.interrupt()
        tasksProcessors = null

        if (latch != null) latch.countDown()

        info(
            "analysis progress",
            "shutting down PropertyStore@"+System.identityHashCode(this).toHexString
        )(GlobalLogContext)
    }

    override def finalize(): Unit = {
        // DEPRECATED: super.finalize()
        shutdown()
    }

    protected[this] override def onFirstException(t: Throwable): Unit = {
        if (tracer.isDefined) tracer.get.firstException(t)
        super.onFirstException(t)
    }

    // --------------------------------------------------------------------------------------------
    //
    // Core functionality
    //
    // --------------------------------------------------------------------------------------------

    override def toString(printProperties: Boolean): String = {
        if (printProperties) {
            val ps = for {
                (eEPSs, pkId) ← properties.iterator.zipWithIndex.take(PropertyKey.maxId + 1)
                propertyKindName = PropertyKey.name(pkId)
                eEPS ← eEPSs.entrySet().asScala.iterator
            } yield {
                val e = eEPS.getKey
                val p = eEPS.getValue
                s"$e -> $propertyKindName[$pkId]=$p"
            }
            ps.mkString("PropertyStore(\n\t", "\n\t", "\n")
        } else {
            s"PropertyStore(properties=${properties.iterator.map(_.size).sum})"
        }
    }

    override def registerLazyPropertyComputation[E <: Entity, P <: Property](
        pk: PropertyKey[P],
        pc: PropertyComputation[E]
    ): Unit = {
        if (debug && openJobs.get() > 0) {
            throw new IllegalStateException(
                "lazy computations can only be registered while no analysis are scheduled"
            )
        }
        lazyComputations.set(pk.id, pc.asInstanceOf[SomePropertyComputation])
    }

    override def isKnown(e: Entity): Boolean = properties.exists(_.containsKey(e))

    override def hasProperty(e: Entity, pk: PropertyKind): Boolean = {
        properties(pk.id).containsKey(e)
    }

    override def properties[E <: Entity](e: E): Iterator[EPS[E, Property]] = {
        for {
            propertiesOfEntity ← properties.iterator
            property = propertiesOfEntity.get(e)
            if property != null
        } yield {
            property.asInstanceOf[EPS[E, Property]]
        }
    }

    override def entities(propertyFilter: SomeEPS ⇒ Boolean): Iterator[Entity] = {
        val entities = ArrayBuffer.empty[Entity]
        val max = properties.length
        var i = 0
        while (i < max) {
            properties(i) forEach { (e, eps) ⇒ if (propertyFilter(eps)) entities += e }
            i += 1
        }
        entities.toIterator
    }

    /**
     * Returns all entities which have the given property bounds based on an "==" (equals)
     * comparison.
     */
    override def entities[P <: Property](lb: P, ub: P): Iterator[Entity] = {
        entities((otherEPS: SomeEPS) ⇒ lb == otherEPS.lb && ub == otherEPS.ub)
    }

    override def entities[P <: Property](pk: PropertyKey[P]): Iterator[EPS[Entity, P]] = {
        properties(pk.id).values().iterator().asScala.asInstanceOf[Iterator[EPS[Entity, P]]]
    }

    // Thread Safe!
    private[this] def scheduleComputationForEntity[E <: Entity](
        e:               E,
        pc:              PropertyComputation[E],
        forceEvaluation: Boolean
    ): Unit = {
        scheduledTasksCounter.incrementAndGet()
        appendTask(new InitialPropertyComputationTask[E](this, e, pc, forceEvaluation))
    }

    // Thread Safe!
    private[this] def computeOrScheduleLazyComputationForEntity[E <: Entity, P <: Property](
        e:  E,
        pk: PropertyKey[P],
        pc: PropertyComputation[E]
    ): EOptionP[E, P] = {
        // Currently, we do not support eagerly scheduled computations and
        // fasttrack properties. In that case, we could have a scheduled
        // computation and "in parallel" a request by another thread. This
        // would trigger the fasttrack evaluation and then result in the
        // situation where we already have a (final) result and we then get
        // the result of the scheduled computation.
        val pkId = pk.id
        val fastTrackPropertyOption =
            if ((computedPropertyKinds(pkId) || delayedPropertyKinds(pkId)) &&
                useFastTrackPropertyComputations)
                fastTrackPropertyBasedOnPkId(this, e, pkId)
            else
                None

        fastTrackPropertyOption match {
            case Some(p) ⇒
                fastTrackPropertiesCounter.incrementAndGet()
                val finalEP = FinalEP(e, p.asInstanceOf[P])
                prependStoreUpdate(PropertyUpdate(IdempotentResult(finalEP), false, true))
                finalEP

            case None ⇒
                prependStoreUpdate(TriggeredLazyComputation(e, pkId, pc))
                EPK(e, pk)
        }
    }

    // Thread Safe!
    override def scheduleEagerComputationForEntity[E <: Entity](
        e: E
    )(
        pc: PropertyComputation[E]
    ): Unit = {
        scheduleComputationForEntity(e, pc, forceEvaluation = true)
    }

    // Thread Safe!
    override def apply[E <: Entity, P <: Property](e: E, pk: PropertyKey[P]): EOptionP[E, P] = {
        val pkId = pk.id
        properties(pkId).get(e) match {
            case null ⇒
                // the entity is unknown ...
                if (computedPropertyKinds == null) {
                    /*&& delayedPropertyKinds ne null (not necessary)*/
                    throw new IllegalStateException("setup phase was not called")
                }

                lazyComputations.get(pkId) match {
                    case null ⇒
                        if (!computedPropertyKinds(pkId) && !delayedPropertyKinds(pkId)) {
                            // ... a property is queried that is not going to be computed...
                            fallbacksUsedCounter.incrementAndGet()
                            // We directly compute the property and store it to make
                            // it accessible later on...
                            val p = PropertyKey.fallbackProperty(store, e, pk)
                            val finalEP = FinalEP(e, p)
                            val r = IdempotentResult(finalEP)
                            prependStoreUpdate(PropertyUpdate(r, false, true))
                            finalEP
                        } else {
                            EPK(e, pk)
                        }

                    case lc: PropertyComputation[E] @unchecked ⇒
                        computeOrScheduleLazyComputationForEntity(e, pk, lc)
                }

            case eps ⇒
                eps.asInstanceOf[EOptionP[E, P]]
        }
    }

    // Thread Safe!
    final def apply[E <: Entity, P <: Property](epk: EPK[E, P]): EOptionP[E, P] = {
        apply(epk.e, epk.pk)
    }

    // Thread Safe!
    override def force[E <: Entity, P <: Property](e: E, pk: PropertyKey[P]): Unit = {
        val pkId = pk.id
        if (forcedComputations(pkId).put(e, e) == null) {
            if (tracer.isDefined) tracer.get.force(e, pkId)

            val lc = lazyComputations.get(pkId)
            if (lc != null && properties(pkId).get(e) == null) {
                computeOrScheduleLazyComputationForEntity(e, pk, lc)
            }
        }
    }

    /**
     * Removes the e/pk from `dependees` and also removes it from the dependers of the
     * e/pk's dependees.
     */
    private[this] def clearDependees(epk: SomeEPK): Int = {
        assert(
            Thread.currentThread() == storeUpdatesProcessor,
            "only to be called by the store updates processing thread"
        )

        val pkId = epk.pk.id
        val dependeesOfEntity = this.dependees(pkId)
        var dependeesCount = 0
        for {
            oldDependees ← dependeesOfEntity.remove(epk.e) // <= the old ones
            EOptionP(oldDependeeE, oldDependeePK) ← oldDependees
            dependersOfOldDependee ← dependers(oldDependeePK.id).get(oldDependeeE)
        } {
            dependeesCount += 1
            dependersOfOldDependee -= epk
        }
        dependeesCount
    }

    // Thread safe!
    override def set(e: Entity, p: Property): Unit = handleExceptions {
        if (debug && lazyComputations.get(p.key.id) != null) {
            throw new IllegalStateException(
                s"$e: setting $p is not supported; lazy computation is (already) registered"
            )
        }
        val r = ExternalResult(e, p)
        prependStoreUpdate(PropertyUpdate(r, /*doesn't matter:*/ false, /*doesn't matter:*/ false))
    }

    // Thread safe!
    final override def handleResult(
        r:               PropertyComputationResult,
        forceEvaluation: Boolean
    ): Unit = {
        handleResult(r, forceEvaluation, false)
    }

    // Thread safe!
    private[par] def handleResult(
        r:                         PropertyComputationResult,
        forceEvaluation:           Boolean,
        forceDependerNotification: Boolean
    ): Unit = {
        r.id match {

            case NoResult.id ⇒ {
                // A computation reported no result; i.e., it is not possible to
                // compute a/some property/properties for a given entity.
            }

            case IncrementalResult.id ⇒
                val IncrementalResult(ir, npcs, propertyComputationsHint) = r
                handleResult(ir, forceEvaluation, forceDependerNotification)
                if (propertyComputationsHint == CheapPropertyComputation) {
                    npcs /*: Traversable[(PropertyComputation[e],e)]*/ foreach { npc ⇒
                        directInTaskThreadPropertyComputationsCounter.incrementAndGet()
                        val (pc, e) = npc
                        handleResult(pc(e), forceEvaluation, forceDependerNotification)
                    }
                } else {
                    npcs /*: Traversable[(PropertyComputation[e],e)]*/ foreach { npc ⇒
                        val (pc, e) = npc
                        // check if we can/should handle the computations immediately in
                        // this thread, because there is still enough to do for the other
                        // threads
                        if (tasksSemaphore.availablePermits() > NumberOfThreadsForProcessingPropertyComputations * 2) {
                            directInTaskThreadPropertyComputationsCounter.incrementAndGet()
                            val (pc, e) = npc
                            handleResult(pc(e), forceEvaluation, forceDependerNotification)
                        } else {
                            scheduleComputationForEntity(e, pc, forceEvaluation)
                        }
                    }
                }

            case Results.id ⇒
                val Results(furtherResults) = r
                furtherResults foreach { r ⇒
                    handleResult(r, forceEvaluation, forceDependerNotification)
                }

            case IntermediateResult.id ⇒
                appendStoreUpdate(PropertyUpdate(r, forceEvaluation, forceDependerNotification))

            case _ /*nothing special...*/ ⇒
                // "final" results are prepended
                prependStoreUpdate(PropertyUpdate(r, forceEvaluation, forceDependerNotification))
        }
    }

    /**
     * Updates the entity and optionally notifies all dependers.
     */
    private[this] def updateAndNotify(
        e:  Entity,
        lb: Property, ub: Property,
        notifyDependersAboutNonFinalUpdates: Boolean                                     = true,
        forceDependerNotification:           Boolean                                     = false,
        pcrs:                                AnyRefArrayStack[PropertyComputationResult]
    ): Boolean = {
        updatesCounter += 1
        assert(
            Thread.currentThread() == storeUpdatesProcessor,
            "only to be called by the store updates processing thread"
        )

        val pk = ub.key
        val pkId = pk.id
        val newEPS = EPS[Entity, Property](e, lb, ub)
        val isFinal = newEPS.isFinal
        val propertiesOfEntity = properties(pkId)

        // 1. update property
        val oldEPS = propertiesOfEntity.put(e, newEPS)
        if (tracer.isDefined) tracer.get.update(oldEPS, newEPS)

        // 2. check if update was ok
        if (oldEPS == null) {
            if (isFinal) oneStepFinalUpdatesCounter += 1
        } else if (debug /*&& oldEPS != null*/ ) {
            // The entity is known and we have a property value for the respective
            // kind; i.e., we may have (old) dependees and/or also dependers.
            val oldLB = oldEPS.lb
            val oldUB = oldEPS.ub
            if (oldEPS.isFinal) {
                throw new IllegalStateException(
                    s"$e@${identityHashCode(e).toHexString}: already final: $oldEPS (given:lb=$lb,ub=$ub)"
                )
            }
            if (lb.isOrderedProperty) {
                try {
                    val lbAsOP = lb.asOrderedProperty
                    val oldLBWithUBType = oldLB.asInstanceOf[lbAsOP.Self]
                    lbAsOP.checkIsEqualOrBetterThan(e, oldLBWithUBType)
                    val pValueUBAsOP = oldUB.asOrderedProperty
                    val ubWithOldUBType = ub.asInstanceOf[pValueUBAsOP.Self]
                    pValueUBAsOP.checkIsEqualOrBetterThan(e, ubWithOldUBType)
                } catch {
                    case t: Throwable ⇒
                        throw new IllegalArgumentException(
                            s"$e: illegal update: (old)lb=$oldLB -> $lb; (old)ub=$oldUB -> $ub; "+
                                "; cause="+t.getMessage,
                            t
                        )
                }
            }
        }

        // 3. handle relevant updates
        val relevantUpdate = newEPS != oldEPS
        if (isFinal ||
            forceDependerNotification ||
            (notifyDependersAboutNonFinalUpdates && relevantUpdate)) {
            // 3.1. notify dependers
            val oldDependersOption = this.dependers(pkId).remove(e)
            if (oldDependersOption.isDefined) {
                oldDependersOption.get foreach { oldDepender ⇒
                    val (oldDependerEPK, (c, onUpdateContinuationHint)) = oldDepender

                    if (tracer.isDefined) tracer.get.notification(newEPS, oldDependerEPK)

                    // Clear depender => dependee lists.
                    // Given that we will trigger the depender, we now have to remove the
                    // respective onUpdateContinuation from all dependees of the respective
                    // depender to avoid that the onUpdateContinuation is triggered multiple times!
                    val dependeesCount = clearDependees(oldDependerEPK)
                    if (onUpdateContinuationHint == CheapPropertyComputation) {
                        directDependerOnUpdateComputationsCounter += 1
                        pcrs += c(newEPS)
                    } else {
                        scheduledOnUpdateComputationsCounter += 1
                        if (isFinal) {
                            if (dependeesCount <= 2)
                                prependTask(new OnFinalUpdateComputationTask(this, newEPS.asFinal, c))
                            else
                                appendTask(new OnFinalUpdateComputationTask(this, newEPS.asFinal, c))
                        } else if (dependeesCount == 1) {
                            prependTask(new OnUpdateComputationTask(this, EPK(e, ub), c))
                        } else {
                            appendTask(new OnUpdateComputationTask(this, EPK(e, ub), c))
                        }
                    }
                }
            }

            // 3.2. perform clean-up if necessary/possible
            if (isFinal) {
                if (tracer.isDefined) tracer.get.metaInformationDeleted(newEPS.asFinal)
                forcedComputations(pkId).remove(e)
                triggeredLazyComputations(pkId).remove(e)
            }
        }
        relevantUpdate
    }

    private[this] def doHandleResult(
        r:                         PropertyComputationResult,
        forceEvaluation:           Boolean,
        forceDependerNotification: Boolean
    ): Unit = {

        // Used to store immediate results, which need to be handled immediately
        val pcrs: AnyRefArrayStack[PropertyComputationResult] = new AnyRefArrayStack(r, 4)

        def processResult(r: PropertyComputationResult): Unit = {
            assert(
                Thread.currentThread() == storeUpdatesProcessor,
                "only to be called by the store updates processing thread"
            )

            if (tracer.isDefined) tracer.get.handlingResult(r, forceEvaluation)

            r.id match {

                case NoResult.id ⇒ {
                    // A computation reported no result; i.e., it is not possible to
                    // compute a/some property/properties for a given entity.
                }

                //
                // Result containers
                //

                case Results.id ⇒
                    val Results(furtherResults) = r
                    pcrs ++= furtherResults

                case IncrementalResult.id ⇒
                    val IncrementalResult(ir, npcs, propertyComputationsHint) = r
                    doHandleResult(ir, forceEvaluation, false)
                    if (propertyComputationsHint == CheapPropertyComputation) {
                        npcs /*: Traversable[(PropertyComputation[e],e)]*/ foreach { npc ⇒
                            val (pc, e) = npc
                            pcrs += pc(e)
                        }
                    } else {
                        npcs /*: Traversable[(PropertyComputation[e],e)]*/ foreach { npc ⇒
                            val (pc, e) = npc
                            scheduleComputationForEntity(e, pc, forceEvaluation)
                        }
                    }

                //
                // Methods which actually store results...
                //

                case Result.id ⇒
                    val Result(e, p) = r
                    clearDependees(EPK(e, p.key))
                    updateAndNotify(e, p, p, pcrs = pcrs)

                case IdempotentResult.id ⇒
                    val IdempotentResult(ep @ FinalEP(e, p)) = r
                    val pkId = p.key.id
                    assert(!dependees(pkId).contains(e))
                    val propertiesOfEntity = properties(pkId)
                    if (!propertiesOfEntity.containsKey(e)) {
                        updateAndNotify(e, p, p, pcrs = pcrs)
                    } else {
                        /*we already have a value*/
                        redundantIdempotentResultsCounter += 1
                        if (debug) {
                            val oldEP = propertiesOfEntity.get(e)
                            if (oldEP != ep) {
                                throw new IllegalArgumentException(s"$e: unexpected update $oldEP => $ep")
                            }
                        }
                    }

                case MultiResult.id ⇒
                    val MultiResult(results) = r
                    results foreach { ep ⇒
                        clearDependees(ep.toEPK)
                        updateAndNotify(ep.e, ep.p, ep.p, pcrs = pcrs)
                    }

                case PartialResult.id ⇒
                    val PartialResult(e, pk, u) = r
                    type E = e.type
                    type P = Property
                    val eOptionP = apply[E, P](e: E, pk: PropertyKey[P])
                    val newEPSOption = u.asInstanceOf[EOptionP[E, P] ⇒ Option[EPS[E, P]]](eOptionP)
                    if (newEPSOption.isDefined) {
                        val newEPS = newEPSOption.get
                        clearDependees(newEPS.toEPK)
                        updateAndNotify(newEPS.e, newEPS.lb, newEPS.ub, pcrs = pcrs)
                    } else {
                        uselessPartialResultComputationCounter += 1
                    }

                case ExternalResult.id ⇒
                    val ExternalResult(e, p) = r
                    if (debug) {
                        val pkId = p.id
                        val oldP = properties(pkId).get(e)
                        if (oldP != null) {
                            throw new IllegalStateException(s"$e: already has a property $oldP")
                        }
                        if (dependees(pkId).contains(e)) {
                            throw new IllegalStateException(s"$e: is already computed/has dependees")
                        }
                    }
                    updateAndNotify(e, p, p, pcrs = pcrs)

                case CSCCsResult.id ⇒
                    val CSCCsResult(cSCCs) = r
                    for (cSCC ← cSCCs) {
                        if (traceCycleResolutions) {
                            val cSCCAsText =
                                if (cSCC.size > 10)
                                    cSCC.take(10).mkString("", ",", "...")
                                else
                                    cSCC.mkString(",")
                            info(
                                "analysis progress",
                                s"resolving cSCC(iteration:$quiescenceCounter): $cSCCAsText"
                            )
                        }
                        // 1. Kill for every member its dependee => depender relation to prevent inner
                        //    cSCC notifications (i.e.,  don't kill the dependers)
                        // 2. update all cycle members and inform the dependers (which, due to step 1,
                        //    do not contain members of the cSCC.)
                        // 3. clean-up all temporary information
                        cSCC.foreach(clearDependees)
                        for (epk ← cSCC) {
                            val e = epk.e
                            val pkId = epk.pk.id
                            val eps = properties(pkId).get(e)
                            val newP = PropertyKey.resolveCycle(this, eps)
                            updateAndNotify(e, newP, newP, pcrs = pcrs)
                        }
                        resolvedCSCCsCounter += 1
                    }

                case IntermediateResult.id ⇒
                    val IntermediateResult(e, lb, ub, seenDependees, c, onUpdateContinuationHint) = r
                    val pk = ub.key
                    val pkId = pk.id
                    val epk = EPK(e, pk)

                    if (forceEvaluation) forcedComputations(pkId).put(e, e)

                    // Given that "on notification" dependees are eagerly killed, clearing
                    // dependees is not necessary!
                    if (debug && dependees(pkId).contains(e)) {
                        throw new IllegalStateException(
                            s"$e: ${properties(pkId).get(e)} has (unexpected) dependees: \n\t"+
                                s"${dependees(pkId).get(e).get.mkString(", ")}\n"+
                                "this happens, e.g., if computations are started eagerly while "+
                                "also a respective lazy property computation is scheduled; "+
                                "in this case use force instead!"
                        )
                    }

                    // 1. let's check if a seen dependee is already updated; if so, we directly
                    //    schedule a task again to compute the property.
                    val seenDependeesIterator = seenDependees.toIterator
                    while (seenDependeesIterator.hasNext) {
                        val seenDependee = seenDependeesIterator.next()

                        if (debug && seenDependee.isFinal) {
                            throw new IllegalStateException(
                                s"$e (lb=$lb, ub=$ub): dependency to final property: $seenDependee"
                            )
                        }

                        val seenDependeeE = seenDependee.e
                        val seenDependeePKId = seenDependee.pk.id
                        val propertiesOfEntity = properties(seenDependeePKId)
                        // seenDependee is guaranteed to be not null
                        // currentDependee may be null => newDependee is an EPK => no update
                        val currentDependee = propertiesOfEntity.get(seenDependeeE)
                        if (currentDependee != null && seenDependee != currentDependee) {
                            // Make the current result available for other threads, but
                            // do not yet trigger dependers; however, we have to ensure
                            // that the dependers are eventually triggered if any update
                            // was relevant!
                            val newForceDependerNotification =
                                updateAndNotify(
                                    e, lb, ub,
                                    notifyDependersAboutNonFinalUpdates = false,
                                    forceDependerNotification = false,
                                    pcrs
                                ) || forceDependerNotification

                            if (tracer.isDefined)
                                tracer.get.immediateDependeeUpdate(e, lb, ub, seenDependee, currentDependee)

                            if (onUpdateContinuationHint == CheapPropertyComputation) {
                                directDependeeUpdatesCounter += 1
                                // we want to avoid potential stack-overflow errors...
                                pcrs += c(currentDependee)
                            } else {
                                scheduledDependeeUpdatesCounter += 1
                                if (currentDependee.isFinal) {
                                    prependTask(
                                        ImmediateOnFinalUpdateComputationTask(
                                            store,
                                            currentDependee.asFinal,
                                            previousResult = r,
                                            newForceDependerNotification,
                                            c
                                        )
                                    )
                                } else {
                                    appendTask(
                                        ImmediateOnUpdateComputationTask(
                                            store,
                                            currentDependee.toEPK,
                                            previousResult = r,
                                            newForceDependerNotification,
                                            c
                                        )
                                    )
                                }
                            }

                            return ;
                        }
                    }

                    // When we reach this point, all potential dependee updates are taken into account;
                    // otherwise we would have had an early return

                    // 2.1.  Update the value (trigger dependers/clear old dependees).
                    updateAndNotify(
                        e, lb, ub,
                        forceDependerNotification = forceDependerNotification,
                        pcrs = pcrs
                    )

                    // 2.2.  The most current value of every dependee was taken into account
                    //       register with new (!) dependees.
                    this.dependees(pkId).put(e, seenDependees)
                    val dependency = (epk, (c, onUpdateContinuationHint))
                    seenDependees foreach { dependee ⇒
                        val dependeeE = dependee.e
                        val dependeePKId = dependee.pk.id
                        dependers(dependeePKId).getOrElseUpdate(dependeeE, AnyRefMap.empty) +=
                            dependency
                    }
            }
        }

        do { processResult(pcrs.pop()) } while (pcrs.nonEmpty)
    }

    override def setupPhase(
        computedPropertyKinds: Set[PropertyKind],
        delayedPropertyKinds:  Set[PropertyKind]
    ): Unit = handleExceptions {
        if (debug && openJobs.get > 0) {
            throw new IllegalStateException(
                "setup phase can only be called as long as no tasks are scheduled"
            )
        }

        val newComputedPropertyKinds = new Array[Boolean](PropertyKind.SupportedPropertyKinds)
        computedPropertyKinds foreach { pk ⇒ newComputedPropertyKinds(pk.id) = true }
        this.computedPropertyKinds = newComputedPropertyKinds

        val newDelayedPropertyKinds = new Array[Boolean](PropertyKind.SupportedPropertyKinds)
        delayedPropertyKinds foreach { pk ⇒ newDelayedPropertyKinds(pk.id) = true }
        this.delayedPropertyKinds = newDelayedPropertyKinds
    }

    override def waitOnPhaseCompletion(): Unit = handleExceptions {
        var continueComputation: Boolean = false
        // We need a consistent interrupt state for fallback and cycle resolution:
        do {
            continueComputation = false
            while (!isSuspended() && openJobs.get() > 0) {
                if (exception != null) throw exception;
                try {
                    latch.await()
                } catch {
                    case ie: InterruptedException ⇒
                        info("analysis progress", "processing aborted due to thread interruption")
                }
            }

            if (exception != null) throw exception;
            if (isSuspended())
                return ;

            assert(openJobs.get == 0, s"unexpected number of open jobs: ${openJobs.get}")

            // We have reached quiescence. let's check if we have to
            // fill in fallbacks or if we have to resolve cyclic computations.
            quiescenceCounter += 1
            if (tracer.isDefined) tracer.get.reachedQuiescence()

            // 1. Let's search all EPKs for which we have no analyses scheduled in the
            //    future and use the fall back for them.
            //    (Recall that we return fallback properties eagerly if no analysis is
            //     scheduled or will be scheduled; but it is still possible that we will
            //     not have a property for a specific entity, if the underlying analysis
            //     doesn't compute one; in that case we need to put in fallback values.)
            val maxPKIndex = SupportedPropertyKinds // PropertyKey.maxId // properties.length
            var pkId = 0
            while (pkId < maxPKIndex) {
                val dependersOfEntity = dependers(pkId)
                val propertiesOfEntity = properties(pkId)
                if (!delayedPropertyKinds(pkId)) {
                    dependersOfEntity.keys foreach { e ⇒
                        if (propertiesOfEntity.get(e) == null) {
                            val fallbackProperty = fallbackPropertyBasedOnPkId(this, e, pkId)
                            if (traceFallbacks) {
                                var message = s"used fallback $fallbackProperty for $e"
                                if (computedPropertyKinds(pkId)) {
                                    message += " (though an analysis was supposedly scheduled)"
                                }
                                trace("analysis progress", message)
                            }
                            fallbacksUsedCounter.incrementAndGet()
                            val update = PropertyUpdate(Result(e, fallbackProperty), false, true)
                            prependStoreUpdate(update)
                            continueComputation = true
                        }
                    }
                }
                pkId += 1
            }

            // 2. let's search for cSCCs that only consist of properties which will not be
            //    updated later on
            if (!continueComputation) {
                val epks = ArrayBuffer.empty[SomeEPK]
                val maxPKIndex = SupportedPropertyKinds // PropertyKey.maxId // properties.length
                var pkId = 0
                while (pkId < maxPKIndex) {
                    if (!delayedPropertyKinds(pkId)) {
                        val dependeesOfEntity = this.dependees(pkId)
                        val dependersOfEntity = this.dependers(pkId)
                        this.properties(pkId) forEach { (e, eps) ⇒
                            if (dependeesOfEntity.contains(e) && dependersOfEntity.contains(e)) {
                                epks += eps.toEPK
                            }
                        }
                    }
                    pkId += 1
                }

                val cSCCs = graphs.closedSCCs(
                    epks,
                    (epk: SomeEPK) ⇒ dependees(epk.pk.id)(epk.e).map(_.toEPK)
                )
                if (cSCCs.nonEmpty) {
                    handleResult(CSCCsResult(cSCCs))
                    continueComputation = true
                }
            }

            if (!continueComputation) {
                // We used no fallbacks and found no cycles, but we may still have
                // (collaboratively computed) properties (e.g. CallGraph) which are
                // not yet final; let's finalize them!
                val maxPKIndex = SupportedPropertyKinds // PropertyKey.maxId // properties.length
                var pkId = 0
                var toBeFinalized: List[SomeEPS] = Nil
                while (pkId < maxPKIndex) {
                    val dependeesOfEntity = dependees(pkId)
                    // Check that the property will not be computed later on.
                    if (!delayedPropertyKinds(pkId)) {
                        properties(pkId) forEach { (e, eps) ⇒
                            // Check that we have no running computations.
                            if (eps.isRefinable && dependeesOfEntity.get(e).isEmpty) {
                                toBeFinalized ::= eps
                            }
                        }
                    }
                    pkId += 1
                }
                if (toBeFinalized.nonEmpty) {
                    toBeFinalized foreach { eps ⇒ handleResult(Result(eps.e, eps.ub)) }
                    continueComputation = true
                }
            }
        } while (continueComputation)

        if (debug) {
            // let's search for "unsatisfied computations" related to "forced properties"
            val maxPKIndex = SupportedPropertyKinds // PropertyKey.maxId // properties.length
            var pkId = 0
            while (pkId < maxPKIndex) {
                properties(pkId) forEach { (e, eps) ⇒
                    if (eps.isFinal && forcedComputations(pkId).containsKey(e)) {
                        error(
                            "analysis progress",
                            s"intermediate property state for forced property: $eps"
                        )
                    }
                }
                pkId += 1
            }
        }

        if (exception != null) throw exception;
    }
}

/**
 * Factory for creating `PKEParallelTasksPropertyStore`s.
 *
 * @author Michael Eichberg
 */
object PKEParallelTasksPropertyStore extends PropertyStoreFactory {

    @volatile var NumberOfThreadsForProcessingPropertyComputations: Int = {
        // We need at least one thread for processing property computations.
        Math.max(NumberOfThreadsForCPUBoundTasks, 1)
    }

    def apply(
        context: PropertyStoreContext[_ <: AnyRef]*
    )(
        implicit
        logContext: LogContext
    ): PKEParallelTasksPropertyStore = {
        val contextMap: Map[Type, AnyRef] = context.map(_.asTuple).toMap
        new PKEParallelTasksPropertyStore(
            contextMap,
            NumberOfThreadsForProcessingPropertyComputations,
            tracer = None
        )
    }

    def apply(
        tracer: PropertyStoreTracer
    )(
        implicit
        logContext: LogContext
    ): PKEParallelTasksPropertyStore = {

        new PKEParallelTasksPropertyStore(
            Map.empty,
            NumberOfThreadsForProcessingPropertyComputations,
            Some(tracer)
        )
    }

    def create(
        tracer:  PropertyStoreTracer,
        context: Map[Type, AnyRef] // ,PropertyStoreContext[_ <: AnyRef]*
    )(
        implicit
        logContext: LogContext
    ): PKEParallelTasksPropertyStore = {

        new PKEParallelTasksPropertyStore(
            context,
            NumberOfThreadsForProcessingPropertyComputations,
            Some(tracer)
        )
    }

}