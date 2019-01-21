/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package tac
package fpcf
package analyses
package cg

import scala.language.existentials

import org.opalj.collection.immutable.UIDSet
import org.opalj.fpcf.EOptionP
import org.opalj.fpcf.EPK
import org.opalj.fpcf.EPS
import org.opalj.fpcf.FinalP
import org.opalj.fpcf.InterimEP
import org.opalj.fpcf.InterimEUBP
import org.opalj.fpcf.InterimPartialResult
import org.opalj.fpcf.InterimUBP
import org.opalj.fpcf.PartialResult
import org.opalj.fpcf.PropertyBounds
import org.opalj.fpcf.PropertyComputationResult
import org.opalj.fpcf.PropertyStore
import org.opalj.fpcf.Results
import org.opalj.fpcf.SomeEPS
import org.opalj.br.fpcf.cg.properties.CallersProperty
import org.opalj.br.fpcf.cg.properties.OnlyVMLevelCallers
import org.opalj.br.DeclaredMethod
import org.opalj.br.DefinedMethod
import org.opalj.br.ObjectType
import org.opalj.br.analyses.DeclaredMethods
import org.opalj.br.analyses.DeclaredMethodsKey
import org.opalj.br.analyses.SomeProject
import org.opalj.br.fpcf.FPCFAnalysis
import org.opalj.br.fpcf.cg.properties.InstantiatedTypes
import org.opalj.br.fpcf.cg.properties.LoadedClasses
import org.opalj.br.fpcf.BasicFPCFEagerAnalysisScheduler

/**
 * Extends the call graph analysis (e.g. [[org.opalj.tac.fpcf.analyses.cg.RTACallGraphAnalysis]]) to
 * include calls to static initializers from within the JVM for each loaded class
 * ([[org.opalj.br.fpcf.cg.properties.LoadedClasses]]).
 *
 * Furthermore, for each instantiated type ([[org.opalj.br.fpcf.cg.properties.InstantiatedTypes]]),
 * it ensures, that its class is also a loaded class.
 *
 * @author Florian Kübler
 */
// TODO This class represents two analyses, please split them up!
// TODO Instead of added the clinits for all super types, add all super types to be loaded
class StaticInitializerAnalysis(val project: SomeProject) extends FPCFAnalysis {

    private val declaredMethods: DeclaredMethods = project.get(DeclaredMethodsKey)

    private case class LCState(
            // only present for non-final values
            var lcDependee:      Option[EOptionP[SomeProject, LoadedClasses]],
            var loadedClassesUB: Option[LoadedClasses],
            var seenClasses:     Int,

            // only present for non-final values
            var itDependee:            Option[EOptionP[SomeProject, InstantiatedTypes]],
            var instantiatedTypesUB:   Option[InstantiatedTypes],
            var seenInstantiatedTypes: Int
    )

    /**
     * For the given project, it registers to the [[org.opalj.br.fpcf.cg.properties.LoadedClasses]]
     * and the [[org.opalj.br.fpcf.cg.properties.InstantiatedTypes]] and ensures that:
     *     1. For each loaded class, its static initializer is called (see
     *     [[org.opalj.br.fpcf.cg.properties.CallersProperty]])
     *     2. For each instantiated type, the type is also a loaded class
     */
    // FIXME "register to" doesn't make sense, here!
    def registerToInstantiatedTypesAndLoadedClasses(p: SomeProject): PropertyComputationResult = {
        val (lcDependee, loadedClassesUB) = propertyStore(project, LoadedClasses.key) match {
            case FinalP(loadedClasses)           ⇒ None → Some(loadedClasses)
            case eps @ InterimUBP(loadedClasses) ⇒ Some(eps) → Some(loadedClasses)
            case epk                             ⇒ Some(epk) → None
        }

        val (itDependee, instantiatedTypesUB) = propertyStore(project, InstantiatedTypes.key) match {
            case FinalP(instantiatedTypes)           ⇒ None → Some(instantiatedTypes)
            case eps @ InterimUBP(instantiatedTypes) ⇒ Some(eps) → Some(instantiatedTypes)
            case epk                                 ⇒ Some(epk) → None
        }

        implicit val state: LCState = LCState(
            lcDependee, loadedClassesUB, 0, itDependee, instantiatedTypesUB, 0
        )

        handleInstantiatedTypesAndLoadedClasses()
    }

