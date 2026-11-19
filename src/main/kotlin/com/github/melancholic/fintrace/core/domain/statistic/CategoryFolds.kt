package com.github.melancholic.fintrace.core.domain.statistic

import com.github.melancholic.fintrace.core.domain.entity.CategoryTotalRow
import java.math.BigDecimal
import java.util.*

fun List<CategoryTotalRow>.toCategoryTotals(): List<CategoryTotal> {
    val parents = associate { it.categoryId to it.parentId }
    val booked = mapNotNull { row ->
        row.currency?.let { currency -> row.selfTotal?.let { Booked(row.categoryId, currency, it) } }
    }

    val totals = mutableMapOf<UUID, MutableMap<String, BigDecimal>>()
    booked.forEach { (categoryId, currency, amount) ->
        generateSequence(categoryId) { parents[it] }.forEach { id ->
            totals.getOrPut(id) { sortedMapOf() }.merge(currency, amount, BigDecimal::add)
        }
    }
    val selfTotals = booked.groupBy({ it.categoryId }, { CurrencyAmount(it.currency, it.amount) })

    return parents.keys.mapNotNull { id ->
        totals[id]?.let { total ->
            CategoryTotal(
                categoryId = id,
                parentId = parents[id],
                total = total.toCurrencyAmounts(),
                selfTotal = CurrencyAmounts(selfTotals[id].orEmpty()),
            )
        }
    }
}

fun CategoryTotals.toTop(limit: Int): TopCategories {
    val root = rows.singleOrNull { it.parentId == null }
    val ranked = root?.let { rows.childrenRanked(it.categoryId) }.orEmpty()
    return TopCategories(
        from = from,
        to = to,
        kind = kind,
        rows = ranked.take(limit).map { TopCategory(it.categoryId, it.total) },
        total = root?.total ?: CurrencyAmounts.EMPTY,
        other = ranked.drop(limit).map { it.total }.sumByCurrency(),
    )
}

internal fun List<CategoryTotal>.childrenRanked(rootId: UUID): List<CategoryTotal> =
    filter { it.parentId == rootId }
        .sortedWith(compareByDescending<CategoryTotal> { it.rankingAmount }.thenBy { it.categoryId.toString() })

private data class Booked(val categoryId: UUID, val currency: String, val amount: BigDecimal)

// One amount per row while `currency` is required; 3.10 ranks by `converted` instead
private val CategoryTotal.rankingAmount: BigDecimal
    get() = total.byCurrency.firstOrNull()?.total ?: BigDecimal.ZERO
