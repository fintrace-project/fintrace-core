package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.dao.BalanceDAO
import com.github.melancholic.fintrace.core.dao.StatisticDAO
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
            rows.toBuckets()
        )
    }

    // Nothing to draw, in the window of the one bucket holding `to`, widened as every answer is
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


    private companion object {
        const val MAX_BUCKETS_LIMIT: Int = 2000;
    }
}

private fun List<BalanceSeriesRow>.toBuckets(): List<BalancesSeriesBucket> =
    this.groupBy { it.start }
        .mapValues { (_, v) ->
            v.filter { it.currency != null  && it.total != null}
                .associateBy { it.currency }
                .map { (currency, row) -> CurrencyAmount(currency!!, row.total!!) }
        }
        .map { (startDate, byCurrency) ->
            BalancesSeriesBucket(
                start = startDate,
                balance = CurrencyAmounts(byCurrency = byCurrency)
            )
        }
