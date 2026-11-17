package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.CashflowRow
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.domain.statistic.Granularity
import com.github.melancholic.fintrace.core.domain.statistic.StatisticBuckets
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `fn_cashflow_series` and cashflow's first day (3.6): the money rules — transfers and anchors in no figure, the
 * signs, the counts — live in SQL, so they are checked against a real Postgres. Rows go straight to the projections.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class CashflowDAOIntegrationTest(
    @Autowired private val statisticDAO: StatisticDAO,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val categoryDAO: CategoryProjectionDAO,
    @Autowired private val operationDAO: OperationProjectionDAO,
    @Autowired private val anchorDAO: BalanceAnchorProjectionDAO,
) {

    private lateinit var workspaceId: UUID
    private lateinit var wallet: UUID
    private lateinit var categoryId: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        wallet = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet", currency = "EUR")
        categoryId = TestWorkspaces.seedCategory(categoryDAO, workspaceId)
    }

    // ------------------------------------------------------------------ cashflow's first day

    @Test
    fun `a workspace with nothing recorded has no first operation`() {
        assertNull(statisticDAO.firstOperationDate(workspaceId, emptySet(), true))
    }

    @Test
    fun `an anchor alone is no first operation, having no cashflow`() {
        anchor(wallet, "2021-01-01T09:00:00", "100.0000")

        assertNull(statisticDAO.firstOperationDate(workspaceId, emptySet(), true))
    }

    @Test
    fun `a transfer alone is no first operation, being neither income nor expense`() {
        transfer(wallet, "2022-01-01T09:00:00", "-40.0000")

        assertNull(statisticDAO.firstOperationDate(workspaceId, emptySet(), true))
    }

    @Test
    fun `the first operation is the earliest income or expense`() {
        anchor(wallet, "2021-01-01T09:00:00", "100.0000")
        transfer(wallet, "2022-01-01T09:00:00", "-40.0000")
        operation(wallet, "2023-05-01T09:00:00", "-5.0000")

        assertEquals(LocalDate.parse("2023-05-01"), statisticDAO.firstOperationDate(workspaceId, emptySet(), true))
    }

    @Test
    fun `the first operation is the selected accounts' own`() {
        val card = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "card", currency = "EUR")
        operation(wallet, "2023-05-01T09:00:00", "-5.0000")
        operation(card, "2024-02-01T09:00:00", "-5.0000")

        assertEquals(LocalDate.parse("2024-02-01"), statisticDAO.firstOperationDate(workspaceId, setOf(card), true))
    }

    @Test
    fun `an archived account's operations count only when archived accounts are included`() {
        val closed = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "closed", currency = "EUR")
        operation(closed, "2021-03-01T09:00:00", "-5.0000")
        operation(wallet, "2025-06-01T09:00:00", "-5.0000")
        archive(closed)

        assertEquals(LocalDate.parse("2021-03-01"), statisticDAO.firstOperationDate(workspaceId, emptySet(), true))
        assertEquals(LocalDate.parse("2025-06-01"), statisticDAO.firstOperationDate(workspaceId, emptySet(), false))
    }

    @Test
    fun `another workspace's operations are not this one's first`() {
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
        val theirs = TestWorkspaces.seedAccount(accountDAO, other, name = "theirs", currency = "EUR")
        val theirCategory = TestWorkspaces.seedCategory(categoryDAO, other)
        operation(theirs, "2020-01-01T09:00:00", "-5.0000", workspace = other, category = theirCategory)
        operation(wallet, "2026-01-10T09:00:00", "-5.0000")

        assertEquals(LocalDate.parse("2026-01-10"), statisticDAO.firstOperationDate(workspaceId, emptySet(), true))
    }

    // ------------------------------------------------------------------ the series

    @Test
    fun `income and expense are magnitudes, net their signed difference, count the operations`() {
        operation(wallet, "2026-01-10T09:00:00", "100.0000")
        operation(wallet, "2026-01-20T09:00:00", "-30.5000")

        assertEquals(
            listOf(row("2026-01-01", "EUR", "100.0000", "30.5000", "69.5000", 2)),
            cashflow(Granularity.MONTH, "2026-01-01", "2026-01-31"),
        )
    }

    @Test
    fun `a transfer is in no figure and no count`() {
        operation(wallet, "2026-01-10T09:00:00", "-10.0000")
        transfer(wallet, "2026-01-12T09:00:00", "-40.0000")

        assertEquals(
            listOf(row("2026-01-01", "EUR", "0.0000", "10.0000", "-10.0000", 1)),
            cashflow(Granularity.MONTH, "2026-01-01", "2026-01-31"),
        )
    }

    @Test
    fun `an anchor is in no figure and no count`() {
        anchor(wallet, "2026-01-05T09:00:00", "1500.0000")
        operation(wallet, "2026-01-10T09:00:00", "-10.0000")

        // §4.6's key rule: an anchor affects balances only, never income or expense
        assertEquals(
            listOf(row("2026-01-01", "EUR", "0.0000", "10.0000", "-10.0000", 1)),
            cashflow(Granularity.MONTH, "2026-01-01", "2026-01-31"),
        )
    }

    @Test
    fun `a quiet month is zeros, and every currency is in every bucket, ordered by code`() {
        val savings = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "GBP")
        operation(wallet, "2026-01-10T09:00:00", "-10.0000")
        operation(savings, "2026-03-10T09:00:00", "5.0000")

        assertEquals(
            listOf(
                row("2026-01-01", "EUR", "0.0000", "10.0000", "-10.0000", 1),
                row("2026-01-01", "GBP", "0.0000", "0.0000", "0.0000", 0),
                row("2026-02-01", "EUR", "0.0000", "0.0000", "0.0000", 0),
                row("2026-02-01", "GBP", "0.0000", "0.0000", "0.0000", 0),
                row("2026-03-01", "EUR", "0.0000", "0.0000", "0.0000", 0),
                row("2026-03-01", "GBP", "5.0000", "0.0000", "5.0000", 1),
            ),
            cashflow(Granularity.MONTH, "2026-01-01", "2026-03-31"),
        )
    }

    @Test
    fun `accounts sharing a currency are summed and counted together`() {
        val card = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "card", currency = "EUR")
        operation(wallet, "2026-01-10T09:00:00", "-10.0000")
        operation(card, "2026-01-11T09:00:00", "-2.5000")

        assertEquals(
            listOf(row("2026-01-01", "EUR", "0.0000", "12.5000", "-12.5000", 2)),
            cashflow(Granularity.MONTH, "2026-01-01", "2026-01-31"),
        )
    }

    @Test
    fun `Sunday's last minute is in its week, Monday's first in the next`() {
        operation(wallet, "2026-11-15T23:59:59", "-1.0000")
        operation(wallet, "2026-11-16T00:00:00", "-2.0000")

        assertEquals(
            listOf(
                row("2026-11-09", "EUR", "0.0000", "1.0000", "-1.0000", 1),
                row("2026-11-16", "EUR", "0.0000", "2.0000", "-2.0000", 1),
            ),
            cashflow(Granularity.WEEK, "2026-11-09", "2026-11-22"),
        )
    }

    @Test
    fun `the last bucket stops at the requested day`() {
        operation(wallet, "2026-03-10T09:00:00", "-10.0000")
        operation(wallet, "2026-03-20T09:00:00", "-20.0000")

        // `to` is 15 March: the 20th's expense is after it
        assertEquals(
            listOf(row("2026-03-01", "EUR", "0.0000", "10.0000", "-10.0000", 1)),
            cashflow(Granularity.MONTH, "2026-03-01", "2026-03-15"),
        )
    }

    @Test
    fun `a filter keeps only the named accounts`() {
        val savings = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "GBP")
        operation(wallet, "2026-01-10T09:00:00", "-10.0000")
        operation(savings, "2026-01-10T09:00:00", "5.0000")

        assertEquals(
            listOf(row("2026-01-01", "GBP", "5.0000", "0.0000", "5.0000", 1)),
            cashflow(Granularity.MONTH, "2026-01-01", "2026-01-31", accountIds = setOf(savings)),
        )
    }

    @Test
    fun `a selection with no accounts still answers every bucket, its currency null`() {
        archive(wallet)

        val rows = cashflow(Granularity.MONTH, "2026-01-01", "2026-02-28", includeArchived = false)

        assertEquals(listOf(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01")), rows.map { it.start })
        assertEquals(listOf(null, null), rows.map { it.currency })
    }

    @Test
    fun `nothing from another workspace enters a bucket`() {
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
        val theirs = TestWorkspaces.seedAccount(accountDAO, other, name = "theirs", currency = "EUR")
        val theirCategory = TestWorkspaces.seedCategory(categoryDAO, other)
        operation(theirs, "2026-01-10T09:00:00", "-999.0000", workspace = other, category = theirCategory)

        assertEquals(
            listOf(row("2026-01-01", "EUR", "0.0000", "0.0000", "0.0000", 0)),
            cashflow(Granularity.MONTH, "2026-01-01", "2026-01-31"),
        )
    }

    @Test
    fun `Postgres truncates and steps by the same unit Kotlin counted, for every granularity`() {
        Granularity.entries.forEach { granularity ->
            val buckets =
                StatisticBuckets.splitByBuckets(granularity, LocalDate.parse("2024-02-29"), LocalDate.parse("2026-11-15"))
            val generated = jdbc.sql(
                "SELECT count(*) FROM generate_series(:first, :last, CAST('1 ' || :unit AS interval))"
            )
                .param("first", buckets.firstStart.atStartOfDay())
                .param("last", buckets.lastStart.atStartOfDay())
                .param("unit", granularity.postgresUnit)
                .query(Long::class.java)
                .single()

            assertEquals(buckets.count, generated, "$granularity")
        }
    }

    private fun cashflow(
        granularity: Granularity,
        from: String,
        to: String,
        accountIds: Set<UUID> = emptySet(),
        includeArchived: Boolean = true,
    ): List<CashflowRow> {
        val buckets = StatisticBuckets.splitByBuckets(granularity, LocalDate.parse(from), LocalDate.parse(to))
        return statisticDAO.getBalanceCashflow(
            workspaceId, buckets, LocalDate.parse(to).plusDays(1).atStartOfDay(), accountIds, includeArchived,
        )
    }

    private fun row(start: String, currency: String, income: String, expense: String, net: String, operations: Long) =
        CashflowRow(LocalDate.parse(start), currency, BigDecimal(income), BigDecimal(expense), BigDecimal(net), operations)

    private fun archive(account: UUID) {
        jdbc.sql("UPDATE t_accounts SET archived = true WHERE id = :id").param("id", account).update()
    }

    private fun operation(
        account: UUID,
        occurredAt: String,
        amount: String,
        workspace: UUID = workspaceId,
        category: UUID = categoryId,
    ) = operationDAO.create(
        OperationProjection(
            id = UUID.randomUUID(),
            workspaceId = workspace,
            amount = BigDecimal(amount),
            kind = if (amount.startsWith("-")) OperationKind.EXPENSE else OperationKind.INCOME,
            accountId = account,
            categoryId = category,
            transferId = null,
            counterpartId = null,
            comment = null,
            externalRef = null,
            occurredAt = LocalDateTime.parse(occurredAt),
            recordedAt = LocalDateTime.parse(occurredAt),
        )
    )

    // One leg of a transfer, as the series sees it: a TRANSFER row with no category
    private fun transfer(account: UUID, occurredAt: String, amount: String) = operationDAO.create(
        OperationProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            amount = BigDecimal(amount),
            kind = OperationKind.TRANSFER,
            accountId = account,
            categoryId = null,
            transferId = UUID.randomUUID(),
            counterpartId = UUID.randomUUID(),
            comment = null,
            externalRef = null,
            occurredAt = LocalDateTime.parse(occurredAt),
            recordedAt = LocalDateTime.parse(occurredAt),
        )
    )

    private fun anchor(account: UUID, occurredAt: String, value: String) = anchorDAO.create(
        BalanceAnchorProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            accountId = account,
            value = BigDecimal(value),
            occurredAt = LocalDateTime.parse(occurredAt),
            externalRef = null,
            recordedAt = LocalDateTime.parse(occurredAt),
        )
    )
}
