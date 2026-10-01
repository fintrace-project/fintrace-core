package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.projection.AccountProjection

sealed interface AccountEventPayload : ProjectionEventPayload {
    val name: String
    val currency: String
    val archived: Boolean
    val icon: String?
    val externalRef: String?

    override fun projections() = listOf(
        AccountProjection(
            id = id,
            workspaceId = workspaceId,
            name = name,
            currency = currency,
            archived = archived,
            icon = icon,
            externalRef = externalRef,
            recordedAt = recordedAt
        )
    )
}
