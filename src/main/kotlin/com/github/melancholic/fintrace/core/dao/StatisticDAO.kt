package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.domain.statistic.BalanceSeriesRow
import com.github.melancholic.fintrace.core.domain.statistic.StatisticBuckets
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

interface StatisticDAO {
    fun firstActivityDate(workspaceId: UUID, accounts: Set<UUID>, includeArchived: Boolean): LocalDate?
    fun getBalanceSeries(
        workspaceId: UUID,
        buckets: StatisticBuckets,
        until: LocalDateTime,
        accountIds: Set<UUID>,
        includeArchived: Boolean
    ): List<BalanceSeriesRow>
}

@Repository
class StatisticDAOImpl(
    private val jdbc: JdbcClient,
) : StatisticDAO {

    override fun firstActivityDate(
        workspaceId: UUID,
        accounts: Set<UUID>,
        includeArchived: Boolean
    ): LocalDate? {
        var sql = buildString {
            append(
                """
            SELECT id
                FROM t_accounts
                WHERE workspace_id = :workspaceId
                    AND (:includeArchived OR NOT archived)
            """
            )
            if (accounts.isNotEmpty()) {
                append(" AND id IN (:accountIds)")
            }
        }

        sql = """
        WITH accounts AS (${sql})
        SELECT LEAST(
            (SELECT MIN(o.occurred_at) FROM t_operations o
                WHERE o.workspace_id = :workspaceId AND o.account_id IN (SELECT id FROM accounts)),
            (SELECT MIN(a.occurred_at) FROM t_balance_anchors a
                WHERE a.workspace_id = :workspaceId AND a.account_id IN (SELECT id FROM accounts))
        )::date            
        """.trimIndent()


        val builder = jdbc.sql(sql)
            .param("workspaceId", workspaceId)
            .param("includeArchived", includeArchived);

        if (accounts.isNotEmpty()) {
            builder.param("accountIds", accounts)
        }

        // NULL when the selection has no activity at all: Spring 7's single() refuses a null value
        return builder
            .query(LocalDate::class.java)
            .optional()
            .orElse(null)
    }

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
            .param("step", buckets.granularity.postgresValue())
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

    private companion object {

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
    }
}