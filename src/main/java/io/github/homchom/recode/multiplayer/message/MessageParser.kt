package io.github.homchom.recode.multiplayer.message

import io.github.homchom.recode.event.Detector
import io.github.homchom.recode.event.Listenable
import io.github.homchom.recode.event.Requester
import io.github.homchom.recode.event.SimpleValidated
import io.github.homchom.recode.event.trial.detector
import io.github.homchom.recode.event.trial.requester
import io.github.homchom.recode.event.trial.trial
import io.github.homchom.recode.multiplayer.ReceiveChatMessageEvent
import io.github.homchom.recode.util.Matcher
import io.github.homchom.recode.util.matcherOf
import io.github.homchom.recode.util.splitByHumps
import net.minecraft.network.chat.Component

sealed interface MessageParser<T : Any, out R : ParsedMessage> : Matcher<Component, R>, Detector<T, R>

val ParsedMessageDetector = detector("parsed message",
    trial(ReceiveChatMessageEvent, Unit) { message, _ ->
        val parsed = ParsedMessage.match(message.value)!!
        if (hidden) message.invalidate()
        instant(ParsedMessageContext(parsed, message))
    }
)

sealed interface ParsedMessage {
    companion object : Matcher<Component, ParsedMessage> {
        private val enums by lazy {
            matcherOf<Component, ParsedMessage>().apply {
                addAll(StateMessages.parsers)
                addAll(CodeMessages.parsers)
                addAll(ShopMessages.parsers)
            }
        }

        override fun match(input: Component) = enums.match(input)

        inline fun <T, reified R : ParsedMessage> detector(defaultInput: T): Detector<T & Any, R> {
            val messageName = R::class.simpleName!!
                .splitByHumps()
                .joinToString("") { it.lowercase() }
            return detector("$messageName message",
                trial(ParsedMessageDetector, defaultInput) { message, _ ->
                    if (message.parsed !is R) fail()
                    if (hidden) message.raw.invalidate()
                    instant(message.parsed)
                }
            )
        }

        inline fun <T, reified R : ParsedMessage> requester(
            lifecycle: Listenable<*>,
            defaultInput: T,
            noinline start: suspend (T & Any) -> Unit
        ): Requester<T & Any, R> {
            val messageName = R::class.simpleName!!
                .splitByHumps()
                .joinToString("") { it.lowercase() }
            return requester("$messageName message", lifecycle,
                trial(ParsedMessageDetector, defaultInput, start) { message, _, _ ->
                    if (message.parsed !is R) fail()
                    if (hidden) message.raw.invalidate()
                    instant(message.parsed)
                }
            )
        }
    }
}

data class ParsedMessageContext(val parsed: ParsedMessage, val raw: SimpleValidated<Component>)