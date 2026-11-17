package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.CashflowResponse
import com.github.melancholic.fintrace.core.domain.statistic.Cashflow
import org.mapstruct.Mapper

@Mapper
interface CashflowMapper {
    fun toResponse(domain: Cashflow): CashflowResponse
}
