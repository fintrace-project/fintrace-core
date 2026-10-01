package com.github.melancholic.fintrace.core.domain.event

import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import java.time.LocalDateTime
import java.util.*

data class Event(
    val id: Long,
    val workspaceId: UUID,
    val entityType: EntityType,
    val entityId: UUID,
    val eventType: EventType,
    val payload: EventPayload,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val recordedBy: UUID
)

enum class EntityType {
    OPERATION,
    ACCOUNT,
    CATEGORY,
    TRANSFER,
    BALANCE_ANCHOR
}

enum class EventType {
    CREATED,
    REVISED,
    CANCELLED,
}

fun Event.projectionChange(): ProjectionChange = payload.projectionChange()