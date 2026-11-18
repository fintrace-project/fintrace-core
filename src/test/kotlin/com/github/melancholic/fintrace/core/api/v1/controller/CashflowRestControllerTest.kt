package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

/** `GET /statistics/cashflow` (3.6): the contract, the defaults and the refusals (§11.4). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class CashflowRestControllerTest(
    @Autowired private val mvc: MockMvc,
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

    private val cashflowPath get() = "/api/v1/workspaces/$workspaceId/statistics/cashflow"

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        wallet = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet", currency = "EUR")
        categoryId = TestWorkspaces.seedCategory(categoryDAO, workspaceId)
    }

    @Test
    fun `answers the widened bounds, a bucket per month and each bucket's figures`() {
        operation(wallet, "2026-01-10T09:00:00", "100.0000")
        operation(wallet, "2026-01-20T09:00:00", "-30.5000")

        mvc.perform(get("$cashflowPath?granularity=MONTH&from=2026-01-15&to=2026-02-10").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.granularity").value("MONTH"))
            .andExpect(jsonPath("$.from").value("2026-01-01"))
            .andExpect(jsonPath("$.to").value("2026-02-28"))
            .andExpect(jsonPath("$.buckets.length()").value(2))
            .andExpect(jsonPath("$.buckets[0].start").value("2026-01-01"))
            .andExpect(jsonPath("$.buckets[0].income.byCurrency[0].currency").value("EUR"))
            .andExpect(jsonPath("$.buckets[0].income.byCurrency[0].total").value(100.0000))
            .andExpect(jsonPath("$.buckets[0].expense.byCurrency[0].total").value(30.5000))
            .andExpect(jsonPath("$.buckets[0].net.byCurrency[0].total").value(69.5000))
            .andExpect(jsonPath("$.buckets[0].count[0].currency").value("EUR"))
            .andExpect(jsonPath("$.buckets[0].count[0].count").value(2))
            .andExpect(jsonPath("$.buckets[1].expense.byCurrency[0].total").value(0))
    }

    @Test
    fun `answers the period's totals and daily averages`() {
        operation(wallet, "2026-01-10T09:00:00", "100.0000")
        operation(wallet, "2026-01-20T09:00:00", "-31.0000")

        // January asked for whole: 31 days
        mvc.perform(get("$cashflowPath?granularity=MONTH&from=2026-01-01&to=2026-01-31").with(user(USER)))
            .andExpect(jsonPath("$.totals.income.byCurrency[0].total").value(100.0000))
            .andExpect(jsonPath("$.totals.expense.byCurrency[0].total").value(31.0000))
            .andExpect(jsonPath("$.totals.net.byCurrency[0].total").value(69.0000))
            .andExpect(jsonPath("$.totals.count[0].count").value(2))
            .andExpect(jsonPath("$.totals.perDay.income.byCurrency[0].total").value(3.2258))
            .andExpect(jsonPath("$.totals.perDay.expense.byCurrency[0].total").value(1.0000))
            .andExpect(jsonPath("$.totals.expenseRatio[0].currency").value("EUR"))
            .andExpect(jsonPath("$.totals.expenseRatio[0].ratio").value(0.3100))
            .andExpect(jsonPath("$.totals.netRatio[0].currency").value("EUR"))
            .andExpect(jsonPath("$.totals.netRatio[0].ratio").value(0.6900))
    }

    @Test
    fun `a period with spending and no income answers its totals and no ratios`() {
        operation(wallet, "2026-01-20T09:00:00", "-12.0000")

        mvc.perform(get("$cashflowPath?granularity=DAY&from=2026-01-20&to=2026-01-20").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totals.expense.byCurrency[0].total").value(12.0000))
            .andExpect(jsonPath("$.totals.expenseRatio.length()").value(0))
            .andExpect(jsonPath("$.totals.netRatio.length()").value(0))
    }

    @Test
    fun `converted is present and null until conversion lands`() {
        operation(wallet, "2026-01-10T09:00:00", "100.0000")

        mvc.perform(get("$cashflowPath?granularity=MONTH&from=2026-01-01&to=2026-01-31").with(user(USER)))
            .andExpect(jsonPath("$.buckets[0].income.converted").value(nullValue()))
            .andExpect(jsonPath("$.totals.net.converted").value(nullValue()))
    }

    @Test
    fun `without from it starts at the first operation, not at an earlier anchor`() {
        anchor(wallet, "2021-01-01T09:00:00", "100.0000")
        operation(wallet, "2026-01-10T09:00:00", "-5.0000")

        mvc.perform(get("$cashflowPath?granularity=MONTH&to=2026-02-28").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-01-01"))
            .andExpect(jsonPath("$.buckets.length()").value(2))
    }

    @Test
    fun `a workspace with no operation answers an empty cashflow in today's bucket`() {
        anchor(wallet, "2026-01-01T09:00:00", "100.0000")
        val today = LocalDate.now()

        mvc.perform(get("$cashflowPath?granularity=MONTH").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.buckets.length()").value(0))
            .andExpect(jsonPath("$.from").value(today.withDayOfMonth(1).toString()))
            .andExpect(jsonPath("$.to").value(today.withDayOfMonth(today.lengthOfMonth()).toString()))
            .andExpect(jsonPath("$.totals.income.byCurrency.length()").value(0))
            .andExpect(jsonPath("$.totals.count.length()").value(0))
            .andExpect(jsonPath("$.totals.expenseRatio.length()").value(0))
            .andExpect(jsonPath("$.totals.netRatio.length()").value(0))
    }

    @Test
    fun `filters by accountIds`() {
        val savings = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "GBP")
        operation(wallet, "2026-01-10T09:00:00", "-10.0000")
        operation(savings, "2026-01-10T09:00:00", "5.0000")

        mvc.perform(
            get("$cashflowPath?granularity=MONTH&from=2026-01-01&to=2026-01-31&accountIds=$savings").with(user(USER))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.buckets[0].income.byCurrency.length()").value(1))
            .andExpect(jsonPath("$.buckets[0].income.byCurrency[0].currency").value("GBP"))
            .andExpect(jsonPath("$.totals.income.byCurrency[0].total").value(5.0000))
    }

    @Test
    fun `refuses an account that is not this workspace's`() {
        mvc.perform(
            get("$cashflowPath?granularity=MONTH&from=2026-01-01&to=2026-01-31&accountIds=${UUID.randomUUID()}")
                .with(user(USER))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("accountIds"))
    }

    @Test
    fun `refuses from after to`() {
        mvc.perform(get("$cashflowPath?granularity=MONTH&from=2026-03-01&to=2026-01-01").with(user(USER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("from"))
    }

    @Test
    fun `refuses more than 2000 buckets, naming granularity`() {
        mvc.perform(get("$cashflowPath?granularity=DAY&from=2020-01-01&to=2026-01-01").with(user(USER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("granularity"))
    }

    @Test
    fun `requires a known granularity`() {
        mvc.perform(get(cashflowPath).with(user(USER))).andExpect(status().isBadRequest)
        mvc.perform(get("$cashflowPath?granularity=FORTNIGHT").with(user(USER))).andExpect(status().isBadRequest)
    }

    @Test
    fun `answers not found for a workspace the caller cannot reach`() {
        mvc.perform(
            get("/api/v1/workspaces/${UUID.randomUUID()}/statistics/cashflow?granularity=MONTH").with(user(USER))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `rejects an unauthenticated read`() {
        mvc.perform(get("$cashflowPath?granularity=MONTH")).andExpect(status().isUnauthorized)
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

    private companion object {
        val USER = TestWorkspaces.TEST_SUBJECT
    }
}
