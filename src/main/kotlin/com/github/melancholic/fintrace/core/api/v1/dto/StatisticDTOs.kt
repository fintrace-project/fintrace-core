package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.statistic.Granularity
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

data class CurrencyAmountsResponse(
    val byCurrency: List<CurrencyAmountResponse>,
    val converted: ConvertedAmountResponse?
)

data class CurrencyAmountResponse(
    val currency: String,
    val total: BigDecimal
)

data class ConvertedAmountResponse(
    val currency: String,
    val total: BigDecimal,
    val coveredCurrencies: List<String>
)

data class BalancesByCurrenciesResponse(
    val asOf: LocalDate,
    val totals: CurrencyAmountsResponse,
)

data class BalancesSeriesResponse(
    val granularity: Granularity,
    val from: LocalDate,
    val to: LocalDate,
    val buckets: List<BalancesSeriesBucketResponse>,
)

data class BalancesSeriesBucketResponse(
    val start: LocalDate,
    val balance: CurrencyAmountsResponse
)

data class CashflowResponse(
    val granularity: Granularity,
    val from: LocalDate,
    val to: LocalDate,
    val buckets: List<CashflowBucketResponse>,
    val totals: CashflowTotalsResponse,
)

data class CashflowBucketResponse(
    val start: LocalDate,
    val income: CurrencyAmountsResponse,
    val expense: CurrencyAmountsResponse,
    val net: CurrencyAmountsResponse,
    val count: List<CurrencyCountResponse>,
)

data class CashflowTotalsResponse(
    val income: CurrencyAmountsResponse,
    val expense: CurrencyAmountsResponse,
    val expenseRatio: List<CurrencyRatioResponse>,
    val net: CurrencyAmountsResponse,
    val netRatio: List<CurrencyRatioResponse>,
    val count: List<CurrencyCountResponse>,
    val perDay: CashflowPerDayResponse,
)

data class CashflowPerDayResponse(
    val income: CurrencyAmountsResponse,
    val expense: CurrencyAmountsResponse,
    val net: CurrencyAmountsResponse,
)

data class CurrencyCountResponse(
    val currency: String,
    val count: Long,
)

data class CurrencyRatioResponse(
    val currency: String,
    val ratio: BigDecimal,
)

data class CategoryTotalsResponse(
    val from: LocalDate,
    val to: LocalDate,
    val kind: CategoryKind,
    val rows: List<CategoryTotalResponse>,
)

data class CategoryTotalResponse(
    val categoryId: UUID,
    val parentId: UUID?,
    val total: CurrencyAmountsResponse,
    val selfTotal: CurrencyAmountsResponse,
)

data class TopCategoriesResponse(
    val from: LocalDate,
    val to: LocalDate,
    val kind: CategoryKind,
    val total: CurrencyAmountsResponse,
    val rows: List<TopCategoryResponse>,
    val other: CurrencyAmountsResponse,
)

data class TopCategoryResponse(
    val categoryId: UUID,
    val total: CurrencyAmountsResponse,
)
