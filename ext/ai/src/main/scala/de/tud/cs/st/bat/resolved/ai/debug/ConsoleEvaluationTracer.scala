/* License (BSD Style License):
 * Copyright (c) 2009 - 2013
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
 *  - Neither the name of the Software Technology Group or Technische
 *    Universität Darmstadt nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific
 *    prior written permission.
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
package de.tud.cs.st
package bat
package resolved
package ai
package debug

import instructions.Instruction

/**
 * A tracer that prints out the evaluation order on the console.
 *
 * Every AI should have its own instance.
 *
 * @author Michael Eichberg
 */
trait ConsoleEvaluationTracer extends AITracer {

    private[this] var indent = 0

    override def instructionEvalution(
        domain: SomeDomain)(
            pc: PC,
            instruction: Instruction,
            operands: List[domain.DomainValue],
            locals: Array[domain.DomainValue]): Unit = {
        print(pc+" ")
    }

    override def continuingInterpretation(
        code: Code,
        domain: SomeDomain)(
            initialWorkList: List[PC],
            alreadyEvaluated: List[PC],
            operandsArray: Array[List[domain.DomainValue]],
            localsArray: Array[Array[domain.DomainValue]]) { /*EMPTY*/ }

    override def rescheduled(
        domain: SomeDomain)(
            sourcePC: PC,
            targetPC: PC): Unit = { /*EMPTY*/ }

    override def flow(
        domain: SomeDomain)(
            currentPC: PC,
            targetPC: PC): Unit = { /*EMPTY*/ }

    override def join(
        domain: SomeDomain)(
            pc: PC,
            thisOperands: domain.Operands,
            thisLocals: domain.Locals,
            otherOperands: domain.Operands,
            otherLocals: domain.Locals,
            result: Update[(domain.Operands, domain.Locals)]): Unit = { /*EMPTY*/ }

    override def abruptMethodExecution(
        domain: SomeDomain)(
            pc: Int,
            exception: domain.DomainValue): Unit = { /*EMPTY*/ }

    private[this] def printIndent = (0 until indent) foreach (i ⇒ print("\t"))

    override def jumpToSubroutine(domain: SomeDomain)(pc: PC): Unit = {
        println
        printIndent
        print(Console.BOLD+"↳\t︎"+Console.RESET)
        indent += 1
    }

    override def returnFromSubroutine(
        domain: SomeDomain)(
            pc: PC,
            returnAddress: PC,
            subroutineInstructions: List[PC]): Unit = {
        indent -= 1
        println(Console.BOLD+"✓"+"(Resetting: "+subroutineInstructions.mkString(", ")+")"+Console.RESET)
        printIndent
    }

    /**
     * Called when a ret instruction is encountered.
     */
    override def ret(
        domain: SomeDomain)(
            pc: PC,
            returnAddress: PC,
            oldWorklist: List[PC],
            newWorklist: List[PC]): Unit = { /*EMPTY*/ }

    override def result(result: AIResult) { /*EMPTY*/ }
}
