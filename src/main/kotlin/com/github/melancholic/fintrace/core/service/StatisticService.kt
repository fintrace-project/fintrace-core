package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.dao.BalanceDAO
import com.github.melancholic.fintrace.core.dao.StatisticDAO
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.statistic.*
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.util.StatisticHelper.sumByCurrency
import com.github.melancholic.fintrace.core.util.StatisticHelper.toCashflowBuckets
import com.github.melancholic.fintrace.core.util.StatisticHelper.toCashflowTotals
import com.github.melancholic.fintrace.core.util.StatisticHelper.toCategoryTotals
import com.github.melancholic.fintrace.core.util.StatisticHelper.toRanked
import com.github.melancholic.fintrace.core.util.StatisticHelper.toSeriesBuckets
import com.github.melancholic.fintrace.core.util.TimestampProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.*

sealed interface StatisticService {

    fun getBalancesByAccounts(workspaceId: UUID, asOf: LocalDate, includeArchived: Boolean): List<AccountBalance>
    fun getBalancesByCurrencies(workspaceId: UUID, asOf: LocalDate, includeArchived: Boolean): CurrencyAmounts
    fun getBalancesSeries(
        workspaceId: UUID,
        granularity: Granularity,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): BalancesSeries

    fun getCashflow(
        workspaceId: UUID,
        granularity: Granularity,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): Cashflow

    fun getCategoryTotals(
        workspaceId: UUID,
        currency: String?,
        kind: CategoryKind,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): CategoryTotals

    fun getTopCategories(
        workspaceId: UUID,
        limit: Int,
        currency: String,
        kind: CategoryKind,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): TopCategories
}

