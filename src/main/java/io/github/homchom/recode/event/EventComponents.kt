package io.github.homchom.recode.event

import io.github.homchom.recode.init.MutatesModuleState
import io.github.homchom.recode.init.RModule

/**
 * A [CustomEvent] without a result.
 */
interface Hook<C> : CustomEvent<C, Unit> {
    operator fun invoke(context: C) = invoke(context, Unit)
}

inline fun <C> Hook<C>.listenFrom(module: RModule, crossinline listener: (C) -> Unit) =
    listenFrom(module) { context, _ -> listener(context) }

/**
 * A [CustomEvent] with a boolean result; this should be used for events whose listeners "validate"
 * it and determine whether the action that caused it should proceed.
 */
interface ValidatedEvent<C> : CustomEvent<C, Boolean> {
    operator fun invoke(context: C) = invoke(context, true)
}

/**
 * A [CustomEvent] with dependencies. When listened to by a module, the dependencies will be
 * implicitly added.
 */
class DependentEvent<C, R : Any>(
    private val event: CustomEvent<C, R>,
    private vararg val dependencies: RModule
) : CustomEvent<C, R> by event {
    @MutatesModuleState
    override fun listenFrom(module: RModule, listener: Listener<C, R>) {
        for (handle in dependencies) handle.addAsDependency(module)
        event.listenFrom(module, listener)
    }
}