package com.github.melancholic.fintrace.core.api.v1.dto

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.hibernate.validator.constraints.Length
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class CreateTransferRequest(
    @NotNull
    val occurredAt: LocalDateTime,
    @Valid
    @NotNull
    val source: TransferLegRequest,
    @Valid
    @NotNull
    val target: TransferLegRequest,
    @Length(max = 255)
    val comment: String?
)

data class UpdateTransferRequest(
    @NotNull
    val occurredAt: LocalDateTime,
    @Valid
    @NotNull
    val source: TransferLegRequest,
    @Valid
    @NotNull
    val target: TransferLegRequest,
    @Length(max = 255)
    val comment: String?
)

data class TransferLegRequest(
    @NotNull
    val accountId: UUID,
    @Positive
    @NotNull
    val amount: BigDecimal
)

data class TransferResponse(
    @Schema(requiredMode = REQUIRED)
    val id: UUID,
    @Schema(requiredMode = REQUIRED)
    val workspaceId: UUID,
    @Schema(requiredMode = REQUIRED)
    val occurredAt: LocalDateTime,
    @Schema(requiredMode = REQUIRED)
    val recordedAt: LocalDateTime,
    val comment: String?,
    @Schema(requiredMode = REQUIRED)
    val source: TransferLegResponse,
    @Schema(requiredMode = REQUIRED)
    val target: TransferLegResponse,
    @Schema(requiredMode = REQUIRED)
    val rate: BigDecimal
)

data class TransferLegResponse(
    @Schema(requiredMode = REQUIRED)
    val operationId: UUID,
    @Schema(requiredMode = REQUIRED)
    val accountId: UUID,
    @Schema(requiredMode = REQUIRED)
    val currency: String,
    @Schema(requiredMode = REQUIRED)
    val amount: BigDecimal
)