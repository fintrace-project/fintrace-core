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
import org.hamcrest.Matchers.containsString
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

/** `GET /statistics/categories/top` (3.8c): the ranking, `other`, the defaults and the refusals (§11.4, D6a). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TopCategoriesRestControllerTest(
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
    private lateinit var expenses: UUID
    private lateinit var food: UUID
    private lateinit var transport: UUID
    private lateinit var cafes: UUID
    private lateinit var salary: UUID

    private val topPath get() = "/api/v1/workspaces/$workspaceId/statistics/categories/top"
    private val january = "from=2026-01-01&to=2026-01-31"

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        wallet = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet", currency = "EUR")
        expenses = TestWorkspaces.seedCategory(categoryDAO, workspaceId, CategoryKind.EXPENSE, name = "expenses")
        food = childCategory(expenses, "food")
        transport = childCategory(expenses, "transport")
        cafes = childCategory(expenses, "cafes")
        val income = TestWorkspaces.seedCategory(categoryDAO, workspaceId, CategoryKind.INCOME, name = "income")
        salary = childCategory(income, "salary", CategoryKind.INCOME)
    }

    @Test
    fun `answers the branch's total, the limit largest top-level categories and the rest as other`() {
        expense(food, "2026-01-10T09:00:00", "30.0000")
        expense(transport, "2026-01-11T09:00:00", "12.5000")
        expense(cafes, "2026-01-12T09:00:00", "4.0000")

        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=2&$january").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-01-01"))
            .andExpect(jsonPath("$.to").value("2026-01-31"))
            .andExpect(jsonPath("$.kind").value("EXPENSE"))
            .andExpect(jsonPath("$.total.byCurrency[0].currency").value("EUR"))
            .andExpect(jsonPath("$.total.byCurrency[0].total").value(46.5000))
            .andExpect(jsonPath("$.total.converted").value(nullValue()))
            .andExpect(jsonPath("$.rows.length()").value(2))
            .andExpect(jsonPath("$.rows[0].categoryId").value(food.toString()))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].total").value(30.0000))
            .andExpect(jsonPath("$.rows[1].categoryId").value(transport.toString()))
            .andExpect(jsonPath("$.rows[1].total.byCurrency[0].total").value(12.5000))
            .andExpect(jsonPath("$.rows[0].parentId").doesNotExist())
            .andExpect(jsonPath("$.rows[0].selfTotal").doesNotExist())
            .andExpect(jsonPath("$.other.byCurrency.length()").value(1))
            .andExpect(jsonPath("$.other.byCurrency[0].total").value(4.0000))
    }

    @Test
    fun `a subcategory's operations count towards its top-level category`() {
        val groceries = childCategory(food, "groceries")
        expense(groceries, "2026-01-10T09:00:00", "9.0000")
        expense(transport, "2026-01-11T09:00:00", "6.0000")

        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=1&$january").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].categoryId").value(food.toString()))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].total").value(9.0000))
            .andExpect(jsonPath("$.other.byCurrency[0].total").value(6.0000))
            .andExpect(jsonPath("$.total.byCurrency[0].total").value(15.0000))
    }

    @Test
    fun `when every category fits, other is present and empty`() {
        expense(food, "2026-01-10T09:00:00", "3.0000")
        expense(cafes, "2026-01-11T09:00:00", "2.0000")

        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=5&$january").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(2))
            .andExpect(jsonPath("$.other.byCurrency.length()").value(0))
            .andExpect(jsonPath("$.other.converted").value(nullValue()))
    }

    @Test
    fun `a category with nothing in the period is not a row`() {
        expense(food, "2026-01-10T09:00:00", "3.0000")

        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=3&$january").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].categoryId").value(food.toString()))
    }

    @Test
    fun `currency narrows to its accounts`() {
        val pounds = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "pounds", currency = "GBP")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(transport, "2026-01-11T09:00:00", "50.0000", account = pounds)

        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=3&$january").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].categoryId").value(food.toString()))
            .andExpect(jsonPath("$.total.byCurrency.length()").value(1))
            .andExpect(jsonPath("$.total.byCurrency[0].currency").value("EUR"))
            .andExpect(jsonPath("$.total.byCurrency[0].total").value(10.0000))
    }

    @Test
    fun `answers only the requested kind, transfers in neither`() {
        val savings = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "EUR")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        income(salary, "2026-01-15T09:00:00", "2000.0000")
        TestWorkspaces.seedTransferPair(
            operationDAO, workspaceId, wallet, savings, occurredAt = LocalDateTime.parse("2026-01-12T09:00:00"),
        )

        mvc.perform(get("$topPath?kind=INCOME&currency=EUR&limit=3&$january").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("INCOME"))
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].categoryId").value(salary.toString()))
            .andExpect(jsonPath("$.total.byCurrency[0].total").value(2000.0000))
    }

    @Test
    fun `a period with no operation answers an empty total, no rows and an empty other`() {
        expense(food, "2026-03-10T09:00:00", "10.0000")

        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=3&$january").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-01-01"))
            .andExpect(jsonPath("$.to").value("2026-01-31"))
            .andExpect(jsonPath("$.total.byCurrency.length()").value(0))
            .andExpect(jsonPath("$.rows.length()").value(0))
            .andExpect(jsonPath("$.other.byCurrency.length()").value(0))
    }

    @Test
    fun `without from it starts at the first operation, without to it ends today`() {
        expense(food, "2026-01-10T09:00:00", "10.0000")

        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=3").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("2026-01-10"))
            .andExpect(jsonPath("$.to").value(LocalDate.now().toString()))
            .andExpect(jsonPath("$.rows[0].total.byCurrency[0].total").value(10.0000))
    }

    @Test
    fun `filters by accountIds`() {
        val card = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "card", currency = "EUR")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(transport, "2026-01-11T09:00:00", "2.5000", account = card)

        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=3&$january&accountIds=$card").with(user(USER)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].categoryId").value(transport.toString()))
            .andExpect(jsonPath("$.total.byCurrency[0].total").value(2.5000))
    }

    @Test
    fun `accepts a limit from 1 to 100`() {
        expense(food, "2026-01-10T09:00:00", "10.0000")

        listOf(1, 100).forEach {
            mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=$it&$january").with(user(USER)))
                .andExpect(status().isOk)
        }
    }

    @Test
    fun `refuses a limit outside 1 to 100`() {
        listOf(0, -1, 101).forEach {
            mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=$it").with(user(USER)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.errors[0].field").value("limit"))
        }
    }

    @Test
    fun `requires limit, currency and an income or expense kind`() {
        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR").with(user(USER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("limit")))
        mvc.perform(get("$topPath?kind=EXPENSE&limit=3").with(user(USER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("currency")))
        mvc.perform(get("$topPath?currency=EUR&limit=3").with(user(USER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("kind")))
        mvc.perform(get("$topPath?kind=TRANSFER&currency=EUR&limit=3").with(user(USER)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `refuses a malformed or unknown currency`() {
        listOf("eur", "EU", "EURO", "XYZ").forEach {
            mvc.perform(get("$topPath?kind=EXPENSE&currency=$it&limit=3").with(user(USER)))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.errors[0].field").value("currency"))
        }
    }

    @Test
    fun `refuses an account that is not this workspace's`() {
        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=3&accountIds=${UUID.randomUUID()}").with(user(USER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("accountIds"))
    }

    @Test
    fun `refuses from after to`() {
        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=3&from=2026-03-01&to=2026-01-01").with(user(USER)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("from"))
    }

    @Test
    fun `answers not found for a workspace the caller cannot reach`() {
        mvc.perform(
            get("/api/v1/workspaces/${UUID.randomUUID()}/statistics/categories/top?kind=EXPENSE&currency=EUR&limit=3")
                .with(user(USER))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `rejects an unauthenticated read`() {
        mvc.perform(get("$topPath?kind=EXPENSE&currency=EUR&limit=3")).andExpect(status().isUnauthorized)
    }

    private fun childCategory(parent: UUID, name: String, kind: CategoryKind = CategoryKind.EXPENSE): UUID =
        categoryDAO.create(
            CategoryProjection(
                id = UUID.randomUUID(),
                workspaceId = workspaceId,
                parentId = parent,
                name = name,
                kind = kind,
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
