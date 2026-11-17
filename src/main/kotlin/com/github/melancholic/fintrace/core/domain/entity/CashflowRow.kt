package com.github.melancholic.fintrace.core.domain.entity

import java.math.BigDecimal
import java.time.LocalDate

data class CashflowRow(
    val start: LocalDate,
    val currency: String?,
    val income: BigDecimal,
    val expense: BigDecimal,
    val net: BigDecimal,
    val operations: Long,
)