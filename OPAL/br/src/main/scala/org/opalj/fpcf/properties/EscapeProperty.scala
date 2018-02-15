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
package properties

import scala.annotation.switch
import org.opalj.fpcf.PropertyKey.SomeEPKs

sealed trait EscapePropertyMetaInformation extends PropertyMetaInformation {

    final type Self = EscapeProperty
}

/**
 * Specifies the lifetime of object instance. This is classically used for compiler optimizations
 * such as scalar replacement, stack allocation or removal of synchronization.
 * However, other usages such as finding bugs or helping to identify immutable data-structures
 * are also supported.
 *
 * == Definitions in the Literature ==
 * Choi et al. [1] describe two predicates that can be used to describe the properties relevant
 * to escape information.
 *
 * "Let O be an object instance and M be a method invocation. O is said to escape M, denoted as
 * Escapes(O, M), if the lifetime of O may exceed the lifetime of M."
 *
 * "Let O be an object instance and T be a thread (instance). O is said to escape T, again
 * denoted as Escapes(O, T), if another thread, T’ != T, may access O."
 *
 * Furthermore it holds that "For any object O, !Escapes(O, M) implies !Escapes(O, T), where method
 * M is invoked in thread T." [1]
 *
 * In contrast to this, Kotzmann and Mössenböck [2] describe the escape of an object with the access
 * to this object from other methods or threads.
 *
 *
 * == Definition ==
 * This EscapeProperty combines both concepts and is more specific about the reason why an object
 * escapes to facilitate comprehension of the results.
 *
 * In the following, we provide further details about the different escape properties:
 *
 * [[NoEscape]] refers to the property of an object instance O created in method M for that
 * !Escapes(O, M) holds and no other method than M has access to O. This implies that there is no
 * method M' != M that can access O (at least when disregarding reflection and native code).
 * Objects with this property can be allocated at the stack or even scalar-replaced [2].
 *
 * An object instance O created in method M and thread T has the property [[EscapeInCallee]], if it
 * holds !Escapes(O, M) but M passes O as a parameter to a method which does not let O escape any
 * further then [[EscapeInCallee]]. This implies that only M and methods M' that are (transitively)
 * called by M have access to O.
 * For objects that have the property [[EscapeInCallee]] no synchronization is needed and they can
 * be allocated on the stack.
 *
 * For objects O, created in method M and thread T, whose lifetime exceeds its method of creation M
 * and is (therefore) accessible by other methods, we provide seven different properties. For all of
 * them we assume that O M and all methods called by M do not let O escape T. But it is not
 * guaranteed that O will not escape T via a caller of M.
 * The properties differ in the reason why the lifetime of O exceeds the lifetime of M.
 * In case of [[EscapeViaReturn]] O is returned by M. If O has an exception type and
 * is thrown in M, it has the property [[EscapeViaAbnormalReturn]].
 * For both of them it has no consequences if O escapes T via a caller of M. This is, because
 * the execution ends with the (abnormal) return of O. All synchronization mechanisms inside of M
 * or callees of M can be removed.
 * The property [[EscapeViaParameter]] describes objects that gets assigned to a parameter of its
 * method of creation (M). If O gets assigned to p.f for a parameter p of M, it could be the case
 * that the actual parameter of p already escaped T. In this case O would also escape T directly
 * via this assignment. Therefore no synchronization for O can be removed.
 * As it could be also the case that O gets assigned to a parameter and returned by M, there are
 * also properties representing the combinations of this kind of escapes. They are
 * [[EscapeViaParameterAndAbnormalReturn]], [[EscapeViaParameterAndReturn]],
 * [[EscapeViaNormalAndAbnormalReturn]] and [[EscapeViaParameterAndNormalAndAbnormalReturn]].
 *
 * An object instance O created in method M and thread T has the property [[GlobalEscape]], if it
 * holds that Escapes(O, M) and Escapes(O, T). For example, this is the case if O gets assigned to
 * a static field ([[EscapeViaStaticField]] but also if assigned to a field of an
 * object that has also [[GlobalEscape]] as property ([[EscapeViaHeapObject]]).
 * Objects that have the property  [[GlobalEscape]] have to be allocated on the heap and
 * synchronization mechanisms can not be removed/proper synchronization is required if the
 * object is accessed concurrently – the latter may be the goal of static analyses that find
 * concurrency bugs). If the reason for the global escape is unspecified the case class
 * [[GlobalEscape]] is used.
 *
 * The property values are partially ordered and form a lattice. The binary relation of the order is
 * called `lessOrEqualRestrictive` and describes the restrictiveness of the scope in, which objects
 * exists. The most restrictive (top) value is [[NoEscape]] and the least restrictive (bottom) one
 * is [[GlobalEscape]].
 * A dot graph of the lattice can be found under br/src/main/resources/org/opalj/fpcf/properties.
 *
 * Algorithms are free to over approximate this property, i.e. for object
 * instance O with actual property P it is okay to say O has property P' if P > P' (or in other
 * words, P' is less restrictive than P).
 *
 * If they simply don't know the actual property they should use [[AtMost]]([[NoEscape]]).
 * If we know that the actual property is at most [[EscapeInCallee (i.e. not [[NoEscape]]),
 * [[AtMost]]([[EscapeInCallee]]) should be used.
 * The same holds for every other non-bottom property.
 * E.g. [[AtMost]]([[EscapeViaParameter]]) should be used if we know that the actual property is at most
 * [[EscapeViaParameter]] (i.e. neither [[NoEscape]] nor [[EscapeInCallee]].
 *
 * [[org.opalj.br.AllocationSite]] and [[org.opalj.br.analyses.VirtualFormalParameter]] are generally
 * used as [[Entity]] in combination with this property.
 *
 * [1] Choi, Jong-Deok, Manish Gupta, Mauricio Serrano, Vugranam C. Sreedhar, and Sam Midkiff.
 * "Escape Analysis for Java." In Proceedings of the 14th ACM SIGPLAN Conference on
 * Object-Oriented Programming, Systems, Languages, and Applications, 1–19. OOPSLA ’99.  New
 * York, NY, USA: ACM, 1999.
 *
 * [2] Kotzmann, Thomas, and Hanspeter Mössenböck. “Escape Analysis in the Context of Dynamic
 * Compilation and Deoptimization.” In Proceedings of the 1st ACM/USENIX International Conference
 * on Virtual Execution Environments, 111–120. VEE ’05. New York, NY, USA: ACM, 2005.
 *
 * @author Florian Kuebler
 */
