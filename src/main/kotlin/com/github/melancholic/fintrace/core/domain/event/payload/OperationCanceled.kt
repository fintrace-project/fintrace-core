package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.service.projection.ProjectionTarget
import java.time.LocalDateTime
import java.util.*

sealed interface OperationCanceled : OperationEventPayload, CancelEventPayload {
    override fun affectedIds(): Set<UUID> = setOf(id)
    override fun projectionTarget() = ProjectionTarget.OPERATION
}

/**
 * WARNING: Shouldn't be changed ever. 
 * In case of any changes required, have to create a next version of entity.
 */
data class OperationCanceledV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val version: Int = VERSION,
    override val recordedAt: LocalDateTime,
) : OperationCanceled {

    companion object {
        const val TYPE = "operation.canceled.v1"
        const val VERSION = 1
    }
}
