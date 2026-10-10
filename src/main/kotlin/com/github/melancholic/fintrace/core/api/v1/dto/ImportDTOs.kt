package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.entity.*
import com.github.melancholic.fintrace.core.domain.event.EntityType
import com.github.melancholic.fintrace.core.validation.ValidationConstants.ACCOUNT_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CATEGORY_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CURRENCY_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_EXTERNAL_REF_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_ICON_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.hibernate.validator.constraints.Length
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class ImportEnvelopRequest(
    @NotBlank
    val importerName: String,
    @NotBlank
    val importerVersion: String,
    @Valid
    @NotNull
    val payload: ImportPayloadRequest
) {
    fun importer() = ImporterDetails(importerName, importerVersion)
}

data class ImportPayloadRequest(
    @Valid
    val accounts: List<ImportAccountRequest> = emptyList(),
    @Valid
    val categories: List<ImportCategoryRequest> = emptyList(),
    @Valid
    val operations: List<ImportOperationRequest> = emptyList(),
    @Valid
    val transfers: List<ImportTransferRequest> = emptyList(),
    @Valid
    val balanceAnchors: List<ImportBalanceAnchorRequest> = emptyList(),
    @Valid
    val diagnostics: List<ImportDiagnosticRequest> = emptyList()
)

sealed interface ImportIdentifiedEntityRequest {
    val id: UUID
}

data class ImportAccountRequest(
    @NotNull
    override val id: UUID,
    @Length(max = MAX_EXTERNAL_REF_LENGTH)
    val externalRef: String?,
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = ACCOUNT_NAME_PATTERN)
    val name: String,
    @NotBlank
    @Pattern(regexp = CURRENCY_PATTERN)
    val currency: String,
    @Length(max = MAX_ICON_LENGTH)
    val icon: String?,
    val archived: Boolean = false,
    val initialBalance: BigDecimal?,
    val initialBalanceAt: LocalDateTime?
) : ImportIdentifiedEntityRequest

data class ImportCategoryRequest(
    @NotNull
    override val id: UUID,
    @Length(max = MAX_EXTERNAL_REF_LENGTH)
    val externalRef: String?,
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = CATEGORY_NAME_PATTERN)
    val name: String,
    @NotNull
    val kind: CategoryKind,
    val parentId: UUID?,
    @Length(max = MAX_ICON_LENGTH)
    val icon: String?
) : ImportIdentifiedEntityRequest

data class ImportOperationRequest(
    @NotNull
    override val id: UUID,
    @Length(max = MAX_EXTERNAL_REF_LENGTH)
    val externalRef: String?,
    @NotNull
    val occurredAt: LocalDateTime,
    @Positive
    @NotNull
    val amount: BigDecimal,
    @NotNull
    val kind: OperationKind,
    @NotNull
    val accountId: UUID,
    val categoryId: UUID?,
    @Length(max = 255)
    val comment: String?
) : ImportIdentifiedEntityRequest

data class ImportTransferRequest(
    @NotNull
    override val id: UUID,
    @Length(max = MAX_EXTERNAL_REF_LENGTH)
    val externalRef: String?,
    @NotNull
    val occurredAt: LocalDateTime,
    @Valid
    @NotNull
    val source: ImportTransferLegRequest,
    @Valid
    @NotNull
    val target: ImportTransferLegRequest,
    @Length(max = 255)
    val comment: String?
) : ImportIdentifiedEntityRequest

data class ImportTransferLegRequest(
    @NotNull
    val accountId: UUID,
    @Positive
    @NotNull
    val amount: BigDecimal
)

data class ImportBalanceAnchorRequest(
    @NotNull
    override val id: UUID,
    @Length(max = MAX_EXTERNAL_REF_LENGTH)
    val externalRef: String?,
    @NotNull
    val accountId: UUID,
    @NotNull
    val occurredAt: LocalDateTime,
    @NotNull
    val value: BigDecimal
) : ImportIdentifiedEntityRequest

data class ImportDiagnosticRequest(
    @NotNull
    val severity: ImportDiagnosticSeverity,
    @NotNull
    val code: ImportDiagnosticCode,
    @NotNull
    @PositiveOrZero
    val count: Long,
    @Length(max = 255)
    val detail: String?
)

data class ImportJobResponse(
    @Schema(requiredMode = REQUIRED)
    val id: UUID,
    @Schema(requiredMode = REQUIRED)
    val workspaceId: UUID,
    @Schema(requiredMode = REQUIRED)
    val status: ImportJobStatus,
    @Schema(requiredMode = REQUIRED)
    val startedBy: UUID,
    @Schema(requiredMode = REQUIRED)
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    @Schema(requiredMode = REQUIRED)
    val importerName: String,
    @Schema(requiredMode = REQUIRED)
    val importerVersion: String,
    val message: String?,
    @Schema(requiredMode = REQUIRED)
    val accounts: Int = 0,
    @Schema(requiredMode = REQUIRED)
    val categories: Int = 0,
    @Schema(requiredMode = REQUIRED)
    val operations: Int = 0,
    @Schema(requiredMode = REQUIRED)
    val transfers: Int = 0,
    @Schema(requiredMode = REQUIRED)
    val anchors: Int = 0,
    @Schema(requiredMode = REQUIRED)
    val diagnostics: List<ImportDiagnosticDTO> = listOf(),
    @Schema(requiredMode = REQUIRED)
    val problems: List<ImportProblemDTO> = listOf()
)

data class ImportDiagnosticDTO(
    @Schema(requiredMode = REQUIRED)
    val severity: ImportDiagnosticSeverity,
    @Schema(requiredMode = REQUIRED)
    val code: ImportDiagnosticCode,
    @Schema(requiredMode = REQUIRED)
    val count: Long,
    val detail: String?
)

data class ImportProblemDTO(
    @Schema(requiredMode = REQUIRED)
    val code: ImportProblemCode,
    @Schema(requiredMode = REQUIRED)
    val message: String,
    @Schema(requiredMode = REQUIRED)
    val aggregateType: EntityType,
    @Schema(requiredMode = REQUIRED)
    val affectedIDs: Set<UUID>,
    val field: String?,
    @Schema(requiredMode = REQUIRED)
    val affectedCount: Int,
)