sealed abstract class EscapeProperty
    extends OrderedProperty
    with ExplicitlyNamedProperty
    with EscapePropertyMetaInformation {

    final def key: PropertyKey[EscapeProperty] = EscapeProperty.key

    override def isValidSuccessorOf(old: OrderedProperty): Option[String] = {
        old match {
            case Conditional(oldP) if this lessOrEqualRestrictive oldP ⇒ None
            case AtMost(oldP) if this lessOrEqualRestrictive oldP ⇒ None
            case p ⇒ Some(s"illegal refinement of property $p to $this")
        }
    }

    /**
     * A unique id for every escape property. Used for table switches.
     */
    def propertyValueID: Int

    /**
     * Tests if this property describes equal or less restricted escapes than the given property.
     * E.g., returns `true` if this property identifies values which [[GlobalEscape]] and the given
     * property (`that`) refers to values that [[NoEscape]].
     *
     * @see [[EscapeProperty]] for further details.
     */

    def lessOrEqualRestrictive(that: EscapeProperty): Boolean

    /**
     * Computes the greatest lower bound of this and that property values.
     *
     * @param that the other escape property value.
     * @return the most restrictive escape that is less or equal restrictive than `this` and `that`.
     * @see [[EscapeProperty.lessOrEqualRestrictive]]
     */
    def meet(that: EscapeProperty): EscapeProperty

    /**
     * Is this the bottom value of the lattice, i.e. [[GlobalEscape]], [[EscapeViaHeapObject]] or
     * [[EscapeViaStaticField]].
     */
    def isBottom: Boolean

    /**
     * Is this the top value of the lattice, i.e. [[NoEscape]].
     */
    def isTop: Boolean
}

sealed abstract class FinalEscapeProperty extends EscapeProperty {
    override def isRefinable: Boolean = false
    def meet(that: FinalEscapeProperty): FinalEscapeProperty
}

object EscapeProperty extends EscapePropertyMetaInformation {

    /**
     * This is the default cycle resolution strategy. The resulting property is
     * the meet of all properties in the cycle. Only [[Conditional]] escape properties are expected
     * within the cycle.
     */
    val cycleResolutionStrategy: (PropertyStore, SomeEPKs) ⇒ Iterable[PropertyComputationResult] =
        (ps: PropertyStore, epks: SomeEPKs) ⇒ {
            epks.map { epk ⇒
                ps(epk) match {
                    case EP(e, Conditional(AtMost(p))) ⇒
                        RefinableResult(e, AtMost(p))
                    case EP(e, VirtualMethodEscapeProperty(Conditional(AtMost(p)))) ⇒
                        RefinableResult(e, AtMost(p))

                    case EP(e, Conditional(p)) ⇒
                        Result(e, p)
                    case EP(e, VirtualMethodEscapeProperty(Conditional(p))) ⇒
                        Result(e, p)

                    case _ ⇒ throw new RuntimeException("Non-conditional in cycle")
                }
            }
        }

