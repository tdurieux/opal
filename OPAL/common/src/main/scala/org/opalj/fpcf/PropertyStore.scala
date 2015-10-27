/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2015
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
package org.opalj.fpcf

import java.util.{ IdentityHashMap ⇒ JIDMap }
import java.util.{ Set ⇒ JSet }
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.{ ConcurrentHashMap ⇒ JCHMap }
import scala.collection.mutable.{ HashSet ⇒ HSet }
import scala.collection.mutable.{ HashMap ⇒ HMap }
import scala.collection.mutable.{ ListBuffer ⇒ Buffer }
import scala.collection.mutable.StringBuilder
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import org.opalj.concurrent.Locking
import org.opalj.collection.immutable.IdentityPair
import org.opalj.concurrent.Locking.{ withReadLock, withWriteLock }
import org.opalj.concurrent.ThreadPoolN
import org.opalj.concurrent.handleUncaughtException
import org.opalj.log.OPALLogger
import org.opalj.concurrent.NumberOfThreadsForCPUBoundTasks
import org.opalj.log.LogContext
import org.opalj.collection.mutable.ArrayMap
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable.ListBuffer
import java.util.Collections

/**
 * The central store which manages the execution of all
 * computations that require and provide information about the entities of the store.
 *
 * ==Usage==
 * The general strategy when using the PropertyStore is to always
 * continue computing the property
 * of an entity and to collect the dependencies on those elements that are relevant.
 * I.e., if some information is not or just not completely available, the analysis should
 * still continue using
 * the provided information and (internally) records the dependency. Later on, when
 * the analysis has computed its result it reports the same and informs the framework
 * about its dependencies.
 *
 * ===Core Requirements on Property Computation Functions===
 *  - (One Function per Property Kind) A specific kind of property is always computed
 *      by only one registered `PropertyComputation` function.
 *  - (Thread-Safe) PropertyComputation functions have to be thread-safe.
 *  - (Non-Overlapping Results) [[PropertyComputation]] functions that are invoked on different
 *      entities have to compute result sets that are disjoint.
 *      For example, an analysis that performs a computation on class files and
 *      that derives properties of a specific kind related to a class file's methods must ensure
 *      that no two analysis of two different class files derive information about
 *      the same method.
 *  - (Monoton) If a `PropertyComputation` function calculates (refines) a (new )property for
 *      a specific element then the result must be more specific.
 *
 * ===Cyclic Dependencies===
 * In general, it may happen that some analyses cannot make any progress, because
 * they are mutually dependent. In this case
 * the computation of a property `p` of an entity `e1` depends
 * on the property `p` of an entity `e2` that requires the property `p` of the entity `e1`.
 * In this case the [[PropertyKey]]'s strategy is used to resolve such a cyclic dependency.
 *
 * ==Thread Safety==
 * The PropertyStore is thread-safe.
 *
 * ==Multi-Threading==
 * The PropertyStore uses its own fixed size ThreadPool with at most
 * [[org.opalj.concurrent.NumberOfThreadsForCPUBoundTasks]] threads.
 *
 * @author Michael Eichberg
 */
/*
 * The ProperStore prevents deadlocks by ensuring that updates of the store are always
 * atomic and by preventing each computation from acquiring more than one (write and/or
 * read) lock at a time.
 * The locking strategy is as follows:
 *  1.  Every entity is directly associated with a ReentrantReadWriteLock that
 *      is always used if a property for the respective entity is read or written.
 *      (Independent of the kind of property that is accessed.)
 *  1.  Associated information (e.g., the internally created observers) also use
 *      the lock associated with the entity.
 *  1.  Each computation is potentially executed concurrently and it is required
 *      that each computation is thread-safe.
 *  1.  The store as a whole is associated with a lock to enable selected methods
 *      to get a consistent view.
 */
