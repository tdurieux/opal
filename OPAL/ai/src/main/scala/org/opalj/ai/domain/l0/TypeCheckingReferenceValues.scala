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
package l0

import scala.reflect.ClassTag

import org.opalj.collection.immutable.UIDSet
import org.opalj.br.ArrayType
import org.opalj.br.ObjectType

/**
 * Default implementation for handling reference values in such a way that we can compute
 * the type information required to construct the StackMapTable attribute.
 *
 * @author Michael Eichberg
 */
trait TypeCheckingReferenceValues
    extends DefaultTypeLevelReferenceValues
    with DefaultExceptionsFactory {
    domain: IntegerValuesDomain with TypedValuesFactory with Configuration with TheClassHierarchy ⇒

    type AReferenceValue = ReferenceValue
    type DomainReferenceValue = AReferenceValue

    final val DomainReferenceValue: ClassTag[DomainReferenceValue] = implicitly

    type DomainNullValue = NullValue
    type DomainObjectValue = ObjectValue
    type DomainArrayValue = ArrayValue

    val TheNullValue: DomainNullValue = new NullValue()

    // -----------------------------------------------------------------------------------
    //
    // REPRESENTATION OF REFERENCE VALUES
    //
    // -----------------------------------------------------------------------------------

    protected class InitializedObjectValue(
            theUpperTypeBound: ObjectType
    ) extends SObjectValue(theUpperTypeBound) {
        this: DomainObjectValue ⇒

        // WIDENING OPERATION
        override protected def doJoin(pc: PC, other: DomainValue): Update[DomainValue] = {
            other match {
                case _: UninitializedObjectValue ⇒ MetaInformationUpdateIllegalValue
                case that                        ⇒ super.doJoin(pc, that)
            }
        }
    }

    /**
     * @param pc The pc of the new instruction or -1 if this represents "uninitialized size".
     */
    protected case class UninitializedObjectValue(
            theType: ObjectType,
            pc:      PC
    ) extends SObjectValue(theType) {
        this: DomainObjectValue ⇒

        override def isPrecise: Boolean = true

        // joins of an uninitialized value with null results in an illegal value
        override def isNull: Answer = No

        // WIDENING OPERATION
        override protected def doJoin(pc: PC, other: DomainValue): Update[DomainValue] = {
            other match {
                case UninitializedObjectValue(`theType`, `pc`) ⇒ NoUpdate
                // this value is not completely useable...
                case _                                         ⇒ MetaInformationUpdateIllegalValue
            }
        }

        override def abstractsOver(other: DomainValue): Boolean = {
            other match {
                case that: UninitializedObjectValue if (
                    (that.theType eq this.theType) && this.pc == that.pc
                ) ⇒
                    true
                case _ ⇒
                    false
            }
        }

        override def adapt(target: TargetDomain, origin: ValueOrigin): target.DomainValue = {
            target.NewObject(origin, theUpperTypeBound)
        }

    }

    // -----------------------------------------------------------------------------------
    //
    // FACTORY METHODS
    //
    // -----------------------------------------------------------------------------------

    override def NullValue(origin: ValueOrigin): DomainNullValue = TheNullValue

    override def NewObject(pc: PC, objectType: ObjectType): DomainObjectValue = {
        new UninitializedObjectValue(objectType, pc)
    }

    override def InitializedObjectValue(pc: PC, objectType: ObjectType): DomainObjectValue = {
        new InitializedObjectValue(objectType)
    }

    override def ObjectValue(origin: ValueOrigin, objectType: ObjectType): DomainObjectValue = {
        new InitializedObjectValue(objectType)
    }

    override def ObjectValue(
        origin:         ValueOrigin,
        upperTypeBound: UIDSet[ObjectType]
    ): DomainObjectValue = {
        if (upperTypeBound.isSingletonSet)
            ObjectValue(origin, upperTypeBound.head)
        else
            new MObjectValue(upperTypeBound)
    }

    override def ArrayValue(origin: ValueOrigin, arrayType: ArrayType): DomainArrayValue = {
        new ArrayValue(arrayType)
    }

}
