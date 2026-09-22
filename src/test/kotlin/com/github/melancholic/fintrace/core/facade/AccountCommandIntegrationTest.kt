package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.domain.command.CreateAccountCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseAccountCommand
import com.github.melancholic.fintrace.core.domain.command.SetAccountArchivedCommand
import com.github.melancholic.fintrace.core.domain.event.payload.AccountCreatedV1
import com.github.melancholic.fintrace.core.domain.event.payload.AccountRevisedV1
import com.github.melancholic.fintrace.core.domain.event.payload.EventPayload
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.ValidationError
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account aggregate through its real entry point, against a real Postgres.
 *
 * Accounts are the second event-sourced aggregate, so this is also where the shapes introduced for
 * operations are checked to generalise: a non-temporal payload (an account has no business date),
 * and a command that carries less than the whole entity — revise and archive take the fields they
 * cannot carry from the newest event rather than from the projection (§4.4).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class AccountCommandIntegrationTest(
    @Autowired private val facade: CommandFacade,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
) {

    private lateinit var workspaceId: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
    }

    @Test
    fun `creates an account and its event`() {
        val id = create(name = "cash-eur", currency = "EUR", icon = "wallet")

        assertEquals(1, count("t_accounts"))
        assertEquals(1, count("t_events"))

        val row = account(id)
        assertEquals("cash-eur", row.name)
        assertEquals("EUR", row.currency)
        assertEquals("wallet", row.icon)
        assertEquals(false, row.archived, "a new account is not archived")
    }

    @Test
    fun `classifies the event as an account creation`() {
        val id = create()

        val event = events().single()
        assertEquals("ACCOUNT", event.entityType, "the aggregate type must not be OPERATION")
        assertEquals("CREATED", event.eventType)
        assertEquals(id, event.entityId)
    }

    @Test
    fun `stores an account payload that reads back as an account`() {
        create()

        // The payload discriminators are what a rebuild dispatches on; a collision with the
        // operation types would silently turn accounts into operations here.
        val payload = payload(events().single().payload)
        assertTrue(payload is AccountCreatedV1, "was ${payload::class.simpleName}")
    }

    @Test
    fun `an account event carries no business date`() {
        create()

        // occurred_at is NOT NULL, so a non-temporal payload falls back to recorded_at rather
        // than inventing a date the domain does not have.
        val event = events().single()
        assertEquals(event.recordedAt, event.occurredAt)
    }

    @Test
    fun `renames an account in place`() {
        val id = create(name = "before", icon = "old")

        facade.processCommand(ReviseAccountCommand(workspaceId, id, name = "after", icon = "new"))

        assertEquals(1, count("t_accounts"), "a revision replaces rather than adds")
        assertEquals(2, count("t_events"))
        assertEquals("after", account(id).name)
        assertEquals("new", account(id).icon)
    }

    @Test
    fun `a revision carries the immutable currency forward`() {
        val id = create(currency = "CZK")

        facade.processCommand(ReviseAccountCommand(workspaceId, id, name = "renamed", icon = null))

        // The command has no currency field (§4.8); the handler copies it from the newest event,
        // so losing it here would mean the copy-forward is broken.
        assertEquals("CZK", account(id).currency)
        assertEquals("CZK", (payload(events().last().payload) as AccountRevisedV1).currency)
    }

    @Test
    fun `a revision leaves the archived flag alone`() {
        val id = create()
        facade.processCommand(SetAccountArchivedCommand(workspaceId, id, archived = true))

        facade.processCommand(ReviseAccountCommand(workspaceId, id, name = "renamed", icon = null))

        assertTrue(account(id).archived, "renaming must not resurrect an archived account")
    }

    @Test
    fun `a revision can clear the icon`() {
        val id = create(icon = "wallet")

        facade.processCommand(ReviseAccountCommand(workspaceId, id, name = "cash", icon = null))

        // Null means "no icon" rather than "unchanged": the body is the complete new state.
        assertNull(account(id).icon)
    }

    @Test
    fun `archives and unarchives an account`() {
        val id = create()

        facade.processCommand(SetAccountArchivedCommand(workspaceId, id, archived = true))
        assertTrue(account(id).archived)

        facade.processCommand(SetAccountArchivedCommand(workspaceId, id, archived = false))
        assertEquals(false, account(id).archived, "archiving is reversible (§4.8)")
    }

    @Test
    fun `archiving keeps the name and currency`() {
        val id = create(name = "savings", currency = "GBP", icon = "piggy")

        facade.processCommand(SetAccountArchivedCommand(workspaceId, id, archived = true))

        val row = account(id)
        assertEquals("savings", row.name)
        assertEquals("GBP", row.currency)
        assertEquals("piggy", row.icon)
    }

    @Test
    fun `archiving is recorded as a revision, never as a cancellation`() {
        val id = create()

        facade.processCommand(SetAccountArchivedCommand(workspaceId, id, archived = true))

        // CANCELLED means end of life; an account is never deleted (§4.8).
        assertEquals("REVISED", events().last().eventType)
    }

    @Test
    fun `archiving twice is a no-op`() {
        val id = create()
        facade.processCommand(SetAccountArchivedCommand(workspaceId, id, archived = true))

        facade.processCommand(SetAccountArchivedCommand(workspaceId, id, archived = true))

        assertTrue(account(id).archived)
    }

    @Test
    fun `rejects a revision of an unknown account, writing nothing`() {
        assertFailsWith<NotFoundEntityException> {
            facade.processCommand(ReviseAccountCommand(workspaceId, UUID.randomUUID(), "renamed", null))
        }

        assertEquals(0, count("t_events"))
        assertEquals(0, count("t_accounts"))
    }

    @Test
    fun `rejects an account belonging to another workspace`() {
        val id = create()
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")

        assertFailsWith<NotFoundEntityException> {
            facade.processCommand(ReviseAccountCommand(other, id, "stolen", null))
        }

        assertEquals("account", account(id).name)
    }

    @Test
    fun `rejects an unknown currency before anything is written`() {
        assertFailsWith<ValidationError> {
            facade.processCommand(CreateAccountCommand(workspaceId = workspaceId, name = "cash", currency = "ZZZ", icon = null))
        }

        assertEquals(0, count("t_events"), "validation runs before the event is appended (§4.10)")
    }

    @Test
    fun `isolates accounts by workspace`() {
        val other = TestWorkspaces.create(workspaceDAO, usersDAO, name = "other-workspace")
        create()
        facade.processCommand(CreateAccountCommand(workspaceId = other, name = "theirs", currency = "USD", icon = null))

        assertEquals(1, countIn(workspaceId))
        assertEquals(1, countIn(other))
    }

    // ------------------------------------------------------------------ initial balance (1.9)

    @Test
    fun `an initial balance becomes the account's first anchor`() {
        val id = create(initialBalance = BigDecimal("1500.0000"))

        // §4.6: an initial balance is an observation, not a column on the account.
        assertEquals(1, count("t_balance_anchors"))
        val anchor = anchorRow()
        assertEquals(id, anchor.first, "the anchor belongs to the account just created")
        assertEquals(BigDecimal("1500.0000"), anchor.second)
    }

    @Test
    fun `creating an account with an initial balance writes two events of different types`() {
        create(initialBalance = BigDecimal("100.0000"))

        // The first command in the system to produce two aggregate types at once.
        val types = events().map { it.entityType to it.eventType }
        assertEquals(listOf("ACCOUNT" to "CREATED", "BALANCE_ANCHOR" to "CREATED"), types)
    }

    @Test
    fun `an account created without an initial balance has no anchor`() {
        create()

        // Omitted is not zero: "I haven't counted" and "I counted, it's empty" differ, and the
        // second would suppress every earlier operation's effect on the balance.
        assertEquals(0, count("t_balance_anchors"))
        assertEquals(1, count("t_events"))
    }

    @Test
    fun `an explicit zero initial balance is an anchor`() {
        create(initialBalance = BigDecimal.ZERO)

        assertEquals(1, count("t_balance_anchors"))
        assertEquals(0, anchorRow().second.signum())
    }

    @Test
    fun `a negative initial balance is accepted`() {
        create(initialBalance = BigDecimal("-42.0000"))

        assertEquals(BigDecimal("-42.0000"), anchorRow().second)
    }

    @Test
    fun `the initial balance anchor is rejected with the account when the command fails`() {
        assertFailsWith<ValidationError> {
            facade.processCommand(CreateAccountCommand(workspaceId = workspaceId, name = "cash", currency = "ZZZ", icon = null, initialBalance = BigDecimal.TEN))
        }

        // One transaction: an invalid account must not leave an anchor behind.
        assertEquals(0, count("t_events"))
        assertEquals(0, count("t_balance_anchors"))
        assertEquals(0, count("t_accounts"))
    }

    private fun anchorRow(): Pair<UUID, BigDecimal> = jdbc
        .sql("SELECT account_id, value FROM t_balance_anchors")
        .query { rs, _ ->
            rs.getObject("account_id", UUID::class.java) to rs.getBigDecimal("value")
        }
        .single()

    private fun create(
        workspaceId: UUID = this.workspaceId,
        name: String = "account",
        currency: String = "EUR",
        icon: String? = null,
        initialBalance: BigDecimal? = null,
    ): UUID = facade.processCommand(CreateAccountCommand(workspaceId = workspaceId, name = name, currency = currency, icon = icon, initialBalance = initialBalance)).id

    private fun count(table: String) =
        jdbc.sql("SELECT count(*) FROM $table").query(Int::class.java).single()

    private fun countIn(workspaceId: UUID) = jdbc
        .sql("SELECT count(*) FROM t_accounts WHERE workspace_id = :ws")
        .param("ws", workspaceId)
        .query(Int::class.java).single()

    private fun payload(json: String): EventPayload = mapper.readValue(json, EventPayload::class.java)

    private fun account(id: UUID): AccountRow = jdbc
        .sql("SELECT name, currency, icon, archived, recorded_at FROM t_accounts WHERE id = :id")
        .param("id", id)
        .query { rs, _ ->
            AccountRow(
                name = rs.getString("name"),
                currency = rs.getString("currency").trim(),
                icon = rs.getString("icon"),
                archived = rs.getBoolean("archived"),
                recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
            )
        }
        .single()

    private fun events(): List<EventRow> = jdbc
        .sql(
            """
			SELECT aggregate_type, aggregate_id, event_type, payload, occurred_at, recorded_at
			FROM t_events ORDER BY id
			"""
        )
        .query { rs, _ ->
            EventRow(
                entityType = rs.getString("aggregate_type"),
                entityId = rs.getObject("aggregate_id", UUID::class.java),
                eventType = rs.getString("event_type"),
                payload = rs.getString("payload"),
                occurredAt = rs.getObject("occurred_at", LocalDateTime::class.java),
                recordedAt = rs.getObject("recorded_at", LocalDateTime::class.java),
            )
        }
        .list()

    private data class AccountRow(
        val name: String,
        val currency: String,
        val icon: String?,
        val archived: Boolean,
        val recordedAt: LocalDateTime,
    )

    private data class EventRow(
        val entityType: String,
        val entityId: UUID,
        val eventType: String,
        val payload: String,
        val occurredAt: LocalDateTime,
        val recordedAt: LocalDateTime,
    )
}
