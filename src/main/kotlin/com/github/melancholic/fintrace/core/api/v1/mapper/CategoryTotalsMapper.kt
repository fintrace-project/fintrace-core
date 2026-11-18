package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.CategoryTotalsResponse
import com.github.melancholic.fintrace.core.domain.statistic.CategoryTotals
import org.mapstruct.Mapper

@Mapper
interface CategoryTotalsMapper {
    fun toResponse(domain: CategoryTotals): CategoryTotalsResponse
}
