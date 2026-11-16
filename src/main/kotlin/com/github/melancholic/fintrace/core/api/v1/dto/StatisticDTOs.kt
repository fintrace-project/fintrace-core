package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.statistic.Granularity
import java.math.BigDecimal
import java.time.LocalDate

data class CurrencyAmountsResponse(
    val byCurrency: List<CurrencyAmountResponse>,
    val converted: ConvertedAmountResponse?
)

data class CurrencyAmountResponse(
    val currency: String,
    val total: BigDecimal
)

data class ConvertedAmountResponse(
    val currency: String,
    val total: BigDecimal,
    val coveredCurrencies: List<String>
)

data class BalancesByCurrenciesResponse(
    val asOf: LocalDate,
    val totals: CurrencyAmountsResponse,
)

data class BalancesSeriesResponse(
    val granularity: Granularity,
    val from: LocalDate,
    val to: LocalDate,
    val buckets: List<BalancesSeriesBucketResponse>,
)

data class BalancesSeriesBucketResponse(
    val start: LocalDate,
    val balance: CurrencyAmountsResponse
)
