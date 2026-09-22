package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.*
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The full-rebuild procedure (task 0.11) and its equality guarantee (task 0.12).
 *
 * This is the one real payoff for the complexity event sourcing costs (§4.10): if the projection
 * cannot be reconstructed from the log, ES has degenerated into an audit log with extra steps.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class AdminFacadeReplayTest(
	@Autowired private val commandFacade: CommandFacade,
	@Autowired private val adminFacade: AdminFacade,
	@Autowired private val jdbc: JdbcClient,
	@Autowired private val workspaceDAO: WorkspaceDAO,
	@Autowired private val usersDAO: UsersDAO,
    @Autowired private val workspaceService: WorkspaceService,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val categoryDAO: CategoryProjectionDAO,
) {

	private lateinit var workspace: UUID

    /**
     * An operation command needs an existing account and category (1.16), so each workspace gets a
     * pair written straight to their projections — no event, so the absolute event counts these
     * tests assert stay meaningful.
     *
     * Nothing in the log describes them, so a rebuild does not reproduce them; [accounts] and
     * [categories] leave them out of what they return for exactly that reason.
     */
    private val fixtures = mutableMapOf<UUID, Pair<UUID, UUID>>()

    private fun fixtureOf(workspaceId: UUID): Pair<UUID, UUID> = fixtures.getOrPut(workspaceId) {
        TestWorkspaces.seedAccount(accountDAO, workspaceId) to
                TestWorkspaces.seedCategory(categoryDAO, workspaceId)
    }

    private fun seededAccountOf(workspaceId: UUID) = fixtureOf(workspaceId).first

    private fun seededCategoryOf(workspaceId: UUID) = fixtureOf(workspaceId).second

	@BeforeEach
	fun clean() {
		TestWorkspaces.reset(jdbc)
		workspace = TestWorkspaces.create(workspaceDAO, usersDAO)
	}

	@Test
	fun `rebuilds a projection identical to the original`() {
		repeat(5) { create(amount = "10.${it}000") }
		val before = operations(workspace)

		adminFacade.replayWorkspace(workspace)

		assertEquals(before, operations(workspace))
		assertEquals(5, before.size, "sanity: the snapshot must not be empty")
	}

	@Test
	fun `reconstructs a projection that was wiped entirely`() {
		repeat(3) { create() }
		val before = operations(workspace)

		// Not just "replay agrees with itself" — the rows are gone, and only the event log
		// remains to rebuild them from.
		jdbc.sql("DELETE FROM t_operations").update()
		assertTrue(operations(workspace).isEmpty(), "sanity: the projection is empty")

		adminFacade.replayWorkspace(workspace)

		assertEquals(before, operations(workspace))
	}

	@Test
	fun `appends no events`() {
		repeat(3) { create() }
		val eventsBefore = eventIds()

		adminFacade.replayWorkspace(workspace)

		// Replay applies stored events; it must never travel the command path, which would
		// write new ones and corrupt the log it is rebuilding from.
		assertEquals(eventsBefore, eventIds())
	}

	@Test
	fun `is idempotent`() {
		repeat(3) { create() }

		adminFacade.replayWorkspace(workspace)
		val once = operations(workspace)
		adminFacade.replayWorkspace(workspace)

		assertEquals(once, operations(workspace))
	}

	@Test
	fun `leaves other workspaces untouched`() {
		val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
		repeat(2) { create() }
		repeat(3) { create(workspaceId = other) }
		val otherBefore = operations(other)

		adminFacade.replayWorkspace(workspace)

		assertEquals(2, operations(workspace).size)
		assertEquals(otherBefore, operations(other), "replay is scoped to one workspace")
	}

	@Test
	fun `replaying a workspace with no events is a no-op`() {
		adminFacade.replayWorkspace(UUID.randomUUID())

		assertEquals(0, operations(workspace).size)
	}

	@Test
	fun `replays a revision onto the same row`() {
		val id = create()
		commandFacade.processCommand(
			ReviseOperationCommand(
				workspaceId = workspace, operationId = id,
				occurredAt = OCCURRED_AT, amount = BigDecimal("777.0000"),
                accountId = seededAccountOf(workspace), kind = OperationKind.EXPENSE,
                categoryId = seededCategoryOf(workspace), comment = null,
			)
		)
		val before = operations(workspace)

		jdbc.sql("DELETE FROM t_operations").update()
		adminFacade.replayWorkspace(workspace)

		// Two events, one row: the create inserts and the revision overwrites, which only holds
		// if applying an event is an upsert rather than an insert.
		assertEquals(before, operations(workspace))
		assertEquals(1, operations(workspace).size)
        assertEquals(BigDecimal("-777.0000"), operations(workspace).single().amount, "an EXPENSE rebuilds signed")
	}

	@Test
	fun `replays a cancellation as an absent row`() {
		val id = create()
		commandFacade.processCommand(
			CancelOperationCommand(workspaceId = workspace, operationId = id)
		)

		jdbc.sql("DELETE FROM t_operations").update()
		adminFacade.replayWorkspace(workspace)

		// The log still holds both events; the rebuilt projection must not hold the row.
		assertEquals(2, eventIds().size)
		assertTrue(operations(workspace).isEmpty(), "a cancelled operation must not come back")
	}

	@Test
	fun `rebuilds a mixed history identically`() {
		val revised = create(amount = "10.0000")
		val cancelled = create(amount = "20.0000")
		create(amount = "30.0000")
		commandFacade.processCommand(
			ReviseOperationCommand(
				workspaceId = workspace, operationId = revised,
				occurredAt = BACK_DATED, amount = BigDecimal("11.0000"),
                accountId = seededAccountOf(workspace), kind = OperationKind.EXPENSE,
                categoryId = seededCategoryOf(workspace), comment = null,
			)
		)
		commandFacade.processCommand(
			CancelOperationCommand(workspaceId = workspace, operationId = cancelled)
		)
		val before = operations(workspace)

		adminFacade.replayWorkspace(workspace)

		assertEquals(before, operations(workspace))
		assertEquals(2, before.size, "sanity: one of the three was cancelled")
	}

	@Test
	fun `rebuilds two aggregates at once`() {
		repeat(2) { create() }
		createAccount(name = "cash")
		createAccount(name = "savings")
		val operationsBefore = operations(workspace)
		val accountsBefore = accounts(workspace)

		// Until now nothing proved the replay path generalises: one table can be rebuilt by a
		// handler that happens to be right, two cannot.
		jdbc.sql("DELETE FROM t_operations").update()
		jdbc.sql("DELETE FROM t_accounts").update()
		adminFacade.replayWorkspace(workspace)

		assertEquals(operationsBefore, operations(workspace))
		assertEquals(accountsBefore, accounts(workspace))
		assertEquals(2, accountsBefore.size, "sanity: the snapshot must not be empty")
	}

	@Test
	fun `replays each aggregate through its own projection`() {
		val account = createAccount(name = "cash")
		create()

		adminFacade.replayWorkspace(workspace)

		// A payload registered under the wrong discriminator would rebuild as the other
		// aggregate, leaving one table short and the other with a row it cannot map.
		assertEquals(1, accounts(workspace).size)
		assertEquals(1, operations(workspace).size)
		assertEquals(account, accounts(workspace).single().id)
	}

	@Test
	fun `replays an account's revisions onto one row`() {
		val account = createAccount(name = "before", currency = "CZK")
		commandFacade.processCommand(ReviseAccountCommand(workspace, account, "after", icon = "new"))
		commandFacade.processCommand(SetAccountArchivedCommand(workspace, account, archived = true))
		val before = accounts(workspace)

		jdbc.sql("DELETE FROM t_accounts").update()
		adminFacade.replayWorkspace(workspace)

		assertEquals(before, accounts(workspace))
		val row = accounts(workspace).single()
		assertEquals("after", row.name)
		assertEquals("CZK", row.currency, "the immutable currency survives three events")
		assertTrue(row.archived)
	}

    @Test
    fun `rebuilds an account and the anchor its initial balance created`() {
        val id = commandFacade.processCommand(
            CreateAccountCommand(workspaceId = workspace, name = "cash", currency = "EUR", icon = null, initialBalance = BigDecimal("1500.0000"))
        ).id
        val accountsBefore = accounts(workspace)
        val anchorsBefore = anchors(workspace)

        jdbc.sql("DELETE FROM t_accounts").update()
        jdbc.sql("DELETE FROM t_balance_anchors").update()
        adminFacade.replayWorkspace(workspace)

        // One command, two events, two aggregate types — the first of its kind, and the case the
        // replay loop has to get right or an account comes back without the balance it opened with.
        assertEquals(accountsBefore, accounts(workspace))
        assertEquals(anchorsBefore, anchors(workspace))
        assertEquals(id, anchors(workspace).single().second)
    }

    @Test
    fun `does not invent an anchor for an account created without an initial balance`() {
        commandFacade.processCommand(CreateAccountCommand(workspaceId = workspace, name = "cash", currency = "EUR", icon = null))

        jdbc.sql("DELETE FROM t_accounts").update()
        adminFacade.replayWorkspace(workspace)

        assertEquals(1, accounts(workspace).size)
        assertEquals(0, anchors(workspace).size, "no anchor event, no anchor row")
    }

    private fun anchors(workspaceId: UUID): List<Pair<UUID, UUID>> = jdbc
        .sql("SELECT id, account_id FROM t_balance_anchors WHERE workspace_id = :ws ORDER BY id")
        .param("ws", workspaceId)
        .query { rs, _ ->
            rs.getObject("id", UUID::class.java) to rs.getObject("account_id", UUID::class.java)
        }
        .list()

    @Test
    fun `rebuilds all three aggregates at once`() {
        // A seeded workspace, so the four system categories are part of what has to come back.
        val seeded = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO, name = "seeded")
        val food = createCategory(seeded, parent = expenseRoot(seeded), name = "Food")
        createCategory(seeded, parent = food, name = "Groceries")
        commandFacade.processCommand(CreateAccountCommand(workspaceId = seeded, name = "cash", currency = "EUR", icon = null))
        create(workspaceId = seeded)
        val categoriesBefore = categories(seeded)
        val accountsBefore = accounts(seeded)
        val operationsBefore = operations(seeded)

        jdbc.sql("DELETE FROM t_categories").update()
        jdbc.sql("DELETE FROM t_accounts").update()
        jdbc.sql("DELETE FROM t_operations").update()
        adminFacade.replayWorkspace(seeded)

        assertEquals(categoriesBefore, categories(seeded))
        assertEquals(accountsBefore, accounts(seeded))
        assertEquals(operationsBefore, operations(seeded))
        assertEquals(6, categoriesBefore.size, "sanity: four seeded plus two of our own")
    }

    @Test
    fun `rebuilds every projection at once, leaving out what was cancelled or deleted`() {
        val ws = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO, name = "everything")
        val food = createCategory(ws, parent = expenseRoot(ws), name = "Food")
        // A move, because it is the one category change that travels by UPDATE rather than INSERT:
        // a rebuild inserts the final parent, so an online path that never wrote it diverges here
        // and nowhere else.
        val snacks = createCategory(ws, parent = expenseRoot(ws), name = "Snacks")
        commandFacade.processCommand(
            ReviseCategoryCommand(workspaceId = ws, categoryId = snacks, parentId = food, name = "Snacks", icon = null)
        )
        val cash = commandFacade.processCommand(
            CreateAccountCommand(workspaceId = ws, name = "cash", currency = "EUR", icon = null, initialBalance = BigDecimal("100.0000"))
        ).id
        val card = commandFacade.processCommand(CreateAccountCommand(workspaceId = ws, name = "card", currency = "EUR", icon = null)).id
        commandFacade.processCommand(ReviseAccountCommand(workspaceId = ws, accountId = card, name = "debit-card", icon = null))
        val kept = create(workspaceId = ws, accountId = cash, categoryId = food)
        commandFacade.processCommand(
            ReviseOperationCommand(
                workspaceId = ws,
                operationId = kept,
                occurredAt = OCCURRED_AT,
                amount = BigDecimal("42.0000"),
                accountId = cash,
                kind = OperationKind.EXPENSE,
                categoryId = food,
                comment = "revised",
            )
        )
        val cancelled = create(workspaceId = ws, accountId = cash, categoryId = food)
        commandFacade.processCommand(CancelOperationCommand(workspaceId = ws, operationId = cancelled))
        val transfer = commandFacade.processCommand(
            CreateTransferCommand(
                workspaceId = ws,
                occurredAt = OCCURRED_AT,
                sourceAccountId = cash,
                sourceAmount = BigDecimal("30.0000"),
                targetAccountId = card,
                targetAmount = BigDecimal("30.0000"),
                comment = "to card",
            )
        )
        commandFacade.processCommand(
            CreateBalanceAnchorCommand(
                workspaceId = ws,
                accountId = card,
                value = BigDecimal("30.0000"),
                occurredAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS),
            )
        )
        val deletedAnchor = commandFacade.processCommand(
            CreateBalanceAnchorCommand(
                workspaceId = ws,
                accountId = cash,
                value = BigDecimal("25.0000"),
                occurredAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS),
            )
        ).projection.id
        commandFacade.processCommand(CancelBalanceAnchorCommand(workspaceId = ws, accountId = cash, anchorId = deletedAnchor))

        val categoriesBefore = categories(ws)
        val accountsBefore = accounts(ws)
        val operationsBefore = operations(ws)
        val anchorsBefore = anchorRows(ws)

        jdbc.sql("DELETE FROM t_categories").update()
        jdbc.sql("DELETE FROM t_accounts").update()
        jdbc.sql("DELETE FROM t_operations").update()
        jdbc.sql("DELETE FROM t_balance_anchors").update()
        adminFacade.replayWorkspace(ws)

        assertEquals(categoriesBefore, categories(ws))
        assertEquals(accountsBefore, accounts(ws))
        assertEquals(operationsBefore, operations(ws))
        assertEquals(anchorsBefore, anchorRows(ws))

        // Equality alone would pass on a history that never cancelled anything; decision 4 is the absence
        assertTrue(operations(ws).none { it.id == cancelled }, "a cancelled operation stays gone")
        assertTrue(anchorRows(ws).none { it.id == deletedAnchor }, "a deleted anchor stays gone")
        assertEquals(3, operationsBefore.size, "sanity: the revised operation plus both legs")
        assertEquals(2, operationsBefore.count { it.transferId == transfer.id }, "sanity: both legs of the transfer")
        assertEquals(BigDecimal("-42.0000"), operationsBefore.single { it.id == kept }.amount, "sanity: the revision")
        assertEquals("debit-card", accountsBefore.single { it.id == card }.name, "sanity: the account revision")
        assertEquals(food, categoriesBefore.single { it.id == snacks }.parentId, "sanity: the category move")
        assertEquals(2, anchorsBefore.size, "sanity: the initial balance and the card's anchor")
    }

    @Test
	fun `rebuilds the tree structure and the system codes`() {
        val seeded = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO, name = "seeded")
        val root = expenseRoot(seeded)
        val food = createCategory(seeded, parent = root, name = "Food")
        createCategory(seeded, parent = food, name = "Groceries")

        jdbc.sql("DELETE FROM t_categories").update()
        adminFacade.replayWorkspace(seeded)

        // parent_id is the tree: if a rebuild loses it, the projection is a flat list that still
        // passes a row-count assertion.
        val rebuilt = categories(seeded).associateBy { it.id }
        assertEquals(root, rebuilt.getValue(food).parentId)
        assertEquals(food, rebuilt.values.single { it.name == "Groceries" }.parentId)
        assertEquals(null, rebuilt.getValue(root).parentId, "a root keeps its null parent")
		assertEquals(
			"EXPENSE_ROOT",
			rebuilt.getValue(root).systemCode,
			"a seeded root rebuilds with the code it was seeded with, not merely as 'a system category'",
		)
		assertNull(rebuilt.getValue(food).systemCode)
    }

    @Test
    fun `rebuilds an archived subtree`() {
        val seeded = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO, name = "seeded")
        val food = createCategory(seeded, parent = expenseRoot(seeded), name = "Food")
        val groceries = createCategory(seeded, parent = food, name = "Groceries")
        commandFacade.processCommand(SetCategoryArchivedCommand(seeded, food, archived = true))
        val before = categories(seeded)

        jdbc.sql("DELETE FROM t_categories").update()
        adminFacade.replayWorkspace(seeded)

        // The cascade wrote one event per node (§4.7). Replaying only the event addressed to the
        // parent would bring Groceries back unarchived.
        assertEquals(before, categories(seeded))
        val rebuilt = categories(seeded).associateBy { it.id }
        assertTrue(rebuilt.getValue(food).archived)
        assertTrue(rebuilt.getValue(groceries).archived, "the child's own event carries its archived state")
    }

	@Test
	fun `preserves amount and timestamps exactly`() {
        create(amount = "1234.5600", occurredAt = BACK_DATED)

		adminFacade.replayWorkspace(workspace)

		val row = operations(workspace).single()
        // Stored signed, so the rebuild has to reproduce the sign, not re-derive it.
		assertEquals(BigDecimal("-1234.5600"), row.amount)
		assertEquals(4, row.amount.scale())
		assertEquals(BACK_DATED, row.occurredAt)
	}

	private fun create(
		workspaceId: UUID = workspace,
		amount: String = "100.0000",
		occurredAt: LocalDateTime = OCCURRED_AT,
        accountId: UUID = seededAccountOf(workspaceId),
        categoryId: UUID? = seededCategoryOf(workspaceId),
    ): UUID = commandFacade.processCommand(
        CreateOperationCommand(
            workspaceId = workspaceId,
            occurredAt = occurredAt,
            amount = BigDecimal(amount),
            accountId = accountId,
            kind = OperationKind.EXPENSE,
            categoryId = categoryId,
            comment = null,
        )
    ).id

	private fun createAccount(
		name: String = "account",
		currency: String = "EUR",
	): UUID = commandFacade.processCommand(CreateAccountCommand(workspaceId = workspace, name = name, currency = currency, icon = null)).id

    private fun createCategory(workspaceId: UUID, parent: UUID, name: String): UUID = commandFacade
        .processCommand(
            CreateCategoryCommand.custom(workspaceId = workspaceId, kind = CategoryKind.EXPENSE, name = name, parentId = parent, icon = null)
        ).id

    private fun expenseRoot(workspaceId: UUID): UUID = jdbc
        .sql("SELECT id FROM t_categories WHERE workspace_id = :ws AND parent_id IS NULL AND kind = 'EXPENSE'")
        .param("ws", workspaceId)
        .query(UUID::class.java)
        .single()

    private fun categories(workspaceId: UUID): List<CategoryRow> = jdbc
        .sql(
            """
			SELECT id, workspace_id, parent_id, name, kind, icon, archived, system_code, external_ref, recorded_at
			FROM t_categories WHERE workspace_id = :ws ORDER BY id
			"""
        )
        .param("ws", workspaceId)
        .query { rs, _ ->
            CategoryRow(
                id = rs.getObject("id", UUID::class.java),
                workspaceId = rs.getObject("workspace_id", UUID::class.java),
                parentId = rs.getObject("parent_id", UUID::class.java),
                name = rs.getString("name"),
                kind = rs.getString("kind"),
                icon = rs.getString("icon"),
                archived = rs.getBoolean("archived"),
				systemCode = rs.getString("system_code"),
                externalRef = rs.getString("external_ref"),
                recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
            )
        }
        .list()
        .filterNot { it.id == fixtures[workspaceId]?.second }

	private fun accounts(workspaceId: UUID): List<AccountRow> = jdbc
		.sql(
			"""
			SELECT id, workspace_id, name, currency, icon, archived, external_ref, recorded_at
			FROM t_accounts WHERE workspace_id = :ws ORDER BY id
			"""
		)
		.param("ws", workspaceId)
		.query { rs, _ ->
			AccountRow(
				id = rs.getObject("id", UUID::class.java),
				workspaceId = rs.getObject("workspace_id", UUID::class.java),
				name = rs.getString("name"),
				currency = rs.getString("currency").trim(),
				icon = rs.getString("icon"),
				archived = rs.getBoolean("archived"),
				externalRef = rs.getString("external_ref"),
				recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
			)
		}
		.list()
        .filterNot { it.id == fixtures[workspaceId]?.first }

	private fun operations(workspaceId: UUID): List<Row> = jdbc
		.sql(
			"""
			SELECT id, workspace_id, amount, kind, account_id, category_id, transfer_id,
			       counterpart_id, comment, external_ref, occurred_at, recorded_at
			FROM t_operations WHERE workspace_id = :ws ORDER BY id
			"""
		)
		.param("ws", workspaceId)
		.query { rs, _ ->
			Row(
				id = rs.getObject("id", UUID::class.java),
				workspaceId = rs.getObject("workspace_id", UUID::class.java),
				amount = rs.getBigDecimal("amount"),
                kind = rs.getString("kind"),
                accountId = rs.getObject("account_id", UUID::class.java),
                categoryId = rs.getObject("category_id", UUID::class.java),
                transferId = rs.getObject("transfer_id", UUID::class.java),
                counterpartId = rs.getObject("counterpart_id", UUID::class.java),
                comment = rs.getString("comment"),
                externalRef = rs.getString("external_ref"),
				occurredAt = rs.getObject("occurred_at", LocalDateTime::class.java),
				recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
			)
		}
		.list()

    private fun anchorRows(workspaceId: UUID): List<AnchorRow> = jdbc
        .sql(
            """
            SELECT id, workspace_id, account_id, value, occurred_at, external_ref, recorded_at
            FROM t_balance_anchors WHERE workspace_id = :ws ORDER BY id
            """
        )
        .param("ws", workspaceId)
        .query { rs, _ ->
            AnchorRow(
                id = rs.getObject("id", UUID::class.java),
                workspaceId = rs.getObject("workspace_id", UUID::class.java),
                accountId = rs.getObject("account_id", UUID::class.java),
                value = rs.getBigDecimal("value"),
                occurredAt = rs.getObject("occurred_at", LocalDateTime::class.java),
                externalRef = rs.getString("external_ref"),
                recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
            )
        }
        .list()

	private fun eventIds(): List<Long> =
		jdbc.sql("SELECT id FROM t_events ORDER BY id").query(Long::class.java).list().filterNotNull()

    private data class AnchorRow(
        val id: UUID,
        val workspaceId: UUID,
        val accountId: UUID,
        val value: BigDecimal,
        val occurredAt: LocalDateTime,
        val externalRef: String?,
        val recordedAt: LocalDateTime,
    )

	private data class AccountRow(
		val id: UUID,
		val workspaceId: UUID,
		val name: String,
		val currency: String,
		val icon: String?,
		val archived: Boolean,
		val externalRef: String?,
		val recordedAt: LocalDateTime,
	)

    private data class CategoryRow(
        val id: UUID,
        val workspaceId: UUID,
        val parentId: UUID?,
        val name: String,
        val kind: String,
        val icon: String?,
        val archived: Boolean,
        val systemCode: String?,
        val externalRef: String?,
        val recordedAt: LocalDateTime,
    )

	private data class Row(
		val id: UUID,
		val workspaceId: UUID,
		val amount: BigDecimal,
        val kind: String,
        val accountId: UUID,
        val categoryId: UUID?,
        val transferId: UUID?,
        val counterpartId: UUID?,
        val comment: String?,
        val externalRef: String?,
		val occurredAt: LocalDateTime,
		val recordedAt: LocalDateTime,
	)

	private companion object {
		val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2026-03-15T14:30:00")
		val BACK_DATED: LocalDateTime = LocalDateTime.parse("2020-01-01T08:00:00")
	}
}