@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
class StatisticServiceImpl(
    private val balanceDAO: BalanceDAO,
    private val statisticDAO: StatisticDAO,
    private val timestampProvider: TimestampProvider
) : StatisticService {

    override fun getBalancesByAccounts(
        workspaceId: UUID,
        asOf: LocalDate,
        includeArchived: Boolean
    ): List<AccountBalance> =
        balanceDAO.getBalancesByAccounts(workspaceId, asOf, includeArchived)

    override fun getBalancesByCurrencies(
        workspaceId: UUID,
        asOf: LocalDate,
        includeArchived: Boolean
    ): CurrencyAmounts {
        return CurrencyAmounts(
            byCurrency = balanceDAO.getBalancesByCurrencies(workspaceId, asOf, includeArchived)
        )
    }

    override fun getBalancesSeries(
        workspaceId: UUID,
        granularity: Granularity,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): BalancesSeries {
        val to = endDate ?: timestampProvider.now().toLocalDate()
        val from = startDate
            ?: statisticDAO.firstActivityDate(workspaceId, accountIds, includeArchived)
            ?: return emptySeries(granularity, to)
        if (from.isAfter(to)) {
            if (startDate != null) {
                throw ValidationError("'from' $from is after 'to' $to", "from")
            }
            return emptySeries(granularity, to)
        }

        val buckets = StatisticBuckets.splitByBuckets(granularity, from, to)
        if (buckets.count > MAX_BUCKETS_LIMIT) {
            throw ValidationError(
                "$granularity from $from to $to is ${buckets.count} buckets, at most $MAX_BUCKETS_LIMIT",
                "granularity"
            )
        }

        val rows = statisticDAO.getBalanceSeries(
            workspaceId,
            buckets,
            to.plusDays(1).atStartOfDay(),
            accountIds,
            includeArchived
        )

        return BalancesSeries(
            granularity,
            buckets.firstStart,
            buckets.end,
            rows.toSeriesBuckets()
        )
    }

    override fun getCashflow(
        workspaceId: UUID,
        granularity: Granularity,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): Cashflow {
        val to = endDate ?: timestampProvider.now().toLocalDate()
        val from = startDate
            ?: statisticDAO.firstOperationDate(workspaceId, accountIds, includeArchived)
            ?: return emptyCashflow(granularity, to)
        if (from.isAfter(to)) {
            if (startDate != null) {
                throw ValidationError("'from' $from is after 'to' $to", "from")
            }
            return emptyCashflow(granularity, to)
        }

        val splits = splitByBuckets(granularity, from, to)
        val until = to.plusDays(1).atStartOfDay()

        val rows = statisticDAO.getCashflow(
            workspaceId,
            splits,
            until,
            accountIds,
            includeArchived
        )

        return Cashflow(
            granularity,
            splits.firstStart,
            splits.end,
            rows.toCashflowBuckets(),
            rows.toCashflowTotals(splits, until)
        )
    }

    override fun getCategoryTotals(
        workspaceId: UUID,
        currency: String?,
        kind: CategoryKind,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): CategoryTotals {
        val to = endDate ?: timestampProvider.now().toLocalDate()
        val from = startDate
            ?: statisticDAO.firstOperationDate(workspaceId, accountIds, includeArchived)
            ?: return emptyCategoryTotals(kind, to)
        if (from.isAfter(to)) {
            if (startDate != null) {
                throw ValidationError("'from' $from is after 'to' $to", "from")
            }
            return emptyCategoryTotals(kind, to)
        }

        val rows = statisticDAO.getCategoryTotals(
            workspaceId,
            currency,
            kind,
            from.atStartOfDay(),
            to.plusDays(1).atStartOfDay(),
            accountIds,
            includeArchived
        )

        return CategoryTotals(
            from,
            to,
            kind,
            rows.toCategoryTotals()
        )
    }

    override fun getTopCategories(
        workspaceId: UUID,
        limit: Int,
        currency: String,
        kind: CategoryKind,
        startDate: LocalDate?,
        endDate: LocalDate?,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): TopCategories {
        val totals = getCategoryTotals(workspaceId, currency, kind, startDate, endDate, accountIds, includeArchived)
        val root = totals.rows.singleOrNull { it.parentId == null }
            ?: return emptyTopCategories(kind, totals.from, totals.to)

        val ranked = totals.rows.toRanked(root)

        val tops = ranked.take(limit).map { TopCategory(it.categoryId, it.total) }
        val other = ranked.drop(limit).map { it.total }.sumByCurrency()
        return TopCategories(
            totals.from,
            totals.to,
            kind,
            tops,
            root.total,
            other
        )
    }

    private fun splitByBuckets(
        granularity: Granularity,
        from: LocalDate,
        to: LocalDate
    ): StatisticBuckets {
        val buckets = StatisticBuckets.splitByBuckets(granularity, from, to)
        if (buckets.count > MAX_BUCKETS_LIMIT) {
            throw ValidationError(
                "$granularity from $from to $to is ${buckets.count} buckets, at most $MAX_BUCKETS_LIMIT",
                "granularity"
            )
        }
        return buckets
    }

    private fun emptySeries(
        granularity: Granularity,
        to: LocalDate
    ): BalancesSeries {
        val window = StatisticBuckets.splitByBuckets(granularity, to, to)
        return BalancesSeries(
            granularity = granularity,
            from = window.firstStart,
            to = window.end,
            buckets = emptyList()
        )
    }

    private fun emptyCashflow(
        granularity: Granularity,
        to: LocalDate
    ): Cashflow {
        val window = StatisticBuckets.splitByBuckets(granularity, to, to)
        return Cashflow(
            granularity = granularity,
            from = window.firstStart,
            to = window.end,
            buckets = emptyList(),
            totals = CashflowTotals(
                income = CurrencyAmounts(emptyList()),
                expense = CurrencyAmounts(emptyList()),
                expenseRatio = emptyList(),
                net = CurrencyAmounts(emptyList()),
                netRatio = emptyList(),
                count = emptyList(),
                perDay = CashflowPerDay(
                    income = CurrencyAmounts(emptyList()),
                    expense = CurrencyAmounts(emptyList()),
                    net = CurrencyAmounts(emptyList())
                )
            )
        )
    }

    private fun emptyCategoryTotals(
        kind: CategoryKind,
        to: LocalDate,
    ): CategoryTotals = CategoryTotals(to, to, kind, emptyList())

    private fun emptyTopCategories(
        kind: CategoryKind,
        from: LocalDate,
        to: LocalDate
    ): TopCategories = TopCategories(
        from,
        to,
        kind,
        emptyList(),
        CurrencyAmounts(emptyList()),
        CurrencyAmounts(emptyList())
    )

    private companion object {
        const val MAX_BUCKETS_LIMIT: Int = 2000;
    }
}