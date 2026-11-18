package com.github.melancholic.fintrace.core.util

import java.math.BigDecimal
import java.math.RoundingMode

object MathUtils {
    fun BigDecimal.divideWithDefaultPrecision(divisor: BigDecimal): BigDecimal =
        divide(divisor, 4, RoundingMode.HALF_EVEN)
}