package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.validation.ValidationConstants.ACCOUNT_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.CURRENCY_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_ICON_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.hibernate.validator.constraints.Length
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

data class CreateAccountRequest(
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = ACCOUNT_NAME_PATTERN)
    val name: String,
    @NotBlank
    @Pattern(regexp = CURRENCY_PATTERN)
    val currency: String,
    @Length(max = MAX_ICON_LENGTH)
    val icon: String?,
    val initialBalance: BigDecimal?
)

data class UpdateAccountRequest(
    @Length(max = MAX_NAME_LENGTH)
    @NotBlank
    @Pattern(regexp = ACCOUNT_NAME_PATTERN)
    val name: String,
    @Length(max = MAX_ICON_LENGTH)
    val icon: String?
)

data class AccountResponse(
    @Schema(requiredMode = REQUIRED)
    val id: UUID,
    @Schema(requiredMode = REQUIRED)
    val workspaceId: UUID,
    @Schema(requiredMode = REQUIRED)
    val name: String,
    @Schema(requiredMode = REQUIRED)
    val currency: String,
    @Schema(requiredMode = REQUIRED)
    val archived: Boolean,
    val icon: String?,
    @Schema(requiredMode = REQUIRED)
    val recordedAt: LocalDateTime
)
