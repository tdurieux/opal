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
package analyses

import org.opalj.br.ObjectType
import org.opalj.br.ClassFile
import org.opalj.br.analyses.SomeProject
import org.opalj.br.instructions.INVOKESPECIAL
import org.opalj.util.GlobalPerformanceEvaluation

import scala.collection.mutable
import org.opalj.fpcf.properties.NotInstantiable
import org.opalj.fpcf.properties.Instantiable
import org.opalj.fpcf.properties.Instantiability

/**
 * This analysis determines which classes can never be instantiated (e.g.,
 * `java.lang.Math`).
 *
 * A class is not instantiable if:
 *  - it only defines private constructors and these constructors are not called
 *    by any static method and the class does not implement Serializable.
 *
 * @note This analysis depends on the project configuration which encodes the analysis mode.
 *       Different analysis modes are: library with open or closed packages assumption or application
 *
 * This information is relevant in various contexts, e.g., to determine
 * precise call graph. For example, instance methods of those objects that cannot be
 * created are always dead.
 *
 * ==Usage==
 * Use the [[FPCFAnalysesManagerKey]] to query the analysis manager of a project. You can run
 * the analysis afterwards as follows:
 * {{{
 *  val analysisManager = project.get(FPCFAnalysisManagerKey)
 *  analysisManager.run(InstantiabilityAnalysis)
 * }}}
 * For detailed information see the documentation of the analysis manager.
 *
 * The results of this analysis are stored in the property store of the project. You can receive
 * the results as follows:
 * {{{
 * val theProjectStore = theProject.get(PropertyStoreKey)
 * val instantiableClasses = theProjectStore.entities { (p: Property) ⇒
 * p == Instantiable
 * }
 * }}}
 *
 * @note The analysis does not take reflective instantiations into account!
 */
class SimpleInstantiabilityAnalysis private (val project: SomeProject) extends FPCFAnalysis {

    import project.classHierarchy.allSubclassTypes

    def determineProperty(
        key:        String,
        classFiles: Seq[ClassFile]
    ): Traversable[EP[ClassFile, Instantiability]] = {

        GlobalPerformanceEvaluation.time('inst) {

            val instantiatedClasses = new Array[EP[ClassFile, Instantiability]](classFiles.length)
            val seenConstructors = mutable.Set.empty[ClassFile]

            var pos = 0
            while (pos < classFiles.length) {
                val classFile = classFiles(pos)
                if (project.libraryClassFilesAreInterfacesOnly && project.isLibraryType(classFile)) {
                    if (classFile.isAbstract) {
                        val hasInstantiableSubtype = allSubclassTypes(classFile.thisType, reflexive = false).exists {
                            subtype ⇒
                                project.classFile(subtype) match {
                                    case Some(cf) ⇒ !cf.isAbstract
                                    case None     ⇒ true
                                }
                        }
                        if (hasInstantiableSubtype)
                            instantiatedClasses(pos) = EP(classFile, Instantiable)
                        else
                            instantiatedClasses(pos) = EP(classFile, NotInstantiable)
                    } else
                        instantiatedClasses(pos) = EP(classFile, Instantiable)
                } else {
                    classFile.methods foreach { method ⇒
                        if (method.isNative && method.isStatic) {
                            val instantiatedClasses = mutable.Set.empty[EP[ClassFile, Instantiability]]
                            classFiles.foreach { classFile ⇒
                                if (classFile.isAbstract &&
                                    (isDesktopApplication || (isClosedLibrary && classFile.isPackageVisible)))
                                    instantiatedClasses += EP(classFile, NotInstantiable)
                                else
                                    instantiatedClasses += EP(classFile, Instantiable)
                            }
                            // we can stop here, we have to assume that native methods instantiate every package visible class
                            return instantiatedClasses;

                        } else if (method.body.nonEmpty) {
                            // prevents the analysis of native instance methods..

                            val body = method.body.get
                            val instructions = body.instructions
                            val max = instructions.length
                            var pc = 0
                            while (pc < max) {
                                val instruction = instructions(pc)
                                if (instruction.opcode == INVOKESPECIAL.opcode) {
                                    instruction match {
                                        case INVOKESPECIAL(classType, _, "<init>", _) if classType.packageName == key ⇒
                                            // We found a constructor call.
                                            val classFile = project.classFile(classType)
                                            if (classFile.nonEmpty) {
                                                seenConstructors += classFile.get
                                            }
                                        case _ ⇒
                                    }
                                }
                                pc = body.pcOfNextInstruction(pc)
                            }
                        } else {
                            // we dont know what happens, be conservative
                            if (!method.isAbstract)
                                instantiatedClasses(pos) = EP(classFile, Instantiable)
                        }
                    }
                }
                pos += 1
            }

            pos = 0
            while (pos < classFiles.length) {
                val resultClassFile = instantiatedClasses(pos)
                if (resultClassFile eq null) {
                    val entityClassFile = classFiles(pos)
                    val constructorInvoked = seenConstructors.collectFirst { case cf: ClassFile if (cf eq entityClassFile) ⇒ cf }
                    constructorInvoked match {
                        case Some(cf) ⇒ instantiatedClasses(pos) = EP(entityClassFile, Instantiable)
                        case None     ⇒ instantiatedClasses(pos) = determineClassInstantiability(entityClassFile)
                    }
                }
                pos += 1
            }

            instantiatedClasses
        }
    }

    def determineClassInstantiability(classFile: ClassFile): EP[ClassFile, Instantiability] = {
        import project.classHierarchy.isSubtypeOf

        if (classFile.isAbstract || classFile.isInterfaceDeclaration) {
            if (isDesktopApplication || (isClosedLibrary && classFile.isPackageVisible))
                // if we analyze an application, abstract classes are not instantiable
                // if we analyze an library, abstract classes could have subtypes in the future
                // hence, we have to assume that the methods of the class are called by future subtypes.
                // if the class is not visible to client, we can consider it as not instantiable, because
                // we know all subtypes and if a method is invoked then, we will recognize it.
                return EP(classFile, NotInstantiable);
        }

        val classType = classFile.thisType

        if (isSubtypeOf(classType, ObjectType.Serializable).isYesOrUnknown &&
            classFile.hasDefaultConstructor)
            //if the class is Serializable or it is unknown, we have to count it as instantiated.
            return EP(classFile, Instantiable)

        val notFinal = !classFile.isFinal
        if ((classFile.isPublic || isOpenLibrary)) {
            if (classFile.constructors exists { cons ⇒
                cons.isPublic ||
                    (isOpenLibrary && !cons.isPrivate) ||
                    (notFinal && cons.isProtected)
                //If the class not final and public or we analyze an open library we have
                //to assume that a subclass is created and instantiated later on.
                //Hence, every time a subclass is instantiated all superclass's have to be
                //considered instantiated as well.
            })
                return EP(classFile, Instantiable);
        }

        return EP(classFile, NotInstantiable);
    }
}

/**
 * Companion object for the [[SimpleInstantiabilityAnalysis]] class.
 */
object SimpleInstantiabilityAnalysis extends FPCFAnalysisRunner {

    final def definingPackage(cf: ClassFile): String = cf.thisType.packageName

    override def derivedProperties: Set[PropertyKind] = Set(Instantiability)

    def start(project: SomeProject, propertyStore: PropertyStore): FPCFAnalysis = {
        val analysis = new SimpleInstantiabilityAnalysis(project)
        propertyStore.execute(PropertyStore.entitySelector[ClassFile], groupBy = definingPackage)(
            analysis.determineProperty
        )
        analysis
    }
}