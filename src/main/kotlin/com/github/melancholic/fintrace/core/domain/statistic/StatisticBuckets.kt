package com.github.melancholic.fintrace.core.domain.statistic

import java.time.LocalDate

data class StatisticBuckets(
    val granularity: Granularity,
    val firstStart: LocalDate,
    val lastStart: LocalDate,
    val end: LocalDate,
    val count: Long,
) {

    companion object {
        fun splitByBuckets(granularity: Granularity, from: LocalDate, to: LocalDate): StatisticBuckets {
            require(!from.isAfter(to)) { "from $from is after to $to" }

            val firstStart = granularity.startDate(from)
            val lastStart = granularity.startDate(to)

            return StatisticBuckets(
                granularity = granularity,
                firstStart = firstStart,
                lastStart = lastStart,
                end = granularity.next(lastStart).minusDays(1),
                count = granularity.between(firstStart, lastStart)
            )
        }
    }
}
