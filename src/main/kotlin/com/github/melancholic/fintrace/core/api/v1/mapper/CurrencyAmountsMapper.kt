package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.ConvertedAmountResponse
import com.github.melancholic.fintrace.core.api.v1.dto.CurrencyAmountResponse
import com.github.melancholic.fintrace.core.api.v1.dto.CurrencyAmountsResponse
import com.github.melancholic.fintrace.core.domain.statistic.ConvertedAmount
import com.github.melancholic.fintrace.core.domain.statistic.CurrencyAmount
import com.github.melancholic.fintrace.core.domain.statistic.CurrencyAmounts
import org.mapstruct.Mapper

@Mapper(
    uses = [
        CurrencyAmountsMapper.CurrencyAmountMapper::class,
        CurrencyAmountsMapper.ConvertedAmountMapper::class
    ]
)
interface CurrencyAmountsMapper {
    fun toResponse(domain: CurrencyAmounts): CurrencyAmountsResponse

    @Mapper
    interface CurrencyAmountMapper {
        fun toResponse(domain: CurrencyAmount): CurrencyAmountResponse
    }

    @Mapper
    interface ConvertedAmountMapper {
        fun toResponse(domain: ConvertedAmount): ConvertedAmountResponse
    }
}