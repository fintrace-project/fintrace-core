package com.github.melancholic.fintrace.core.util

import com.github.melancholic.fintrace.core.domain.entity.CashflowRow
import com.github.melancholic.fintrace.core.domain.entity.CategoryTotalRow
import com.github.melancholic.fintrace.core.domain.statistic.CategoryTotal
import com.github.melancholic.fintrace.core.domain.statistic.CurrencyAmount
import com.github.melancholic.fintrace.core.domain.statistic.CurrencyAmounts
import com.github.melancholic.fintrace.core.domain.statistic.CurrencyCount
import com.github.melancholic.fintrace.core.domain.statistic.CurrencyRatio
import com.github.melancholic.fintrace.core.domain.statistic.Granularity
import com.github.melancholic.fintrace.core.domain.statistic.StatisticBuckets
import com.github.melancholic.fintrace.core.util.StatisticHelper.toCashflowBuckets
import com.github.melancholic.fintrace.core.util.StatisticHelper.toCashflowTotals
import com.github.melancholic.fintrace.core.util.StatisticHelper.sumByCurrency
import com.github.melancholic.fintrace.core.util.StatisticHelper.toCategoryTotals
import com.github.melancholic.fintrace.core.util.StatisticHelper.toRanked
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*
import kotlin.test.assertEquals

/** Cashflow's rows into buckets and the period's totals (3.6), category totals up the tree (3.8): no Spring, no database. */
class StatisticHelperTest {

    @Test
    fun `a period's rows make one bucket holding every currency`() {
        val buckets = listOf(
            row("2026-03-01", "EUR", income = "0", expense = "12", net = "-12", operations = 1),
            row("2026-03-01", "USD", income = "5", expense = "0", net = "5", operations = 1),
        ).toCashflowBuckets()

        assertEquals(1, buckets.size)
        val march = buckets.single()
        assertEquals(LocalDate.parse("2026-03-01"), march.start)
        assertEquals(listOf(amount("EUR", "0"), amount("USD", "5")), march.income.byCurrency)
        assertEquals(listOf(amount("EUR", "12"), amount("USD", "0")), march.expense.byCurrency)
        assertEquals(listOf(amount("EUR", "-12"), amount("USD", "5")), march.net.byCurrency)
        assertEquals(listOf(CurrencyCount("EUR", 1), CurrencyCount("USD", 1)), march.count)
    }

    @Test
    fun `buckets keep the rows' order of dates`() {
        val starts = listOf(
            row("2026-01-01", "EUR"),
            row("2026-02-01", "EUR"),
            row("2026-03-01", "EUR"),
        ).toCashflowBuckets().map { it.start.toString() }

        assertEquals(listOf("2026-01-01", "2026-02-01", "2026-03-01"), starts)
    }

    @Test
    fun `a period with no account in the selection is kept, empty`() {
        val buckets = listOf(
            CashflowRow(LocalDate.parse("2026-01-01"), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0),
            CashflowRow(LocalDate.parse("2026-02-01"), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0),
        ).toCashflowBuckets()

        // D3: every bucket is present; it has nothing to show, not nothing to be
        assertEquals(2, buckets.size)
        buckets.forEach {
            assertEquals(emptyList(), it.income.byCurrency)
            assertEquals(emptyList(), it.expense.byCurrency)
            assertEquals(emptyList(), it.net.byCurrency)
            assertEquals(emptyList(), it.count)
        }
    }

    @Test
    fun `totals sum each currency over the buckets, in Core's order`() {
        val rows = listOf(
            row("2026-01-01", "EUR", income = "100", expense = "30.5", net = "69.5", operations = 2),
            row("2026-01-01", "USD", income = "0", expense = "0", net = "0", operations = 0),
            row("2026-02-01", "EUR", income = "0", expense = "12", net = "-12", operations = 1),
            row("2026-02-01", "USD", income = "5", expense = "0", net = "5", operations = 1),
        )

        val totals = rows.toCashflowTotals(february(), until("2026-03-01"))

        assertEquals(listOf(amount("EUR", "100"), amount("USD", "5")), totals.income.byCurrency)
        assertEquals(listOf(amount("EUR", "42.5"), amount("USD", "0")), totals.expense.byCurrency)
        assertEquals(listOf(amount("EUR", "57.5"), amount("USD", "5")), totals.net.byCurrency)
        assertEquals(listOf(CurrencyCount("EUR", 3), CurrencyCount("USD", 1)), totals.count)
    }

