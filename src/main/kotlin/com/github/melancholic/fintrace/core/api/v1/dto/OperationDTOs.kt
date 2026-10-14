package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.hibernate.validator.constraints.Length
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class CreateOperationRequest(
    @Positive
    @NotNull
    val amount: BigDecimal,
    @NotNull
    val occurredAt: LocalDateTime,
    @NotNull
    val kind: OperationKind,
    @NotNull
    val accountId: UUID,
    val categoryId: UUID?,
    @Length(max = 255)
    val comment: String?
)

data class UpdateOperationRequest(
    @Positive
    val amount: BigDecimal,
    @NotNull
    val occurredAt: LocalDateTime,
    @NotNull
    val kind: OperationKind,
    @NotNull
    val accountId: UUID,
    val categoryId: UUID,
    @Length(max = 255)
    val comment: String?
)

data class OperationResponse(
    @Schema(requiredMode = REQUIRED)
    val id: UUID,
    @Schema(requiredMode = REQUIRED)
    val workspaceId: UUID,
    @Schema(requiredMode = REQUIRED)
    val accountId: UUID,
    val categoryId: UUID?,
    val transferId: UUID?,
    @Schema(requiredMode = REQUIRED)
    val amount: BigDecimal,
    @Schema(requiredMode = REQUIRED)
    val occurredAt: LocalDateTime,
    @Schema(requiredMode = REQUIRED)
    val recordedAt: LocalDateTime,
    @Schema(requiredMode = REQUIRED)
    val kind: OperationKind,
    val counterpartId: UUID?,
    val comment: String?,
    val externalRef: String?
)
