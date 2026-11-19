package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.TopCategoriesResponse
import com.github.melancholic.fintrace.core.domain.statistic.TopCategories
import org.mapstruct.Mapper

@Mapper
interface TopCategoriesMapper {
    fun toResponse(domain: TopCategories): TopCategoriesResponse
}
