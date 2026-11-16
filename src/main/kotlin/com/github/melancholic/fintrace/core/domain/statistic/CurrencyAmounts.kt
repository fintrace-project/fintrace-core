package com.github.melancholic.fintrace.core.domain.statistic

import java.math.BigDecimal

data class CurrencyAmounts(
    val byCurrency: List<CurrencyAmount>,
    val converted: ConvertedAmount? = null
)

data class CurrencyAmount(
    val currency: String,
    val total: BigDecimal
)

data class ConvertedAmount(
    val currency: String,
    val total: BigDecimal,
    val coveredCurrencies: List<String>
)
