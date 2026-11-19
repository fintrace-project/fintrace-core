package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.dao.BalanceDAO
import com.github.melancholic.fintrace.core.dao.StatisticDAO
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.statistic.*
import com.github.melancholic.fintrace.core.exception.ValidationError
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
    ): CurrencyAmounts =
        CurrencyAmounts(byCurrency = balanceDAO.getBalancesByCurrencies(workspaceId, asOf, includeArchived))

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

        val buckets = splitByBuckets(granularity, from, to)

        val rows = statisticDAO.getBalanceSeries(
            workspaceId,
            buckets,
            to.plusDays(1).atStartOfDay(),
            accountIds,
            includeArchived
        )

        return BalancesSeries(
            granularity = granularity,
            from = buckets.firstStart,
            to = buckets.end,
            buckets = rows.toSeriesBuckets()
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
            granularity = granularity,
            from = splits.firstStart,
            to = splits.end,
            buckets = rows.toCashflowBuckets(),
            totals = rows.toCashflowTotals(splits.firstStart, until)
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
            from = from,
            to = to,
            kind = kind,
            rows = rows.toCategoryTotals()
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
    ): TopCategories =
        getCategoryTotals(
            workspaceId,
            currency,
            kind,
            startDate,
            endDate,
            accountIds,
            includeArchived
        ).toTop(limit)

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
            totals = CashflowTotals.EMPTY
        )
    }

    private fun emptyCategoryTotals(
        kind: CategoryKind,
        to: LocalDate,
    ): CategoryTotals = CategoryTotals(from = to, to = to, kind = kind, rows = emptyList())

    private companion object {
        const val MAX_BUCKETS_LIMIT: Int = 2000
    }
}