package io.github.homchom.recode.event

import io.github.homchom.recode.lifecycle.ListenableModule
import io.github.homchom.recode.lifecycle.MutatesModuleState
import io.github.homchom.recode.lifecycle.RModule

/**
 * A [CustomEvent] without a result.
 *
 * @see hookFrom
 */
interface HookEvent<C> : CustomEvent<C, Unit> {
    operator fun invoke(context: C) = invoke(context, Unit)
}

/**
 * An [REvent] with a boolean result; this should be used for events whose listeners "validate"
 * it and determine whether the action that caused it should proceed.
 */
interface ValidatedEvent<C> : REvent<C, Boolean>

/**
 * A [CustomEvent] with children. When listened to by a [ListenableModule], the children will
 * be implicitly added.
 */
class DependentEvent<C, R : Any>(
    private val delegate: CustomEvent<C, R>,
    vararg children: RModule
) : CustomEvent<C, R> by delegate {
    private val children = children.clone()

    @MutatesModuleState
    override fun listenFrom(module: ListenableModule, listener: Listener<C, R>) {
        for (child in children) child.addParent(module)
        delegate.listenFrom(module, listener)
    }
}