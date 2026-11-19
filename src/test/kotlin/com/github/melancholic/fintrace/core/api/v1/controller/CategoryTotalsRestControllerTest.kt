package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import org.hamcrest.Matchers.contains
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

/** `GET /statistics/categories/totals` (3.7, 3.8): the contract, the rollup, the defaults and the refusals (§11.4). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class CategoryTotalsRestControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val categoryDAO: CategoryProjectionDAO,
    @Autowired private val operationDAO: OperationProjectionDAO,
) {

    private lateinit var workspaceId: UUID
    private lateinit var wallet: UUID
    private lateinit var food: UUID
    private lateinit var salary: UUID

    private val totalsPath get() = "/api/v1/workspaces/$workspaceId/statistics/categories/totals"

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        wallet = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet", currency = "EUR")
        food = TestWorkspaces.seedCategory(categoryDAO, workspaceId, CategoryKind.EXPENSE, name = "food")
        salary = TestWorkspaces.seedCategory(categoryDAO, workspaceId, CategoryKind.INCOME, name = "salary")
    }

    @Test
    fun `answers the period, the kind and a row per category with its own and its total per currency`() {
        expense(food, "2026-01-10T09:00:00", "30.5000")

        mvc.perform(get("$totalsPath?kind=EXPENSE&from=2026-01-01&to=2026-01-31").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-01-01"))
            .andExpect(jsonPath("$.to").value("2026-01-31"))
            .andExpect(jsonPath("$.kind").value("EXPENSE"))
            .andExpect(jsonPath("$.mode").doesNotExist())
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].categoryId").value(food.toString()))
            .andExpect(jsonPath("$.rows[0].parentId").value(nullValue()))
            .andExpect(jsonPath("$.rows[0].selfTotal.byCurrency[0].currency").value("EUR"))
            .andExpect(jsonPath("$.rows[0].selfTotal.byCurrency[0].total").value(30.5000))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].currency").value("EUR"))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].total").value(30.5000))
            .andExpect(jsonPath("$.rows[0].total.converted").value(nullValue()))
    }

    @Test
    fun `a category's selfTotal is its own operations, its total rolls its subcategories in`() {
        val groceries = childCategory(food, "groceries")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(groceries, "2026-01-11T09:00:00", "5.0000")
        expense(groceries, "2026-01-12T09:00:00", "2.5000")

        mvc.perform(get("$totalsPath?kind=EXPENSE&from=2026-01-01&to=2026-01-31").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(2))
            .andExpect(jsonPath("$.rows[?(@.categoryId == '$food')].selfTotal.byCurrency[0].total").value(contains(10.0)))
            .andExpect(jsonPath("$.rows[?(@.categoryId == '$food')].total.byCurrency[0].total").value(contains(17.5)))
            .andExpect(jsonPath("$.rows[?(@.categoryId == '$groceries')].selfTotal.byCurrency[0].total").value(contains(7.5)))
            .andExpect(jsonPath("$.rows[?(@.categoryId == '$groceries')].total.byCurrency[0].total").value(contains(7.5)))
            .andExpect(jsonPath("$.rows[?(@.categoryId == '$groceries')].parentId").value(contains(food.toString())))
    }

    @Test
    fun `a parent with nothing booked to it is answered with an empty selfTotal, an empty branch is not`() {
        val groceries = childCategory(food, "groceries")
        val organic = childCategory(groceries, "organic")
        childCategory(food, "cafes")
        expense(organic, "2026-01-11T09:00:00", "5.0000")

        mvc.perform(get("$totalsPath?kind=EXPENSE&from=2026-01-01&to=2026-01-31").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(3))
            .andExpect(jsonPath("$.rows[?(@.categoryId == '$food')].selfTotal.byCurrency.length()").value(contains(0)))
            .andExpect(jsonPath("$.rows[?(@.categoryId == '$food')].total.byCurrency[0].total").value(contains(5.0)))
            .andExpect(jsonPath("$.rows[?(@.categoryId == '$groceries')].total.byCurrency[0].total").value(contains(5.0)))
            .andExpect(jsonPath("$.rows[?(@.categoryId == '$organic')].selfTotal.byCurrency[0].total").value(contains(5.0)))
    }

    @Test
    fun `a currency narrows to its accounts, one no account holds answers no rows`() {
        val pounds = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "pounds", currency = "GBP")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(food, "2026-01-11T09:00:00", "4.0000", account = pounds)

        mvc.perform(get("$totalsPath?kind=EXPENSE&currency=GBP&from=2026-01-01&to=2026-01-31").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].total.byCurrency.length()").value(1))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].currency").value("GBP"))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].total").value(4.0000))

        mvc.perform(get("$totalsPath?kind=EXPENSE&currency=USD&from=2026-01-01&to=2026-01-31").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(0))
    }

    @Test
    fun `answers only the requested kind, transfers in neither`() {
        val savings = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "EUR")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        income(salary, "2026-01-15T09:00:00", "2000.0000")
        TestWorkspaces.seedTransferPair(
            operationDAO, workspaceId, wallet, savings, occurredAt = LocalDateTime.parse("2026-01-12T09:00:00"),
        )

        mvc.perform(get("$totalsPath?kind=INCOME&from=2026-01-01&to=2026-01-31").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("INCOME"))
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].categoryId").value(salary.toString()))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].total").value(2000.0000))
    }

    @Test
    fun `a category used in two currencies is one row, its currencies ordered by code`() {
        val pounds = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "pounds", currency = "GBP")
        expense(food, "2026-01-11T09:00:00", "4.0000", account = pounds)
        expense(food, "2026-01-10T09:00:00", "10.0000")

        mvc.perform(get("$totalsPath?kind=EXPENSE&from=2026-01-01&to=2026-01-31").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].total.byCurrency.length()").value(2))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].currency").value("EUR"))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].total").value(10.0000))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[1].currency").value("GBP"))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[1].total").value(4.0000))
    }

    @Test
    fun `both bounds are inclusive days`() {
        expense(food, "2026-01-09T23:59:59", "1.0000")
        expense(food, "2026-01-10T00:00:00", "2.0000")
        expense(food, "2026-01-20T23:59:59", "4.0000")
        expense(food, "2026-01-21T00:00:00", "8.0000")

        mvc.perform(get("$totalsPath?kind=EXPENSE&from=2026-01-10&to=2026-01-20").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].total").value(6.0000))
    }

    @Test
    fun `without from it starts at the first operation, without to it ends today`() {
        expense(food, "2026-01-10T09:00:00", "10.0000")

        mvc.perform(get("$totalsPath?kind=EXPENSE").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-01-10"))
            .andExpect(jsonPath("$.to").value(LocalDate.now().toString()))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].total").value(10.0000))
    }

    @Test
    fun `a workspace with no operation answers no rows, from and to today`() {
        val today = LocalDate.now().toString()

        mvc.perform(get("$totalsPath?kind=EXPENSE").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value(today))
            .andExpect(jsonPath("$.to").value(today))
            .andExpect(jsonPath("$.rows.length()").value(0))
    }

    @Test
    fun `filters by accountIds`() {
        val card = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "card", currency = "EUR")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(food, "2026-01-11T09:00:00", "2.5000", account = card)

        mvc.perform(
            get("$totalsPath?kind=EXPENSE&from=2026-01-01&to=2026-01-31&accountIds=$card")
                .with(user(USER))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].total").value(2.5000))
    }

    @Test
    fun `refuses an account that is not this workspace's`() {
        mvc.perform(
            get("$totalsPath?kind=EXPENSE&accountIds=${UUID.randomUUID()}").with(user(USER))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("accountIds"))
    }

    @Test
    fun `refuses from after to`() {
        mvc.perform(get("$totalsPath?kind=EXPENSE&from=2026-03-01&to=2026-01-01").with(user(USER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("from"))
    }

    @Test
    fun `requires an income or expense kind`() {
        mvc.perform(get(totalsPath).with(user(USER))).andExpect(status().isBadRequest)
        mvc.perform(get("$totalsPath?kind=TRANSFER").with(user(USER))).andExpect(status().isBadRequest)
        mvc.perform(get("$totalsPath?kind=REFUND").with(user(USER))).andExpect(status().isBadRequest)
    }

    @Test
    fun `refuses a malformed or unknown currency`() {
        listOf("eur", "EU", "EURO", "XYZ").forEach {
            mvc.perform(get("$totalsPath?kind=EXPENSE&currency=$it").with(user(USER)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.errors[0].field").value("currency"))
        }
    }

    @Test
    fun `answers not found for a workspace the caller cannot reach`() {
        mvc.perform(
            get("/api/v1/workspaces/${UUID.randomUUID()}/statistics/categories/totals?kind=EXPENSE")
                .with(user(USER))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `rejects an unauthenticated read`() {
        mvc.perform(get("$totalsPath?kind=EXPENSE")).andExpect(status().isUnauthorized)
    }

    private fun childCategory(parent: UUID, name: String): UUID = categoryDAO.create(
        CategoryProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            parentId = parent,
            name = name,
            kind = CategoryKind.EXPENSE,
            archived = false,
            systemCode = null,
            icon = null,
            externalRef = null,
            recordedAt = LocalDateTime.now(),
        )
    )

    private fun expense(category: UUID, occurredAt: String, magnitude: String, account: UUID = wallet) =
        operation(OperationKind.EXPENSE, category, occurredAt, BigDecimal(magnitude).negate(), account)

    private fun income(category: UUID, occurredAt: String, magnitude: String) =
        operation(OperationKind.INCOME, category, occurredAt, BigDecimal(magnitude), wallet)

    private fun operation(kind: OperationKind, category: UUID, occurredAt: String, amount: BigDecimal, account: UUID) =
        operationDAO.create(
            OperationProjection(
                id = UUID.randomUUID(),
                workspaceId = workspaceId,
                amount = amount,
                kind = kind,
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

    private companion object {
        val USER = TestWorkspaces.TEST_SUBJECT
    }
}
