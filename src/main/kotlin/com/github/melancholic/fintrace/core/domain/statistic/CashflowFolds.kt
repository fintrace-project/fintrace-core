package com.github.melancholic.fintrace.core.domain.statistic

import com.github.melancholic.fintrace.core.domain.entity.CashflowRow
import com.github.melancholic.fintrace.core.util.MathUtils.divideWithDefaultPrecision
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

fun List<CashflowRow>.toCashflowBuckets(): List<CashflowBucket> =
    groupBy { it.start }.map { (start, rows) ->
        CashflowBucket(
            start = start,
            income = rows.amountsOf(CashflowRow::currency, CashflowRow::income),
            expense = rows.amountsOf(CashflowRow::currency, CashflowRow::expense),
            net = rows.amountsOf(CashflowRow::currency, CashflowRow::net),
            count = rows.mapNotNull { row -> row.currency?.let { CurrencyCount(it, row.operations) } },
        )
    }

fun List<CashflowRow>.toCashflowTotals(from: LocalDate, until: LocalDateTime): CashflowTotals {
    val byCurrency = groupBy { it.currency }
        .mapNotNull { (currency, rows) -> currency?.let { it to rows } }

    fun total(pick: (CashflowRow) -> BigDecimal) =
        CurrencyAmounts(byCurrency.map { (currency, rows) -> CurrencyAmount(currency, rows.sumOf(pick)) })

    fun ratio(part: (CashflowRow) -> BigDecimal, base: (CashflowRow) -> BigDecimal): List<CurrencyRatio> =
        byCurrency.mapNotNull { (currency, rows) ->
            val baseSum = rows.sumOf(base)
            if (baseSum.signum() == 0) null
            else CurrencyRatio(currency, rows.sumOf(part).divideWithDefaultPrecision(baseSum))
        }

    val days = BigDecimal.valueOf(ChronoUnit.DAYS.between(from, until.toLocalDate()))

    fun perDay(amounts: CurrencyAmounts) = CurrencyAmounts(
        amounts.byCurrency.map { CurrencyAmount(it.currency, it.total.divideWithDefaultPrecision(days)) }
    )

    val income = total(CashflowRow::income)
    val expense = total(CashflowRow::expense)
    val net = total(CashflowRow::net)

    return CashflowTotals(
        income = income,
        expense = expense,
        expenseRatio = ratio(part = CashflowRow::expense, base = CashflowRow::income),
        net = net,
        count = byCurrency.map { (currency, rows) -> CurrencyCount(currency, rows.sumOf { it.operations }) },
        netRatio = ratio(part = CashflowRow::net, base = CashflowRow::income),
        perDay = CashflowPerDay(income = perDay(income), expense = perDay(expense), net = perDay(net)),
    )
}
