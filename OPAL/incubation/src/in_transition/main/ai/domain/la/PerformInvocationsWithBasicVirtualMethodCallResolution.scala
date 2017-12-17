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
package domain
package la

import org.opalj.br.ReferenceType
import org.opalj.br.MethodDescriptor
import org.opalj.br.PC
import org.opalj.ai.analyses.cg.Callees
import org.opalj.ai.domain.l2.PerformInvocations
import org.opalj.br.analyses.cg.TypeExtensibilityKey

/**
 * Mix in this trait if methods that are called by `invokeXYZ` instructions should
 * actually be interpreted using a custom domain.
 *
 * @author Michael Eichberg
 */
trait PerformInvocationsWithBasicVirtualMethodCallResolution
    extends PerformInvocations
    with Callees {
    callingDomain: ValuesFactory with ReferenceValuesDomain with Configuration with TheProject with TheMethod ⇒

    lazy val isExtensible = project.get(TypeExtensibilityKey)

    /**
     * The default implementation only supports the case where we can precisely resolve the target.
     */
    override def doInvokeVirtual(
        pc:             PC,
        declaringClass: ReferenceType,
        isInterface:    Boolean,
        name:           String,
        descriptor:     MethodDescriptor,
        operands:       Operands,
        fallback:       () ⇒ MethodCallResult
    ): MethodCallResult = {

        def handleVirtualInvokeFallback(): MethodCallResult = {
            val receiver = operands(descriptor.parametersCount)

            receiver match {
                case DomainReferenceValue(refValue) if (
                    refValue.isNull.isNo && // IMPROVE the case if the value maybe null
                    refValue.upperTypeBound.isSingletonSet &&
                    refValue.upperTypeBound.head.isObjectType
                ) ⇒
                    val receiverType = refValue.upperTypeBound.head.asObjectType
                    if (isExtensible(receiverType).isYesOrUnknown)
                        return fallback();

                    val methods = classHierarchy.isInterface(receiverType) match {
                        case Yes ⇒ callees(method, receiverType, true, name, descriptor)
                        case No  ⇒ callees(method, receiverType, false, name, descriptor)
                        case _   ⇒ return fallback();
                    }
                    if (methods.size == 1) {
                        testAndDoInvoke(pc, methods.head, operands, fallback)
                    } else {
                        fallback()
                    }

                case _ ⇒
                    fallback()
            }
        }

        super.doInvokeVirtual(
            pc,
            declaringClass, isInterface, name, descriptor,
            operands,
            handleVirtualInvokeFallback _
        )
    }

}