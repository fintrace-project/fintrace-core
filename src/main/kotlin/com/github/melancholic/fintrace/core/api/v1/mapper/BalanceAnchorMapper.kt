package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.BalanceAnchorResponse
import com.github.melancholic.fintrace.core.domain.entity.BalanceAnchorContainer
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import java.math.BigDecimal

@Mapper(componentModel = "spring")
interface BalanceAnchorMapper {
    @Mapping(source = "difference", target = "difference")
    fun toResponse(projection: BalanceAnchorProjection, difference: BigDecimal): BalanceAnchorResponse

    fun toResponseList(containers: List<BalanceAnchorContainer>): List<BalanceAnchorResponse> =
        containers.map { toResponse(it.projection, it.difference) }

}