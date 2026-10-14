package com.github.melancholic.fintrace.core.api.v1.dto

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class CreateBalanceAnchorRequest(
    val value: BigDecimal
)

data class BalanceAnchorResponse(
    @Schema(requiredMode = REQUIRED)
    val id: UUID,
    @Schema(requiredMode = REQUIRED)
    val workspaceId: UUID,
    @Schema(requiredMode = REQUIRED)
    val accountId: UUID,
    @Schema(requiredMode = REQUIRED)
    val value: BigDecimal,
    @Schema(requiredMode = REQUIRED)
    val difference: BigDecimal,
    @Schema(requiredMode = REQUIRED)
    val occurredAt: LocalDateTime,
    @Schema(requiredMode = REQUIRED)
    val recordedAt: LocalDateTime
)