package com.github.melancholic.fintrace.core.service.command

import com.github.melancholic.fintrace.core.domain.command.Command
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.command.handler.CommandHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Service
import kotlin.reflect.KClass

sealed interface CommandDispatcher {
    fun <R> dispatch(command: Command<R>): R
}

@Service
class CommandDispatcherImpl(
    private val handlerProvider: ObjectProvider<CommandHandler<*, *, *>>
) : CommandDispatcher, SmartInitializingSingleton {
    private lateinit var handlers: Map<KClass<*>, CommandHandler<*, *, *>>

    /**
     * Resolved once every singleton exists rather than in the constructor: a handler may itself
     * dispatch — creating an account with an initial balance creates an anchor — and collecting
     * handlers while this bean is being built closes a bean cycle.
     */
    override fun afterSingletonsInstantiated() {
        handlers = handlerProvider.groupBy { it.commandType }
            .mapValues { (type, found) ->
                require(found.size == 1) { "Several handlers registered for $type: $found" }
                found.single()
            }
    }

    override fun <R> dispatch(command: Command<R>): R {
        return resolveHandler(command).handle(command)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <C : Command<R>, R> resolveHandler(command: Command<R>): CommandHandler<C, R, EventPayload> {
        return handlers[command::class] as? CommandHandler<C, R, EventPayload>
            ?: throw IllegalArgumentException("Unknown command type: ${command::class.qualifiedName}")
    }
}