    private[this] def handleInstantiatedTypesAndLoadedClasses()(
        implicit
        state: LCState
    ): PropertyComputationResult = {
        val (unseenLoadedClasses, seenClasses, loadedClassesUB) =
            if (state.loadedClassesUB.isDefined) {
                val lcUB = state.loadedClassesUB.get
                (lcUB.getNewClasses(state.seenClasses), lcUB.numElements, lcUB.classes)
            } else {
                (Iterator.empty, 0, UIDSet.empty[ObjectType])
            }
        state.seenClasses = seenClasses

        val (unseenInstantiatedTypes, numUnseenInstantiatedTypes) =
            if (state.instantiatedTypesUB.isDefined) {
                val itUB = state.instantiatedTypesUB.get
                (itUB.getNewTypes(state.seenInstantiatedTypes), itUB.numElements)
            } else (Iterator.empty, 0)
        state.seenInstantiatedTypes = numUnseenInstantiatedTypes

        var newLoadedClasses = UIDSet.empty[ObjectType]
        for (unseenInstantiatedType ← unseenInstantiatedTypes) {
            // todo load class if not already loaded
            if (!loadedClassesUB.contains(unseenInstantiatedType)) {
                newLoadedClasses += unseenInstantiatedType
            }
        }

        val loadedClassesPartialResult = Some(PartialResult[SomeProject, LoadedClasses](
            p,
            LoadedClasses.key,
            {
                case InterimUBP(ub) ⇒
                    val newUb = ub.classes ++ newLoadedClasses
                    // due to monotonicity:
                    // the size check sufficiently replaces the subset check
                    if (newUb.size > ub.classes.size)
                        Some(InterimEUBP(project, ub.updated(newLoadedClasses)))
                    else
                        None

                case _: EPK[_, LoadedClasses] ⇒
                    Some(InterimEUBP(project, LoadedClasses(newLoadedClasses)))

                case r ⇒
                    throw new IllegalStateException(s"unexpected previous result $r")
            }
        ))

        val lcResultOption =
            if (state.itDependee.isDefined || state.lcDependee.isDefined)
                Some(InterimPartialResult(
                    if (newLoadedClasses.nonEmpty) loadedClassesPartialResult else None,
                    state.itDependee ++ state.lcDependee,
                    continuation
                ))
            else if (newLoadedClasses.nonEmpty)
                loadedClassesPartialResult
            else
                None

        /* OLD
        var newCLInits = Set.empty[DeclaredMethod]
        for (newLoadedClass ← unseenLoadedClasses) {
            // todo create result for static initializers
            newCLInits ++= retrieveStaticInitializers(newLoadedClass)
        }

        val callersResult = newCLInits.iterator map { clInit ⇒
            PartialResult[DeclaredMethod, CallersProperty](
                clInit,
                CallersProperty.key,
                {
                    case InterimUBP(ub) if !ub.hasVMLevelCallers ⇒
                        Some(InterimEUBP(clInit, ub.updatedWithVMLevelCall()))

                    case _: InterimEP[_, _] ⇒ None

                    case _: EPK[_, _]       ⇒ Some(InterimEUBP(clInit, OnlyVMLevelCallers))
                }
            )
        }
        */

        val callersResult =
            unseenLoadedClasses
                .flatMap { lc ⇒ retrieveStaticInitializers(lc) }
                .map { clInit ⇒
                    PartialResult[DeclaredMethod, CallersProperty](
                        clInit,
                        CallersProperty.key,
                        {
                            case InterimUBP(ub) if !ub.hasVMLevelCallers ⇒
                                Some(InterimEUBP(clInit, ub.updatedWithVMLevelCall()))

                            case _: InterimEP[_, _] ⇒ None

                            case _: EPK[_, _]       ⇒ Some(InterimEUBP(clInit, OnlyVMLevelCallers))
                        }
                    )
                }

        Results(lcResultOption, callersResult)
    }

    private[this] def continuation(
        someEPS: SomeEPS
    )(
        implicit
        state: LCState
    ): PropertyComputationResult = {
        (someEPS: @unchecked) match {

            case FinalP(loadedClasses: LoadedClasses) ⇒
                state.lcDependee = None
                state.loadedClassesUB = Some(loadedClasses)
                handleInstantiatedTypesAndLoadedClasses()
            case InterimUBP(loadedClasses: LoadedClasses) ⇒
                state.lcDependee = Some(someEPS.asInstanceOf[EPS[SomeProject, LoadedClasses]])
                state.loadedClassesUB = Some(loadedClasses)
                handleInstantiatedTypesAndLoadedClasses()

            case FinalP(instantiatedTypes: InstantiatedTypes) ⇒
                state.itDependee = None
                state.instantiatedTypesUB = Some(instantiatedTypes)
                handleInstantiatedTypesAndLoadedClasses()
            case InterimUBP(instantiatedTypes: InstantiatedTypes) ⇒
                state.itDependee = Some(someEPS.asInstanceOf[EPS[SomeProject, InstantiatedTypes]])
                state.instantiatedTypesUB = Some(instantiatedTypes)
                handleInstantiatedTypesAndLoadedClasses()
        }
    }

    private[this] def retrieveStaticInitializers(
        declaringClassType: ObjectType
    ): Iterator[DefinedMethod] = {
        // TODO only for interfaces with default methods
        ch.allSuperclassesIterator(declaringClassType, reflexive = true).flatMap { cf ⇒
            // IMPROVE Only return the static initializer if it is not already present
            cf.staticInitializer map { clInit ⇒ declaredMethods(clInit) }
        }
    }

}

object TriggeredStaticInitializerAnalysis extends BasicFPCFEagerAnalysisScheduler {

    override def uses: Set[PropertyBounds] = PropertyBounds.ubs(LoadedClasses, InstantiatedTypes)

    override def derivesCollaboratively: Set[PropertyBounds] = PropertyBounds.ubs(
        LoadedClasses,
        CallersProperty
    )

    override def derivesEagerly: Set[PropertyBounds] = Set.empty

    override def start(p: SomeProject, ps: PropertyStore, unused: Null): FPCFAnalysis = {
        val analysis = new StaticInitializerAnalysis(p)
        ps.scheduleEagerComputationForEntity(p)(
            analysis.registerToInstantiatedTypesAndLoadedClasses
        )
        analysis
    }

}