    final lazy val key: PropertyKey[EscapeProperty] = PropertyKey.create(
        // Name of the property
        "EscapeProperty",
        // fallback value
        AtMost(NoEscape),
        // cycle-resolution strategy
        cycleResolutionStrategy
    )
}

/**
 * The object is accessible only from within the method of creation. Objects with this
 * escape level are also referred to as being method-local.
 *
 *  * @example
 * Given the following code:
 * {{{
 * public class Circle{
 *  public int area;
 *  public final static int PI = 3;
 *
 *  public Circle(int radius){
 *   this.area = PI*radius*radius;
 *  }
 *
 *  public static int areaOfCircle(int r) {
 *   Circle c = new Circle(3);        // ALLOCATION SITE
 *   return c.area;
 *  }
 * }
 * }}}
 *
 * @see [[EscapeProperty]] for further details.
 */
case object NoEscape extends FinalEscapeProperty {

    final val PID = 0

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "No"

    override def meet(that: EscapeProperty): EscapeProperty = that

    override def meet(that: FinalEscapeProperty): FinalEscapeProperty = that

    override final def lessOrEqualRestrictive(that: EscapeProperty): Boolean = PID == that.propertyValueID

    override def isBottom: Boolean = false

    override def isTop: Boolean = true
}

/**
 * The object escapes the current method M via the arguments of a method M' that is called by M
 * but does not let the argument escape. This implies that the object is also local to the thread.
 *
 * @example
 * Given the following code:
 * {{{
 * public class X{
 *  public Object f;
 *  public void foo() {
 *   Object o = new Object();        // ALLOCATION SITE
 *   bar(o);
 *  }
 *  public int bar(Object p) {
 *   if (p == null) // do not let p escape
 *    return -1;
 *   return 0;
 *  }
 * }
 * }}}
 * An analysis is only expected to return [[EscapeInCallee]] for the object o
 * instantiated in foo, if the analyses knows(!) that no subclass of X overrides bar s.t. it let
 * its parameter escape.
 * @see [[EscapeProperty]] for further details.
 * @author Florian Kuebler
 */
case object EscapeInCallee extends FinalEscapeProperty {

    final val PID = 1

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "InCallee"

    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean =
        (that.propertyValueID: @switch) match {
            case NoEscape.PID | EscapeInCallee.PID ⇒ true
            case _                                 ⇒ false
        }

    override def isBottom: Boolean = false

    override def isTop: Boolean = false

    override def meet(that: EscapeProperty): EscapeProperty = that match {
        case that: FinalEscapeProperty ⇒ this meet that
        case _: GlobalEscape           ⇒ that
        case Conditional(property)     ⇒ Conditional(property meet this)
        case AtMost(property)          ⇒ AtMost(property meet this)
    }
    override def meet(that: FinalEscapeProperty): FinalEscapeProperty = (that.propertyValueID: @switch) match {
        case NoEscape.PID ⇒ this
        case _            ⇒ that
    }
}

/**
 * Characterizes escapes via an assignment to a field of a method parameter. It may also escape
 * [[EscapeInCallee]].
 * For a given entity this characterizes only the escape state within its method of creation (M),
 * i.e. it could escape globally in a caller of M. As the actual parameter could escaped globally
 * before the call of M, the entity could also be global within M. Analyses are expected to be
 * context insensitive and do not check all callers of M.
 *
 * @example
 * Given the following code:
 * {{{
 * public class X{
 *  public Object f;
 *  private void foo(X param) {
 *   param.f = new Object();        // ALLOCATION SITE
 *  }
 * }
 * }}}
 */
case object EscapeViaParameter extends FinalEscapeProperty {

    final val PID = 2

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "ViaParameter"

    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean =
        (that.propertyValueID: @switch) match {
            case NoEscape.PID | EscapeInCallee.PID | EscapeViaParameter.PID ⇒ true
            case _ ⇒ false
        }

    override def isBottom: Boolean = false

    override def isTop: Boolean = false

    override def meet(that: EscapeProperty): EscapeProperty = that match {
        case that: FinalEscapeProperty ⇒ this meet that
        case _: GlobalEscape           ⇒ that
        case Conditional(property)     ⇒ Conditional(property meet this)
        case AtMost(property)          ⇒ AtMost(property meet this)
    }
    override def meet(that: FinalEscapeProperty): FinalEscapeProperty = (that.propertyValueID: @switch) match {
        case NoEscape.PID | EscapeInCallee.PID    ⇒ this
        case EscapeViaReturn.PID                  ⇒ EscapeViaParameterAndReturn
        case EscapeViaAbnormalReturn.PID          ⇒ EscapeViaParameterAndAbnormalReturn
        case EscapeViaNormalAndAbnormalReturn.PID ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case _                                    ⇒ that
    }
}

