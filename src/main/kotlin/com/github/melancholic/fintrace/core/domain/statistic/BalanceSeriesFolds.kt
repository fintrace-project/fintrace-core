package com.github.melancholic.fintrace.core.domain.statistic

import com.github.melancholic.fintrace.core.domain.entity.BalanceSeriesRow

fun List<BalanceSeriesRow>.toSeriesBuckets(): List<BalancesSeriesBucket> =
    groupBy { it.start }.map { (start, rows) ->
        BalancesSeriesBucket(
            start = start,
            balance = rows.amountsOf(BalanceSeriesRow::currency, BalanceSeriesRow::total),
        )
    }
