package com.github.melancholic.fintrace.core.api.v1.dto

import java.math.BigDecimal
import java.util.*

data class AccountBalanceResponse(
    val accountId: UUID,
    val currency: String,
    val balance: BigDecimal
)