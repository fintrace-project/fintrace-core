package com.github.melancholic.fintrace.core.util

import com.github.melancholic.fintrace.core.domain.entity.BalanceSeriesRow
import com.github.melancholic.fintrace.core.domain.entity.CashflowRow
import com.github.melancholic.fintrace.core.domain.entity.CategoryTotalRow
import com.github.melancholic.fintrace.core.domain.statistic.*
import com.github.melancholic.fintrace.core.util.MathUtils.divideWithDefaultPrecision
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object StatisticHelper {

    fun List<BalanceSeriesRow>.toSeriesBuckets(): List<BalancesSeriesBucket> =
        this.groupBy { it.start }
            .mapValues { (_, v) ->
                v.filter { it.currency != null && it.total != null }
                    .associateBy { it.currency }
                    .map { (currency, row) -> CurrencyAmount(currency!!, row.total!!) }
            }
            .map { (startDate, byCurrency) ->
                BalancesSeriesBucket(
                    start = startDate,
                    balance = CurrencyAmounts(byCurrency = byCurrency)
                )
            }


    fun List<CashflowRow>.toCashflowBuckets(): List<CashflowBucket> {
        return groupBy { it.start }
            .map { (start, rows) ->
                val real = rows.filter { it.currency != null }
                CashflowBucket(
                    start = start,
                    income = CurrencyAmounts(real.map { CurrencyAmount(it.currency!!, it.income) }),
                    expense = CurrencyAmounts(real.map { CurrencyAmount(it.currency!!, it.expense) }),
                    net = CurrencyAmounts(real.map { CurrencyAmount(it.currency!!, it.net) }),
                    count = real.map { CurrencyCount(it.currency!!, it.operations) },
                )
            }
    }

    fun List<CashflowRow>.toCashflowTotals(splits: StatisticBuckets, until: LocalDateTime): CashflowTotals {
        val byCurrency = filter { it.currency != null }
            .groupBy { it.currency!! }

        fun total(pick: (CashflowRow) -> BigDecimal) = CurrencyAmounts(
            byCurrency.map { (currency, rows) ->
                CurrencyAmount(currency, rows.sumOf(pick))
            }
        )

        val days = ChronoUnit.DAYS.between(splits.firstStart, until.toLocalDate())

        fun perDay(amounts: CurrencyAmounts) = CurrencyAmounts(amounts.byCurrency.map {
            CurrencyAmount(
                it.currency,
                it.total.divideWithDefaultPrecision(BigDecimal.valueOf(days))
            )
        })

        fun ratio(pick1: (CashflowRow) -> BigDecimal, pick2: (CashflowRow) -> BigDecimal): List<CurrencyRatio> =
            byCurrency.mapNotNull { (currency, rows) ->
                val d1 = rows.sumOf(pick1)
                val d2 = rows.sumOf(pick2)
                return@mapNotNull if (d2.signum() != 0) CurrencyRatio(
                    currency,
                    d1.divideWithDefaultPrecision(d2)
                ) else null
            }

        val income = total { x -> x.income }
        val expense = total { it.expense }
        val net = total { it.net }

        return CashflowTotals(
            income = income,
            expense = expense,
            expenseRatio = ratio(CashflowRow::expense, CashflowRow::income),
            net = net,
            netRatio = ratio(CashflowRow::net, CashflowRow::income),
            count = byCurrency.map { (currency, rows) ->
                CurrencyCount(
                    currency,
                    rows.sumOf { it.operations })
            },
            perDay = CashflowPerDay(
                perDay(income),
                perDay(expense),
                perDay(net)
            )
        )
    }

    fun List<CategoryTotalRow>.toCategoryTotals(): List<CategoryTotal> = groupBy { it.categoryId }
        .map { (catId, rows) ->
            CategoryTotal(
                catId,
                rows.map { it.parentId }.distinct().single(),
                CurrencyAmounts(rows.map { CurrencyAmount(it.currency, it.total) })
            )
        }
}