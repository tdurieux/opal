/* BSD 2-Clause License:
 * Copyright (c) 2009 - 2016
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
package br
package instructions

import org.opalj.collection.mutable.UShortSet
import org.opalj.br.ObjectType.OutOfMemoryError

/**
 * Create new object.
 *
 * @author Michael Eichberg
 */
case class NEW(objectType: ObjectType) extends Instruction with ConstantLengthInstruction {

    final def opcode: Opcode = NEW.opcode

    final def mnemonic: String = "new"

    final def jvmExceptions: List[ObjectType] = NEW.jvmExceptions

    final def length: Int = 3

    final def numberOfPoppedOperands(ctg: Int ⇒ ComputationalTypeCategory): Int = 0

    final def numberOfPushedOperands(ctg: Int ⇒ ComputationalTypeCategory): Int = 1

    final def stackSlotsChange: Int = 1

    final def isIsomorphic(thisPC: PC, otherPC: PC)(implicit code: Code): Boolean = {
        this == code.instructions(otherPC)
    }

    final def readsLocal: Boolean = false

    final def indexOfReadLocal: Int = throw new UnsupportedOperationException()

    final def writesLocal: Boolean = false

    final def indexOfWrittenLocal: Int = throw new UnsupportedOperationException()

    final def nextInstructions(
        currentPC:             PC,
        regularSuccessorsOnly: Boolean
    )(
        implicit
        code: Code
    ): PCs = {
        if (regularSuccessorsOnly)
            UShortSet(indexOfNextInstruction(currentPC))
        else
            Instruction.nextInstructionOrExceptionHandler(this, currentPC, OutOfMemoryError)
    }

    final def expressionResult: Stack.type = Stack

    override def toString: String = "NEW "+objectType.toJava

}

object NEW {

    final val opcode = 187

    final val jvmExceptions = List(ObjectType.OutOfMemoryError)

    def apply(fqn: String): NEW = NEW(ObjectType(fqn))

}
