package com.github.melancholic.fintrace.core.domain.statistic

import java.math.BigDecimal
import java.time.LocalDate

data class Cashflow(
    val granularity: Granularity,
    val from: LocalDate,
    val to: LocalDate,
    val buckets: List<CashflowBucket>,
    val totals: CashflowTotals,
)

data class CashflowBucket(
    val start: LocalDate,
    val income: CurrencyAmounts,
    val expense: CurrencyAmounts,
    val net: CurrencyAmounts,
    val count: List<CurrencyCount>,
)

data class CashflowTotals(
    val income: CurrencyAmounts,
    val expense: CurrencyAmounts,
    val expenseRatio: List<CurrencyRatio>,
    val net: CurrencyAmounts,
    val count: List<CurrencyCount>,
    val netRatio: List<CurrencyRatio>,
    val perDay: CashflowPerDay,
)

data class CashflowPerDay(
    val income: CurrencyAmounts,
    val expense: CurrencyAmounts,
    val net: CurrencyAmounts,
)

data class CurrencyCount(
    val currency: String,
    val count: Long,
)

data class CurrencyRatio(
    val currency: String,
    val ratio: BigDecimal,
)
