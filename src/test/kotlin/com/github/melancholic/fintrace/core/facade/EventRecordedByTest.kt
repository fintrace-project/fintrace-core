package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.domain.command.CommandContext
import com.github.melancholic.fintrace.core.domain.command.CreateAccountCommand
import com.github.melancholic.fintrace.core.service.WorkspaceService
import com.github.melancholic.fintrace.core.service.command.CommandDispatcher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.*
import kotlin.test.assertEquals

/**
 * Task 1.27: every event records who issued the command behind it (§4.11), taken from the
 * `CommandContext` passed down the dispatch chain — never from the workspace's owner.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class EventRecordedByTest(
    @Autowired private val commandFacade: CommandFacade,
    @Autowired private val adminFacade: AdminFacade,
    @Autowired private val dispatcher: CommandDispatcher,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val workspaceService: WorkspaceService,
    @Autowired private val transactions: TransactionTemplate,
) {

    private lateinit var caller: UUID
    private lateinit var workspaceId: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        caller = TestWorkspaces.ownerId(usersDAO)
        workspaceId = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO)
    }

    @Test
    fun `every event records the user who issued the command, seeded categories included`() {
        commandFacade.processCommand(cash(initialBalance = BigDecimal("100.0000")))

        val events = events()
        assertEquals(
            setOf("CATEGORY", "ACCOUNT", "BALANCE_ANCHOR"),
            events.map { it.type }.toSet(),
            "sanity: the four seeded categories, the account, and the anchor its initial balance created",
        )
        assertEquals(setOf(caller), events.map { it.recordedBy }.toSet())
    }

    @Test
    fun `records the user in the context, not the workspace owner, through a nested dispatch too`() {
        // Only the owner can reach a workspace through the facade, so a different recorder has to
        // come straight through the dispatcher — which is what proves the value is not the owner's
        val other = insertUser()

        transactions.execute {
            dispatcher.dispatch(cash(initialBalance = BigDecimal("5.0000")), CommandContext(initiator = other))
        }

        val written = events().filter { it.type in setOf("ACCOUNT", "BALANCE_ANCHOR") }
        assertEquals(2, written.size, "sanity: the account and the anchor dispatched from its handler")
        assertEquals(setOf(other), written.map { it.recordedBy }.toSet())
    }

    @Test
    fun `replay leaves every recorder as it was and appends nothing`() {
        commandFacade.processCommand(cash(initialBalance = BigDecimal("100.0000")))
        val before = events()

        adminFacade.replayWorkspace(workspaceId)

        assertEquals(before, events())
    }

    private fun cash(initialBalance: BigDecimal?) = CreateAccountCommand(
        workspaceId = workspaceId,
        name = "cash",
        currency = "EUR",
        icon = null,
        initialBalance = initialBalance,
    )

    private fun insertUser(): UUID = UUID.randomUUID().also { id ->
        jdbc.sql("INSERT INTO t_users (id, external_id, username, created_at) VALUES (:id, :subject, :name, now())")
            .param("id", id)
            .param("subject", "stub:$id")
            .param("name", "user-$id")
            .update()
    }

    private fun events(): List<EventRow> = jdbc
        .sql("SELECT id, aggregate_type, recorded_by FROM t_events WHERE workspace_id = :ws ORDER BY id")
        .param("ws", workspaceId)
        .query { rs, _ ->
            EventRow(rs.getLong("id"), rs.getString("aggregate_type"), rs.getObject("recorded_by", UUID::class.java))
        }
        .list()

    private data class EventRow(val id: Long, val type: String, val recordedBy: UUID)
}