/**
 * Characterizes escapes via a return statement. It may also escape [[EscapeInCallee]].
 * For a given entity this characterizes only the escape state within its method of creation (M),
 * i.e. it could escape globally in a caller of M.
 *
 * @example
 * Given the following code:
 * {{{
 * public class X{
 *  public Object f;
 *  public Object foo() {
 *   Object o = new Object();        // ALLOCATION SITE
 *   return o;
 *  }
 * }
 * }}}
 */
case object EscapeViaReturn extends FinalEscapeProperty {

    final val PID = 3

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "ViaReturn"

    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean =
        (that.propertyValueID: @switch) match {
            case NoEscape.PID | EscapeInCallee.PID | EscapeViaReturn.PID ⇒ true
            case _ ⇒ false
        }

    override def isBottom: Boolean = false

    override def isTop: Boolean = false

    override def meet(that: EscapeProperty): EscapeProperty = that match {
        case that: FinalEscapeProperty ⇒ this meet that
        case _: GlobalEscape           ⇒ that
        case Conditional(property)     ⇒ Conditional(property meet this)
        case AtMost(property)          ⇒ AtMost(property meet this)
    }

    override def meet(that: FinalEscapeProperty): FinalEscapeProperty = (that.propertyValueID: @switch) match {
        case NoEscape.PID | EscapeInCallee.PID       ⇒ this
        case EscapeViaParameter.PID                  ⇒ EscapeViaParameterAndReturn
        case EscapeViaAbnormalReturn.PID             ⇒ EscapeViaNormalAndAbnormalReturn
        case EscapeViaParameterAndAbnormalReturn.PID ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case _                                       ⇒ that
    }
}

/**
 * Characterizes escapes via a throw statement. It may also escape [[EscapeInCallee]].
 * For a given entity this characterizes only the escape state within its method of creation (M),
 * i.e. it could escape globally in a caller of M.
 *
 * @example
 * Given the following code:
 * {{{
 * public class X{
 *  public Object f;
 *  private Object foo() {
 *   RuntimeException o = new RuntimeException();        // ALLOCATION SITE
 *   throw o;
 *  }
 *  public void bar() {
 *   foo();
 *  }
 * }
 * }}}
 */
case object EscapeViaAbnormalReturn extends FinalEscapeProperty {

    final val PID = 4

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "ViaAbnormalReturn"

    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean =
        (that.propertyValueID: @switch) match {
            case NoEscape.PID | EscapeInCallee.PID | EscapeViaAbnormalReturn.PID ⇒ true
            case _ ⇒ false
        }

    override def isBottom: Boolean = false

    override def isTop: Boolean = false

    override def meet(that: EscapeProperty): EscapeProperty = that match {
        case that: FinalEscapeProperty ⇒ this meet that
        case _: GlobalEscape           ⇒ that
        case Conditional(property)     ⇒ Conditional(property meet this)
        case AtMost(property)          ⇒ AtMost(property meet this)
    }

    override def meet(that: FinalEscapeProperty): FinalEscapeProperty = (that.propertyValueID: @switch) match {
        case NoEscape.PID | EscapeInCallee.PID ⇒ this
        case EscapeViaParameter.PID            ⇒ EscapeViaParameterAndAbnormalReturn
        case EscapeViaReturn.PID               ⇒ EscapeViaNormalAndAbnormalReturn
        case EscapeViaParameterAndReturn.PID   ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case _                                 ⇒ that
    }
}

/**
 * Characterizes escapes that are [[EscapeViaParameter]] and [[EscapeViaReturn]].
 */
