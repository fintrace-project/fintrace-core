package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.entity.*
import com.github.melancholic.fintrace.core.validation.ValidationConstants.ACCOUNT_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CATEGORY_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CURRENCY_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_EXTERNAL_REF_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_ICON_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
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

data class ImportAccountRequest(
    @NotNull
    val id: UUID,
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
)

data class ImportCategoryRequest(
    @NotNull
    val id: UUID,
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
)

data class ImportOperationRequest(
    @NotNull
    val id: UUID,
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
)

data class ImportTransferRequest(
    @NotNull
    val id: UUID,
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
)

data class ImportTransferLegRequest(
    @NotNull
    val accountId: UUID,
    @Positive
    @NotNull
    val amount: BigDecimal
)

data class ImportBalanceAnchorRequest(
    @NotNull
    val id: UUID,
    @Length(max = MAX_EXTERNAL_REF_LENGTH)
    val externalRef: String?,
    @NotNull
    val accountId: UUID,
    @NotNull
    val occurredAt: LocalDateTime,
    @NotNull
    val value: BigDecimal
)

data class ImportDiagnosticRequest(
    @NotNull
    val severity: ImportDiagnosticSeverity,
    @NotNull
    val code: ImportDiagnosticCode,
    @PositiveOrZero
    val count: Long,
    @Length(max = 255)
    val detail: String?
)

data class ImportJobResponse(
    val id: UUID,
    val workspaceId: UUID,
    val status: ImportJobStatus,
    val startedBy: UUID,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val importerName: String,
    val importerVersion: String,
    val message: String?,
    val accounts: Int = 0,
    val categories: Int = 0,
    val operations: Int = 0,
    val transfers: Int = 0,
    val anchors: Int = 0,
    val diagnostics: List<ImportDiagnosticDTO> = listOf()
)

data class ImportDiagnosticDTO(
    val severity: ImportDiagnosticSeverity,
    val code: ImportDiagnosticCode,
    val count: Long,
    val detail: String?
)
