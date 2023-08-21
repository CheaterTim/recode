package io.github.homchom.recode.event.trial

import io.github.homchom.recode.*
import io.github.homchom.recode.event.Detector
import io.github.homchom.recode.event.Listenable
import io.github.homchom.recode.event.Requester
import io.github.homchom.recode.event.createEvent
import io.github.homchom.recode.lifecycle.ExposedModule
import io.github.homchom.recode.lifecycle.ModuleDetail
import io.github.homchom.recode.lifecycle.RModule
import io.github.homchom.recode.lifecycle.module
import io.github.homchom.recode.ui.sendSystemToast
import io.github.homchom.recode.ui.translateText
import io.github.homchom.recode.util.coroutines.cancelAndLog
import io.github.homchom.recode.util.nullable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.*
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Creates a [Detector] that runs via one or more [DetectorTrial] objects.
 *
 * @param name The name of what is being detected (used for debugging purposes).
 */
fun <T, R : Any> detector(
    name: String,
    primaryTrial: DetectorTrial<T, R>,
    vararg secondaryTrials: DetectorTrial<T, R>,
    timeoutDuration: Duration = DEFAULT_TIMEOUT_DURATION
): Detector<T & Any, R> {
    return TrialDetector(name, listOf(primaryTrial, *secondaryTrials), timeoutDuration)
}

/**
 * Creates a [Requester] that runs via one or more [RequesterTrial] objects.
 *
 * @param name The name of what is being detected (used for debugging purposes).
 * @param lifecycle The [Listenable] object that defines the requester's lifecycle. If the event is run during
 * a request, the request is cancelled.
 */
fun <T, R : Any> requester(
    name: String,
    lifecycle: Listenable<*>,
    primaryTrial: RequesterTrial<T, R>,
    vararg secondaryTrials: DetectorTrial<T, R>,
    timeoutDuration: Duration = DEFAULT_TIMEOUT_DURATION
): Requester<T & Any, R> {
    return TrialRequester(name, lifecycle, primaryTrial, secondaryTrials, timeoutDuration)
}

private open class TrialDetector<T, R : Any>(
    protected val name: String,
    private val trials: List<Trial<T, R>>,
    override val timeoutDuration: Duration
) : Detector<T & Any, R> {
    private val event = createEvent<R, R> { it }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val module = module("$name detection", ModuleDetail.Exposed) { m ->
        m.extend(event)

        m.onEnable {
            for (trialIndex in trials.indices) trials[trialIndex].results.listenEach { supplier ->
                val successContext = CompletableDeferred<R>(coroutineContext.job)
                if (entries.isEmpty()) {
                    considerEntry(trialIndex, null, supplier, successContext)
                }

                val iterator = entries.iterator()
                for (entry in iterator) {
                    if (entry.responses.isClosedForSend) {
                        iterator.remove()
                        continue
                    }
                    considerEntry(trialIndex, entry, supplier, successContext)
                }

                successContext.invokeOnCompletion { exception ->
                    if (exception == null) {
                        val completed = successContext.getCompleted()
                        logDebug("${this@TrialDetector} succeeded; running with context $completed")
                        event.run(completed)
                    }
                }
            }
        }

        m
    }

    override val isEnabled by module::isEnabled

    private val entries = ConcurrentLinkedQueue<DetectEntry<T, R>>()

    override val notifications by event::notifications
    override val previous by event::previous

    override fun detect(input: T?, hidden: Boolean) = responseFlow(input, false, hidden)

    protected fun responseFlow(input: T?, isRequest: Boolean, hidden: Boolean) = flow {
        coroutineScope {
            module.assert()
            val responses = Channel<R?>(Channel.UNLIMITED)
            try {
                // add entry after all current detection loops
                launch(RecodeDispatcher) {
                    yield()
                    entries += DetectEntry(isRequest, input, responses, hidden)
                }
                while (isActive) emit(responses.receive())
            } finally {
                responses.close()
                module.unassert()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun ExposedModule.considerEntry(
        trialIndex: Int,
        entry: DetectEntry<T, R>?,
        supplier: Trial.ResultSupplier<T & Any, R>,
        successContext: CompletableDeferred<R>,
    ) {
        val entryScope = CoroutineScope(coroutineContext + Job(successContext))
        val result = nullable {
            val trialScope = TrialScope(
                this@considerEntry,
                this@nullable,
                entryScope,
                entry?.hidden ?: false
            )
            logDebug("trial $trialIndex started for ${debugString(entry?.input, entry?.hidden)}")
            supplier.supplyIn(trialScope, entry?.input, entry?.isRequest ?: false)
        }

        val entryJob = entryScope.launch {
            if (result == null) {
                entry?.responses?.trySend(null)
                return@launch
            }

            val awaited = result.await()
            entry?.responses?.trySend(awaited)
            awaited?.let(successContext::complete)
        }

        entryJob.invokeOnCompletion { exception ->
            val state = when (exception) {
                is CancellationException -> "cancelled"
                null -> "ended"
                else -> "ended with exception $exception"
            }
            entryScope.cancelAndLog("trial $trialIndex $state for ${debugString(entry?.input, entry?.hidden)}")
        }
    }

    override fun extend(vararg parents: RModule) = module.extend(*parents)

    override fun toString() = "$name detector"

    protected fun debugString(input: T?, hidden: Boolean?): String {
        val hiddenString = if (hidden == true) "hidden " else ""
        val entryString = if (input != null) "explicit entry with input $input" else "default entry"
        return "$this ($hiddenString$entryString)"
    }
}

private data class DetectEntry<T, R : Any>(
    val isRequest: Boolean,
    val input: T?,
    val responses: SendChannel<R?>,
    val hidden: Boolean
)

private class TrialRequester<T, R : Any>(
    name: String,
    private val lifecycle: Listenable<*>,
    primaryTrial: RequesterTrial<T, R>,
    secondaryTrials: Array<out DetectorTrial<T, R>>,
    timeoutDuration: Duration
) : TrialDetector<T, R>(name, listOf(primaryTrial, *secondaryTrials), timeoutDuration), Requester<T & Any, R> {
    private val start = primaryTrial.start

    override val activeRequests get() = _activeRequests.get()

    private val _activeRequests = AtomicInteger(0)

    override suspend fun request(input: T & Any, hidden: Boolean) = withContext(NonCancellable) {
        lifecycle.notifications
            .onEach { cancel("${this@TrialRequester} lifecycle ended during a request") }
            .launchIn(this)

        val detectChannel = responseFlow(input, true, hidden)
            .filterNotNull()
            .produceIn(this)

        _activeRequests.incrementAndGet()
        try {
            delay(50.milliseconds) // https://github.com/PaperMC/Velocity/issues/909 TODO: remove
            val response = start(input) ?: withTimeout(timeoutDuration) { detectChannel.receive() }
            coroutineContext.cancelChildren()
            response
        } catch (timeout: TimeoutCancellationException) {
            mc.sendSystemToast(
                translateText("multiplayer.recode.request_timeout.toast.title"),
                translateText("multiplayer.recode.request_timeout.toast")
            )
            logError("${debugString(input, hidden)} timed out after $timeoutDuration")
            throw timeout
        } finally {
            _activeRequests.decrementAndGet()
        }
    }

    override fun toString() = "$name requester"
}