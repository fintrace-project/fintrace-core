package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.StatisticDAO
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CancelOperationCommand
import com.github.melancholic.fintrace.core.domain.command.CreateOperationCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseOperationCommand
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.domain.statistic.Granularity
import com.github.melancholic.fintrace.core.domain.statistic.StatisticBuckets
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals

/**
 * The old bot's day-by-day tests handed over by 2.16 (3.13, `plans/M3.md`), ported as balance-series tests: a
 * re-date or a deletion in the source is a revision or a cancellation here, so they run through the command path.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class BotHandoverSeriesIntegrationTest(
    @Autowired private val facade: CommandFacade,
    @Autowired private val statisticDAO: StatisticDAO,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val categoryDAO: CategoryProjectionDAO,
) {

    private lateinit var workspaceId: UUID
    private lateinit var wallet: UUID
    private lateinit var incomeCategory: UUID
    private lateinit var expenseCategory: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        wallet = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet", currency = "EUR")
        incomeCategory = TestWorkspaces.seedCategory(categoryDAO, workspaceId, CategoryKind.INCOME, "salary")
        expenseCategory = TestWorkspaces.seedCategory(categoryDAO, workspaceId, CategoryKind.EXPENSE, "food")
    }

    @Test
    fun `a balance carries forward across days with nothing on them`() {
        income("2025-10-01T12:00:00", "1000")
        income("2025-10-02T12:00:00", "200")

        assertEquals(
            listOf("1000", "1200", "1200", "1200"),
            daily("2025-10-01", "2025-10-04"),
        )
    }

    @Test
    fun `monthly and yearly buckets hold the balance at the period's end`() {
        income("2025-01-01T00:00:00", "1000")
        income("2025-01-03T12:00:00", "200")
        expense("2025-01-05T12:00:00", "300")

        assertEquals(listOf("900"), totals(Granularity.MONTH, "2025-01-01", "2025-01-31"))
        assertEquals(listOf("900"), totals(Granularity.YEAR, "2025-01-01", "2025-12-31"))
    }

    @Test
    fun `a week runs Monday to Sunday`() {
        income("2025-01-01T00:00:00", "100")

        // 1 January 2025 is a Wednesday: its week starts on Monday 30 December 2024
        val rows = series(Granularity.WEEK, "2025-01-01", "2025-01-05")
        assertEquals(listOf(LocalDate.parse("2024-12-30")), rows.map { it.start })
        assertEquals(listOf("100"), rows.map { it.total?.stripTrailingZeros()?.toPlainString() })
    }

    @Test
    fun `a day-one amount change carries into the later days`() {
        income("2025-12-03T01:00:00", "100").let { redate(it, "2025-12-01T01:00:00") }
        val second = income("2025-12-03T02:00:00", "10").let { redate(it, "2025-12-01T02:00:00") }
        income("2025-12-03T03:00:00", "1").let { redate(it, "2025-12-01T03:00:00") }
        revalue(second, "20")
        income("2025-12-02T01:00:00", "1000")

        assertEquals(listOf("121", "1121", "1121"), daily("2025-12-01", "2025-12-03"))
    }

    @Test
    fun `an operation re-dated before the account's first day moves its first day and lands there`() {
        income("2025-12-02T01:00:00", "500").let { redate(it, "2025-12-01T00:00:00") }

        assertEquals(LocalDate.parse("2025-12-01"), statisticDAO.firstActivityDate(workspaceId, emptySet(), true))
        assertEquals(listOf("500", "500"), daily("2025-12-01", "2025-12-02"))
    }

    @Test
    fun `a re-dated operation cancelled leaves nothing on any day`() {
        val operation = income("2025-12-03T01:00:00", "500").let { redate(it, "2025-12-01T01:00:00") }
        facade.processCommand(CancelOperationCommand(workspaceId, operation.id))

        assertEquals(listOf("0", "0", "0"), daily("2025-12-01", "2025-12-03"))
    }

    @Test
    fun `test-scenario's re-dated operations land on their target days`() {
        val dt0 = "2025-01-01"
        val dt2 = "2025-01-03"
        val dt3 = "2025-01-04"
        val dt4 = "2025-01-05"
        income("${dt0}T01:00:00", "100")
        expense("${dt0}T02:00:00", "10")
        expense("${dt0}T03:00:00", "10")
        income("${dt2}T01:00:00", "100")
        expense("${dt2}T02:00:00", "20")
        income("${dt4}T01:00:00", "100")
        expense("${dt4}T02:00:00", "10").let { redate(it, "${dt0}T04:00:00") }
        expense("${dt4}T03:00:00", "10").let { redate(it, "${dt2}T03:00:00") }
        expense("${dt4}T04:00:00", "10").let { redate(it, "${dt3}T01:00:00") }
        expense("${dt4}T05:00:00", "10").let { redate(it, "${dt0}T05:00:00") }
        income("${dt4}T06:00:00", "20")

        // The bot's test, not test-scenario.md, which leaves out the DT3 expense: 130 and 250 there
        assertEquals(listOf("60", "60", "130", "120", "240"), daily(dt0, dt4))
    }

    private fun income(occurredAt: String, amount: String) = create(occurredAt, amount, OperationKind.INCOME, incomeCategory)

    private fun expense(occurredAt: String, amount: String) = create(occurredAt, amount, OperationKind.EXPENSE, expenseCategory)

    private fun create(occurredAt: String, amount: String, kind: OperationKind, category: UUID) = facade.processCommand(
        CreateOperationCommand(
            workspaceId = workspaceId,
            occurredAt = LocalDateTime.parse(occurredAt),
            amount = BigDecimal(amount),
            accountId = wallet,
            kind = kind,
            categoryId = category,
            comment = null,
        )
    )

    private fun redate(operation: OperationProjection, occurredAt: String) =
        revise(operation, LocalDateTime.parse(occurredAt), operation.amount.abs())

    private fun revalue(operation: OperationProjection, amount: String) =
        revise(operation, operation.occurredAt, BigDecimal(amount))

    private fun revise(operation: OperationProjection, occurredAt: LocalDateTime, amount: BigDecimal) = facade.processCommand(
        ReviseOperationCommand(
            workspaceId = workspaceId,
            operationId = operation.id,
            occurredAt = occurredAt,
            amount = amount,
            accountId = operation.accountId,
            kind = operation.kind,
            categoryId = operation.categoryId!!,
            comment = operation.comment,
        )
    )

    private fun daily(from: String, to: String) = totals(Granularity.DAY, from, to)

    private fun totals(granularity: Granularity, from: String, to: String) =
        series(granularity, from, to).map { it.total?.stripTrailingZeros()?.toPlainString() }

    private fun series(granularity: Granularity, from: String, to: String) = statisticDAO.getBalanceSeries(
        workspaceId,
        StatisticBuckets.splitByBuckets(granularity, LocalDate.parse(from), LocalDate.parse(to)),
        LocalDate.parse(to).plusDays(1).atStartOfDay(),
        emptySet(),
        true,
    )
}
