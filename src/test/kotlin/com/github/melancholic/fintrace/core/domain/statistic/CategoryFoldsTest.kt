package com.github.melancholic.fintrace.core.domain.statistic

import com.github.melancholic.fintrace.core.domain.entity.CategoryTotalRow
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.*
import kotlin.test.assertEquals

/** Category totals up the tree (3.8) and the root's children ranked (3.8c): no Spring, no database. */
class CategoryFoldsTest {

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
    fun `only the root's children are ranked, largest first, a subcategory counted in its parent`() {
        val totals = listOf(
            category(ROOT, null),
            category(FOOD, ROOT, "EUR", "3"),
            category(GROCERIES, FOOD, "EUR", "5"),
            category(TRANSPORT, ROOT, "EUR", "6"),
            category(TAXI, TRANSPORT, "EUR", "1"),
            category(CAFES, ROOT, "EUR", "2"),
        ).toCategoryTotals()

        val ranked = totals.childrenRanked(ROOT)

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

        val ranked = totals.childrenRanked(ROOT)

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

        val ranked = totals.childrenRanked(ROOT)
        val kept = ranked.take(2).map { it.total }
        val other = ranked.drop(2).map { it.total }.sumByCurrency()

        assertEquals(amounts("EUR" to "2.5"), other)
        assertEquals(root.total, (kept + other).sumByCurrency())
    }

    private fun category(id: UUID, parentId: UUID?, currency: String? = null, selfTotal: String? = null) =
        CategoryTotalRow(id, parentId, currency, selfTotal?.let { BigDecimal(it).setScale(4) })

    private fun amounts(vararg byCurrency: Pair<String, String>) =
        CurrencyAmounts(byCurrency.map { (currency, total) -> CurrencyAmount(currency, BigDecimal(total).setScale(4)) })

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
