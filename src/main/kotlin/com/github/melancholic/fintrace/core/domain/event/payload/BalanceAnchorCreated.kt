package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface BalanceAnchorCreated : BalanceAnchorEventPayload, CreateEventPayload, TemporalEventPayload {
    val value: BigDecimal
    val externalRef: String?

    override fun projections() = listOf(
        BalanceAnchorProjection(
            id = id,
            workspaceId = workspaceId,
            accountId = accountId,
            value = value,
            occurredAt = occurredAt,
            externalRef = externalRef,
            recordedAt = recordedAt
        )
    )}

/**
 * WARNING: Shouldn't be changed ever. 
 * In case of any changes required, have to create a next version of entity.
 */
data class BalanceAnchorCreatedV1(
    override val id: UUID,
    override val workspaceId: UUID,
    override val accountId: UUID,
    override val value: BigDecimal,
    override val occurredAt: LocalDateTime,
    override val recordedAt: LocalDateTime,
    override val externalRef: String?,
    override val version: Int = VERSION
) : BalanceAnchorCreated {

    companion object {
        const val TYPE = "balance_anchor.created.v1"
        const val VERSION = 1
    }
}
