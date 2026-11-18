package com.github.melancholic.fintrace.core.domain.entity

import java.math.BigDecimal
import java.util.*

data class CategoryTotalRow(
    val categoryId: UUID,
    val parentId: UUID?,
    val currency: String,
    val total: BigDecimal,
)
