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
import java.time.LocalDateTime
import java.util.*

/**
 * `GET /statistics/balances/accounts` (1.24) — §11.4's endpoint arriving early, because M1 ends
 * with "read balances" and the alternative is a throwaway route under the account.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class StatisticsRestControllerTest(
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
    private lateinit var accountId: UUID
    private lateinit var categoryId: UUID

    private val balancesPath get() = "/api/v1/workspaces/$workspaceId/statistics/balances/accounts"

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        accountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet", currency = "EUR")
        categoryId = TestWorkspaces.seedCategory(categoryDAO, workspaceId)
    }

    @Test
    fun `answers with a balance per account, in each account's own currency`() {
        TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "GBP")
        operation(MARCH_01, "-300.0000")

        mvc.perform(get(balancesPath).with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.accountId=='$accountId')].balance").value(-300.0000))
            .andExpect(jsonPath("$[?(@.accountId=='$accountId')].currency").value("EUR"))
    }

    @Test
    fun `an anchor replaces everything before it`() {
        operation(MARCH_01, "-300.0000")
        anchor(MARCH_15, "1500.0000")
        operation(MARCH_20, "-50.0000")

        mvc.perform(get("$balancesPath?asOf=2026-03-31").with(user(USER)))
            .andExpect(jsonPath("$[0].balance").value(1450.0000))
    }

    @Test
    fun `asOf includes the whole of that day`() {
        operation(MARCH_10, "-200.0000")

        mvc.perform(get("$balancesPath?asOf=2026-03-10").with(user(USER)))
            .andExpect(jsonPath("$[0].balance").value(-200.0000))
        mvc.perform(get("$balancesPath?asOf=2026-03-09").with(user(USER)))
            .andExpect(jsonPath("$[0].balance").value(0))
    }

    @Test
    fun `archived accounts stay in the report by default`() {
        val closed = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "closed")
        jdbc.sql("UPDATE t_accounts SET archived = true WHERE id = :id").param("id", closed).update()

        // §11.4: `includeArchived` defaults to true here — the opposite of the accounts listing,
        // because a report that silently drops a closed account misstates the totals.
        mvc.perform(get(balancesPath).with(user(USER)))
            .andExpect(jsonPath("$.length()").value(2))

        mvc.perform(get("$balancesPath?includeArchived=false").with(user(USER)))
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `reads nothing from another workspace`() {
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
        TestWorkspaces.seedAccount(accountDAO, other, name = "theirs")

        mvc.perform(get(balancesPath).with(user(USER)))
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `without asOf the balance is as of the end of today`() {
        operation(LocalDateTime.now().minusMinutes(1), "-10.0000")
        operation(LocalDateTime.now().plusDays(1), "-99.0000")

        // Today's spending is in, tomorrow's is not.
        mvc.perform(get(balancesPath).with(user(USER)))
            .andExpect(jsonPath("$[0].balance").value(-10.0000))
    }

    @Test
    fun `answers not found for a workspace the caller cannot reach`() {
        mvc.perform(get("/api/v1/workspaces/${UUID.randomUUID()}/statistics/balances/accounts").with(user(USER)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `rejects a malformed asOf`() {
        mvc.perform(get("$balancesPath?asOf=not-a-date").with(user(USER)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects a malformed includeArchived`() {
        mvc.perform(get("$balancesPath?includeArchived=maybe").with(user(USER)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects an unauthenticated read`() {
        mvc.perform(get(balancesPath)).andExpect(status().isUnauthorized)
    }

    private fun operation(occurredAt: LocalDateTime, amount: String) = operationDAO.createOrUpdate(
        OperationProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            amount = BigDecimal(amount),
            kind = OperationKind.EXPENSE,
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

    private fun anchor(occurredAt: LocalDateTime, value: String) = anchorDAO.createOrUpdate(
        BalanceAnchorProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            accountId = accountId,
            value = BigDecimal(value),
            occurredAt = occurredAt,
            externalRef = null,
            recordedAt = occurredAt,
        )
    )

    private companion object {
        val USER = TestWorkspaces.TEST_SUBJECT
        val MARCH_01: LocalDateTime = LocalDateTime.parse("2026-03-01T10:00:00")
        val MARCH_10: LocalDateTime = LocalDateTime.parse("2026-03-10T10:00:00")
        val MARCH_15: LocalDateTime = LocalDateTime.parse("2026-03-15T10:00:00")
        val MARCH_20: LocalDateTime = LocalDateTime.parse("2026-03-20T10:00:00")
    }
}
