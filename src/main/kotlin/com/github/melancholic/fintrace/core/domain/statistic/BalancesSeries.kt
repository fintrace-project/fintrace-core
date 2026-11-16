package com.github.melancholic.fintrace.core.domain.statistic

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class BalancesSeries(
    val granularity: Granularity,
    val from: LocalDate,
    val to: LocalDate,
    val buckets: List<BalancesSeriesBucket>,
)

enum class Granularity(val unit: ChronoUnit) {
    DAY(ChronoUnit.DAYS),
    WEEK(ChronoUnit.WEEKS),
    MONTH(ChronoUnit.MONTHS),
    YEAR(ChronoUnit.YEARS);

    fun startDate(date: LocalDate): LocalDate = when (this) {
        DAY -> date
        WEEK -> date.with(DayOfWeek.MONDAY)
        MONTH -> date.withDayOfMonth(1)
        YEAR -> date.withDayOfYear(1)
    }

    fun next(date: LocalDate): LocalDate = date.plus(1, unit)

    fun between(start: LocalDate, end: LocalDate): Long = unit.between(start, end) + 1

    fun postgresValue(): String = when (this) {
        DAY -> "1 day"
        WEEK -> "1 week"
        MONTH -> "1 month"
        YEAR -> "1 year"
    }
}

data class BalancesSeriesBucket(
    val start: LocalDate,
    val balance: CurrencyAmounts
)

// Null currency and total: a bucket with no account in the selection (fn_balance_series' LEFT JOIN)
data class BalanceSeriesRow(
    val start: LocalDate,
    val currency: String?,
    val total: BigDecimal?
)