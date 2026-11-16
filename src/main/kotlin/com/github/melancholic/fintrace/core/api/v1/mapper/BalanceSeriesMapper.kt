package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.BalancesSeriesResponse
import com.github.melancholic.fintrace.core.domain.statistic.BalancesSeries
import org.mapstruct.Mapper

@Mapper
interface BalanceSeriesMapper {
    fun toResponse(domain: BalancesSeries): BalancesSeriesResponse
}