// COMMON ABBREVIATONS USED IN THE FOLLOWING:
// ==========================================
// e = ENTITY
// p = Property
// ps = Properties
// pk = PropertyKey
// pc = (Property)Computation
// c = Continuation (The rest of a computation if a specific, dependent property was computed.)
// o = (Property)Observer
// os = (Property)Observers
// pos = A pair consisting of a property and its observers
// EPK = An entity and a property key
// EP = An entity and an associated property
// 
// REMARKS:
// ========
// SORTING IS NOT VERY RELIABLE DUE TO THE CONCURRENT EXECUTION OF THE ANALYSES WRT 
// THE INIDIVUAL ENTITIES
class PropertyStore private (
        private[this] val data: JIDMap[Entity, PropertyStoreValue],
        val isInterrupted: () ⇒ Boolean)(
                implicit val logContext: LogContext) { store ⇒

    import UpdateTypes.FinalUpdate
    import UpdateTypes.OneStepFinalUpdate
    import UpdateTypes.IntermediateUpdate
    import UpdateTypes.FallbackUpdate

    /**
     * Counts how often some observer was notified. I.e., how often an analysis reacted
     * upon the update of a value and was no able to directly use a/the value.
     */
    private[this] val propagationCount = new java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Counts how often a default property was effectively assigned to an entity.
     */
    private[this] val effectiveDefaultPropertiesCount = new java.util.concurrent.atomic.AtomicLong(0)
    private[this] val candidateDefaultPropertiesCount = new java.util.concurrent.atomic.AtomicLong(0)

    // We want to be able to make sure that methods that access the store as
    // a whole always get a consistent snapshot view
    private[this] final val StoreLock = new ReentrantReadWriteLock
    @inline final private[this] def accessEntity[B](f: ⇒ B) = withReadLock(StoreLock)(f)
    @inline final private[this] def accessStore[B](f: ⇒ B) = withWriteLock(StoreLock)(f)

    /**
     * The final set of all stored elements.
     */
    // This set is not mutated.
    private[this] final val keys: JSet[Entity] = data.keySet()
    // An (immutable) list view of all keys.
    private[this] final val keysList: List[Entity] = {
        import scala.collection.JavaConverters._
        keys.asScala.toList
    }

    // =============================================================================================
    //
    // SET PROPERTIES
    //
    //

    private[this] final val theSetPropertyObserversLock = new ReentrantReadWriteLock
    // access to this field needs to be synchronized!
    private[this] final val theSetPropertyObservers = ArrayMap[List[AnyRef ⇒ Unit]](5)
    private[this] final val theSetProperties = ArrayMap[JSet[AnyRef]](5)

    private[this] def createIdentityHashSet(): JSet[AnyRef] = {
        Collections.newSetFromMap(new JIDMap[AnyRef, java.lang.Boolean]());
    }

    private[this] final def scheduleFforE[E <: Entity](e: E, f: (E) ⇒ Unit): Unit = {
        val runnable = new Runnable {
            override def run(): Unit = {
                try {
                    f(e)
                } catch {
                    case t: Throwable ⇒ handleUncaughtException(Thread.currentThread(), t)
                } finally {
                    Tasks.taskCompleted()
                }
            }
        }
        scheduleTask(runnable)
    }

    /**
     * Registers the callback function `f` that is called if any entity is added to the set
     * identified by the given [[SetProperty]].
     *
     * Adds the given function `f` to the set of functions that will be called
     * when an entity `e` gets the [[SetProperty]] `sp`. If some entities already
     * have the respective property we will immediately schedule respective
     * computations to take place.
     */
    def onPropertyDerivation[E <: AnyRef](sp: SetProperty[E])(f: (E) ⇒ Unit): Unit = {
        val spId = sp.id
        val spMutex = sp.mutex
        withWriteLock(theSetPropertyObserversLock) {
            theSetPropertyObservers(spId) = f.asInstanceOf[AnyRef ⇒ Unit] :: theSetPropertyObservers.getOrElse(spId, Nil)
            spMutex.acquire()
            try {
                accessEntity { // <= we don't need an overall consistent view
                    import scala.collection.JavaConversions._
                    val spSet = theSetProperties.getOrElseUpdate(spId, createIdentityHashSet())
                    spSet.asInstanceOf[JSet[E]] foreach { e ⇒ scheduleFforE(e, f) }
                }
            } finally {
                spMutex.release()
            }
        }
    }

    /**
     * Directly associates the given [[SetProperty]] `sp` with the given entity `e`.
     *
     * If the given entity already has the associated property nothing will happen;
     * if not, we will immediately schedule the execution of all functions that
     * are interested in this property.
     */
    def add[E <: AnyRef](sp: SetProperty[E])(e: E): Unit = {
        val spId = sp.id
        val spMutex = sp.mutex
        withReadLock(theSetPropertyObserversLock) {
            spMutex.acquire()
            try {
                accessEntity {
                    val currentEs = theSetProperties.getOrElse(spId, createIdentityHashSet())
                    if (!currentEs.contains(e)) {
                        currentEs.add(e)
                        theSetPropertyObservers.getOrElse(spId, Nil) foreach { f ⇒
                            propagationCount.incrementAndGet()
                            scheduleFforE(e, f)
                        }
                    }
                }
            } finally {
                spMutex.release()
            }
        }
    }

    // =============================================================================================
    //
    // PER ENTITY PROPERTIES
    //
    //

    // access to this field needs to be synchronized using the store's (global) lock
    private[this] final val theOnPropertyComputations = ArrayMap[List[(Entity, Property) ⇒ Unit]](5)

    // The list of observers used by the entity e to compute the property of kind k (EPK).
    // In other words: the mapping between a Depender and its Observers!
    // The list of observers needs to be maintained whenever:
    //  1. A computation of a property finishes. In this kind all observers need to
    //     be notified and removed from this map afterwards.
    //  1. A computation of a property generates an [[IntermediatResult]], but the
    //     the observer is one-time observer. (Such observers are only used internally.
    private[this] final val observers = new JCHMap[EPK, Buffer[(EPK, PropertyObserver)]]()

    /**
     * Returns the property of the respective property kind `pk` currently associated
     * with the given element `e`.
     *
     * This is most basic method to get some property and it is the preferred way
     * if (a) you know that the property is already available – e.g., because some
     * property computation function was strictly run before the current one – or
     * if (b) the running computation has a comparatively
     * huge, complex state that is not completely required if the computation
     * needs to be suspended because the property is not (yet) available. In the latter
     * case it may be beneficial to only store the strictly necessary information and to rerun
     * the entire computation on demand.
     *
     * @note The returned value may change over time but only such that it
     *      is strictly more precise.
     *
     * @param e An entity stored in the property store.
     * @param pk The kind of property.
     * @return `None` if no information about the respective property is (yet) available.
     *      `Some(Property)` otherwise.
     */
    def apply(e: Entity, pk: PropertyKey): Option[Property] = {
        val pos = accessEntity {
            val lps = data.get(e)
            assert(lps ne null, s"the entity $e is unknown to the property store")
            val (lock, properties) = lps
            withReadLock(lock) { properties(pk.id) }
        }
        if (pos ne null)
            Option(pos._1 /*property*/ )
        else
            None
    }

    /**
     * Returns an iterator of the different properties associated with the given element.
     *
     * This method is the preferred way to get all properties of an entity and should be used,
     * if you know that all properties are already computed.
     *
     * @note The returned value may change over time but only such that it
     *      is strictly more precise.
     *
     * @param e An entity sotred in the property store.
     * @return `Iterator[Property]` independently if properties are stored or not.
     */
    def apply(e: Entity): Iterator[Property] = {
        accessEntity {
            val (lock, properties) = data.get(e)
            withReadLock(lock) {
                properties.values collect {
                    case (p, _) if p ne null ⇒ p
                }
            }
        }
    }

    /**
     * Returns the property associated with the respective `dependeeE`.
     *
     * The function `c` is the function that is called when the property becomes
     * available and which computes – and then returns – the property for the depender.
     *
     * @example
     * {{{
     *   val c: Continuation =
     *      (dependeeE: Entity, dependeeP: Property) ⇒
     *          if (dependeeP == EffectivelyFinal) {
     *              val nextPC = body.pcOfNextInstruction(currentPC)
     *              determinePurityCont(method, nextPC, dependees)
     *          } else {
     *              Result(method, Impure)
     *          }
     * }}}
     */
    def require(
        dependerE: Entity,
        dependerPK: PropertyKey,
        dependeeE: Entity,
        dependeePK: PropertyKey)(
            c: Continuation): PropertyComputationResult = {
        this(dependeeE, dependeePK) match {
            case Some(dependeeP) ⇒
                c(dependeeE, dependeeP)
            case _ /*None*/ ⇒
                new Suspended(dependerE, dependerPK, dependeeE, dependeePK) {
                    def continue(dependeeE: Entity, dependeeP: Property) =
                        c(dependeeE, dependeeP)
                }
        }
    }

    /**
     * Tests if all entities have the given property. If the respective property is
     * not yet available, the computation will be suspended until the property of
     * the respective kind is available. Hence, it only makes sense to use this
     * function if the respective property is computed by an independent analysis or
     * if it is an inherent property of the analysis/analyses that the information about the
     * dependees is guaranteed to become available without requiring information
     * about the depender.
     *
     * This function eagerly tries to determine if the answer is false and only
     * suspends the computation if the (negative) answer cannot directly be computed.
     */
    def allHaveProperty(
        dependerE: Entity, dependerPK: PropertyKey,
        dependees: Traversable[Entity], expectedP: Property)(
            c: (Boolean) ⇒ PropertyComputationResult): PropertyComputationResult = {

        val dependeePK = expectedP.key
        var remainingEs = dependees
        var unavailableEs: List[Entity] = Nil
        accessEntity { // we want to get a consistent view across the read entities
            while (remainingEs.nonEmpty) {
                // The idea is to eagerly try to determine if the answer might be false.
                val dependeeE = remainingEs.head
                remainingEs = remainingEs.tail
                this(dependeeE, dependeePK) match {
                    case Some(dependeeP) ⇒
                        if (expectedP != dependeeP)
                            return c(false);
                    case None ⇒
                        unavailableEs = dependeeE :: unavailableEs
                }
            }
        }
        if (unavailableEs.isEmpty) {
            // all information was available and was always as expected
            return c(true);
        }

        //OPALLogger.debug(
        //    "analysis progress",
        //    s"$dependerE($dependerPK) has to wait for the results of ${dependees.mkString(",")}")

        // Let's wait on the next result and then try to get as many results as
        // possible, by using haveProperty again... i.e., we try to minimize the
        // number of suspended computations that we need to create.
        val deependeeE = unavailableEs.head
        new Suspended(dependerE, dependerPK, deependeeE, dependeePK) {
            def continue(
                dependeeE: Entity,
                dependeeP: Property): PropertyComputationResult = {
                if (expectedP != dependeeP)
                    c(false)
                else {
                    val nextEs = unavailableEs.tail
                    if (nextEs.isEmpty) {
                        c(true)
                    } else {
                        allHaveProperty(dependerE, dependerPK, nextEs, expectedP)(c)
                    }
                }
            }
        }
    }

    /**
     * Directly associate the given property `p` with given entity `e`.
     *
     * This method must not be used if the given entity might already be associated with
     * a property of the respective kind or if there might be a computation that
     * computes the property p.
     *
     * The primary use case is an analysis that does not use the property store for
     * executing the analysis, but wants to store some results in the store.
     */
    def set(e: Entity, p: Property): Unit = update(e, p, OneStepFinalUpdate)

    /**
     * Returns all elements which have a property of the respective kind. This method
     * returns a consistent snapshot view of the store w.r.t. the given
     * [[PropertyKey]].
     *
     * While the view is computed all other computations are blocked.
     */
    def apply(pk: PropertyKey): Traversable[(Entity, Property)] = {
        accessStore {
            import scala.collection.JavaConversions._
            val valuesWithProperty =
                data.entrySet() filter { v ⇒
                    val (_ /*lock*/ , properties) = v.getValue()
                    properties(pk.id) match {
                        case null | (null, _) ⇒ false
                        case _                ⇒ true
                    }
                }
            valuesWithProperty map { v ⇒ (v.getKey, v.getValue._2(pk.id)._1) }
        }
    }

    /**
     * Registers the function `f`  that is called whenever an element `e` is associated with
     * a property of the respective kind (`pk`). For those elements that are already associated with a
     * respective property `p`,  `f` will immediately be scheduled (i.e., `f` will not be executed
     * concurrently.)
     */
    def onPropertyChange(pk: PropertyKey)(f: (Entity, Property) ⇒ Unit): Unit = {
        val pkId = pk.id
        accessStore {
            // We need exclusive access to make sure that `f` is called for all existing values
            // only once! 

            // register `f`
            theOnPropertyComputations(pkId) = f :: theOnPropertyComputations.getOrElse(pkId, Nil)

            // call `f` for all entities with a respective property
            this(pk) foreach { ep ⇒
                val (e, p) = ep
                val task = new Runnable {
                    override def run(): Unit = {
                        try {
                            f(e, p)
                        } catch {
                            case t: Throwable ⇒ handleUncaughtException(Thread.currentThread(), t)
                        } finally {
                            Tasks.taskCompleted()
                        }
                    }
                }
                scheduleTask(task)
            }
        }
    }

    /**
     * Registers a function that calculates a property for all or some elements
     * of the store.
     *
     * This store ensures that the property
     * computation function `pc` is never invoked more than once for the
     * same element at the same time. If `pc` is invoked again for a specific element
     * then only because a dependee has changed!
     */
    def <<(pc: PropertyComputation): Unit = {
        bulkScheduleComputations(keysList, pc)
    }

    /**
     * Registers a function that calculates a property for those elements
     * of the store that pass the filter `f`.
     *
     * The filter is evaluated as part of this method; i.e., the calling thread.
     *
     * @param f A filter that selects those entities that are relevant to the analysis.
     *      For which the analysis may compute some property.
     *      The filter function is performed in the context of the calling thread.
     */
    def <|<(f: Entity ⇒ Boolean, c: PropertyComputation): Unit = {
        val it = keys.iterator()
        var es: List[Entity] = Nil
        while (it.hasNext()) {
            if (isInterrupted())
                return ;
            val e = it.next()
            if (f(e)) es = e :: es
        }
        bulkScheduleComputations(es, c)
    }

    /**
     * Registers a function `c` that calculates a property for those elements
     * of the store that are collected by the given partial function `pf`.
     *
     * The partial function is evaluated for all entities as part of this
     * method; i.e., the calling thread.
     *
     * @param pf A a partial function that is used to collect those elements that will be
     *      passed to the function`c` and for which the analysis may compute some property.
     *      The function pf is performed in the context of the calling thread.
     */
    def <||<[E <: Entity](
        pf: PartialFunction[Entity, E],
        c: E ⇒ PropertyComputationResult): Unit = {
        val es = keysList.collect(pf)
        bulkScheduleComputations(es, c.asInstanceOf[Object ⇒ PropertyComputationResult])
    }

    /**
     * Awaits the completion of the computation of all
     * properties of all previously registered property computation functions. I.e.,
     * if a second thread is used to register [[PropertyComputation]] functions then
     * no guarantees are given. In general it is recommended to schedule all
     * property computation functions using one thread.
     *
     * This function is only '''guaranteed''' to wait on the completion of the computation
     * of those properties for which a property computation function was registered by
     * the calling thread.
     */
    def waitOnPropertyComputationCompletion(
        useDefaultForIncomputableProperties: Boolean = true): Unit = {
        Tasks.waitOnCompletion(useDefaultForIncomputableProperties)
    }

    /**
     * Returns a string representation of the stored properties.
     */
    def toString(printProperties: Boolean): String = accessStore /* <=> Exclusive Access*/ {

        val entitiesPerSetPropertyCount = theSetProperties map { (key, entities) ⇒
            (SetProperty.name(key), entities.size)
        }
        val overallSetPropertyCount = entitiesPerSetPropertyCount.map(_._2).sum
        val setPropertiesStatistics =
            s"∑$overallSetPropertyCount: "+
                entitiesPerSetPropertyCount.map(e ⇒ e._1+":"+e._2).mkString("(", ", ", ")")

        val perPropertyKeyEntities = new Array[Int](PropertyKey.maxId + 1)
        var perEntityPropertiesCount = 0
        var unsatisfiedPropertyDependencies = 0
        val properties = new StringBuilder
        val it = data.entrySet().iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val ps = entry.getValue._2.map { (pk, pos) ⇒
                val (p, os) = pos
                (
                    if (p eq null) {
                        unsatisfiedPropertyDependencies += 1
                        s"<Unsatisfied: ${PropertyKey.name(pk)}>"
                    } else {
                        perEntityPropertiesCount += 1
                        perPropertyKeyEntities(pk) = perPropertyKeyEntities(pk) + 1
                        p.toString
                    }
                )+"["+(if (os eq null) 0 else os.size)+"]"
            }
            if (printProperties && ps.nonEmpty) {
                val s = ps.mkString("\t\t"+entry.getKey.toString+" => {", ", ", "}\n")
                properties.append(s)
            }
        }

        val perEntityPropertiesStatistics = s"∑$perEntityPropertiesCount: "+
            (perPropertyKeyEntities.zipWithIndex.filter(_._1 > 0) map { e ⇒
                val (sum, pk) = e
                (PropertyKey.name(pk), sum)
            }).map(e ⇒ e._1+":"+e._2).mkString("(", ", ", ")")

        "PropertyStore(\n"+
            s"\tentitiesCount=${data.size()},\n"+
            s"\texecutedComputations=${Tasks.executedComputations}\n"+
            s"\tpropagations=${propagationCount.get}\n"+
            s"\tunsatisfiedPropertyDependencies=$unsatisfiedPropertyDependencies\n"+
            s"\tcandidateDefaultPropertiesCount=$candidateDefaultPropertiesCount\n"+
            s"\teffectiveDefaultPropertiesCount=$effectiveDefaultPropertiesCount\n"+
            s"\tperEntityProperties[$perEntityPropertiesStatistics]\n"+
            (if (overallSetPropertyCount > 0) s"\tperSetPropertyEntities[$setPropertiesStatistics]\n" else "") +
            (if (printProperties) s"=$properties)" else ")")
    }

    override def toString: String = toString(false)

    //
    //
    // INTERNAL IMPLEMENTATION
    //
    //

    val ThreadCount = Math.max(NumberOfThreadsForCPUBoundTasks, 2)
    private[this] final val threadPool = ThreadPoolN(ThreadCount)

    /**
     * General handling of the tasks that are executed.
     */
    private[this] object Tasks {

        @volatile var useFallbackForIncomputableProperties: Boolean = false

        // ALL ACCESSES TO "executed" ARE SYNCHRONIZED
        private[this] var executed = 0

        private[PropertyStore] def executedComputations: Int = synchronized { executed }

        private[this] var isInterrupted: Boolean = false

        /**
         * The number of scheduled tasks. I.e., the number of tasks that are running or
         * that will run in the future.
         */
        private[this] var scheduled = 0

        /**
         * Terminates all scheduled but not executing computations and afterwards
         * deregisters all observers.
         */
        private[PropertyStore] def interrupt(): Unit = {

            if (isInterrupted)
                return ;

            this.synchronized {
                // double-checked locking idiom...
                if (isInterrupted)
                    return ;

                isInterrupted = true
                OPALLogger.debug("analysis progress", "cancelling scheduled computations")
                val waitingTasks = threadPool.shutdownNow()
                scheduled -= waitingTasks.size
            }

            def clearAllObservers(): Unit = {

                // TODO
                // We iterate over all entities and remove all related observers
                // to help to make sure that the computation can finish in due time.

                threadPool.awaitTermination(5000l, TimeUnit.MILLISECONDS)

                OPALLogger.debug("analysis progress", "garbage collecting property computations")
                accessStore {
                    observers.clear()
                    import scala.collection.JavaConversions._
                    for {
                        entry ← data.entrySet()
                        (_ /*p*/ , os) ← entry.getValue()._2.values
                    } {
                        os.clear()
                    }
                }
            }

            // Invoke the garbage collector either in this thread if this thread
            // is not a thread belonging to the property store's thread pool or
            // in a new thread.
            if (threadPool.group == Thread.currentThread().getThreadGroup) {
                new Thread(new Runnable { def run(): Unit = clearAllObservers() }).start()
            } else {
                clearAllObservers()
            }

        }

        def taskStarted() = synchronized {
            scheduled += 1
        }

        def tasksStarted(tasksCount: Int) = synchronized {
            scheduled += tasksCount
        }

        def tasksAborted(tasksCount: Int) = synchronized {
            scheduled -= tasksCount
        }

        private[this] def registeredObservers: Int = {
            import scala.collection.JavaConverters._
            val ps = data.values().asScala.map(_._2)
            val poss = ps.map(_.values).flatten
            poss.map(pos ⇒ if (pos._2 eq null) 0 else pos._2.filter(_ ne null).size).sum
        }

        def taskCompleted() = synchronized {
            assert(scheduled > 0)
            scheduled -= 1
            executed += 1

            // When all scheduled tasks are completed, we check if there are
            // pending computations that now can be activated.
            if (scheduled == 0) {
                // Let's check if we have some potentially refineable intermediate results.
                OPALLogger.debug(
                    "analysis progress",
                    s"all $executed previously scheduled tasks have finished")

                try {
                    if (!isInterrupted) {
                        OPALLogger.debug("analysis progress", s"handling unsatisfied dependencies")
                        handleUnsatisfiedDependencies()
                    }
                } catch {
                    case t: Throwable ⇒
                        OPALLogger.error(
                            "analysis progress",
                            "handling suspended computations failed; aborting analyses",
                            t)
                        interrupt()
                        notifyAll()
                }

                if (scheduled == 0 /*scheduled is still === 0*/ ) {
                    OPALLogger.debug(
                        "analysis progress",
                        "computation of all properties finished"+
                            s" (remaining computations: $registeredObservers)")
                    notifyAll()
                } else {
                    OPALLogger.debug(
                        "analysis progress",
                        s"(re)scheduled $scheduled property computations")
                }
            }
        }

        @inline private[this] def getObservers(e: Entity, pkId: Int): Observers = {
            val value = data.get(e)
            if (value eq null)
                return null;

            val (_, properties) = value
            if (properties eq null)
                return null;

            val (_, observers) = properties(pkId)
            observers
        }

        // THIS METHOD REQUIRES EXCLUSIVE ACCESS TO THE STORE!
        // Handle unsatisfied dependencies supports both cases:
        //  1. computations that are part of a cyclic computation dependency
        //  1. computations that depend on knowledge related to a specific kind of
        //     property that was not computed (final lack of knowledge) and for
        //     which no computation exits.
        private[this] def handleUnsatisfiedDependencies(): Unit = {
            import scala.collection.JavaConverters._
            // GIVEN: data: JIDMap[Entity,PropertyStoreValue = (ReentrantReadWriteLock, Properties = OArrayMap[(Property, Observers)])]
            // GIVEN: observers: new JCHMap[EPK, Buffer[(EPK, PropertyObserver)]]()
            val observers = store.observers

            val indirectlyIncomputableEPKs = HSet.empty[EPK]

            // All those EPKs that require some information that do not depend (directly
            // or indirectly) on an incomputableEPK. However, this set also includes
            // those EPKs that may depend on another strongly connected component which
            // is a knot (which has no outgoing dependency).
            val cyclicComputableEPKCandidates = HSet.empty[EPK]

            // Let's determine all EPKs that have a dependency on an incomputableEPK
            // (They may be in a strongly connected component, but we don't care about
            // these, because they may still be subject to some refinement.)
            def determineIncomputableEPKs(dependerEPK: EPK): Unit = {
                var worklist = List(dependerEPK)
                while (worklist.nonEmpty) {
                    val dependerEPK = worklist.head
                    worklist = worklist.tail
                    val ps = data.get(dependerEPK.e)._2(dependerEPK._2.id)
                    if ((ps ne null) && (ps._2 ne null)) {
                        val os = ps._2
                        os foreach { o ⇒
                            val dependerEPK = o.depender
                            if (indirectlyIncomputableEPKs.add(dependerEPK)) {
                                cyclicComputableEPKCandidates -= dependerEPK
                                worklist = dependerEPK :: worklist
                            }
                        }
                    }
                }
            }

            val directlyIncomputableEPKs = HSet.empty[EPK]
            observers.entrySet().asScala foreach { e ⇒
                val dependerEPK = e.getKey
                if (!indirectlyIncomputableEPKs.contains(dependerEPK)) {
                    val dependees = e.getValue
                    dependees foreach { dependee ⇒
                        val dependeeEPK = dependee._1
                        if (!observers.containsKey(dependeeEPK)) {
                            directlyIncomputableEPKs += dependeeEPK
                            assert(
                                data.get(dependeeEPK.e)._2(dependeeEPK.pk.id)._1 eq null,
                                s"property propagation failed $dependeeEPK has a property. but $dependerEPK was not notified"
                            )
                            indirectlyIncomputableEPKs += dependerEPK
                            determineIncomputableEPKs(dependerEPK)
                        } else {
                            // this EPK observes EPKs that have observers...
                            // but, is it also observed?
                            val observers = getObservers(dependerEPK.e, dependerEPK.pk.id)
                            if ((observers ne null) && observers.nonEmpty) {
                                cyclicComputableEPKCandidates += dependerEPK
                            }
                        }
                    }
                }
            }
            //
            //            println("Store..."+store.toString)
            //            println("Directly..."+directlyIncomputableEPKs)
            //            println("Indirectly..."+indirectlyIncomputableEPKs)
            //            println("Cyclic..."+cyclicComputableEPKCandidates)

            // Now

            // Let's get the set of observers that will never be notified, because
            // there are no open computations related to the respective property.
            // This is also the case if no respective analysis is registered so far.
            if (useFallbackForIncomputableProperties) {
                println(store.toString)
                for {
                    EPK(e, pk) ← directlyIncomputableEPKs
                } {
                    val defaultP = PropertyKey.fallbackProperty(pk.id)
                    scheduleHandleFallbackResult(e, defaultP)
                }
            }
        }

        def waitOnCompletion(useFallbackForIncomputableProperties: Boolean): Unit =
            synchronized {
                this.useFallbackForIncomputableProperties = useFallbackForIncomputableProperties
                while (scheduled > 0) {
                    OPALLogger.debug(
                        "analysis progress",
                        "all previously scheduled tasks finished"+
                            s" (newly scheduled tasks: $scheduled)")
                    wait
                }
            }
    }

    /**
     * Schedules the handling of the result of a property computation.
     */
    private[this] def scheduleHandleFallbackResult(e: Entity, p: Property): Unit = {
        scheduleTask(new Runnable {
            override def run(): Unit = {
                try {
                    candidateDefaultPropertiesCount.incrementAndGet()
                    handleResult(FallbackResult(e, p))
                } catch {
                    case t: Throwable ⇒ handleUncaughtException(Thread.currentThread(), t)
                } finally {
                    Tasks.taskCompleted()
                }
            }
        })
    }

    /**
     * Schedules the continuation w.r.t. the entity `e`.
     */
    private[this] def scheduleContinuation(
        dependeeE: Entity,
        dependeeP: Property,
        c: Continuation): Unit = {
        scheduleTask(new Runnable {
            override def run(): Unit = {
                try {
                    handleResult(c(dependeeE, dependeeP))
                } catch {
                    case t: Throwable ⇒ handleUncaughtException(Thread.currentThread(), t)
                } finally {
                    Tasks.taskCompleted()
                }
            }
        })
    }

    /**
     * Schedules the computation of a property w.r.t. the entity `e`.
     */
    private[this] def scheduleComputation(e: Entity, pc: PropertyComputation): Unit = {
        scheduleTask(new Runnable {
            override def run() = {
                try {
                    handleResult(pc(e))
                } catch {
                    case t: Throwable ⇒ handleUncaughtException(Thread.currentThread(), t)
                } finally {
                    Tasks.taskCompleted()
                }
            }
        })
    }

    /**
     * Schedules the computation of a property w.r.t. the entity `e`.
     */
    private[this] def bulkScheduleComputations(
        es: List[_ <: Entity],
        pc: PropertyComputation): Unit = {
        val ges = es.grouped(Math.max(es.size / (ThreadCount * 2), ThreadCount)).toList
        val tasks = ges.map { es ⇒
            new Runnable {
                override def run(): Unit = {
                    try {
                        es foreach { e ⇒ handleResult(pc(e)) }
                    } catch {
                        case t: Throwable ⇒ handleUncaughtException(Thread.currentThread(), t)
                    } finally {
                        Tasks.taskCompleted()
                    }
                }
            }
        }
        if (isInterrupted())
            return ;

        scheduleTasks(tasks)
    }

    private[this] def scheduleTask(r: Runnable): Unit = {
        if (isInterrupted()) {
            Tasks.interrupt()
            return ;
        }

        Tasks.taskStarted()
        try {
            threadPool.submit(r)
        } catch {
            // exceptions thrown by "r" do not end up here... here, we handle threadpool exceptions
            case reh: RejectedExecutionException ⇒ Tasks.taskCompleted()
            case t: Throwable                    ⇒ Tasks.taskCompleted(); handleUncaughtException(t)
        }
    }

    private[this] def scheduleTasks(rs: Traversable[Runnable]): Unit = {
        if (isInterrupted()) {
            Tasks.interrupt()
            return ;
        }

        val allTasksCount = rs.size
        Tasks.tasksStarted(allTasksCount)
        var startedTasksCount = 0
        try {
            rs foreach { r ⇒ threadPool.execute(r); startedTasksCount += 1 }
        } catch {
            case reh: RejectedExecutionException ⇒
                Tasks.tasksAborted(allTasksCount - startedTasksCount)
            case t: Throwable ⇒
                Tasks.tasksAborted(allTasksCount - startedTasksCount)
                handleUncaughtException(t);
        }
    }

    // Clears all observers that were registered with other entities to compute the
    // respective property of the given entity.
    // This method handles the situation where the computation of a property
    // potentially depended on some other entities and we now have a final result
    // and now need to cleanup the registered observers.
    /**
     * @return `true` if some observers were removed.
     */
    private[this] def clearDependeeObservers(e: Entity, pk: PropertyKey): Boolean = {
        // observers : JCHMap[EPK, Buffer[(EPK, PropertyObserver)]]()
        val epk = EPK(e, pk)
        val observers = store.observers
        val dependerOs = observers.get(epk) // outgoing ones...
        if (dependerOs ne null) {
            dependerOs foreach { epkos ⇒
                val (dependeeEPK, epkO) = epkos
                val (lock, dependeePs) = data.get(dependeeEPK.e)
                withWriteLock(lock) {
                    val dependeeOs = dependeePs(dependeeEPK.pk.id)._2
                    //           println(s"$e with $pk .... dependeeOs: $dependeeOs")
                    if (dependeeOs ne null) {
                        dependeeOs -= epkO
                    }
                }
            }
            observers.remove(epk) /*clear all*/ ne null
        } else {
            false
        }
    }

    /**
     * Associates / Updates the property with element e. If observers are registered
     * with the respective property then those observers will be informed about the
     * property change.
     */
    // Invariant: always only at most one function exists that will compute/update
    // the property p belonging to property kind k of an element e.
    private[this] def update(e: Entity, p: Property, updateType: UpdateType): Unit = {
        val pk = p.key
        val pkId = pk.id

        accessEntity {
            val (lock, properties) = data.get(e)
            var obsoleteOs: List[PropertyObserver] = Nil
            val os = withWriteLock(lock) {

                // 1. inform all onPropertyComputations about the value
                val onPropertyComputations = theOnPropertyComputations.getOrElse(pkId, Nil)
                onPropertyComputations foreach { opc ⇒ opc(e, p) }

                // 2. update the property
                properties(pk.id) match {
                    case null ⇒ // No one was interested in this property so far...
                        updateType match {
                            case OneStepFinalUpdate ⇒
                                assert(
                                    clearDependeeObservers(e, pk) == false,
                                    s"the analysis returned an immediate result for $e($pk) though it relied on other properties")
                                properties(pkId) = (p, null)

                            case FinalUpdate ⇒
                                // We (still) may have a hard dependency on another entity...
                                clearDependeeObservers(e, pk)
                                properties(pkId) = (p, null)

                            case _ ⇒
                                assert(
                                    updateType ne FallbackUpdate,
                                    "fallback property assigned to an element that is not a dependee")

                                val os = Buffer.empty[PropertyObserver]
                                properties(pkId) = (p, os)
                        }
                        return ;

                    case (oldP, os) ⇒
                        assert(
                            (oldP eq null) || oldP.isRefineable || updateType == FallbackUpdate,
                            s"the old property $oldP is already a final property and refinement to $p is not supported")
                        assert(
                            os != null || updateType == FallbackUpdate,
                            s"$e: the list of observers is null; the old property was ($oldP) and the new property is $p")
                        assert(
                            oldP != p,
                            s"$e: the old ($oldP) and the new property ($p) are identical (updateType=$updateType)")

                        updateType match {

                            case OneStepFinalUpdate ⇒
                                // The computation did not create any (still living) dependencies!
                                assert(
                                    clearDependeeObservers(e, pk) == false,
                                    s"the analysis returned an immediate result for $e($pk) though it had intermediate dependencies")
                                properties(pkId) = (p, null /*The list of observers is no longer required!*/ )

                            case FinalUpdate ⇒
                                // We still may observe other entities... we have to clear
                                // these dependencies.
                                clearDependeeObservers(e, pk)
                                properties(pkId) = (p, null /*The incoming observers are no longer required!*/ )

                            case IntermediateUpdate ⇒
                                // We still continue observing all other entities;
                                // hence, we only need to clear our one-time observers.
                                val newOs = os.filter { o ⇒
                                    if (o.removeAfterNotification) {
                                        obsoleteOs = o :: obsoleteOs
                                        false
                                    } else {
                                        true
                                    }
                                }
                                properties(pkId) = (p, newOs)

                            case FallbackUpdate ⇒
                                if (oldP eq null) {
                                    OPALLogger.debug(
                                        "analysis progress",
                                        s"associated default property $p with $e")
                                    effectiveDefaultPropertiesCount.incrementAndGet()
                                    val newOs =
                                        if (p.isFinal) {
                                            clearDependeeObservers(e, pk)
                                            null /*The incoming observers are no longer required!*/
                                        } else {
                                            // the fallback property is refineable...
                                            os.filter { o ⇒
                                                if (o.removeAfterNotification) {
                                                    obsoleteOs = o :: obsoleteOs
                                                    false
                                                } else {
                                                    true
                                                }
                                            }
                                        }
                                    properties(pkId) = (p, newOs)
                                } else {
                                    // Nothing to do... the entity is already associated
                                    // with a property.
                                    OPALLogger.debug(
                                        "analysis progress",
                                        s"fallback update ignored; the property $oldP is already associated with $e")
                                    assert(
                                        (os eq null) || os.isEmpty,
                                        s"the fallback update of $e was aborted due to the existing property $oldP but observers found")
                                    return ;
                                }
                        }
                        os
                }
            }
            // ... non-exclusive access (just clear the observers)
            if (obsoleteOs.nonEmpty) {
                val dependeeEPK = EPK(e, pk)
                val data = store.data
                val observers = store.observers
                obsoleteOs foreach { o ⇒
                    val dependerEPK = o.depender
                    val (lock, _) = data.get(dependerEPK.e)
                    withWriteLock(lock) {
                        val dependerOs = observers.get(dependerEPK)
                        if (dependerOs ne null) {
                            dependerOs -= ((dependeeEPK, o))
                        }
                    }
                }
            }

            // inform all (previously registered) observers about the value
            os foreach { o ⇒ o(e, p) }
        }
    }

    //    /**
    //     * Registers the observer, if the property is not yet available or equal to the
    //     * specified property value. If the property is already refined, the observer is
    //     * immediately invoked and not registered.
    //     *
    //     * @return `true` if an observer was registered, `false` otherwise.
    //     */
    //    private[this] def handleDependency(
    //        e: Entity,
    //        pk: PropertyKey,
    //        pOption: Option[Property],
    //        o: PropertyObserver): Unit = {
    //        // always only at most one function exists that will update the property p
    //        // of the element e
    //        val (lock, properties) = data.get(e)
    //        withWriteLock(lock) {
    //            val propertyAndObservers = properties.get(pk)
    //            if (propertyAndObservers == None) {
    //                properties.put(pk, (null, Buffer(o)))
    //                true
    //            } else {
    //                val Some((p, observers)) = propertyAndObservers
    //                if ((p eq null) || pOption.isEmpty || pOption.get == p) {
    //                    observers += o
    //                } else {
    //                    // ... the value in the store is already a more refined value
    //                    // than the value given by pOption
    //                    o (e, p)
    //                }
    //            }
    //        }
    //    }

    private[this] def registerObserverWithItsDepender(
        dependerEPK: EPK,
        dependeeEPK: EPK,
        o: PropertyObserver): Unit = {
        val (dependerLock, _) = data.get(dependerEPK.e)
        withWriteLock(dependerLock) {
            var buffer = store.observers.get(dependerEPK)
            if (buffer eq null) {
                buffer = ListBuffer.empty
                observers.put(dependerEPK, buffer)
            }
            buffer += ((dependeeEPK, o))
        }
    }

    private[PropertyStore] def handleResult(r: PropertyComputationResult): Unit = {
        accessEntity {

            r match {

                case NoResult              ⇒ // Nothing to do..

                case ImmediateResult(e, p) ⇒ update(e, p, OneStepFinalUpdate)

                case Result(e, p)          ⇒ update(e, p, FinalUpdate)

                case ImmediateMultiResult(results) ⇒
                    results foreach { ep ⇒ val (e, p) = ep; update(e, p, OneStepFinalUpdate) }

                case MultiResult(results) ⇒
                    results foreach { ep ⇒ val (e, p) = ep; update(e, p, FinalUpdate) }

                case FallbackResult(e, p) ⇒ update(e, p, FallbackUpdate)

                case IntermediateResult(e, p, dependees: Traversable[EOptionP], c) ⇒
                    val dependerEPK = EPK(e, p.key)
                    dependees foreach { eOptionP ⇒

                        val dependeeE = eOptionP.e
                        val dependeePK = eOptionP.pk
                        val dependeePKId = dependeePK.id
                        val dependeeEPK = EPK(dependeeE, dependeePK)
                        val (dependeeLock, properties) = data.get(dependeeE)

                        val o = {
                            new DefaultPropertyObserver(dependerEPK, false) {
                                def apply(dependeeE: Entity, dependeeP: Property): Unit = {
                                    propagationCount.incrementAndGet()
                                    scheduleContinuation(dependeeE, dependeeP, c)
                                }
                            }
                        }

                        registerObserverWithItsDepender(dependerEPK, dependeeEPK, o)
                        withWriteLock(dependeeLock) {
                            properties(dependeePKId) match {
                                case null       ⇒ properties(dependeePKId) = (null, Buffer(o))
                                case (null, os) ⇒ os += o
                                case (`p`, os)  ⇒ os += o
                                case (dependeeNewP, os) ⇒
                                    // `os` will be null if dependeeNewP is a final property
                                    // and no further refinement is made. (dependeeNewP
                                    // became available after the analysis originally queried
                                    // the database.)
                                    if (dependeeNewP.isRefineable)
                                        os += o
                                    scheduleContinuation(dependeeE, dependeeNewP, c)
                            }
                        }
                    }
                    update(e, p, IntermediateUpdate)

                case suspended @ Suspended(dependerE, dependerPK, dependeeE, dependeePK) ⇒

                    // CONCEPT
                    // First, let's get the property, then...
                    //  - If we now have a property, let's immediately continue
                    //    the computation.
                    //  - If the property is still not computed, register an
                    //    observer that will schedule the computation when the
                    //    property was computed.

                    def createAndRegisterObserver(): PropertyObserver = {
                        val dependerEPK = EPK(dependerE, dependerPK)
                        val dependeeEPK = EPK(dependeeE, dependeePK)
                        val o = new DefaultPropertyObserver(
                            dependerEPK,
                            removeAfterNotification = true) {

                            def apply(dependeeE: Entity, dependeeP: Property): Unit = {
                                propagationCount.incrementAndGet()
                                val pc = (e: AnyRef) ⇒ suspended.continue(dependeeE, dependeeP)
                                scheduleComputation(dependerE, pc)
                            }
                        }
                        registerObserverWithItsDepender(dependerEPK, dependeeEPK, o)
                        o
                    }

                    val (lock, properties) = data.get(dependeeE)
                    val continuation = withWriteLock(lock) {
                        properties(dependeePK.id) match {
                            case null ⇒
                                // this computation is the first who is interested in the property
                                properties(dependeePK.id) = (null, Buffer(createAndRegisterObserver()))
                                null

                            case (dependeeP, dependeeOs) ⇒
                                if (dependeeP eq null) {
                                    // we have other computations that are also waiting...
                                    dependeeOs += createAndRegisterObserver()
                                    null
                                } else {
                                    //OPALLogger.debug(
                                    //    "analysis progress",
                                    //    s"immediately continued the suspended computation of $dependerE($dependerPK) using $dependeeE(dependeeP)")

                                    // the property was computed in the meantime...
                                    // but we don't want to call the continuation while we
                                    // still hold the lock on dependee
                                    ( /*e: AnyRef*/ ) ⇒ suspended.continue(dependeeE, dependeeP)
                                }
                        }
                    }
                    if (continuation ne null) {
                        //scheduleComputation(dependerE, continuation)
                        handleResult(continuation())
                    }
            }
        }
    }
}

/**
 * Factory to create [[PropertyStore]]s.
 */
object PropertyStore {

    def apply(
        entities: Traversable[Entity],
        isInterrupted: () ⇒ Boolean)(
            implicit logContext: LogContext): PropertyStore = {

        val entitiesCount = entities.size
        val map = new JIDMap[Entity, PropertyStoreValue](entitiesCount)

        entities.foreach { e ⇒ map.put(e, (new ReentrantReadWriteLock, ArrayMap.empty)) }

        new PropertyStore(map, isInterrupted)
    }

}

