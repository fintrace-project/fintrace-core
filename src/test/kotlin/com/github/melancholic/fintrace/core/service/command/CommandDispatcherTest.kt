package com.github.melancholic.fintrace.core.service.command

import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CommandContext
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.service.command.handler.CommandHandler
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import java.util.stream.Stream
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Routing only — no Spring, no database. */
class CommandDispatcherTest {

	private class RecordingHandler(
		private val result: OperationProjection,
	) : CommandHandler<CreateOperationCommand, OperationProjection, EventPayload> {
		override val commandType: KClass<out CreateOperationCommand> = CreateOperationCommand::class
		var received: CreateOperationCommand? = null
        var receivedContext: CommandContext? = null

        override fun handle(command: CreateOperationCommand, context: CommandContext): OperationProjection {
			received = command
            receivedContext = context
			return result
		}
	}

    private val context = CommandContext(initiator = UUID.randomUUID())

	@Test
	fun `routes a command to the handler declaring its type`() {
		val expected = projection()
		val handler = RecordingHandler(expected)
		val dispatcher = dispatcherOf(handler)
		val command = createOperation()

        val result = dispatcher.dispatch(command, context)

		assertEquals(expected, result)
		assertEquals(command, handler.received, "the handler received the original command")
        assertEquals(context, handler.receivedContext, "and the context it was dispatched with, unchanged")
	}

	@Test
	fun `fails when no handler is registered for the command`() {
		val dispatcher = dispatcherOf(RecordingHandler(projection()))

		val failure = assertFailsWith<IllegalArgumentException> {
			dispatcher.dispatch(
				CancelOperationCommand(
					workspaceId = UUID.randomUUID(),
					operationId = UUID.randomUUID(),
                ),
                context,
			)
		}

		assertEquals(true, failure.message?.contains("CancelOperationCommand"), failure.message)
	}

	@Test
	fun `fails when no handlers are registered at all`() {
		val dispatcher = dispatcherOf()

        assertFailsWith<IllegalArgumentException> { dispatcher.dispatch(createOperation(), context) }
	}

	/**
	 * The dispatcher takes an `ObjectProvider` rather than a `List` so a handler may itself
	 * dispatch without closing a bean cycle; only `getObject` and `stream` need standing in for.
	 */
	private fun providerOf(vararg handlers: CommandHandler<*, *, *>) =
		object : ObjectProvider<CommandHandler<*, *, *>> {
			override fun getObject(): CommandHandler<*, *, *> = handlers.single()
			override fun stream(): Stream<CommandHandler<*, *, *>> = handlers.toList().stream()
		}

	/** The container calls this once every singleton exists; a hand-built dispatcher must too. */
	private fun dispatcherOf(vararg handlers: CommandHandler<*, *, *>) =
		CommandDispatcherImpl(providerOf(*handlers)).apply { afterSingletonsInstantiated() }

	private fun projection() = OperationProjection(
		id = UUID.randomUUID(),
		workspaceId = UUID.randomUUID(),
		amount = BigDecimal("100.0000"),
        kind = OperationKind.EXPENSE,
        accountId = UUID.randomUUID(),
        categoryId = UUID.randomUUID(),
        transferId = null,
        counterpartId = null,
        comment = null,
        externalRef = null,
		occurredAt = LocalDateTime.parse("2026-03-15T14:30:00"),
		recordedAt = LocalDateTime.parse("2026-03-16T09:00:00"),
	)

	private fun createOperation() = CreateOperationCommand(
		workspaceId = UUID.randomUUID(),
		occurredAt = LocalDateTime.parse("2026-03-15T14:30:00"),
		amount = BigDecimal("100.0000"),
        accountId = UUID.randomUUID(),
        kind = OperationKind.EXPENSE,
        categoryId = UUID.randomUUID(),
        comment = null,
	)
}
