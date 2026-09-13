package com.github.melancholic.fintrace.core.domain.entity

import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import java.math.BigDecimal

data class BalanceAnchorContainer(
    val projection: BalanceAnchorProjection,
    val difference: BigDecimal
)
