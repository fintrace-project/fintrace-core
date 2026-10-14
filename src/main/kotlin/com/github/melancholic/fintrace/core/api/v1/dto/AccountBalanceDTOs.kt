package com.github.melancholic.fintrace.core.api.v1.dto

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import java.math.BigDecimal
import java.util.*

data class AccountBalanceResponse(
    @Schema(requiredMode = REQUIRED)
    val accountId: UUID,
    @Schema(requiredMode = REQUIRED)
    val currency: String,
    @Schema(requiredMode = REQUIRED)
    val balance: BigDecimal
)