    @Test
    fun `per day divides each total by the period's days, at four places, half even`() {
        // January's 31 days, the whole of it asked for
        val totals = listOf(row("2026-01-01", "EUR", income = "100", expense = "31", net = "69", operations = 3))
            .toCashflowTotals(january(), until("2026-02-01"))

        assertEquals(listOf(amount("EUR", "3.2258")), totals.perDay.income.byCurrency)
        assertEquals(listOf(amount("EUR", "1.0000")), totals.perDay.expense.byCurrency)
        assertEquals(listOf(amount("EUR", "2.2258")), totals.perDay.net.byCurrency)
    }

    @Test
    fun `per day counts the days up to the requested to, not the bucket's end`() {
        // Half of January asked for: 15 days, so 30 a day rather than 30 over the month's 31
        val totals = listOf(row("2026-01-01", "EUR", income = "450", expense = "0", net = "450", operations = 1))
            .toCashflowTotals(january(), until("2026-01-16"))

        assertEquals(listOf(amount("EUR", "30.0000")), totals.perDay.income.byCurrency)
    }

    @Test
    fun `ratios divide the period's totals, one per currency, not each bucket's`() {
        val totals = listOf(
            row("2026-01-01", "EUR", income = "100", expense = "30", net = "70", operations = 2),
            row("2026-02-01", "EUR", income = "0", expense = "12", net = "-12", operations = 1),
        ).toCashflowTotals(february(), until("2026-03-01"))

        assertEquals(listOf(ratio("EUR", "0.4200")), totals.expenseRatio)
        assertEquals(listOf(ratio("EUR", "0.5800")), totals.netRatio)
    }

    @Test
    fun `a currency with no income is left out of both ratios, the rest in Core's order`() {
        val totals = listOf(
            row("2026-01-01", "EUR", income = "100", expense = "25", net = "75", operations = 2),
            row("2026-01-01", "GBP", income = "0", expense = "10", net = "-10", operations = 1),
            row("2026-01-01", "USD", income = "50", expense = "10", net = "40", operations = 2),
        ).toCashflowTotals(january(), until("2026-02-01"))

        assertEquals(listOf(ratio("EUR", "0.2500"), ratio("USD", "0.2000")), totals.expenseRatio)
        assertEquals(listOf(ratio("EUR", "0.7500"), ratio("USD", "0.8000")), totals.netRatio)
    }

    @Test
    fun `spending past the income answers an expense ratio above one and a negative net ratio`() {
        val totals = listOf(row("2026-01-01", "EUR", income = "10", expense = "35", net = "-25", operations = 2))
            .toCashflowTotals(january(), until("2026-02-01"))

        assertEquals(listOf(ratio("EUR", "3.5000")), totals.expenseRatio)
        assertEquals(listOf(ratio("EUR", "-2.5000")), totals.netRatio)
    }

    @Test
    fun `ratios round at four places, half even`() {
        // 1 / 20000 = 0.00005 exactly: half-up would answer 0.0001
        val totals = listOf(row("2026-01-01", "EUR", income = "20000", expense = "1", net = "19999", operations = 2))
            .toCashflowTotals(january(), until("2026-02-01"))

        assertEquals(listOf(ratio("EUR", "0.0000")), totals.expenseRatio)
        assertEquals(listOf(ratio("EUR", "1.0000")), totals.netRatio)
    }

