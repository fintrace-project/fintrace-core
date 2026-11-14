package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.ConvertedAmountResponse
import com.github.melancholic.fintrace.core.api.v1.dto.CurrencyAmountResponse
import com.github.melancholic.fintrace.core.api.v1.dto.CurrencyAmountsResponse
import com.github.melancholic.fintrace.core.domain.entity.ConvertedAmount
import com.github.melancholic.fintrace.core.domain.entity.CurrencyAmount
import com.github.melancholic.fintrace.core.domain.entity.CurrencyAmounts
import org.mapstruct.Mapper

@Mapper(
    componentModel = "spring", uses = [
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