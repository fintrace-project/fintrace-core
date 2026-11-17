package com.github.melancholic.fintrace.core.domain.entity

import java.math.BigDecimal
import java.time.LocalDate

data class BalanceSeriesRow(
    val start: LocalDate,
    val currency: String?,
    val total: BigDecimal?
)