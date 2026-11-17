package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.domain.entity.BalanceSeriesRow
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
 * `fn_balance_series` and the first-activity query (3.5): the buckets meet `fn_balance_of`'s anchor rules here,
 * so this needs a real Postgres. Rows are written straight to the projections, as in `BalanceDAOIntegrationTest`.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class StatisticDAOIntegrationTest(
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

    // ------------------------------------------------------------------ the first day with activity

    @Test
    fun `a workspace with nothing recorded has no first day`() {
        assertNull(statisticDAO.firstActivityDate(workspaceId, emptySet(), true))
    }

    @Test
    fun `an opening anchor alone is a first day`() {
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")

        assertEquals(LocalDate.parse("2026-01-10"), statisticDAO.firstActivityDate(workspaceId, emptySet(), true))
    }

    @Test
    fun `an operation back-dated before the opening anchor moves the first day back`() {
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")
        operation(wallet, "2025-12-20T09:00:00", "-5.0000")

        assertEquals(LocalDate.parse("2025-12-20"), statisticDAO.firstActivityDate(workspaceId, emptySet(), true))
    }

    @Test
    fun `the first day is the selected accounts' own, not the workspace's`() {
        val card = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "card", currency = "EUR")
        anchor(wallet, "2021-03-01T09:00:00", "100.0000")
        anchor(card, "2025-06-01T09:00:00", "40.0000")

        assertEquals(LocalDate.parse("2025-06-01"), statisticDAO.firstActivityDate(workspaceId, setOf(card), true))
    }

    @Test
    fun `an archived account's history counts only when archived accounts are included`() {
        val closed = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "closed", currency = "EUR")
        anchor(closed, "2021-03-01T09:00:00", "10.0000")
        anchor(wallet, "2025-06-01T09:00:00", "100.0000")
        archive(closed)

        assertEquals(LocalDate.parse("2021-03-01"), statisticDAO.firstActivityDate(workspaceId, emptySet(), true))
        assertEquals(LocalDate.parse("2025-06-01"), statisticDAO.firstActivityDate(workspaceId, emptySet(), false))
    }

    @Test
    fun `another workspace's older history is not this one's first day`() {
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
        val theirs = TestWorkspaces.seedAccount(accountDAO, other, name = "theirs", currency = "EUR")
        anchor(theirs, "2020-01-01T09:00:00", "1.0000", workspace = other)
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")

        assertEquals(LocalDate.parse("2026-01-10"), statisticDAO.firstActivityDate(workspaceId, emptySet(), true))
    }

    // ------------------------------------------------------------------ the series

    @Test
    fun `each bucket is the balance at its end, carried through a quiet month`() {
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")
        operation(wallet, "2026-02-05T09:00:00", "-30.0000")
        operation(wallet, "2026-04-20T09:00:00", "-20.0000")

        val rows = series(Granularity.MONTH, "2026-01-01", "2026-04-30")

        assertEquals(
            listOf(
                row("2026-01-01", "EUR", "100.0000"),
                row("2026-02-01", "EUR", "70.0000"),
                row("2026-03-01", "EUR", "70.0000"),
                row("2026-04-01", "EUR", "50.0000"),
            ),
            rows,
        )
    }

    @Test
    fun `the last bucket measures up to the requested day, not to its own end`() {
        anchor(wallet, "2026-03-01T09:00:00", "100.0000")
        operation(wallet, "2026-03-20T09:00:00", "-20.0000")

        // `to` is 15 March: March's bucket stops there, so the 20th's expense is not in it (D4)
        val rows = series(Granularity.MONTH, "2026-03-01", "2026-03-15")

        assertEquals(listOf(row("2026-03-01", "EUR", "100.0000")), rows)
    }

    @Test
    fun `an operation at an anchor's own instant is in no bucket`() {
        anchor(wallet, "2026-02-10T10:00:00", "500.0000")
        operation(wallet, "2026-02-10T10:00:00", "-40.0000")

        // §4.6: neither the anchor's difference nor the balance after it counts that instant
        assertEquals(listOf(row("2026-02-01", "EUR", "500.0000")), series(Granularity.MONTH, "2026-02-01", "2026-02-28"))
    }

    @Test
    fun `a back-dated operation moves only the buckets before the anchor that absorbed it`() {
        anchor(wallet, "2026-01-05T09:00:00", "100.0000")
        anchor(wallet, "2026-03-15T09:00:00", "100.0000")
        operation(wallet, "2026-02-10T09:00:00", "-30.0000")

        // §4.6's table: less money in February; from the second anchor on, the observation stands
        assertEquals(
            listOf(
                row("2026-01-01", "EUR", "100.0000"),
                row("2026-02-01", "EUR", "70.0000"),
                row("2026-03-01", "EUR", "100.0000"),
            ),
            series(Granularity.MONTH, "2026-01-01", "2026-03-31"),
        )
    }

    @Test
    fun `every currency is in every bucket, ordered by code, at zero before it holds anything`() {
        val savings = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "GBP")
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")
        anchor(savings, "2026-02-10T09:00:00", "40.0000")

        assertEquals(
            listOf(
                row("2026-01-01", "EUR", "100.0000"),
                row("2026-01-01", "GBP", "0.0000"),
                row("2026-02-01", "EUR", "100.0000"),
                row("2026-02-01", "GBP", "40.0000"),
            ),
            series(Granularity.MONTH, "2026-01-01", "2026-02-28"),
        )
    }

    @Test
    fun `accounts sharing a currency are summed`() {
        val card = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "card", currency = "EUR")
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")
        anchor(card, "2026-01-12T09:00:00", "25.5000")

        assertEquals(listOf(row("2026-01-01", "EUR", "125.5000")), series(Granularity.MONTH, "2026-01-01", "2026-01-31"))
    }

    @Test
    fun `a filter keeps only the named accounts`() {
        val savings = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "GBP")
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")
        anchor(savings, "2026-01-10T09:00:00", "40.0000")

        assertEquals(
            listOf(row("2026-01-01", "GBP", "40.0000")),
            series(Granularity.MONTH, "2026-01-01", "2026-01-31", accountIds = setOf(savings)),
        )
    }

    @Test
    fun `a selection with no accounts still answers every bucket, its currency null`() {
        archive(wallet)

        // The NULL currency marks the empty bucket; its total is fn_balance_of's zero for no account
        assertEquals(
            listOf(null, null),
            series(Granularity.MONTH, "2026-01-01", "2026-02-28", includeArchived = false).map { it.currency },
        )
        assertEquals(
            listOf(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01")),
            series(Granularity.MONTH, "2026-01-01", "2026-02-28", includeArchived = false).map { it.start },
        )
    }

    @Test
    fun `nothing from another workspace enters a bucket`() {
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
        val theirs = TestWorkspaces.seedAccount(accountDAO, other, name = "theirs", currency = "EUR")
        anchor(theirs, "2026-01-10T09:00:00", "999.0000", workspace = other)

        assertEquals(listOf(row("2026-01-01", "EUR", "0.0000")), series(Granularity.MONTH, "2026-01-01", "2026-01-31"))
    }

    @Test
    fun `Postgres steps through exactly the buckets Kotlin counted, for every granularity`() {
        Granularity.entries.forEach { granularity ->
            val buckets = StatisticBuckets.splitByBuckets(granularity, LocalDate.parse("2024-02-29"), LocalDate.parse("2026-11-15"))
            val generated = jdbc.sql("SELECT count(*) FROM generate_series(:first, :last, CAST(:step AS interval))")
                .param("first", buckets.firstStart.atStartOfDay())
                .param("last", buckets.lastStart.atStartOfDay())
                .param("step", granularity.postgresInterval())
                .query(Long::class.java)
                .single()

            assertEquals(buckets.count, generated, "$granularity")
        }
    }

    private fun series(
        granularity: Granularity,
        from: String,
        to: String,
        accountIds: Set<UUID> = emptySet(),
        includeArchived: Boolean = true,
    ): List<BalanceSeriesRow> {
        val buckets = StatisticBuckets.splitByBuckets(granularity, LocalDate.parse(from), LocalDate.parse(to))
        return statisticDAO.getBalanceSeries(
            workspaceId, buckets, LocalDate.parse(to).plusDays(1).atStartOfDay(), accountIds, includeArchived,
        )
    }

    private fun row(start: String, currency: String, total: String) =
        BalanceSeriesRow(LocalDate.parse(start), currency, BigDecimal(total))

    private fun archive(account: UUID) {
        jdbc.sql("UPDATE t_accounts SET archived = true WHERE id = :id").param("id", account).update()
    }

    private fun operation(account: UUID, occurredAt: String, amount: String) = operationDAO.create(
        OperationProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            amount = BigDecimal(amount),
            kind = if (amount.startsWith("-")) OperationKind.EXPENSE else OperationKind.INCOME,
            accountId = account,
            categoryId = categoryId,
            transferId = null,
            counterpartId = null,
            comment = null,
            externalRef = null,
            occurredAt = LocalDateTime.parse(occurredAt),
            recordedAt = LocalDateTime.parse(occurredAt),
        )
    )

    private fun anchor(account: UUID, occurredAt: String, value: String, workspace: UUID = workspaceId) =
        anchorDAO.create(
            BalanceAnchorProjection(
                id = UUID.randomUUID(),
                workspaceId = workspace,
                accountId = account,
                value = BigDecimal(value),
                occurredAt = LocalDateTime.parse(occurredAt),
                externalRef = null,
                recordedAt = LocalDateTime.parse(occurredAt),
            )
        )
}
