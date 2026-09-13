package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.AccountBalanceResponse
import com.github.melancholic.fintrace.core.domain.entity.AccountBalance
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface AccountBalanceMapper {
    fun toResponse(domain: AccountBalance): AccountBalanceResponse
    fun toResponseList(domain: List<AccountBalance>): List<AccountBalanceResponse>
}