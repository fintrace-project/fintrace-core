package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
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

/**
 * `fn_category_totals` (3.7, the whole branch since 3.8), called directly: every category of one kind, each with its own
 * operations as magnitudes per currency, NULL where it has none. Rows go straight to the projections.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class CategoryTotalsSqlIntegrationTest(
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
    private lateinit var food: UUID
    private lateinit var salary: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        wallet = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet", currency = "EUR")
        food = TestWorkspaces.seedCategory(categoryDAO, workspaceId, CategoryKind.EXPENSE, name = "food")
        salary = TestWorkspaces.seedCategory(categoryDAO, workspaceId, CategoryKind.INCOME, name = "salary")
    }

    @Test
    fun `each category answers its own operations, its subcategories not rolled in`() {
        val groceries = childCategory(food, "groceries")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(groceries, "2026-01-11T09:00:00", "5.0000")
        expense(groceries, "2026-01-12T09:00:00", "2.5000")

        assertEquals(
            setOf(
                row(food, null, "EUR", "10.0000"),
                row(groceries, food, "EUR", "7.5000"),
            ),
            totals("EXPENSE", "2026-01-01", "2026-01-31").toSet(),
        )
    }

    @Test
    fun `only the requested kind is answered, each as a magnitude`() {
        expense(food, "2026-01-10T09:00:00", "10.0000")
        income(salary, "2026-01-15T09:00:00", "2000.0000")

        assertEquals(listOf(row(food, null, "EUR", "10.0000")), totals("EXPENSE", "2026-01-01", "2026-01-31"))
        assertEquals(listOf(row(salary, null, "EUR", "2000.0000")), totals("INCOME", "2026-01-01", "2026-01-31"))
    }

    @Test
    fun `transfers and anchors are in no category's total`() {
        val savings = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings", currency = "EUR")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        TestWorkspaces.seedTransferPair(
            operationDAO, workspaceId, wallet, savings, occurredAt = LocalDateTime.parse("2026-01-12T09:00:00"),
        )
        anchor(wallet, "2026-01-05T09:00:00", "1500.0000")

        assertEquals(listOf(row(food, null, "EUR", "10.0000")), totals("EXPENSE", "2026-01-01", "2026-01-31"))
        assertEquals(listOf(empty(salary, null)), totals("INCOME", "2026-01-01", "2026-01-31"))
    }

    @Test
    fun `a category with nothing booked to it answers one row with no amount, carrying its parent`() {
        val groceries = childCategory(food, "groceries")
        expense(groceries, "2026-01-11T09:00:00", "5.0000")

        assertEquals(
            setOf(empty(food, null), row(groceries, food, "EUR", "5.0000")),
            totals("EXPENSE", "2026-01-01", "2026-01-31").toSet(),
        )
    }

    @Test
    fun `the whole branch is answered with no operation at all, the other kind's categories not`() {
        val groceries = childCategory(food, "groceries")

        assertEquals(
            setOf(empty(food, null), empty(groceries, food)),
            totals("EXPENSE", "2026-01-01", "2026-01-31").toSet(),
        )
        assertEquals(listOf(empty(salary, null)), totals("INCOME", "2026-01-01", "2026-01-31"))
    }

    @Test
    fun `a currency keeps only its own accounts' sums, every category still answered`() {
        val pounds = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "pounds", currency = "GBP")
        val groceries = childCategory(food, "groceries")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(groceries, "2026-01-11T09:00:00", "4.0000", account = pounds)

        assertEquals(
            setOf(empty(food, null), row(groceries, food, "GBP", "4.0000")),
            totals("EXPENSE", "2026-01-01", "2026-01-31", currency = "GBP").toSet(),
        )
        assertEquals(
            setOf(empty(food, null), empty(groceries, food)),
            totals("EXPENSE", "2026-01-01", "2026-01-31", currency = "USD").toSet(),
        )
    }

    @Test
    fun `a category used in two currencies answers a row for each`() {
        val pounds = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "pounds", currency = "GBP")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(food, "2026-01-11T09:00:00", "4.0000", account = pounds)

        assertEquals(
            listOf(row(food, null, "EUR", "10.0000"), row(food, null, "GBP", "4.0000")),
            totals("EXPENSE", "2026-01-01", "2026-01-31"),
        )
    }

    @Test
    fun `accounts sharing a currency are summed into one row`() {
        val card = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "card", currency = "EUR")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(food, "2026-01-11T09:00:00", "2.5000", account = card)

        assertEquals(listOf(row(food, null, "EUR", "12.5000")), totals("EXPENSE", "2026-01-01", "2026-01-31"))
    }

    @Test
    fun `a category whose operations total zero answers a row with no amount`() {
        expense(food, "2026-01-10T09:00:00", "0.0000")

        assertEquals(listOf(empty(food, null)), totals("EXPENSE", "2026-01-01", "2026-01-31"))
    }

    @Test
    fun `both bounds are inclusive days`() {
        expense(food, "2026-01-09T23:59:59", "1.0000")
        expense(food, "2026-01-10T00:00:00", "2.0000")
        expense(food, "2026-01-20T23:59:59", "4.0000")
        expense(food, "2026-01-21T00:00:00", "8.0000")

        assertEquals(listOf(row(food, null, "EUR", "6.0000")), totals("EXPENSE", "2026-01-10", "2026-01-20"))
    }

    @Test
    fun `an archived category still reports`() {
        expense(food, "2026-01-10T09:00:00", "10.0000")
        jdbc.sql("UPDATE t_categories SET archived = true WHERE id = :id").param("id", food).update()

        assertEquals(listOf(row(food, null, "EUR", "10.0000")), totals("EXPENSE", "2026-01-01", "2026-01-31"))
    }

    @Test
    fun `a filter keeps only the named accounts`() {
        val card = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "card", currency = "EUR")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(food, "2026-01-11T09:00:00", "2.5000", account = card)

        assertEquals(
            listOf(row(food, null, "EUR", "2.5000")),
            totals("EXPENSE", "2026-01-01", "2026-01-31", accountIds = setOf(card)),
        )
    }

    @Test
    fun `an archived account's operations count only when archived accounts are included`() {
        val closed = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "closed", currency = "EUR")
        expense(food, "2026-01-10T09:00:00", "10.0000")
        expense(food, "2026-01-11T09:00:00", "2.5000", account = closed)
        jdbc.sql("UPDATE t_accounts SET archived = true WHERE id = :id").param("id", closed).update()

        assertEquals(listOf(row(food, null, "EUR", "12.5000")), totals("EXPENSE", "2026-01-01", "2026-01-31"))
        assertEquals(
            listOf(row(food, null, "EUR", "10.0000")),
            totals("EXPENSE", "2026-01-01", "2026-01-31", includeArchived = false),
        )
    }

    @Test
    fun `nothing from another workspace is answered`() {
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
        val theirs = TestWorkspaces.seedAccount(accountDAO, other, name = "theirs", currency = "EUR")
        val theirFood = TestWorkspaces.seedCategory(categoryDAO, other, CategoryKind.EXPENSE, name = "food")
        expense(theirFood, "2026-01-10T09:00:00", "999.0000", account = theirs, workspace = other)

        assertEquals(listOf(empty(food, null)), totals("EXPENSE", "2026-01-01", "2026-01-31"))
    }

    private data class Row(val categoryId: UUID, val parentId: UUID?, val currency: String?, val selfTotal: BigDecimal?)

    private fun row(categoryId: UUID, parentId: UUID?, currency: String, selfTotal: String) =
        Row(categoryId, parentId, currency, BigDecimal(selfTotal))

    private fun empty(categoryId: UUID, parentId: UUID?) = Row(categoryId, parentId, null, null)

    private fun totals(
        kind: String,
        from: String,
        to: String,
        accountIds: Set<UUID> = emptySet(),
        currency: String? = null,
        includeArchived: Boolean = true,
    ): List<Row> = jdbc.sql(
        """
        SELECT category_id, parent_id, currency, self_total
        FROM fn_category_totals(
            :workspaceId, :kind, :from, :until, CAST(:accountIds AS uuid[]), CAST(:currency AS char(3)), :includeArchived
        )
        """
    )
        .param("workspaceId", workspaceId)
        .param("kind", kind)
        .param("from", LocalDate.parse(from).atStartOfDay())
        .param("until", LocalDate.parse(to).plusDays(1).atStartOfDay())
        .param("accountIds", accountIds.takeIf { it.isNotEmpty() }?.toTypedArray())
        .param("currency", currency)
        .param("includeArchived", includeArchived)
        .query { rs, _ ->
            Row(
                rs.getObject("category_id", UUID::class.java),
                rs.getObject("parent_id", UUID::class.java),
                rs.getString("currency"),
                rs.getBigDecimal("self_total"),
            )
        }
        .list()

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

    private fun expense(
        category: UUID,
        occurredAt: String,
        magnitude: String,
        account: UUID = wallet,
        workspace: UUID = workspaceId,
    ) = operation(OperationKind.EXPENSE, category, occurredAt, BigDecimal(magnitude).negate(), account, workspace)

    private fun income(category: UUID, occurredAt: String, magnitude: String) =
        operation(OperationKind.INCOME, category, occurredAt, BigDecimal(magnitude), wallet, workspaceId)

    private fun operation(
        kind: OperationKind,
        category: UUID,
        occurredAt: String,
        amount: BigDecimal,
        account: UUID,
        workspace: UUID,
    ) = operationDAO.create(
        OperationProjection(
            id = UUID.randomUUID(),
            workspaceId = workspace,
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
