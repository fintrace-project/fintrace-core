package com.github.melancholic.fintrace.core.domain.entity

import java.math.BigDecimal
import java.util.*

data class AccountBalance(
    val accountId: UUID,
    val currency: String,
    val balance: BigDecimal
)