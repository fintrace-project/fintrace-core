package com.github.melancholic.fintrace.core.domain.statistic

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import java.time.LocalDate
import java.util.*

data class TopCategories(
    val from: LocalDate,
    val to: LocalDate,
    val kind: CategoryKind,
    val rows: List<TopCategory>,
    val total: CurrencyAmounts,
    val other: CurrencyAmounts,
)

data class TopCategory(
    val categoryId: UUID,
    val total: CurrencyAmounts,
)
