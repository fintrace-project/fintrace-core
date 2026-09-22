package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.domain.command.*
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.OperationNotAllowedException
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals

/**
 * The workspace status guard (§4.1.1), asserted once at the facade for every aggregate, so a handler
 * added later cannot quietly escape it: `ARCHIVED` refuses every command and writes nothing while
 * reads still work, and `DELETED` is invisible to both.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class WorkspaceStatusGuardTest(
    @Autowired private val commandFacade: CommandFacade,
    @Autowired private val workspaceFacade: WorkspaceFacade,
    @Autowired private val projectionFacade: ProjectionFacade,
    @Autowired private val statisticFacade: StatisticFacade,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceService: WorkspaceService,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val transactions: TransactionTemplate,
) {

    private lateinit var workspaceId: UUID
    private lateinit var cash: UUID
    private lateinit var card: UUID
    private lateinit var food: UUID
    private lateinit var operationId: UUID
    private lateinit var transferId: UUID
    private lateinit var anchorId: UUID

    @BeforeEach
    fun seed() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO)
        // One of every aggregate, through the command path; the first command also activates the workspace
        cash = commandFacade.processCommand(CreateAccountCommand(workspaceId = workspaceId, name = "cash", currency = "EUR", icon = null)).id
        card = commandFacade.processCommand(CreateAccountCommand(workspaceId = workspaceId, name = "card", currency = "EUR", icon = null)).id
        food = commandFacade.processCommand(CreateCategoryCommand.custom(workspaceId = workspaceId, kind = CategoryKind.EXPENSE, name = "Food", parentId = expenseRoot(), icon = null)).id
        operationId = commandFacade.processCommand(expense()).id
        transferId = commandFacade.processCommand(transfer()).id
        anchorId = commandFacade.processCommand(
            CreateBalanceAnchorCommand(
                workspaceId = workspaceId,
                accountId = cash,
                value = BigDecimal("50.0000"),
                occurredAt = LocalDateTime.now(),
            )
        ).projection.id
    }

    @Test
    fun `an archived workspace refuses a command of every aggregate and writes nothing`() {
        workspaceFacade.archiveWorkspace(workspaceId)
        val before = state()

        for (command in commandsOfEveryAggregate()) {
            assertThrows<OperationNotAllowedException>(command::class.simpleName!!) { dispatch(command) }
        }

        assertEquals(before, state())
    }

    @Test
    fun `an archived workspace still answers every read`() {
        workspaceFacade.archiveWorkspace(workspaceId)

        for ((name, read) in reads()) {
            assertDoesNotThrow(name) { read() }
        }
    }

    @Test
    fun `a deleted workspace is invisible to every read`() {
        workspaceFacade.deleteWorkspace(workspaceId, workspaceFacade.getWorkspace(workspaceId).version)

        for ((name, read) in reads()) {
            assertThrows<NotFoundEntityException>(name) { read() }
        }
    }

    @Test
    fun `a deleted workspace refuses a command of every aggregate and writes nothing`() {
        workspaceFacade.deleteWorkspace(workspaceId, workspaceFacade.getWorkspace(workspaceId).version)
        val before = state()

        for (command in commandsOfEveryAggregate()) {
            assertThrows<NotFoundEntityException>(command::class.simpleName!!) { dispatch(command) }
        }

        assertEquals(before, state())
    }

    // Create, revise and cancel across all five command families, each aimed at a row that exists
    private fun commandsOfEveryAggregate(): List<Command<*>> = listOf(
        CreateAccountCommand(workspaceId = workspaceId, name = "savings", currency = "EUR", icon = null),
        ReviseAccountCommand(workspaceId = workspaceId, accountId = cash, name = "wallet", icon = null),
        CreateCategoryCommand.custom(workspaceId = workspaceId, kind = CategoryKind.EXPENSE, name = "Rent", parentId = expenseRoot(), icon = null),
        SetCategoryArchivedCommand(workspaceId = workspaceId, categoryId = food, archived = true),
        expense(),
        CancelOperationCommand(workspaceId = workspaceId, operationId = operationId),
        transfer(),
        CancelTransferCommand(workspaceId = workspaceId, transferId = transferId),
        CreateBalanceAnchorCommand(
            workspaceId = workspaceId,
            accountId = cash,
            value = BigDecimal.ZERO,
            occurredAt = LocalDateTime.now(),
        ),
        CancelBalanceAnchorCommand(workspaceId = workspaceId, accountId = cash, anchorId = anchorId),
    )

    private fun reads(): List<Pair<String, () -> Any>> = listOf(
        "workspace" to { workspaceFacade.getWorkspace(workspaceId) },
        "account" to { projectionFacade.getAccount(workspaceId, cash) },
        "accounts" to { projectionFacade.getAllAccounts(workspaceId, includeArchived = true) },
        "category" to { projectionFacade.getCategory(workspaceId, food) },
        "categories" to { projectionFacade.getAllCategories(workspaceId, includeArchived = true) },
        "operation" to { projectionFacade.getOperation(workspaceId, operationId) },
        "transfer" to { projectionFacade.getTransfer(workspaceId, transferId) },
        "anchors" to { projectionFacade.getAllBalanceAnchors(workspaceId, cash) },
        "anchor" to { projectionFacade.getBalanceAnchor(workspaceId, cash, anchorId) },
        "balances" to { statisticFacade.getAllBalancesOf(workspaceId, LocalDate.now(), includeArchived = true) },
    )

    @Suppress("UNCHECKED_CAST")
    private fun dispatch(command: Command<*>) {
        commandFacade.processCommand(command as Command<Any?>)
    }

    private fun expense() = CreateOperationCommand(
        workspaceId = workspaceId,
        occurredAt = OCCURRED_AT,
        amount = BigDecimal("12.5000"),
        accountId = cash,
        kind = OperationKind.EXPENSE,
        categoryId = food,
        comment = null,
    )

    private fun transfer() = CreateTransferCommand(
        workspaceId = workspaceId,
        occurredAt = OCCURRED_AT,
        sourceAccountId = cash,
        sourceAmount = BigDecimal("10.0000"),
        targetAccountId = card,
        targetAmount = BigDecimal("10.0000"),
        comment = null,
    )

    private fun expenseRoot(): UUID = jdbc
        .sql("SELECT id FROM t_categories WHERE workspace_id = :ws AND system_code = 'EXPENSE_ROOT'")
        .param("ws", workspaceId)
        .query(UUID::class.java)
        .single()

    // A count plus a hash of every row, so a refused command that still changed a row fails as surely as one that added one
    private fun state(): Map<String, String> = TABLES.associateWith { table ->
        jdbc.sql(
            """
            SELECT count(*) || ':' || md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), ''))
            FROM $table t WHERE t.workspace_id = :ws
            """
        )
            .param("ws", workspaceId)
            .query(String::class.java)
            .single()
    }

    companion object {
        private val OCCURRED_AT: LocalDateTime = LocalDateTime.of(2026, 9, 1, 12, 0)
        private val TABLES = listOf("t_events", "t_accounts", "t_categories", "t_operations", "t_balance_anchors")
    }
}