case object EscapeViaParameterAndReturn extends FinalEscapeProperty {

    final val PID = 5

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "ViaParameterAndReturn"

    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean =
        (that.propertyValueID: @switch) match {
            case NoEscape.PID           ⇒ true
            case EscapeInCallee.PID     ⇒ true
            case EscapeViaParameter.PID ⇒ true
            case EscapeViaReturn.PID    ⇒ true
            case PID                    ⇒ true
            case _                      ⇒ false
        }

    override def isBottom: Boolean = false

    override def isTop: Boolean = false

    override def meet(that: EscapeProperty): EscapeProperty = that match {
        case that: FinalEscapeProperty ⇒ this meet that
        case _: GlobalEscape           ⇒ that
        case Conditional(property)     ⇒ Conditional(property meet this)
        case AtMost(property)          ⇒ AtMost(property meet this)
    }

    override def meet(that: FinalEscapeProperty): FinalEscapeProperty = (that.propertyValueID: @switch) match {
        case NoEscape.PID | EscapeInCallee.PID            ⇒ this
        case EscapeViaParameter.PID | EscapeViaReturn.PID ⇒ this
        case EscapeViaAbnormalReturn.PID                  ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case EscapeViaParameterAndAbnormalReturn.PID      ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case EscapeViaNormalAndAbnormalReturn.PID         ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case _                                            ⇒ that
    }
}

/**
 * Characterizes escapes that are [[EscapeViaParameter]] and [[EscapeViaAbnormalReturn]].
 */
case object EscapeViaParameterAndAbnormalReturn extends FinalEscapeProperty {

    final val PID = 6

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "ViaParameterAndAbnormalReturn"

    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean =
        (that.propertyValueID: @switch) match {
            case NoEscape.PID                ⇒ true
            case EscapeInCallee.PID          ⇒ true
            case EscapeViaParameter.PID      ⇒ true
            case EscapeViaAbnormalReturn.PID ⇒ true
            case PID                         ⇒ true
            case _                           ⇒ false
        }

    override def isBottom: Boolean = false

    override def isTop: Boolean = false

    override def meet(that: EscapeProperty): EscapeProperty = that match {
        case that: FinalEscapeProperty ⇒ this meet that
        case _: GlobalEscape           ⇒ that
        case Conditional(property)     ⇒ Conditional(property meet this)
        case AtMost(property)          ⇒ AtMost(property meet this)
    }

    override def meet(that: FinalEscapeProperty): FinalEscapeProperty = (that.propertyValueID: @switch) match {
        case NoEscape.PID | EscapeInCallee.PID ⇒ this
        case EscapeViaParameter.PID | EscapeViaAbnormalReturn.PID ⇒ this
        case EscapeViaReturn.PID ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case EscapeViaParameterAndReturn.PID ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case EscapeViaNormalAndAbnormalReturn.PID ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case _ ⇒ that
    }
}

/**
 * Characterizes escapes that are [[EscapeViaAbnormalReturn]] and [[EscapeViaReturn]].
 */
case object EscapeViaNormalAndAbnormalReturn extends FinalEscapeProperty {

    final val PID = 7

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "ViaNormalAndAbnormalReturn"

    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean =
        (that.propertyValueID: @switch) match {
            case NoEscape.PID                ⇒ true
            case EscapeInCallee.PID          ⇒ true
            case EscapeViaAbnormalReturn.PID ⇒ true
            case EscapeViaReturn.PID         ⇒ true
            case PID                         ⇒ true
            case _                           ⇒ false
        }

    override def isBottom: Boolean = false

    override def isTop: Boolean = false

    override def meet(that: EscapeProperty): EscapeProperty = that match {
        case that: FinalEscapeProperty ⇒ this meet that
        case _: GlobalEscape           ⇒ that
        case Conditional(property)     ⇒ Conditional(property meet this)
        case AtMost(property)          ⇒ AtMost(property meet this)
    }

    override def meet(that: FinalEscapeProperty): FinalEscapeProperty = (that.propertyValueID: @switch) match {
        case NoEscape.PID | EscapeInCallee.PID                 ⇒ this
        case EscapeViaReturn.PID | EscapeViaAbnormalReturn.PID ⇒ this
        case EscapeViaParameter.PID                            ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case EscapeViaParameterAndReturn.PID                   ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case EscapeViaParameterAndAbnormalReturn.PID           ⇒ EscapeViaParameterAndNormalAndAbnormalReturn
        case _                                                 ⇒ that
    }
}

/**
 * Characterizes escapes that are [[EscapeViaParameter]], [[EscapeViaAbnormalReturn]] and
 * [[EscapeViaReturn]].
 */
