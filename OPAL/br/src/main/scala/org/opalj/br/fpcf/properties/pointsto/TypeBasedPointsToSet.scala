/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj
package br
package fpcf
package properties
package pointsto

import org.opalj.collection.immutable.Chain
import org.opalj.collection.immutable.UIDSet
import org.opalj.fpcf.Entity
import org.opalj.fpcf.FallbackReason
import org.opalj.fpcf.OrderedProperty
import org.opalj.fpcf.PropertyIsNotDerivedByPreviouslyExecutedAnalysis
import org.opalj.fpcf.PropertyKey
import org.opalj.fpcf.PropertyMetaInformation
import org.opalj.fpcf.PropertyStore

/**
 * Represent the set of types that have allocations reachable from the respective entry points.
 *
 * @author Florian Kuebler
 */
// TODO: we should definition sites (real points-to sets) instead of just the types
sealed trait TypeBasedPointsToSetPropertyMetaInformation extends PropertyMetaInformation {

    final type Self = TypeBasedPointsToSet
}

case class TypeBasedPointsToSet private[properties] (
        private val orderedTypes: Chain[ObjectType],
        override val types:       UIDSet[ObjectType]
) extends PointsToSetLike[ObjectType, UIDSet[ObjectType], TypeBasedPointsToSet]
    with OrderedProperty
    with TypeBasedPointsToSetPropertyMetaInformation {

    assert(orderedTypes == null || orderedTypes.size == types.size)

    final def key: PropertyKey[TypeBasedPointsToSet] = TypeBasedPointsToSet.key

    override def toString: String = s"PointsTo(size=${types.size})"

    override def checkIsEqualOrBetterThan(e: Entity, other: TypeBasedPointsToSet): Unit = {
        if (!types.subsetOf(other.types)) {
            throw new IllegalArgumentException(s"$e: illegal refinement of property $other to $this")
        }
    }

    override def included(
        other: TypeBasedPointsToSet
    ): TypeBasedPointsToSet = {
        var newOrderedTypes = orderedTypes
        var typesUnion = types
        for (t ← other.types) {
            if (!types.contains(t)) {
                newOrderedTypes :&:= t
                typesUnion += t
            }
        }

        if (types eq typesUnion)
            return this;

        new TypeBasedPointsToSet(newOrderedTypes, typesUnion)
    }

    override def numTypes: Int = types.size

    override def elements: UIDSet[ObjectType] = types

    override def equals(obj: Any): Boolean = {
        obj match {
            case that: TypeBasedPointsToSet ⇒
                that.numTypes == this.numTypes && that.orderedTypes == this.orderedTypes
            case _ ⇒ false
        }
    }

    override def hashCode: Int = types.hashCode() * 31

    override def numElements: Int = types.size

    override def included(other: TypeBasedPointsToSet, seenElements: Int): TypeBasedPointsToSet = {
        included(other) // todo: implement correct version
    }

    override def forNewestNTypes[U](n: Int)(f: ObjectType ⇒ U): Unit = {
        orderedTypes.forFirstN(n)(f)
    }

    // here, the elements are the types
    override def forNewestNElements[U](n: Int)(f: ObjectType ⇒ U): Unit = {
        orderedTypes.forFirstN(n)(f)
    }
}

object TypeBasedPointsToSet extends TypeBasedPointsToSetPropertyMetaInformation {

    def apply(
        initialPointsTo: UIDSet[ObjectType]
    ): TypeBasedPointsToSet = {
        new TypeBasedPointsToSet(
            initialPointsTo.foldLeft(Chain.empty[ObjectType])((l, t) ⇒ t :&: l),
            initialPointsTo
        )
    }

    final val key: PropertyKey[TypeBasedPointsToSet] = {
        val name = "opalj.TypeBasedPointsToSet"
        PropertyKey.create(
            name,
            (_: PropertyStore, reason: FallbackReason, _: Entity) ⇒ reason match {
                case PropertyIsNotDerivedByPreviouslyExecutedAnalysis ⇒ NoTypes
                case _ ⇒
                    throw new IllegalStateException(s"no analysis is scheduled for property: $name")
            }
        )
    }
}

object NoTypes extends TypeBasedPointsToSet(Chain.empty, UIDSet.empty)