package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 1.3: a workspace is empty when no entity references it besides the four system categories
 * seeded with it (§4.2). Rows are written straight to the projections — the check reads nothing
 * else, and events would only add noise.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class WorkspaceEmptinessTest(
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val workspaceService: WorkspaceService,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val categoryDAO: CategoryProjectionDAO,
    @Autowired private val operationDAO: OperationProjectionDAO,
    @Autowired private val anchorDAO: BalanceAnchorProjectionDAO,
) {

    private lateinit var workspaceId: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO)
    }

    @Test
    fun `a freshly created workspace is empty, its seeded system categories included`() {
        assertEquals(4, jdbc.sql("SELECT count(*) FROM t_categories WHERE workspace_id = :ws")
            .param("ws", workspaceId).query(Int::class.java).single(), "sanity: the four system categories exist")

        assertTrue(workspaceDAO.isEmpty(workspaceId))
    }

    @Test
    fun `an account makes it non-empty`() {
        TestWorkspaces.seedAccount(accountDAO, workspaceId)

        assertFalse(workspaceDAO.isEmpty(workspaceId))
    }

    @Test
    fun `an archived account still counts`() {
        accountDAO.createOrUpdate(
            AccountProjection(
                id = UUID.randomUUID(),
                workspaceId = workspaceId,
                name = "closed",
                currency = "EUR",
                archived = true,
                icon = null,
                externalRef = null,
                recordedAt = LocalDateTime.now(),
            )
        )

        assertFalse(workspaceDAO.isEmpty(workspaceId))
    }

    @Test
    fun `a custom category makes it non-empty`() {
        TestWorkspaces.seedCategory(categoryDAO, workspaceId)

        assertFalse(workspaceDAO.isEmpty(workspaceId))
    }

    @Test
    fun `an archived custom category still counts`() {
        categoryDAO.createOrUpdate(
            CategoryProjection(
                id = UUID.randomUUID(),
                workspaceId = workspaceId,
                parentId = null,
                name = "retired",
                kind = CategoryKind.EXPENSE,
                archived = true,
                systemCode = null,
                icon = null,
                externalRef = null,
                recordedAt = LocalDateTime.now(),
            )
        )

        assertFalse(workspaceDAO.isEmpty(workspaceId))
    }

    @Test
    fun `an operation makes it non-empty`() {
        // Booked against a system category, so the operation is the only thing that can count
        operationDAO.createOrUpdate(
            OperationProjection(
                id = UUID.randomUUID(),
                workspaceId = workspaceId,
                amount = BigDecimal("-10.0000"),
                kind = OperationKind.EXPENSE,
                accountId = UUID.randomUUID(),
                categoryId = systemCategory("EXPENSE_OTHERS"),
                transferId = null,
                counterpartId = null,
                comment = null,
                externalRef = null,
                occurredAt = NOW,
                recordedAt = NOW,
            )
        )

        assertFalse(workspaceDAO.isEmpty(workspaceId))
    }

    @Test
    fun `a transfer makes it non-empty`() {
        TestWorkspaces.seedTransferPair(operationDAO, workspaceId, UUID.randomUUID(), UUID.randomUUID())

        assertFalse(workspaceDAO.isEmpty(workspaceId))
    }

    @Test
    fun `an anchor makes it non-empty`() {
        seedAnchor(workspaceId)

        assertFalse(workspaceDAO.isEmpty(workspaceId))
    }

    @Test
    fun `another workspace's rows never count`() {
        val other = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO, name = "other")
        TestWorkspaces.seedAccount(accountDAO, other)
        TestWorkspaces.seedCategory(categoryDAO, other)
        TestWorkspaces.seedTransferPair(operationDAO, other, UUID.randomUUID(), UUID.randomUUID())
        seedAnchor(other)

        assertTrue(workspaceDAO.isEmpty(workspaceId))
        assertFalse(workspaceDAO.isEmpty(other), "sanity: the other workspace is not empty")
    }

    @Test
    fun `every table carrying workspace_id is checked by the function or excluded on purpose`() {
        val tables = jdbc.sql(
            """
            SELECT table_name FROM information_schema.columns
            WHERE column_name = 'workspace_id' AND table_schema = 'public'
            """
        ).query(String::class.java).set()

        // A new table fails here first: add it to fn_is_workspace_empty, or to EXCLUDED with its reason
        assertEquals(CHECKED + EXCLUDED, tables)

        val definition = jdbc.sql("SELECT pg_get_functiondef('fn_is_workspace_empty(uuid)'::regprocedure)")
            .query(String::class.java).single()
        assertEquals(emptySet(), CHECKED.filterNot { it in definition }.toSet(), "tables the function does not read")
    }

    private fun seedAnchor(workspaceId: UUID) = anchorDAO.createOrUpdate(
        BalanceAnchorProjection(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            accountId = UUID.randomUUID(),
            value = BigDecimal("50.0000"),
            occurredAt = NOW,
            externalRef = null,
            recordedAt = NOW,
        )
    )

    private fun systemCategory(code: String): UUID = jdbc
        .sql("SELECT id FROM t_categories WHERE workspace_id = :ws AND system_code = :code")
        .param("ws", workspaceId)
        .param("code", code)
        .query(UUID::class.java)
        .single()

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 1, 12, 0)
        private val CHECKED = setOf("t_accounts", "t_categories", "t_operations", "t_balance_anchors")
        // t_events: a fresh workspace already holds the four category events, so the log cannot say "empty"
        // t_import_jobs: operational metadata, not workspace data — and counting it would break §4.2's
        // promise that a failed import can be retried, since the FAILED row would make the workspace
        // permanently non-empty
        private val EXCLUDED = setOf("t_events", "t_import_jobs")
    }
}
