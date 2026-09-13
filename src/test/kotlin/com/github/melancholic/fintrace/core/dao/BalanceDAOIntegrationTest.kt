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
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
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
import kotlin.test.assertFailsWith

/**
 * The balance formula (1.24, 1.25), which lives in `fn_balance_of` and
 * `fn_unexplained_difference_of` rather than in Kotlin — so this is the only place it is checked
 * at all, and it needs a real Postgres.
 *
 * Rows are written straight to the projections: what is under test is the arithmetic over them,
 * not how they got there.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class BalanceDAOIntegrationTest(
    @Autowired private val balanceDAO: BalanceDAO,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val categoryDAO: CategoryProjectionDAO,
    @Autowired private val operationDAO: OperationProjectionDAO,
    @Autowired private val anchorDAO: BalanceAnchorProjectionDAO,
) {

    private lateinit var workspaceId: UUID
    private lateinit var accountId: UUID
    private lateinit var categoryId: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        accountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet")
        categoryId = TestWorkspaces.seedCategory(categoryDAO, workspaceId)
    }

    @Test
    fun `an account with nothing on it has a zero balance`() {
        // Zero, not null, and at the column's scale — a caller comparing BigDecimals cares.
        assertEquals(BigDecimal("0.0000"), balanceOf(MARCH_31))
    }

    @Test
    fun `with no anchor it is the sum of everything up to the date`() {
        operation(MARCH_01, "-300.0000")
        operation(MARCH_20, "-50.0000")

        assertEquals(BigDecimal("-350.0000"), balanceOf(MARCH_31))
    }

    @Test
    fun `operations after the as-of date are not counted`() {
        operation(MARCH_01, "-300.0000")
        operation(MARCH_20, "-50.0000")

        assertEquals(BigDecimal("-300.0000"), balanceOf(MARCH_10))
    }

    @Test
    fun `an anchor replaces everything before it`() {
        operation(MARCH_01, "-300.0000")
        anchor(MARCH_15, "1500.0000")
        operation(MARCH_20, "-50.0000")

        // §4.6: the anchor is an observed fact; only what happened after it is added.
        assertEquals(BigDecimal("1450.0000"), balanceOf(MARCH_31))
    }

    @Test
    fun `a date before the anchor is computed without it`() {
        operation(MARCH_01, "-300.0000")
        anchor(MARCH_15, "1500.0000")

        assertEquals(BigDecimal("-300.0000"), balanceOf(MARCH_10))
    }

    @Test
    fun `the latest anchor at or before the date wins`() {
        anchor(MARCH_01, "100.0000")
        anchor(MARCH_15, "1500.0000")

        assertEquals(BigDecimal("100.0000"), balanceOf(MARCH_10))
        assertEquals(BigDecimal("1500.0000"), balanceOf(MARCH_31))
    }

    @Test
    fun `a back-dated operation leaves the balance after the anchor untouched`() {
        operation(MARCH_01, "-300.0000")
        anchor(MARCH_15, "1500.0000")
        operation(MARCH_20, "-50.0000")
        val after = balanceOf(MARCH_31)
        val before = balanceOf(MARCH_12)

        operation(MARCH_10, "-200.0000")

        // The case the whole model exists for (§4.6): the anchor absorbs what is remembered later,
        // while the window before it moves — there really was less money then.
        assertEquals(after, balanceOf(MARCH_31))
        assertEquals(before.subtract(BigDecimal("200.0000")), balanceOf(MARCH_12))
    }

    @Test
    fun `transfer legs count towards the balance`() {
        // They move money, which is the one place this query differs from income/expense figures.
        operationDAO.createOrUpdate(
            leg(MARCH_05, BigDecimal("-120.0000"))
        )

        assertEquals(BigDecimal("-120.0000"), balanceOf(MARCH_31))
    }

    @Test
    fun `another workspace's rows are never counted`() {
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
        operation(MARCH_01, "-300.0000")

        // The same account id read through another workspace must see nothing at all.
        assertEquals(BigDecimal("0.0000"), balanceDAO.getBalanceOf(other, accountId, MARCH_31))
    }

    @Test
    fun `the difference is what the anchor absorbed`() {
        operation(MARCH_01, "-300.0000")
        val id = anchor(MARCH_15, "1500.0000")

        // 1500 observed against -300 explained.
        assertEquals(BigDecimal("1800.0000"), balanceDAO.getUnexplainedDifference(workspaceId, id))
    }

    @Test
    fun `the difference is measured against the previous anchor, not itself`() {
        anchor(MARCH_01, "1000.0000")
        operation(MARCH_05, "-100.0000")
        val second = anchor(MARCH_15, "1500.0000")

        // 1500 − (1000 − 100): counting the anchor itself would make this zero forever.
        assertEquals(BigDecimal("600.0000"), balanceDAO.getUnexplainedDifference(workspaceId, second))
    }

    @Test
    fun `a back-dated operation shrinks the difference it explains`() {
        val id = anchor(MARCH_15, "1500.0000")
        val before = balanceDAO.getUnexplainedDifference(workspaceId, id)

        operation(MARCH_10, "200.0000")

        assertEquals(
            before.subtract(BigDecimal("200.0000")),
            balanceDAO.getUnexplainedDifference(workspaceId, id),
            "explaining part of the gap is what closes it",
        )
    }

    @Test
    fun `an operation at the anchor's own instant is not counted as explaining it`() {
        val at = MARCH_15
        operation(at, "-50.0000")
        val id = anchor(at, "1500.0000")

        // An anchor observes the state before anything sharing its instant.
        assertEquals(BigDecimal("1500.0000"), balanceDAO.getUnexplainedDifference(workspaceId, id))
    }

    @Test
    fun `a date means the end of that day`() {
        operation(MARCH_10, "-200.0000")

        // The off-by-a-day that matters: asking for the 10th must include the 10th's spending.
        assertEquals(
            BigDecimal("-200.0000"),
            balanceDAO.getBalanceOf(workspaceId, accountId, LocalDate.of(2026, 3, 10))
        )
        assertEquals(BigDecimal("0.0000"), balanceDAO.getBalanceOf(workspaceId, accountId, LocalDate.of(2026, 3, 9)))
    }

    @Test
    fun `an instant excludes anything stamped at exactly that instant`() {
        operation(MARCH_15, "-50.0000")

        // The bound is exclusive, which is what lets an anchor observe the state before anything
        // sharing its own instant.
        assertEquals(BigDecimal("0.0000"), balanceOf(MARCH_15))
        assertEquals(BigDecimal("-50.0000"), balanceOf(MARCH_20))
    }

    // ------------------------------------------------------------------ every account at once

    @Test
    fun `lists a balance for every account, including untouched ones`() {
        val savings = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "GBP")
        operation(MARCH_01, "-300.0000")

        val balances = balanceDAO.getAllBalancesOf(workspaceId, LocalDate.of(2026, 3, 31), true)

        // The accounts table drives the rows: an account with no activity is 0, not missing.
        assertEquals(2, balances.size)
        assertEquals(BigDecimal("-300.0000"), balances.single { it.accountId == accountId }.balance)
        assertEquals(BigDecimal("0.0000"), balances.single { it.accountId == savings }.balance)
    }

    @Test
    fun `carries each account's own currency`() {
        TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "GBP")

        val currencies = balanceDAO.getAllBalancesOf(workspaceId, LocalDate.of(2026, 3, 31), true)
            .map { it.currency }.sorted()

        // §11.4: balances here are always in the account's own currency; conversion is elsewhere.
        assertEquals(listOf("EUR", "GBP"), currencies)
    }

    @Test
    fun `excludes archived accounts only when asked to`() {
        val closed = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "closed")
        jdbc.sql("UPDATE t_accounts SET archived = true WHERE id = :id").param("id", closed).update()

        assertEquals(2, balanceDAO.getAllBalancesOf(workspaceId, LocalDate.of(2026, 3, 31), true).size)
        assertEquals(1, balanceDAO.getAllBalancesOf(workspaceId, LocalDate.of(2026, 3, 31), false).size)
    }

    @Test
    fun `lists no account from another workspace`() {
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
        TestWorkspaces.seedAccount(accountDAO, other, name = "theirs")

        assertEquals(1, balanceDAO.getAllBalancesOf(workspaceId, LocalDate.of(2026, 3, 31), true).size)
    }

    @Test
    fun `a listed balance agrees with the single-account one`() {
        operation(MARCH_01, "-300.0000")
        anchor(MARCH_15, "1500.0000")
        operation(MARCH_20, "-50.0000")
        val asOf = LocalDate.of(2026, 3, 31)

        assertEquals(
            balanceDAO.getBalanceOf(workspaceId, accountId, asOf),
            balanceDAO.getAllBalancesOf(workspaceId, asOf, true).single().balance,
        )
    }

    @Test
    fun `computes several differences in one call, keyed by anchor`() {
        operation(MARCH_01, "-300.0000")
        val first = anchor(MARCH_10, "1000.0000")
        operation(MARCH_12, "-100.0000")
        val second = anchor(MARCH_15, "1500.0000")

        val differences = balanceDAO.getUnexplainedDifference(workspaceId, setOf(first, second))

        // Keyed per anchor, not collapsed: 1000 − (−300), then 1500 − (1000 − 100).
        assertEquals(
            mapOf(first to BigDecimal("1300.0000"), second to BigDecimal("600.0000")),
            differences,
        )
    }

    @Test
    fun `leaves unknown and foreign anchors out of a batch`() {
        val known = anchor(MARCH_15, "1500.0000")
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")

        val differences = balanceDAO.getUnexplainedDifference(other, setOf(known, UUID.randomUUID()))

        assertEquals(emptyMap(), differences, "an anchor read through another workspace does not resolve")
    }

    @Test
    fun `an empty batch asks the database nothing`() {
        assertEquals(emptyMap(), balanceDAO.getUnexplainedDifference(workspaceId, emptySet()))
    }

    @Test
    fun `an unknown single anchor is reported as missing`() {
        assertFailsWith<NotFoundEntityException> {
            balanceDAO.getUnexplainedDifference(workspaceId, UUID.randomUUID())
        }
    }

    private fun balanceOf(asOf: LocalDateTime) = balanceDAO.getBalanceOf(workspaceId, accountId, asOf)

    private fun operation(occurredAt: LocalDateTime, amount: String): UUID = operationDAO.createOrUpdate(
        OperationProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            amount = BigDecimal(amount),
            kind = if (amount.startsWith("-")) OperationKind.EXPENSE else OperationKind.INCOME,
            accountId = accountId,
            categoryId = categoryId,
            transferId = null,
            counterpartId = null,
            comment = null,
            externalRef = null,
            occurredAt = occurredAt,
            recordedAt = occurredAt,
        )
    )

    private fun leg(occurredAt: LocalDateTime, amount: BigDecimal) = OperationProjection(
        id = UUID.randomUUID(),
        workspaceId = workspaceId,
        amount = amount,
        kind = OperationKind.TRANSFER,
        accountId = accountId,
        categoryId = null,
        transferId = UUID.randomUUID(),
        counterpartId = UUID.randomUUID(),
        comment = null,
        externalRef = null,
        occurredAt = occurredAt,
        recordedAt = occurredAt,
    )

    private fun anchor(occurredAt: LocalDateTime, value: String): UUID = anchorDAO.createOrUpdate(
        BalanceAnchorProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            accountId = accountId,
            value = BigDecimal(value),
            occurredAt = occurredAt,
            recordedAt = occurredAt,
        )
    )

    private companion object {
        val MARCH_01: LocalDateTime = LocalDateTime.parse("2026-03-01T10:00:00")
        val MARCH_05: LocalDateTime = LocalDateTime.parse("2026-03-05T10:00:00")
        val MARCH_10: LocalDateTime = LocalDateTime.parse("2026-03-10T10:00:00")
        val MARCH_12: LocalDateTime = LocalDateTime.parse("2026-03-12T10:00:00")
        val MARCH_15: LocalDateTime = LocalDateTime.parse("2026-03-15T10:00:00")
        val MARCH_20: LocalDateTime = LocalDateTime.parse("2026-03-20T10:00:00")
        val MARCH_31: LocalDateTime = LocalDateTime.parse("2026-03-31T23:59:00")
    }
}
