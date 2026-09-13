package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.domain.entity.AccountBalance
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

interface BalanceDAO {
    fun getBalanceOf(workspaceId: UUID, accountId: UUID, asOf: LocalDate): BigDecimal
    fun getBalanceOf(workspaceId: UUID, accountId: UUID, asOf: LocalDateTime): BigDecimal
    fun getAllBalancesOf(workspaceId: UUID, asOf: LocalDate, includeArchived: Boolean): List<AccountBalance>
    fun getAllBalancesOf(workspaceId: UUID, asOf: LocalDateTime, includeArchived: Boolean): List<AccountBalance>

    fun getUnexplainedDifference(workspaceId: UUID, anchorId: UUID): BigDecimal
    fun getUnexplainedDifference(workspaceId: UUID, anchorIds: Set<UUID>): Map<UUID, BigDecimal>
}

@Repository
class BalanceDAOImpl(
    private val jdbc: JdbcClient,
) : BalanceDAO {

    override fun getBalanceOf(
        workspaceId: UUID,
        accountId: UUID,
        asOf: LocalDate
    ): BigDecimal = getBalanceOf(workspaceId, accountId, asOf.atBeginNextDay())

    override fun getBalanceOf(
        workspaceId: UUID,
        accountId: UUID,
        asOf: LocalDateTime
    ): BigDecimal = jdbc.sql(CALL_BALANCE_OF_FUNC)
        .param("workspaceId", workspaceId)
        .param("accountId", accountId)
        .param("until", asOf)
        .query(BigDecimal::class.java)
        .single()

    override fun getUnexplainedDifference(workspaceId: UUID, anchorId: UUID): BigDecimal =
        getUnexplainedDifference(workspaceId, setOf(anchorId))[anchorId]
            ?: throw NotFoundEntityException("Could not find correspondent balance anchor (workspaceId=$workspaceId, anchorId=$anchorId)")

    override fun getUnexplainedDifference(
        workspaceId: UUID,
        anchorIds: Set<UUID>
    ): Map<UUID, BigDecimal> {
        if (anchorIds.isEmpty()) return emptyMap()
        return jdbc.sql(CALL_UNEXPLAINED_DIFF_FUNC)
            .param("workspaceId", workspaceId)
            .param("anchorIds", anchorIds)
            .query { rs, _ -> rs.getObject("anchor_id", UUID::class.java) to rs.getBigDecimal("difference") }
            .list()
            .toMap()
    }

    override fun getAllBalancesOf(
        workspaceId: UUID,
        asOf: LocalDate,
        includeArchived: Boolean
    ): List<AccountBalance> = getAllBalancesOf(workspaceId, asOf.atBeginNextDay(), includeArchived)

    override fun getAllBalancesOf(
        workspaceId: UUID,
        asOf: LocalDateTime,
        includeArchived: Boolean
    ): List<AccountBalance> = jdbc.sql(CALL_BALANCE_OF_MULTI_ACCOUNT_FUNC)
        .param("workspaceId", workspaceId)
        .param("until", asOf)
        .param("includeArchived", includeArchived)
        .query(AccountBalance::class.java)
        .list() as List<AccountBalance>

    companion object {
        const val CALL_BALANCE_OF_FUNC = """
            SELECT fn_balance_of(:workspaceId, :accountId, :until)
        """

        const val CALL_BALANCE_OF_MULTI_ACCOUNT_FUNC = """
            SELECT a.id AS account_id, a.currency, fn_balance_of(:workspaceId, a.id, :until) AS balance
            FROM t_accounts a
            WHERE a.workspace_id = :workspaceId
              AND (:includeArchived OR NOT a.archived)
            ORDER BY a.name
        """

        const val CALL_UNEXPLAINED_DIFF_FUNC = """
            SELECT a.id AS anchor_id, fn_unexplained_difference_of(a.workspace_id, a.id) AS difference
            FROM t_balance_anchors a
            WHERE a.workspace_id = :workspaceId
              AND a.id IN (:anchorIds)
        """
    }
}

private fun LocalDate.atBeginNextDay(): LocalDateTime = this.plusDays(1).atStartOfDay()
