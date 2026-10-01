package com.github.melancholic.fintrace.core.domain.event.payload

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.service.projection.ProjectionChange
import java.util.*

sealed interface CategoryEventPayload : ProjectionEventPayload {
    val parentId: UUID?
    val name: String
    val kind: CategoryKind
    val icon: String?
    val archived: Boolean
    val systemCode: CategorySystemCode?
    val externalRef: String?

   override fun projections() = listOf(CategoryProjection(
        id = id,
        workspaceId = workspaceId,
        parentId = parentId,
        name = name,
        kind = kind,
        icon = icon,
        archived = archived,
        systemCode = systemCode,
        externalRef = externalRef,
        recordedAt = recordedAt
    ))
}
