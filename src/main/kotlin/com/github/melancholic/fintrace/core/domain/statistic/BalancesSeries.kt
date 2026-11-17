package com.github.melancholic.fintrace.core.domain.statistic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class BalancesSeries(
    val granularity: Granularity,
    val from: LocalDate,
    val to: LocalDate,
    val buckets: List<BalancesSeriesBucket>,
)

enum class Granularity(val unit: ChronoUnit, val postgresUnit: String) {
    DAY(unit = ChronoUnit.DAYS, postgresUnit = "day"),
    WEEK(unit = ChronoUnit.WEEKS, postgresUnit = "week"),
    MONTH(unit = ChronoUnit.MONTHS, postgresUnit = "month"),
    YEAR(unit = ChronoUnit.YEARS, postgresUnit = "year");

    fun startDate(date: LocalDate): LocalDate = when (this) {
        DAY -> date
        WEEK -> date.with(DayOfWeek.MONDAY)
        MONTH -> date.withDayOfMonth(1)
        YEAR -> date.withDayOfYear(1)
    }

    fun next(date: LocalDate): LocalDate = date.plus(1, unit)

    fun between(start: LocalDate, end: LocalDate): Long = unit.between(start, end) + 1

    fun postgresInterval(): String = "1 $postgresUnit"
}

data class BalancesSeriesBucket(
    val start: LocalDate,
    val balance: CurrencyAmounts
)

