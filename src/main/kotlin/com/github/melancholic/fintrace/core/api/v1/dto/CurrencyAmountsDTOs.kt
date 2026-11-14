package com.github.melancholic.fintrace.core.api.v1.dto

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
