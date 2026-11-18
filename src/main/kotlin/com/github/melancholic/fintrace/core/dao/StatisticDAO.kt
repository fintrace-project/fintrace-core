package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.domain.entity.BalanceSeriesRow
import com.github.melancholic.fintrace.core.domain.entity.CashflowRow
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.CategoryTotalRow
import com.github.melancholic.fintrace.core.domain.statistic.StatisticBuckets
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

interface StatisticDAO {
    fun firstActivityDate(
        workspaceId: UUID,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): LocalDate?

    fun firstOperationDate(
        workspaceId: UUID,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): LocalDate?

    fun getBalanceSeries(
        workspaceId: UUID,
        buckets: StatisticBuckets,
        until: LocalDateTime,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): List<BalanceSeriesRow>

    fun getCashflow(
        workspaceId: UUID,
        buckets: StatisticBuckets,
        until: LocalDateTime,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): List<CashflowRow>

    fun getCategoryTotalsExpanded(
        workspaceId: UUID,
        kind: CategoryKind,
        from: LocalDateTime,
        until: LocalDateTime,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): List<CategoryTotalRow>
}

@Repository
class StatisticDAOImpl(
    private val jdbc: JdbcClient,
) : StatisticDAO {

    override fun firstActivityDate(
        workspaceId: UUID,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): LocalDate? =
        jdbc.sql(SELECT_FIRST_ACTIVITY_DATE)
            .param("workspaceId", workspaceId)
            .param("accountIds", accountIds.takeIf { it.isNotEmpty() }?.toTypedArray())
            .param("includeArchived", includeArchived)
            .query(LocalDate::class.java)
            .optional()
            .orElse(null)


    override fun firstOperationDate(
        workspaceId: UUID,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): LocalDate? =
        jdbc.sql(SELECT_FIRST_OPERATION_DATE)
            .param("workspaceId", workspaceId)
            .param("accountIds", accountIds.takeIf { it.isNotEmpty() }?.toTypedArray())
            .param("includeArchived", includeArchived)
            .query(LocalDate::class.java)
            .optional()
            .orElse(null)

    override fun getBalanceSeries(
        workspaceId: UUID,
        buckets: StatisticBuckets,
        until: LocalDateTime,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): List<BalanceSeriesRow> {
        val builder = jdbc.sql(SELECT_BALANCE_SERIES)
            .param("workspaceId", workspaceId)
            .param("firstStart", buckets.firstStart.atStartOfDay())
            .param("lastStart", buckets.lastStart.atStartOfDay())
            .param("step", buckets.granularity.postgresInterval())
            .param("until", until)
            .param("accountIds", accountIds.takeIf { it.isNotEmpty() }?.toTypedArray())
            .param("includeArchived", includeArchived)

        return builder
            .query { rs, _ ->
                BalanceSeriesRow(
                    rs.getObject("bucket_start", LocalDate::class.java),
                    rs.getString("currency"),
                    rs.getBigDecimal("total")
                )
            }.list()
    }

    override fun getCashflow(
        workspaceId: UUID,
        buckets: StatisticBuckets,
        until: LocalDateTime,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): List<CashflowRow> {
        val builder = jdbc.sql(SELECT_BALANCE_CASHFLOW)
            .param("workspaceId", workspaceId)
            .param("firstStart", buckets.firstStart.atStartOfDay())
            .param("lastStart", buckets.lastStart.atStartOfDay())
            .param("unit", buckets.granularity.postgresUnit)
            .param("until", until)
            .param("accountIds", accountIds.takeIf { it.isNotEmpty() }?.toTypedArray())
            .param("includeArchived", includeArchived)

        return builder
            .query { rs, _ ->
                CashflowRow(
                    rs.getObject("bucket_start", LocalDate::class.java),
                    rs.getString("currency"),
                    rs.getBigDecimal("income"),
                    rs.getBigDecimal("expense"),
                    rs.getBigDecimal("net"),
                    rs.getLong("operations")
                )
            }.list()
    }

    override fun getCategoryTotalsExpanded(
        workspaceId: UUID,
        kind: CategoryKind,
        from: LocalDateTime,
        until: LocalDateTime,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): List<CategoryTotalRow> {
        val builder = jdbc.sql(SELECT_CATEGORY_TOTALS)
            .param("workspaceId", workspaceId)
            .param("kind", kind.name)
            .param("from", from)
            .param("until", until)
            .param("accountIds", accountIds.takeIf { it.isNotEmpty() }?.toTypedArray())
            .param("includeArchived", includeArchived)

        return builder
            .query { rs, _ ->
                CategoryTotalRow(
                    categoryId = rs.getObject("category_id", UUID::class.java),
                    parentId = rs.getObject("parent_id", UUID::class.java),
                    currency = rs.getString("currency"),
                    total = rs.getBigDecimal("total")
                )
            }.list()
    }

    private companion object {
        const val SELECT_FIRST_ACTIVITY_DATE = """
            WITH accounts AS (SELECT id
                              FROM t_accounts
                              WHERE workspace_id = :workspaceId
                                AND (:includeArchived OR NOT archived)
                                AND (CAST(:accountIds AS uuid[]) IS NULL 
                                    OR id = ANY (CAST(:accountIds AS uuid[]))))
            SELECT LEAST(
                       (SELECT MIN(o.occurred_at)
                            FROM t_operations o
                            WHERE o.workspace_id = :workspaceId AND o.account_id IN (SELECT id FROM accounts)),
                       (SELECT MIN(b.occurred_at)
                            FROM t_balance_anchors b
                            WHERE b.workspace_id = :workspaceId AND b.account_id IN (SELECT id FROM accounts))
                   )::date
        """

        const val SELECT_FIRST_OPERATION_DATE = """
            SELECT MIN(o.occurred_at)::date
            FROM t_operations o
                JOIN t_accounts a ON a.workspace_id = o.workspace_id AND a.id = o.account_id
            WHERE o.workspace_id = :workspaceId
              AND o.kind <> 'TRANSFER'
              AND (:includeArchived OR NOT a.archived)
              AND (CAST(:accountIds AS uuid[]) IS NULL OR a.id = ANY (CAST(:accountIds AS uuid[])))
        """

        const val SELECT_BALANCE_SERIES = """
            SELECT bucket_start, currency, total
            FROM fn_balance_series(
                :workspaceId,
                :firstStart,
                :lastStart,
                CAST(:step AS interval),
                :until,
                CAST(:accountIds AS uuid[]),
                :includeArchived
        )
        """

        const val SELECT_BALANCE_CASHFLOW = """
            SELECT bucket_start, currency, income, expense, net, operations
            FROM fn_cashflow_series(
                :workspaceId,
                :firstStart,
                :lastStart,
                :unit,
                :until,
                CAST(:accountIds AS uuid[]),
                :includeArchived
            )
        """

        const val SELECT_CATEGORY_TOTALS = """
            SELECT category_id, parent_id, currency, total
            FROM fn_category_totals(
                :workspaceId,
                :kind,
                :from,
                :until,
                CAST(:accountIds AS uuid[]),
                :includeArchived
            )
        """
    }
}