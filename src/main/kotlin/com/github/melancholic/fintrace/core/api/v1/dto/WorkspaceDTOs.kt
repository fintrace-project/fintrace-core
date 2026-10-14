package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.entity.WorkspaceStatus
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CURRENCY_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.WORKSPACE_NAME_PATTERN
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import org.hibernate.validator.constraints.Length
import java.time.LocalDateTime
import java.util.*

data class CreateWorkspaceRequest(
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = WORKSPACE_NAME_PATTERN)
    val workspaceName: String,

    @NotBlank
    @Pattern(regexp = CURRENCY_PATTERN)
    val defaultCurrency: String,
)

data class EditWorkspaceRequest(
    @NotNull
    @PositiveOrZero
    val version: Long,

    @Length(max = MAX_NAME_LENGTH)
    @Pattern(regexp = WORKSPACE_NAME_PATTERN)
    val workspaceName: String?,

    @Pattern(regexp = CURRENCY_PATTERN)
    val defaultCurrency: String?,
)

data class WorkspaceResponse(
    @Schema(requiredMode = REQUIRED)
    val id: UUID,
    @Schema(requiredMode = REQUIRED)
    val name: String,
    @Schema(requiredMode = REQUIRED)
    val status: WorkspaceStatus,
    @Schema(requiredMode = REQUIRED)
    val defaultCurrency: String,
    @Schema(requiredMode = REQUIRED)
    val version: Long,
    @Schema(requiredMode = REQUIRED)
    val createdAt: LocalDateTime,
    @Schema(requiredMode = REQUIRED)
    val updatedAt: LocalDateTime,
    val deletedAt: LocalDateTime?
)