    @Test
    fun `an empty selection totals nothing`() {
        val totals = listOf(
            CashflowRow(LocalDate.parse("2026-01-01"), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0),
        ).toCashflowTotals(january(), until("2026-02-01"))

        assertEquals(emptyList(), totals.income.byCurrency)
        assertEquals(emptyList(), totals.count)
        assertEquals(emptyList(), totals.perDay.net.byCurrency)
        assertEquals(emptyList(), totals.expenseRatio)
        assertEquals(emptyList(), totals.netRatio)
    }

    @Test
    fun `a parent with nothing booked to it rolls up its subcategories, up to the root`() {
        val totals = listOf(
            category(ROOT, null),
            category(FOOD, ROOT),
            category(GROCERIES, FOOD, "EUR", "200"),
            category(CAFES, FOOD, "EUR", "100"),
        ).toCategoryTotals()

        assertEquals(
            listOf(
                CategoryTotal(ROOT, null, amounts("EUR" to "300"), amounts()),
                CategoryTotal(FOOD, ROOT, amounts("EUR" to "300"), amounts()),
                CategoryTotal(GROCERIES, FOOD, amounts("EUR" to "200"), amounts("EUR" to "200")),
                CategoryTotal(CAFES, FOOD, amounts("EUR" to "100"), amounts("EUR" to "100")),
            ),
            totals,
        )
    }

    @Test
    fun `a parent's own operations are its selfTotal and part of its total`() {
        val totals = listOf(
            category(FOOD, null, "EUR", "50"),
            category(GROCERIES, FOOD, "EUR", "200"),
        ).toCategoryTotals()

        assertEquals(
            listOf(
                CategoryTotal(FOOD, null, amounts("EUR" to "250"), amounts("EUR" to "50")),
                CategoryTotal(GROCERIES, FOOD, amounts("EUR" to "200"), amounts("EUR" to "200")),
            ),
            totals,
        )
    }

    @Test
    fun `every level of a deep branch carries its descendants' total`() {
        val totals = listOf(
            category(ROOT, null),
            category(FOOD, ROOT),
            category(GROCERIES, FOOD),
            category(ORGANIC, GROCERIES, "EUR", "7"),
        ).toCategoryTotals()

        assertEquals(List(4) { amounts("EUR" to "7") }, totals.map { it.total })
        assertEquals(listOf(amounts(), amounts(), amounts(), amounts("EUR" to "7")), totals.map { it.selfTotal })
    }

    @Test
    fun `each currency is rolled up apart, ordered by code`() {
        val totals = listOf(
            category(FOOD, null),
            category(GROCERIES, FOOD, "EUR", "10"),
            category(GROCERIES, FOOD, "GBP", "4"),
            category(CAFES, FOOD, "USD", "3"),
            category(CAFES, FOOD, "EUR", "5"),
        ).toCategoryTotals()

        assertEquals(
            listOf(
                CategoryTotal(FOOD, null, amounts("EUR" to "15", "GBP" to "4", "USD" to "3"), amounts()),
                CategoryTotal(
                    GROCERIES, FOOD, amounts("EUR" to "10", "GBP" to "4"), amounts("EUR" to "10", "GBP" to "4"),
                ),
                CategoryTotal(CAFES, FOOD, amounts("EUR" to "5", "USD" to "3"), amounts("USD" to "3", "EUR" to "5")),
            ),
            totals,
        )
    }

    @Test
    fun `a branch with no money in it is left out`() {
        val totals = listOf(
            category(ROOT, null),
            category(FOOD, ROOT),
            category(GROCERIES, FOOD, "EUR", "5"),
            category(TRANSPORT, ROOT),
            category(TAXI, TRANSPORT),
        ).toCategoryTotals()

        assertEquals(listOf(ROOT, FOOD, GROCERIES), totals.map { it.categoryId })
    }

