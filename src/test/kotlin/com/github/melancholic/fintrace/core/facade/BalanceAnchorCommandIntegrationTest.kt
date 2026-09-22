package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CancelBalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.command.CreateBalanceAnchorCommand
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.ValidationError
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The balance anchor aggregate through its real entry point (1.21–1.23).
 *
 * An anchor is an observation: absolute, never back-dated, and the only entity whose projection
 * row is physically removed (§10.4). So what is checked here is that the row is written at all,
 * that deletion takes the right one, that only the latest may go, and that a rebuild reproduces
 * both facts from the log alone.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class BalanceAnchorCommandIntegrationTest(
    @Autowired private val facade: CommandFacade,
    @Autowired private val adminFacade: AdminFacade,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
) {

    private lateinit var workspaceId: UUID
    private lateinit var accountId: UUID
    private lateinit var otherAccountId: UUID

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        accountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "wallet")
        otherAccountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "savings")
    }

    @Test
    fun `writes an anchor and its event`() {
        val anchor = facade.processCommand(create(BigDecimal("1500.0000")))

        assertEquals(1, count("t_events"))
        assertEquals(1, count("t_balance_anchors"))
        assertEquals(BigDecimal("1500.0000"), storedValue(anchor.projection.id))
    }

    @Test
    fun `records the event over the anchor aggregate`() {
        val anchor = facade.processCommand(create())

        val event = events().single()
        assertEquals("BALANCE_ANCHOR", event.entityType)
        assertEquals("CREATED", event.eventType)
        assertEquals(anchor.projection.id, event.entityId)
    }

    @Test
    fun `dates an interactive anchor at the moment of the count`() {
        val anchor = facade.processCommand(create())

        // §4.6: a count happens now. Since decision 6 made the command temporal the caller supplies
        // occurredAt, so the two stamps are separate clock reads rather than one — what still has to
        // hold is that the business date is the present, and that it is populated at all, because
        // the balance query orders by it.
        val (occurredAt, recordedAt) = timestamps(anchor.projection.id)
        assertFalse(occurredAt.isAfter(recordedAt), "the count cannot happen after it was recorded")
        assertTrue(
            Duration.between(occurredAt, recordedAt).abs() < Duration.ofSeconds(1),
            "an interactive anchor is dated now, not at some other time",
        )
    }

    @Test
    fun `rejects an anchor dated in the future`() {
        // §4.6 as amended: a count cannot have happened yet.
        assertFailsWith<ValidationError> {
            facade.processCommand(create(occurredAt = LocalDateTime.now().plusDays(1)))
        }
        assertEquals(0, count("t_balance_anchors"))
    }

    @Test
    fun `rejects an anchor that precedes the account's newest`() {
        facade.processCommand(create(occurredAt = LocalDateTime.now().minusDays(1)))

        // Per-account anchors are append-only in business time — the ordering fn_balance_of and
        // the delete-only-the-head rule (§10.4) both assume. Inserting behind the head would
        // shift every balance after it.
        assertFailsWith<ActionConflictException> {
            facade.processCommand(create(occurredAt = LocalDateTime.now().minusDays(2)))
        }
        assertEquals(1, count("t_balance_anchors"), "the refused anchor wrote nothing")
    }

    @Test
    fun `accepts an anchor at the same instant as the newest`() {
        // The rule is "may not precede", not "must be strictly after" — import can legitimately
        // produce two readings a microsecond apart, and fn_balance_of breaks the tie by id.
        //
        // Truncated on purpose: Postgres stores microseconds and rounds, so a value carrying finer
        // digits comes back as a different instant and the comparison stops being about the rule.
        val sameInstant = LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.MICROS)
        facade.processCommand(create(occurredAt = sameInstant))
        facade.processCommand(create(occurredAt = sameInstant))

        assertEquals(2, count("t_balance_anchors"))
    }

    @Test
    fun `accepts a back-dated anchor on an account that has none`() {
        // Import's opening anchor is dated before the earliest operation (decision 5), so
        // back-dating is only refused relative to an existing anchor, never on its own.
        facade.processCommand(create(occurredAt = LocalDateTime.now().minusYears(3)))

        assertEquals(1, count("t_balance_anchors"))
    }

    @Test
    fun `orders anchors per account, not across them`() {
        facade.processCommand(create(occurredAt = LocalDateTime.now().minusDays(1)))

        // A second account starts its own sequence; the first account's head must not constrain it.
        facade.processCommand(create(accountId = otherAccountId, occurredAt = LocalDateTime.now().minusDays(5)))

        assertEquals(2, count("t_balance_anchors"))
    }

    @Test
    fun `accepts a negative value`() {
        // A balance is an observation, not a magnitude: an overdraft is a real reading.
        val anchor = facade.processCommand(create(BigDecimal("-250.0000")))

        assertEquals(BigDecimal("-250.0000"), storedValue(anchor.projection.id))
    }

    @Test
    fun `accepts zero, which is how a closed account is reconciled`() {
        val anchor = facade.processCommand(create(BigDecimal.ZERO))

        assertEquals(0, storedValue(anchor.projection.id).signum())
    }

    @Test
    fun `rejects an anchor on an archived account`() {
        archive(accountId)

        assertFailsWith<ActionConflictException> { facade.processCommand(create()) }

        assertEquals(0, count("t_events"), "a rejected command writes nothing")
    }

    @Test
    fun `rejects an anchor on an account that does not exist`() {
        assertFailsWith<NotFoundEntityException> {
            facade.processCommand(create(accountId = UUID.randomUUID()))
        }

        assertEquals(0, count("t_events"))
    }

    @Test
    fun `deleting removes the row and keeps the log`() {
        val anchor = facade.processCommand(create())

        facade.processCommand(cancel(anchor.projection.id))

        // §10.4: the sole physical deletion in the system — the row goes, the event stays.
        assertEquals(0, count("t_balance_anchors"))
        assertEquals(2, count("t_events"))
    }

    @Test
    fun `classifies the deletion as a cancellation`() {
        val anchor = facade.processCommand(create())

        facade.processCommand(cancel(anchor.projection.id))

        // Decision 5: delete-anchor maps to CANCELLED. Three event types exist and this is one.
        val deletion = events().last()
        assertEquals("CANCELLED", deletion.eventType)
        assertEquals("BALANCE_ANCHOR", deletion.entityType)
        assertEquals(anchor.projection.id, deletion.entityId)
    }

    @Test
    fun `deleting one anchor leaves the others alone`() {
        val kept = facade.processCommand(create(BigDecimal("10.0000")))
        val doomed = facade.processCommand(create(BigDecimal("20.0000")))

        facade.processCommand(cancel(doomed.projection.id))

        assertEquals(1, count("t_balance_anchors"))
        assertEquals(kept.projection.id, singleAnchorId())
    }

    @Test
    fun `refuses to delete an anchor that is not the most recent`() {
        val older = facade.processCommand(create(BigDecimal("10.0000")))
        facade.processCommand(create(BigDecimal("20.0000")))

        // §10.4: deleting from the middle would shift every balance after it.
        assertFailsWith<ActionConflictException> { facade.processCommand(cancel(older.projection.id)) }

        assertEquals(2, count("t_balance_anchors"))
    }

    @Test
    fun `refuses to delete an anchor belonging to another account`() {
        val anchor = facade.processCommand(create(accountId = otherAccountId))

        // The account is in the path; an anchor reached through the wrong one must not resolve.
        assertFailsWith<NotFoundEntityException> {
            facade.processCommand(cancel(anchor.projection.id, accountId = accountId))
        }

        assertEquals(1, count("t_balance_anchors"))
    }

    @Test
    fun `rejects deleting an unknown anchor`() {
        assertFailsWith<NotFoundEntityException> { facade.processCommand(cancel(UUID.randomUUID())) }

        assertEquals(0, count("t_events"))
    }

    @Test
    fun `replays anchors from the log alone`() {
        facade.processCommand(create(BigDecimal("10.0000")))
        facade.processCommand(create(BigDecimal("20.0000")))
        val before = anchorRows()

        jdbc.sql("DELETE FROM t_balance_anchors").update()
        adminFacade.replayWorkspace(workspaceId)

        assertEquals(before, anchorRows())
    }

    @Test
    fun `a deleted anchor does not come back on replay`() {
        val anchor = facade.processCommand(create())
        facade.processCommand(cancel(anchor.projection.id))

        adminFacade.replayWorkspace(workspaceId)

        // The whole reason the deletion is an event: without it a rebuild resurrects the anchor
        // and every balance after it silently changes.
        assertEquals(0, count("t_balance_anchors"))
    }

    private fun create(
        value: BigDecimal = BigDecimal("100.0000"),
        accountId: UUID = this.accountId,
        occurredAt: LocalDateTime = LocalDateTime.now(),
    ) = CreateBalanceAnchorCommand(
        workspaceId = workspaceId,
        accountId = accountId,
        value = value,
        occurredAt = occurredAt,
    )

    private fun cancel(anchorId: UUID, accountId: UUID = this.accountId) =
        CancelBalanceAnchorCommand(workspaceId = workspaceId, accountId = accountId, anchorId = anchorId)

    private fun archive(id: UUID) = jdbc
        .sql("UPDATE t_accounts SET archived = true WHERE id = :id")
        .param("id", id)
        .update()

    private fun count(table: String) =
        jdbc.sql("SELECT count(*) FROM $table").query(Int::class.java).single()

    private fun storedValue(id: UUID) = jdbc
        .sql("SELECT value FROM t_balance_anchors WHERE id = :id")
        .param("id", id)
        .query(BigDecimal::class.java).single()

    private fun timestamps(id: UUID) = jdbc
        .sql("SELECT occurred_at, recorded_at FROM t_balance_anchors WHERE id = :id")
        .param("id", id)
        .query { rs, _ ->
            rs.getObject("occurred_at", LocalDateTime::class.java) to
                    rs.getObject("recorded_at", LocalDateTime::class.java)
        }
        .single()

    private fun singleAnchorId() = jdbc
        .sql("SELECT id FROM t_balance_anchors")
        .query(UUID::class.java).single()

    private fun anchorRows(): List<String> = jdbc
        .sql("SELECT id, account_id, value, occurred_at FROM t_balance_anchors ORDER BY occurred_at, id")
        .query { rs, _ ->
            listOf(
                rs.getObject("id", UUID::class.java).toString(),
                rs.getObject("account_id", UUID::class.java).toString(),
                rs.getBigDecimal("value").toPlainString(),
                rs.getObject("occurred_at", LocalDateTime::class.java).toString(),
            ).joinToString("|")
        }
        .list()

    private fun events(): List<EventRow> = jdbc
        .sql("SELECT aggregate_type, aggregate_id, event_type FROM t_events ORDER BY id")
        .query { rs, _ ->
            EventRow(
                entityType = rs.getString("aggregate_type"),
                entityId = rs.getObject("aggregate_id", UUID::class.java),
                eventType = rs.getString("event_type"),
            )
        }
        .list()

    private data class EventRow(val entityType: String, val entityId: UUID, val eventType: String)
}
