package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
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
import java.time.temporal.ChronoUnit
import java.util.*

/** `GET /statistics/balances/series` (3.5): the contract, the defaults and the refusals (§11.4). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BalanceSeriesRestControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val anchorDAO: BalanceAnchorProjectionDAO,
) {

    private lateinit var workspaceId: UUID
    private lateinit var wallet: UUID

    private val seriesPath get() = "/api/v1/workspaces/$workspaceId/statistics/balances/series"

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        wallet = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet", currency = "EUR")
    }

    @Test
    fun `answers the widened bounds and one bucket per month`() {
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")

        mvc.perform(get("$seriesPath?granularity=MONTH&from=2026-01-15&to=2026-03-10").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.granularity").value("MONTH"))
            .andExpect(jsonPath("$.from").value("2026-01-01"))
            .andExpect(jsonPath("$.to").value("2026-03-31"))
            .andExpect(jsonPath("$.buckets.length()").value(3))
            .andExpect(jsonPath("$.buckets[0].start").value("2026-01-01"))
            .andExpect(jsonPath("$.buckets[2].start").value("2026-03-01"))
            .andExpect(jsonPath("$.buckets[2].balance.byCurrency[0].currency").value("EUR"))
            .andExpect(jsonPath("$.buckets[2].balance.byCurrency[0].total").value(100.0000))
    }

    @Test
    fun `converted is present and null in every bucket until conversion lands`() {
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")

        mvc.perform(get("$seriesPath?granularity=MONTH&from=2026-01-01&to=2026-01-31").with(user(USER)))
            .andExpect(jsonPath("$.buckets[0].balance.converted").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `without from the series starts at the first activity, and without to it ends today`() {
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")
        val today = LocalDate.now()
        val months = ChronoUnit.MONTHS.between(LocalDate.parse("2026-01-01"), today.withDayOfMonth(1)) + 1

        mvc.perform(get("$seriesPath?granularity=MONTH").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-01-01"))
            .andExpect(jsonPath("$.buckets.length()").value(months))
            .andExpect(jsonPath("$.buckets[-1].start").value(today.withDayOfMonth(1).toString()))
    }

    @Test
    fun `a workspace with nothing recorded answers an empty series in today's bucket`() {
        val today = LocalDate.now()

        mvc.perform(get("$seriesPath?granularity=MONTH").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.buckets.length()").value(0))
            .andExpect(jsonPath("$.from").value(today.withDayOfMonth(1).toString()))
            .andExpect(jsonPath("$.to").value(today.withDayOfMonth(today.lengthOfMonth()).toString()))
    }

    @Test
    fun `a to before all activity, with from defaulted, is an empty series rather than a 400`() {
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")

        mvc.perform(get("$seriesPath?granularity=MONTH&to=2025-06-30").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.buckets.length()").value(0))
    }

    @Test
    fun `filters by accountIds`() {
        val savings = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "GBP")
        anchor(wallet, "2026-01-10T09:00:00", "100.0000")
        anchor(savings, "2026-01-10T09:00:00", "40.0000")

        mvc.perform(get("$seriesPath?granularity=MONTH&from=2026-01-01&to=2026-01-31&accountIds=$savings").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.buckets[0].balance.byCurrency.length()").value(1))
            .andExpect(jsonPath("$.buckets[0].balance.byCurrency[0].currency").value("GBP"))
    }

    @Test
    fun `refuses an account that is not this workspace's`() {
        mvc.perform(
            get("$seriesPath?granularity=MONTH&from=2026-01-01&to=2026-01-31&accountIds=${UUID.randomUUID()}")
                .with(user(USER))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("accountIds"))
    }

    @Test
    fun `an archived account can be named even when archived accounts are excluded from the totals`() {
        val closed = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "closed", currency = "EUR")
        jdbc.sql("UPDATE t_accounts SET archived = true WHERE id = :id").param("id", closed).update()

        mvc.perform(
            get("$seriesPath?granularity=MONTH&from=2026-01-01&to=2026-01-31&accountIds=$closed&includeArchived=false")
                .with(user(USER))
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `refuses from after to`() {
        mvc.perform(get("$seriesPath?granularity=MONTH&from=2026-03-01&to=2026-01-01").with(user(USER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("from"))
    }

    @Test
    fun `refuses more than 2000 buckets, naming granularity`() {
        mvc.perform(get("$seriesPath?granularity=DAY&from=2020-01-01&to=2026-01-01").with(user(USER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("granularity"))
    }

    @Test
    fun `requires granularity`() {
        mvc.perform(get(seriesPath).with(user(USER))).andExpect(status().isBadRequest)
    }

    @Test
    fun `rejects an unknown granularity`() {
        mvc.perform(get("$seriesPath?granularity=FORTNIGHT").with(user(USER))).andExpect(status().isBadRequest)
    }

    @Test
    fun `answers not found for a workspace the caller cannot reach`() {
        mvc.perform(
            get("/api/v1/workspaces/${UUID.randomUUID()}/statistics/balances/series?granularity=MONTH").with(user(USER))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `rejects an unauthenticated read`() {
        mvc.perform(get("$seriesPath?granularity=MONTH")).andExpect(status().isUnauthorized)
    }

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
