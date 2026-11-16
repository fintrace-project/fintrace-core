package com.github.melancholic.fintrace.core.domain.statistic

import java.math.BigDecimal
import java.util.UUID

data class AccountBalance(
    val accountId: UUID,
    val currency: String,
    val balance: BigDecimal
)