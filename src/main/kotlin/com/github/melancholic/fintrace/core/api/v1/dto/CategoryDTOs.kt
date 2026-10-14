package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategorySystemCode
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CATEGORY_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_ICON_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.hibernate.validator.constraints.Length
import java.time.LocalDateTime
import java.util.*

data class CreateCategoryRequest(
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = CATEGORY_NAME_PATTERN)
    val name: String,
    val parentId: UUID,
    @Length(max = MAX_ICON_LENGTH)
    val icon: String?
)

data class UpdateCategoryRequest(
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = CATEGORY_NAME_PATTERN)
    val name: String,
    val parentId: UUID,
    @Length(max = MAX_ICON_LENGTH)
    val icon: String?
)

data class CategoryResponse(
    @Schema(requiredMode = REQUIRED)
    val id: UUID,
    @Schema(requiredMode = REQUIRED)
    val workspaceId: UUID,
    val parentId: UUID?,
    @Schema(requiredMode = REQUIRED)
    val name: String,
    @Schema(requiredMode = REQUIRED)
    val kind: CategoryKind,
    @Schema(requiredMode = REQUIRED)
    val archived: Boolean,
    val systemCode: CategorySystemCode?,
    val icon: String?,
    @Schema(requiredMode = REQUIRED)
    val recordedAt: LocalDateTime
)