    @Test
    fun `a tree with no amount anywhere answers nothing`() {
        assertEquals(emptyList(), listOf(category(ROOT, null), category(FOOD, ROOT)).toCategoryTotals())
        assertEquals(emptyList(), emptyList<CategoryTotalRow>().toCategoryTotals())
    }

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
        assertEquals(CurrencyAmounts(emptyList()), emptyList<CurrencyAmounts>().sumByCurrency())
    }

    @Test
    fun `only the root's children are ranked, largest first, a subcategory counted in its parent`() {
        val totals = listOf(
            category(ROOT, null),
            category(FOOD, ROOT, "EUR", "3"),
            category(GROCERIES, FOOD, "EUR", "5"),
            category(TRANSPORT, ROOT, "EUR", "6"),
            category(TAXI, TRANSPORT, "EUR", "1"),
            category(CAFES, ROOT, "EUR", "2"),
        ).toCategoryTotals()

        val ranked = totals.toRanked(totals.single { it.parentId == null })

        assertEquals(listOf(FOOD, TRANSPORT, CAFES), ranked.map { it.categoryId })
        assertEquals(listOf(amounts("EUR" to "8"), amounts("EUR" to "7"), amounts("EUR" to "2")), ranked.map { it.total })
    }

    @Test
    fun `equal totals rank by categoryId as text`() {
        // Signed comparison would put the 8… id first; text order puts the 1… id first
        val low = UUID.fromString("10000000-0000-7000-8000-000000000000")
        val high = UUID.fromString("80000000-0000-7000-8000-000000000000")
        val totals = listOf(
            category(ROOT, null),
            category(high, ROOT, "EUR", "4"),
            category(low, ROOT, "EUR", "4"),
        ).toCategoryTotals()

        val ranked = totals.toRanked(totals.single { it.parentId == null })

        assertEquals(listOf(low, high), ranked.map { it.categoryId })
    }

    @Test
    fun `the kept rows and the summed rest add up to the root's total`() {
        val totals = listOf(
            category(ROOT, null),
            category(FOOD, ROOT, "EUR", "8"),
            category(GROCERIES, FOOD, "EUR", "1.25"),
            category(TRANSPORT, ROOT, "EUR", "6"),
            category(CAFES, ROOT, "EUR", "2"),
            category(TAXI, ROOT, "EUR", "0.5"),
        ).toCategoryTotals()
        val root = totals.single { it.parentId == null }

        val ranked = totals.toRanked(root)
        val kept = ranked.take(2).map { it.total }
        val other = ranked.drop(2).map { it.total }.sumByCurrency()

        assertEquals(amounts("EUR" to "2.5"), other)
        assertEquals(root.total, (kept + other).sumByCurrency())
    }

    private fun category(id: UUID, parentId: UUID?, currency: String? = null, selfTotal: String? = null) =
        CategoryTotalRow(id, parentId, currency, selfTotal?.let { BigDecimal(it).setScale(4) })

    private fun amounts(vararg byCurrency: Pair<String, String>) =
        CurrencyAmounts(byCurrency.map { (currency, total) -> amount(currency, total) })

    private fun row(
        start: String,
        currency: String,
        income: String = "0",
        expense: String = "0",
        net: String = "0",
        operations: Long = 0,
    ) = CashflowRow(
        LocalDate.parse(start),
        currency,
        BigDecimal(income).setScale(4),
        BigDecimal(expense).setScale(4),
        BigDecimal(net).setScale(4),
        operations,
    )

    private fun amount(currency: String, total: String) = CurrencyAmount(currency, BigDecimal(total).setScale(4))

    private fun ratio(currency: String, ratio: String) = CurrencyRatio(currency, BigDecimal(ratio))

    private fun january() =
        StatisticBuckets.splitByBuckets(Granularity.MONTH, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"))

    private fun february() =
        StatisticBuckets.splitByBuckets(Granularity.MONTH, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-28"))

    private fun until(date: String) = LocalDate.parse(date).atStartOfDay()

    private companion object {
        val ROOT: UUID = UUID.randomUUID()
        val FOOD: UUID = UUID.randomUUID()
        val GROCERIES: UUID = UUID.randomUUID()
        val ORGANIC: UUID = UUID.randomUUID()
        val CAFES: UUID = UUID.randomUUID()
        val TRANSPORT: UUID = UUID.randomUUID()
        val TAXI: UUID = UUID.randomUUID()
    }
}