case object EscapeViaParameterAndNormalAndAbnormalReturn extends FinalEscapeProperty {
    final val PID = 8

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "ViaParameterAndNormalAndAbnormalReturn"

    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean =
        (that.propertyValueID: @switch) match {
            case NoEscape.PID                            ⇒ true
            case EscapeInCallee.PID                      ⇒ true
            case EscapeViaParameter.PID                  ⇒ true
            case EscapeViaReturn.PID                     ⇒ true
            case EscapeViaAbnormalReturn.PID             ⇒ true
            case EscapeViaNormalAndAbnormalReturn.PID    ⇒ true
            case EscapeViaParameterAndReturn.PID         ⇒ true
            case EscapeViaParameterAndAbnormalReturn.PID ⇒ true
            case PID                                     ⇒ true
            case _                                       ⇒ false
        }

    override def isBottom: Boolean = false

    override def isTop: Boolean = false

    override def meet(that: EscapeProperty): EscapeProperty = that match {
        case _: GlobalEscape        ⇒ that
        case _: FinalEscapeProperty ⇒ this
        case Conditional(property)  ⇒ Conditional(property meet this)
        case AtMost(property)       ⇒ AtMost(property meet this)
    }

    override def meet(that: FinalEscapeProperty): FinalEscapeProperty = this
}

/**
 * ''The object escapes globally, typically because it is assigned to a static variable or to a
 * field of a heap object.''
 *
 * This property should be used if and only if the analysis is conclusive and could determine
 * that the value definitively escapes globally.
 * If a more advanced analysis – potentially run later – could identify an object
 * as only [[EscapeViaParameter]], [[EscapeInCallee]] or even [[NoEscape]] then the refineable
 * property AtMost(NoEscape) (or another non final property) should be used.
 *
 * @example
 * Given the following library code:
 * {{{
 * public class X{
 *  public static Object o;
 *  public void m(boolean b) {
 *      Object o = new Object();        // ALLOCATION SITE
 *      if (b) X.o = o;
 *      return;
 *  }
 * }
 * }}}
 * An analysis is only expected to return [[EscapeViaStaticField]] for the object o
 * instantiated in m, if the analyses ''knows'' that m is called and the parameter b is
 * potentially `true`. If the above code is found in a library it may very well be the case that
 * certain parameter values/combinations will never be used in a certain setting and – therefore –
 * o does not escape.
 *
 * However, from a pure technical point-of-view it may be useful/necessary to use
 * [[GlobalEscape]] at some point to let depending computations know that no more
 * changes will happen and therefore the dependencies can be deleted.
 * @see [[EscapeProperty]] for further details.
 * @author Florian Kuebler
 *          * todo why not final
 */
trait GlobalEscape extends EscapeProperty {
    override def isBottom: Boolean = true

    override def isTop: Boolean = false

    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean = true

    override def isRefinable: Boolean = false
}

/**
 * The object escapes globally but the reason for this is not further specified, e.g. because it
 * escapes via static field and a heap object.
 */
case object GlobalEscape extends GlobalEscape {

    final val PID = 9

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "Global"

    override def meet(that: EscapeProperty): EscapeProperty = this
}

/**
 * The object is assigned to a (global) heap object.
 *
 * @example
 * Given the following code:
 * {{{
 * public class X{
 *  public static X o = new X();
 *  public Object f;
 *  public void m() {
 *      Object o = new Object();        // ALLOCATION SITE
 *      X x = X.o;
 *      x.f = o;
 *      return;
 *  }
 * }
 * }}}
 *
 * @see [[GlobalEscape]] for further details.
 * @author Florian Kuebler
 */
case object EscapeViaHeapObject extends GlobalEscape {

    final val PID = 10

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "ViaHeapObject"

    override def meet(that: EscapeProperty): EscapeProperty =
        if (that.propertyValueID == EscapeViaStaticField.PID || that.propertyValueID == GlobalEscape.PID)
            GlobalEscape
        else this
}

/**
 * Characterizes escapes via the write to a static field. (
 *
 * @example
 * Given the following code:
 * {{{
 * public class X{
 *  public static Object o;
 *  public void m() {
 *      Object o = new Object();        // ALLOCATION SITE
 *      X.o = o;
 *      return;
 *  }
 * }
 * }}}
 *
 * @see [[GlobalEscape]] for further details.
 * @author Florian Kuebler
 */
case object EscapeViaStaticField extends GlobalEscape {

    final val PID = 11

    override def propertyValueID: PropertyKeyID = PID

    override def propertyName: String = "ViaStaticField"

    override def meet(that: EscapeProperty): EscapeProperty =
        if (that.propertyValueID == EscapeViaHeapObject.PID || that.propertyValueID == GlobalEscape.PID)
            GlobalEscape
        else this
}

/**
 * A refineable property that provides an upper bound. Only refinements to values below or equal to
 * `property` are allowed to perform.
 * This property should be used, if the analysis is not able to compute a more precise property
 * (i.e. for [[org.opalj.fpcf.RefinableResult]]).
 *
 * It must not be used to indicate open dependencies, when the analysis has completed analyzing the
 * local method (i.e. for [[org.opalj.fpcf.IntermediateResult]]). In that case [[Conditional]] has to be used.
 */
