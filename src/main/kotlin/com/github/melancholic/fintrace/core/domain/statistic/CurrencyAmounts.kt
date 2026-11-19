package com.github.melancholic.fintrace.core.domain.statistic

import java.math.BigDecimal

data class CurrencyAmounts(
    val byCurrency: List<CurrencyAmount>,
    val converted: ConvertedAmount? = null
) {
    companion object {
        val EMPTY = CurrencyAmounts(emptyList())
    }
}

data class CurrencyAmount(
    val currency: String,
    val total: BigDecimal
)

data class ConvertedAmount(
    val currency: String,
    val total: BigDecimal,
    val coveredCurrencies: List<String>
)

internal fun List<CurrencyAmounts>.sumByCurrency(): CurrencyAmounts {
    val sums = sortedMapOf<String, BigDecimal>()
    forEach {
        amounts -> amounts.byCurrency.forEach {
            sums.merge(it.currency, it.total, BigDecimal::add)
        }
    }
    return sums.toCurrencyAmounts()
}

internal fun <R> List<R>.amountsOf(currency: (R) -> String?, total: (R) -> BigDecimal?) = CurrencyAmounts(
    mapNotNull { row ->
        currency(row)?.let { c ->
            total(row)?.let { CurrencyAmount(c, it) }
        }
    }
)

internal fun Map<String, BigDecimal>.toCurrencyAmounts() =
    CurrencyAmounts(map { (currency, total) -> CurrencyAmount(currency, total) })
