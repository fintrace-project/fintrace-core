package com.github.melancholic.fintrace.core.domain.statistic

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/** Sums across `CurrencyAmounts` (3.8c): no Spring, no database. */
class CurrencyAmountsTest {

    @Test
    fun `amounts are summed per currency, ordered by code`() {
        val sum = listOf(
            amounts("USD" to "3", "EUR" to "10"),
            amounts("EUR" to "2.5"),
            amounts("GBP" to "1"),
        ).sumByCurrency()

        assertEquals(amounts("EUR" to "12.5", "GBP" to "1", "USD" to "3"), sum)
    }

    @Test
    fun `nothing to sum is an empty amount`() {
        assertEquals(CurrencyAmounts.EMPTY, emptyList<CurrencyAmounts>().sumByCurrency())
    }

    private fun amounts(vararg byCurrency: Pair<String, String>) =
        CurrencyAmounts(byCurrency.map { (currency, total) -> CurrencyAmount(currency, BigDecimal(total).setScale(4)) })
}
