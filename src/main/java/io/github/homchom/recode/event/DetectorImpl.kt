package io.github.homchom.recode.event

import io.github.homchom.recode.DEFAULT_TIMEOUT_DURATION
import io.github.homchom.recode.lifecycle.*
import io.github.homchom.recode.util.attempt
import io.github.homchom.recode.util.collections.synchronizedLinkedList
import io.github.homchom.recode.util.nullable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Creates a [DetectorModule] that runs via one or more [trials].
 *
 * @see DetectorTrial
 */
fun <T : Any, R : Any> detector(
    vararg trials: DetectorTrial<T, R>,
    timeoutDuration: Duration = DEFAULT_TIMEOUT_DURATION
): DetectorModule<T, R> {
    val detail = TrialDetector(trials.toList(), timeoutDuration)
    return SimpleDetectorModule(detail, module(detail))
}

/**
 * Creates a [RequesterModule] that runs via one or more [trials].
 *
 * @see RequesterTrial
 */
fun <T : Any, R : Any> requester(
    vararg trials: RequesterTrial<T, R>,
    timeoutDuration: Duration = DEFAULT_TIMEOUT_DURATION
): RequesterModule<T, R> {
    val detail = TrialRequester(trials.toList(), timeoutDuration)
    return SimpleRequesterModule(detail, module(detail))
}

private sealed class DetectorDetail<T : Any, R : Any, S> : Detector<T, R>, ModuleDetail {
    protected abstract val trials: List<Trial<S>>

    private val event = createEvent<R, R> { it }

    private val entries = synchronizedLinkedList<TrialEntry<T, R>>()

    override val prevResult: R? get() = event.prevResult

    override fun getNotificationsFrom(module: RModule) = event.getNotificationsFrom(module)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun ExposedModule.onEnable() {
        for (trial in trials) trial.supplyResultsFrom(this).listenEach { supplier ->
            suspend fun getResponse(entry: TrialEntry<T, *>?) = trialScope { runTests(supplier, entry) }
            suspend fun awaitResponse(response: Deferred<R?>?) = nullable { response?.await() }

            if (entries.isEmpty()) {
                val response = getResponse(null)
                if (response != null) launch {
                    awaitResponse(response)?.let { event.run(it) }
                }
            } else {
                val iterator = entries.iterator()
                val successful = AtomicBoolean(false)

                for (entry in iterator) {
                    if (entry.responses.isClosedForSend) {
                        iterator.remove()
                        continue
                    }
                    if (entry.basis != trial.basis) continue

                    val response = getResponse(entry)
                    if (response == null) {
                        entry.responses.send(null)
                    } else launch {
                        val awaited = awaitResponse(response)
                        if (successful.compareAndSet(false, true)) {
                            entry.responses.send(awaited)
                            if (awaited != null) event.run(awaited)
                        }
                    }
                }
            }
        }
    }

    override fun ExposedModule.onLoad() {}
    override fun ExposedModule.onDisable() {}
    override fun children() = emptyModuleList()

    override suspend fun detectFrom(module: RModule, input: T?, basis: Listenable<*>?) =
        addDetectAndAwait(input, basis) { block ->
            attempt(timeoutDuration, block)
        }

    override suspend fun checkNextFrom(module: RModule, input: T?, basis: Listenable<*>?, attempts: UInt) =
        addDetectAndAwait(input, basis) { block ->
            attempt(attempts) { block() }
        }

    private suspend inline fun addDetectAndAwait(
        input: T?,
        basis: Listenable<*>?,
        attemptFunc: (block: suspend () -> R?) -> R?
    ): R? {
        return addAndAwait(input, basis ?: trials[0].basis, false, attemptFunc)
    }

    protected suspend inline fun addAndAwait(
        input: T?,
        basis: Listenable<*>,
        isRequest: Boolean,
        attemptFunc: (suspend () -> R?) -> R?
    ): R? {
        val responses = Channel<R?>()
        entries += TrialEntry(isRequest, input, basis, responses)
        val final = attemptFunc {
            withTimeoutOrNull(timeoutDuration) { responses.receive() }
        }
        responses.close()
        return final
    }

    protected abstract suspend fun TrialScope.runTests(supplier: S, entry: TrialEntry<T, *>?): TrialResult<R>
}

private data class TrialEntry<T : Any, R : Any>(
    val isRequest: Boolean,
    val input: T?,
    val basis: Listenable<*>,
    val responses: Channel<R?>
)

private class TrialDetector<T : Any, R : Any>(
    override val trials: List<DetectorTrial<T, R>>,
    override val timeoutDuration: Duration
) : DetectorDetail<T, R, DetectorTrial.ResultSupplier<T, R>>() {
    override suspend fun TrialScope.runTests(
        supplier: DetectorTrial.ResultSupplier<T, R>,
        entry: TrialEntry<T, *>?
    ): TrialResult<R> {
        return supplier.supplyIn(this, entry?.input)
    }
}

private class TrialRequester<T : Any, R : Any>(
    override val trials: List<RequesterTrial<T, R>>,
    override val timeoutDuration: Duration
) : DetectorDetail<T, R, RequesterTrial.ResultSupplier<T, R>>(), Requester<T, R> {
    init {
        require(trials.isNotEmpty())
    }

    override suspend fun TrialScope.runTests(
        supplier: RequesterTrial.ResultSupplier<T, R>,
        entry: TrialEntry<T, *>?
    ): TrialResult<R> {
        return supplier.supplyIn(this, entry?.input, entry?.isRequest ?: false)
    }

    override suspend fun requestFrom(module: RModule, input: T): R {
        val response = addAndAwait(input, trials[0].basis, true) { block ->
            var started = false
            attempt(timeoutDuration) {
                if (started) block() else {
                    started = true
                    trials[0].start(input) ?: block()
                }
            }
        }
        return response ?: error("Requester trial failed")
    }
}

private open class SimpleDetectorModule<T : Any, R : Any>(
    private val detail: DetectorDetail<T, R, *>,
    module: RModule
) : DetectorModule<T, R>, RModule by module {
    override val timeoutDuration get() = detail.timeoutDuration
    override val prevResult get() = detail.prevResult

    override fun getNotificationsFrom(module: RModule): Flow<R> {
        module.depend(this)
        return detail.getNotificationsFrom(module)
    }

    override suspend fun checkNextFrom(module: RModule, input: T?, basis: Listenable<*>?, attempts: UInt): R? {
        module.depend(this)
        return detail.checkNextFrom(module, input, basis, attempts)
    }

    override suspend fun detectFrom(module: RModule, input: T?, basis: Listenable<*>?): R? {
        module.depend(this)
        return detail.detectFrom(module, input, basis)
    }
}

private class SimpleRequesterModule<T : Any, R : Any>(
    private val detail: TrialRequester<T, R>,
    module: RModule
) : SimpleDetectorModule<T, R>(detail, module), RequesterModule<T, R> {
    override suspend fun requestFrom(module: RModule, input: T): R {
        module.depend(this)
        return detail.requestFrom(module, input)
    }
}