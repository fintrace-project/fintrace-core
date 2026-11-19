package com.github.melancholic.fintrace.core.domain.statistic

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import java.time.LocalDate
import java.util.*

data class CategoryTotals(
    val from: LocalDate,
    val to: LocalDate,
    val kind: CategoryKind,
    val rows: List<CategoryTotal>,
)

data class CategoryTotal(
    val categoryId: UUID,
    val parentId: UUID?,
    val total: CurrencyAmounts,
    val selfTotal: CurrencyAmounts,
)