case class AtMost private (property: FinalEscapeProperty) extends EscapeProperty {
    override def propertyValueID: PropertyKeyID = property.propertyValueID + 20
    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean = that match {
        case _: FinalEscapeProperty ⇒ property lessOrEqualRestrictive that
        case AtMost(thatProperty)   ⇒ property lessOrEqualRestrictive thatProperty
        case _                      ⇒ false
    }
    override def isBottom = false
    override def isTop = false
    override def propertyName = s"AtMost${property.propertyName}"
    override def isRefinable = true
    override def meet(that: EscapeProperty): EscapeProperty = that match {
        case AtMost(thatProperty)      ⇒ AtMost(thatProperty meet property)
        case Conditional(thatProperty) ⇒ Conditional(this meet thatProperty)
        case that: FinalEscapeProperty ⇒ AtMost(property meet that)
        case _: GlobalEscape           ⇒ that
    }
}

object AtMost {
    final val AtMostNoEscape = new AtMost(NoEscape)
    final val AtMostEscapeInCallee = new AtMost(EscapeInCallee)
    final val AtMostEscapeViaParameter = new AtMost(EscapeViaParameter)
    final val AtMostEscapeViaReturn = new AtMost(EscapeViaReturn)
    final val AtMostEscapeViaAbnormalReturn = new AtMost(EscapeViaAbnormalReturn)
    final val AtMostEscapeViaParameterAndReturn = new AtMost(EscapeViaParameterAndReturn)
    final val AtMostEscapeViaParameterAndAbnormalReturn = new AtMost(EscapeViaParameterAndAbnormalReturn)
    final val AtMostEscapeViaNormalAndAbnormalReturn = new AtMost(EscapeViaNormalAndAbnormalReturn)
    final val AtMostEscapeViaParameterAndNormalAndAbnormalReturn = new AtMost(EscapeViaParameterAndNormalAndAbnormalReturn)

    /**
     * Ensures for each [[FinalEscapeProperty]] only one object will be created.
     */
    def apply(property: FinalEscapeProperty): AtMost = (property.propertyValueID: @switch) match {
        case NoEscape.PID                                     ⇒ AtMostNoEscape
        case EscapeInCallee.PID                               ⇒ AtMostEscapeInCallee
        case EscapeViaParameter.PID                           ⇒ AtMostEscapeViaParameter
        case EscapeViaReturn.PID                              ⇒ AtMostEscapeViaReturn
        case EscapeViaAbnormalReturn.PID                      ⇒ AtMostEscapeViaAbnormalReturn
        case EscapeViaParameterAndReturn.PID                  ⇒ AtMostEscapeViaParameterAndReturn
        case EscapeViaParameterAndAbnormalReturn.PID          ⇒ AtMostEscapeViaParameterAndAbnormalReturn
        case EscapeViaNormalAndAbnormalReturn.PID             ⇒ AtMostEscapeViaNormalAndAbnormalReturn
        case EscapeViaParameterAndNormalAndAbnormalReturn.PID ⇒ AtMostEscapeViaParameterAndNormalAndAbnormalReturn
    }
}

/**
 * A refineable property that provides an upper bound. Only refinements to values below or equal to
 * `property` are allowed to perform.
 * This property should be used, if the local analysis is completed, but there are open dependencies
 * left (i.e. for [[org.opalj.fpcf.IntermediateResult]]).
 *
 * It must not be used if the analysis can not perform better (i.e. for
 * [[org.opalj.fpcf.RefinableResult]]).
 */
case class Conditional private (property: EscapeProperty) extends EscapeProperty {
    override def propertyValueID: PropertyKeyID = property.propertyValueID + 40
    override def lessOrEqualRestrictive(that: EscapeProperty): Boolean = that match {
        case _: FinalEscapeProperty    ⇒ property lessOrEqualRestrictive that
        case Conditional(thatProperty) ⇒ property lessOrEqualRestrictive thatProperty
        case AtMost(thatProperty)      ⇒ property lessOrEqualRestrictive thatProperty
        case _                         ⇒ false
    }
    override def isBottom = false
    override def isTop = false
    override def propertyName = s"Conditional${property.propertyName}"
    override def isRefinable = true
    override def meet(that: EscapeProperty): EscapeProperty = that match {
        case Conditional(thatProperty) ⇒ Conditional(thatProperty meet property)
        case _: GlobalEscape           ⇒ that
        case _                         ⇒ Conditional(property meet that)
    }
}

