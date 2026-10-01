package com.github.melancholic.fintrace.core.domain.command

import java.time.LocalDateTime
import java.util.*

sealed interface Command<R> {
    val workspaceId: UUID
}

sealed interface TemporalCommand<R> : Command<R> {
    val occurredAt: LocalDateTime
}

sealed interface IdentifiedCommand<R> : Command<R> {
    val id: UUID?
    val externalRef: String?
}

sealed interface CreateCommand<R> : IdentifiedCommand<R>

sealed interface ReviseCommand<R> : Command<R>

sealed interface CancelCommand<R> : Command<R>