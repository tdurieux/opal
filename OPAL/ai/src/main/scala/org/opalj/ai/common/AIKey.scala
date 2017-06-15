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
package ai
package common

import scala.collection.concurrent.TrieMap

import org.opalj.br.Method
import org.opalj.br.ClassFile
import org.opalj.br.analyses.ProjectInformationKey
import org.opalj.br.analyses.SomeProject
import org.opalj.ai.domain.RecordDefUse

/**
 * ''Key'' to get the result of the abstract interpretation of a method.
 *
 * @author Michael Eichberg
 */
trait AIKey extends ProjectInformationKey[Method ⇒ AIResult { val domain: RecordDefUse }]

/**
 * Key to get the result of the abstract interpreation of a method using a configured domain
 * factory.
 *
 * @example To get the index use the [[org.opalj.br.analyses.Project]]'s `get` method and
 *          pass in `this` object.
 *
 * @author Michael Eichberg
 */
object SimpleAIKey extends AIKey {

    @volatile var domainFactory: (SomeProject, ClassFile, Method) ⇒ Domain with RecordDefUse =
        (p: SomeProject, cf: ClassFile, m: Method) ⇒ {
            new domain.l1.DefaultDomainWithCFGAndDefUse(p, cf, m)
        }

    /**
     * The SimpleAIKey has no special prerequisites.
     */
    override protected def requirements: Seq[ProjectInformationKey[Nothing]] = Nil

    /**
     * Returns an object which performs and caches the result of the abstract interpretation of a
     * method when required.
     *
     * All methods belonging to a project are analyzed using the same `domainFactory`. Hence,
     * the `domainFactory` needs to be set before compute is called/this key is passed to a
     * specific project. If multiple projects are instead concurrently, external synchronization
     * is necessary (e.g., on the ProjectInformationKey) to ensure that each project is
     * instantiated using the desired domain.
     */
    override protected def compute(
        project: SomeProject
    ): Method ⇒ AIResult { val domain: RecordDefUse } = {
        val domainFactory = this.domainFactory

        val aiResults = TrieMap.empty[Method, AIResult { val domain: RecordDefUse }]

        (m: Method) ⇒ {
            aiResults.get(m) match {
                case Some(taCode) ⇒ taCode
                case None ⇒
                    val brCode = m.body.get
                    // Basically, we use double checked locking; we really don't want to
                    // transform the code more than once!
                    brCode.synchronized {
                        aiResults.get(m) match {
                            case Some(aiResult) ⇒ aiResult
                            case None ⇒
                                val cf = project.classFile(m)
                                val domain = domainFactory(project, cf, m)
                                val aiResult = BaseAI(cf, m, domain)
                                aiResults.put(m, aiResult)
                                aiResult
                        }
                    }
            }
        }
    }
}