object Conditional {
    private[this] val ConditionalNoEscape = new Conditional(NoEscape)
    private[this] val ConditionalEscapeInCallee = new Conditional(EscapeInCallee)
    private[this] val ConditionalEscapeViaParameter = new Conditional(EscapeViaParameter)
    private[this] val ConditionalEscapeViaReturn = new Conditional(EscapeViaReturn)
    private[this] val ConditionalEscapeViaAbnormalReturn = new Conditional(EscapeViaAbnormalReturn)
    private[this] val ConditionalEscapeViaParameterAndReturn = new Conditional(EscapeViaParameterAndReturn)
    private[this] val ConditionalEscapeViaParameterAndAbnormalReturn = new Conditional(EscapeViaParameterAndAbnormalReturn)
    private[this] val ConditionalEscapeViaNormalAndAbnormalReturn = new Conditional(EscapeViaNormalAndAbnormalReturn)
    private[this] val ConditionalEscapeViaParameterAndNormalAndAbnormalReturn = new Conditional(EscapeViaParameterAndNormalAndAbnormalReturn)
    private[this] val AtMostConditionalNoEscape = new Conditional(AtMost(NoEscape))
    private[this] val AtMostConditionalEscapeInCallee = new Conditional(AtMost(EscapeInCallee))
    private[this] val AtMostConditionalEscapeViaParameter = new Conditional(AtMost(EscapeViaParameter))
    private[this] val AtMostConditionalEscapeViaReturn = new Conditional(AtMost(EscapeViaReturn))
    private[this] val AtMostConditionalEscapeViaAbnormalReturn = new Conditional(AtMost(EscapeViaAbnormalReturn))
    private[this] val AtMostConditionalEscapeViaParameterAndReturn = new Conditional(AtMost(EscapeViaParameterAndReturn))
    private[this] val AtMostConditionalEscapeViaParameterAndAbnormalReturn = new Conditional(AtMost(EscapeViaParameterAndAbnormalReturn))
    private[this] val AtMostConditionalEscapeViaNormalAndAbnormalReturn = new Conditional(AtMost(EscapeViaNormalAndAbnormalReturn))
    private[this] val AtMostConditionalEscapeViaParameterAndNormalAndAbnormalReturn = new Conditional(AtMost(EscapeViaParameterAndNormalAndAbnormalReturn))

    /**
     * Ensures for each property only one object will be created.
     */
    def apply(property: EscapeProperty): Conditional = {
        property match {
            case NoEscape ⇒ ConditionalNoEscape
            case EscapeInCallee ⇒ ConditionalEscapeInCallee
            case EscapeViaParameter ⇒ ConditionalEscapeViaParameter
            case EscapeViaReturn ⇒ ConditionalEscapeViaReturn
            case EscapeViaAbnormalReturn ⇒ ConditionalEscapeViaAbnormalReturn
            case EscapeViaParameterAndReturn ⇒ ConditionalEscapeViaParameterAndReturn
            case EscapeViaParameterAndAbnormalReturn ⇒ ConditionalEscapeViaParameterAndAbnormalReturn
            case EscapeViaNormalAndAbnormalReturn ⇒ ConditionalEscapeViaNormalAndAbnormalReturn
            case EscapeViaParameterAndNormalAndAbnormalReturn ⇒ ConditionalEscapeViaParameterAndNormalAndAbnormalReturn
            case AtMost(NoEscape) ⇒ AtMostConditionalNoEscape
            case AtMost(EscapeInCallee) ⇒ AtMostConditionalEscapeInCallee
            case AtMost(EscapeViaParameter) ⇒ AtMostConditionalEscapeViaParameter
            case AtMost(EscapeViaReturn) ⇒ AtMostConditionalEscapeViaReturn
            case AtMost(EscapeViaAbnormalReturn) ⇒ AtMostConditionalEscapeViaAbnormalReturn
            case AtMost(EscapeViaParameterAndReturn) ⇒ AtMostConditionalEscapeViaParameterAndReturn
            case AtMost(EscapeViaParameterAndAbnormalReturn) ⇒ AtMostConditionalEscapeViaParameterAndAbnormalReturn
            case AtMost(EscapeViaNormalAndAbnormalReturn) ⇒ AtMostConditionalEscapeViaNormalAndAbnormalReturn
            case AtMost(EscapeViaParameterAndNormalAndAbnormalReturn) ⇒ AtMostConditionalEscapeViaParameterAndNormalAndAbnormalReturn
            case _ ⇒ throw new RuntimeException(s"Unsupported property: $property")
        }
    }
}
