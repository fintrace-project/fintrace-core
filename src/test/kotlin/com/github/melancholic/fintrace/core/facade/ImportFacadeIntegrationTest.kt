package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.api.v1.dto.*
import com.github.melancholic.fintrace.core.config.WorkspaceImportConstants.IMPORT_LEASE_TIME
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.CreateAccountCommand
import com.github.melancholic.fintrace.core.domain.entity.CategoryKind
import com.github.melancholic.fintrace.core.domain.entity.ImportJobStatistics
import com.github.melancholic.fintrace.core.domain.entity.ImportJobStatus
import com.github.melancholic.fintrace.core.domain.entity.ImporterDetails
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.entity.WorkspaceStatus
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.OperationNotAllowedException
import com.github.melancholic.fintrace.core.service.ImportLifecycleService
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The import endpoint (2.18–2.22), asserted through the facade so the three transactions of
 * decision 10 are real ones.
 *
 * The ids come from the payload rather than from Core (§5.1, reversed at M2), so every assertion
 * here can name the row it expects instead of searching for it.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class ImportFacadeIntegrationTest(
    @Autowired private val importFacade: ImportFacade,
    @Autowired private val commandFacade: CommandFacade,
    @Autowired private val workspaceFacade: WorkspaceFacade,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceService: WorkspaceService,
    @Autowired private val importLifecycleService: ImportLifecycleService,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val transactions: TransactionTemplate,
) {

    private lateinit var workspaceId: UUID

    @BeforeEach
    fun seed() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO)
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `imports every section and activates the workspace`() {
        val job = importFacade.importWorkspaceData(workspaceId, fullPayload())

        assertEquals(ImportJobStatus.SUCCEEDED, job.status)
        assertEquals(2, job.accounts)
        assertEquals(1, job.categories)
        assertEquals(1, job.operations)
        assertEquals(1, job.transfers)
        assertEquals(1, job.anchors)
        assertNotNull(job.finishedAt)

        assertEquals("ACTIVE", status(), "a successful import closes import for this workspace")
        assertEquals(2, count("t_accounts"))
        assertEquals(3, count("t_operations"), "one operation plus the transfer's two legs")
        // The four seeded system categories plus the imported one
        assertEquals(5, count("t_categories"))
    }

    @Test
    fun `records the job with the importer that produced it`() {
        val job = importFacade.importWorkspaceData(workspaceId, fullPayload())

        assertEquals(IMPORTER_NAME, job.importerName)
        assertEquals(IMPORTER_VERSION, job.importerVersion)
        assertEquals(workspaceId, job.workspaceId)
    }

    // ---------------------------------------------------------------- identity

    @Test
    fun `uses the ids the importer supplied, and keeps the source reference`() {
        importFacade.importWorkspaceData(workspaceId, fullPayload())

        val row = jdbc.sql("SELECT external_ref FROM t_accounts WHERE id = :id AND workspace_id = :ws")
            .param("id", CASH).param("ws", workspaceId)
            .query(String::class.java).single()

        assertEquals("mok-account:1", row, "the payload's id is the row's id, and the ref survives")
    }

    // ---------------------------------------------------------------- the balance trap

    @Test
    fun `dates the opening anchor from the payload, not from the clock`() {
        // An anchor dated `now` sits after every imported operation and suppresses them from the
        // balance entirely (§4.6, decision 5) — silently, with plausible figures.
        importFacade.importWorkspaceData(workspaceId, fullPayload())

        val occurredAt = jdbc.sql(
            "SELECT occurred_at FROM t_balance_anchors WHERE account_id = :id AND workspace_id = :ws ORDER BY occurred_at LIMIT 1"
        ).param("id", CASH).param("ws", workspaceId)
            .query(LocalDateTime::class.java).single()

        assertEquals(OPENED_AT, occurredAt)
        assertTrue(occurredAt.isBefore(OCCURRED_AT), "the opening anchor must precede the operations it explains")
    }

    // ---------------------------------------------------------------- archiving

    @Test
    fun `archives an account only after its anchors have landed`() {
        // Decision 7: an anchor on an archived account is refused, so archiving must come last —
        // an archived account carrying an anchor is exactly the case that breaks a naive order.
        val job = importFacade.importWorkspaceData(workspaceId, payloadWithArchivedAccount())

        assertEquals(ImportJobStatus.SUCCEEDED, job.status)
        assertTrue(accountDAO.getById(workspaceId, CARD).archived, "the account ends archived")
        assertEquals(
            2,
            count("t_balance_anchors WHERE account_id = '$CARD'"),
            "both its opening balance and its own anchor were written before it was archived",
        )
    }

    // ---------------------------------------------------------------- failure

    @Test
    fun `a failed import leaves a genuinely empty NEW workspace and a FAILED job`() {
        // §4.2 and 2.22: the repair for a failed import is to retry it, which only works if
        // nothing survives. The job row must survive anyway — that is decision 10's whole point.
        assertThrows<Exception> { importFacade.importWorkspaceData(workspaceId, payloadWithDanglingAccount()) }

        assertEquals("NEW", status(), "the workspace must still be importable")
        assertEquals(0, count("t_accounts"))
        assertEquals(0, count("t_operations"))
        assertEquals(0, count("t_balance_anchors"))

        val job = jobRow()
        assertEquals("FAILED", job.first)
        assertNotNull(job.second, "a failed import records when it finished")
    }

    @Test
    fun `the events of a failed import are rolled back`() {
        val before = count("t_events")

        assertThrows<Exception> { importFacade.importWorkspaceData(workspaceId, payloadWithDanglingAccount()) }

        assertEquals(before, count("t_events"), "an import that fails appends nothing to the log")
    }

    // ---------------------------------------------------------------- guards

    @Test
    fun `refuses a workspace that is no longer NEW`() {
        commandFacade.processCommand(
            CreateAccountCommand(workspaceId = workspaceId, name = "manual", currency = "EUR", icon = null)
        )

        assertThrows<OperationNotAllowedException> {
            importFacade.importWorkspaceData(workspaceId, fullPayload())
        }
    }

    @Test
    fun `refuses a NEW workspace that already holds data`() {
        // Status alone is not enough: rows can reach a NEW workspace without activating it.
        TestWorkspaces.seedAccount(accountDAO, workspaceId)

        assertThrows<OperationNotAllowedException> {
            importFacade.importWorkspaceData(workspaceId, fullPayload())
        }
    }

    @Test
    fun `refuses a second import once the first has activated the workspace`() {
        importFacade.importWorkspaceData(workspaceId, fullPayload())

        assertThrows<OperationNotAllowedException> {
            importFacade.importWorkspaceData(workspaceId, fullPayload())
        }
        assertFalse(count("t_accounts") > 2, "the refused import wrote nothing")
    }

    // ---------------------------------------------------------------- concurrent access

    /**
     * These drive the guard rather than two real threads. The mutex is a single-statement
     * compare-and-swap (`changeStatus` from `NEW`), so a second caller arriving mid-import sees
     * exactly what a sequential second call sees — `IMPORTING` — and threads would only add
     * flakiness to the same assertion.
     */

    @Test
    fun `refuses a manual command while an import is in flight`() {
        beginImport()

        // The whole reason §4.1.1 gained the status: a command landing mid-import made §4.2's
        // no-overlap property false, and left a workspace a failed import could not retry into.
        assertThrows<OperationNotAllowedException> {
            commandFacade.processCommand(
                CreateAccountCommand(workspaceId = workspaceId, name = "manual", currency = "EUR", icon = null)
            )
        }
        assertEquals(0, count("t_accounts"), "the refused command wrote nothing")
    }

    @Test
    fun `refuses a second import while the first is in flight`() {
        beginImport()

        assertThrows<OperationNotAllowedException> { importFacade.importWorkspaceData(workspaceId, fullPayload()) }
        assertEquals("IMPORTING", status(), "the running import keeps the workspace")
    }

    @Test
    fun `only one of two callers takes the workspace`() {
        beginImport()

        // The second caller's compare-and-swap finds no row in NEW and must refuse rather than
        // join the import already under way.
        assertThrows<OperationNotAllowedException> { beginImport() }
    }

    @Test
    fun `still serves reads while an import is in flight`() {
        beginImport()

        // IMPORTING is readable on purpose — §7.6's progress polling has to see the workspace.
        assertEquals(WorkspaceStatus.IMPORTING, workspaceFacade.getWorkspace(workspaceId).status)
    }

    @Test
    fun `still allows deleting a workspace left in flight`() {
        beginImport()

        // TO_DELETE includes IMPORTING so a stranded workspace is never a dead end, whatever
        // happens to the lease.
        workspaceFacade.deleteWorkspace(workspaceId, version())

        assertEquals("DELETED", status())
    }

    @Test
    fun `a failed import hands the workspace back`() {
        assertThrows<Exception> { importFacade.importWorkspaceData(workspaceId, payloadWithDanglingAccount()) }

        // The repair is what makes the retry in §4.2 real; without it the workspace stays
        // IMPORTING and only the lease could release it.
        assertEquals("NEW", status())
        assertEquals(null, importStartedAt(), "the lease is released with the status")

        // And the retry actually works.
        val job = importFacade.importWorkspaceData(workspaceId, fullPayload())
        assertEquals(ImportJobStatus.SUCCEEDED, job.status)
    }

    // ---------------------------------------------------------------- abandoned imports

    @Test
    fun `releases a workspace whose import lease expired`() {
        val jobId = beginImport()
        expireLease()

        val workspace = transactions.execute { workspaceService.requireWritable(ownerId(), workspaceId) }!!

        assertEquals(WorkspaceStatus.NEW, workspace.status)
        assertEquals(null, importStartedAt(), "the lease is cleared with the status")
        assertEquals(ImportJobStatus.FAILED.name, jobStatus(jobId), "the sweep reconciles the job row too")
    }

    @Test
    fun `leaves a live import lease alone`() {
        val jobId = beginImport()

        assertThrows<OperationNotAllowedException> {
            transactions.execute { workspaceService.requireWritable(ownerId(), workspaceId) }
        }
        assertEquals("IMPORTING", status())
        assertEquals(ImportJobStatus.RUNNING.name, jobStatus(jobId))
    }

    @Test
    fun `the sweep releases a workspace nobody is touching`() {
        val jobId = beginImport()
        expireLease()

        val released = transactions.execute { importLifecycleService.recoverAbandoned(10) }!!

        assertEquals(listOf(workspaceId), released)
        assertEquals("NEW", status())
        assertEquals(ImportJobStatus.FAILED.name, jobStatus(jobId))
    }

    @Test
    fun `the sweep passes over a live import`() {
        beginImport()

        assertTrue(transactions.execute { importLifecycleService.recoverAbandoned(10) }!!.isEmpty())
        assertEquals("IMPORTING", status())
    }

    @Test
    fun `an import that lost its lease cannot commit`() {
        val jobId = beginImport()
        expireLease()
        transactions.execute { importLifecycleService.recoverAbandoned(10) }

        assertThrows<ActionConflictException> {
            transactions.execute {
                importLifecycleService.succeed(ownerId(), workspaceId, jobId, NO_COUNTS, emptyList())
            }
        }
        assertEquals("NEW", status(), "the workspace stays released")
    }

    @Test
    fun `retries an import after its lease expired`() {
        beginImport()
        expireLease()

        val job = importFacade.importWorkspaceData(workspaceId, fullPayload())

        assertEquals(ImportJobStatus.SUCCEEDED, job.status)
        assertEquals("ACTIVE", status())
        assertEquals(2, count("t_import_jobs"), "the abandoned job is kept beside the one that worked")
    }

    // ---------------------------------------------------------------- fixtures

    /** Takes the workspace the way an import does, without running one. */
    private fun beginImport(): UUID = importLifecycleService.begin(
        userId = TestWorkspaces.ownerId(usersDAO),
        workspaceId = workspaceId,
        importer = ImporterDetails(name = IMPORTER_NAME, version = IMPORTER_VERSION)
    )

    private fun ownerId(): UUID = TestWorkspaces.ownerId(usersDAO)

    private fun expireLease() = jdbc.sql("UPDATE t_workspaces SET import_started_at = :startedAt WHERE id = :id")
        .param("id", workspaceId)
        .param("startedAt", LocalDateTime.now().minusSeconds(IMPORT_LEASE_TIME * 2))
        .update()

    private fun jobStatus(jobId: UUID): String = jdbc.sql("SELECT status FROM t_import_jobs WHERE id = :id")
        .param("id", jobId).query(String::class.java).single()

    private fun version(): Long = jdbc.sql("SELECT version FROM t_workspaces WHERE id = :id")
        .param("id", workspaceId).query(Long::class.java).single()

    private fun importStartedAt(): LocalDateTime? = jdbc
        .sql("SELECT import_started_at FROM t_workspaces WHERE id = :id")
        .param("id", workspaceId)
        .query(LocalDateTime::class.java)
        .optional()
        .orElse(null)

    private fun fullPayload() = envelope(
        ImportPayloadRequest(
            accounts = listOf(
                ImportAccountRequest(
                    id = CASH,
                    externalRef = "mok-account:1",
                    name = "Мои деньги",
                    currency = "EUR",
                    icon = null,
                    initialBalance = BigDecimal("100.0000"),
                    initialBalanceAt = OPENED_AT,
                ),
                ImportAccountRequest(
                    id = CARD,
                    externalRef = "mok-account:2",
                    name = "Наличные EUR",
                    currency = "EUR",
                    icon = null,
                    initialBalance = null,
                    initialBalanceAt = null,
                ),
            ),
            categories = listOf(
                ImportCategoryRequest(
                    id = FOOD,
                    externalRef = "mok-category:7",
                    name = "Еда вне дома",
                    kind = CategoryKind.EXPENSE,
                    parentId = null,
                    icon = null,
                ),
            ),
            operations = listOf(
                ImportOperationRequest(
                    id = UUID.randomUUID(),
                    externalRef = "mok-op:11",
                    occurredAt = OCCURRED_AT,
                    amount = BigDecimal("42.0000"),
                    kind = OperationKind.EXPENSE,
                    accountId = CASH,
                    categoryId = FOOD,
                    comment = "lunch",
                ),
            ),
            transfers = listOf(
                ImportTransferRequest(
                    id = UUID.randomUUID(),
                    externalRef = "mok-transfer:3",
                    occurredAt = OCCURRED_AT,
                    source = ImportTransferLegRequest(accountId = CASH, amount = BigDecimal("30.0000")),
                    target = ImportTransferLegRequest(accountId = CARD, amount = BigDecimal("30.0000")),
                    comment = null,
                ),
            ),
            balanceAnchors = listOf(
                ImportBalanceAnchorRequest(
                    id = UUID.randomUUID(),
                    externalRef = "mok-account:1",
                    accountId = CASH,
                    occurredAt = OCCURRED_AT.plusDays(1),
                    value = BigDecimal("28.0000"),
                ),
            ),
        )
    )

    private fun payloadWithArchivedAccount() = envelope(
        ImportPayloadRequest(
            accounts = listOf(
                ImportAccountRequest(
                    id = CARD,
                    externalRef = "mok-account:2",
                    name = "closed",
                    currency = "EUR",
                    icon = null,
                    archived = true,
                    initialBalance = BigDecimal("10.0000"),
                    initialBalanceAt = OPENED_AT,
                ),
            ),
            balanceAnchors = listOf(
                ImportBalanceAnchorRequest(
                    id = UUID.randomUUID(),
                    externalRef = null,
                    accountId = CARD,
                    occurredAt = OCCURRED_AT,
                    value = BigDecimal.ZERO,
                ),
            ),
        )
    )

    /** An operation pointing at an account no section defines — 2.21's hard failure. */
    private fun payloadWithDanglingAccount() = envelope(
        ImportPayloadRequest(
            accounts = listOf(
                ImportAccountRequest(
                    id = CASH,
                    externalRef = null,
                    name = "cash",
                    currency = "EUR",
                    icon = null,
                    initialBalance = null,
                    initialBalanceAt = null,
                ),
            ),
            operations = listOf(
                ImportOperationRequest(
                    id = UUID.randomUUID(),
                    externalRef = null,
                    occurredAt = OCCURRED_AT,
                    amount = BigDecimal("1.0000"),
                    kind = OperationKind.EXPENSE,
                    accountId = UUID.randomUUID(),
                    categoryId = null,
                    comment = null,
                ),
            ),
        )
    )

    private fun envelope(payload: ImportPayloadRequest) =
        ImportEnvelopRequest(importerName = IMPORTER_NAME, importerVersion = IMPORTER_VERSION, payload = payload)

    private fun status(): String = jdbc.sql("SELECT status FROM t_workspaces WHERE id = :id")
        .param("id", workspaceId).query(String::class.java).single()

    private fun count(table: String): Int =
        jdbc.sql("SELECT count(*) FROM $table").query(Int::class.java).single()

    private fun jobRow(): Pair<String, LocalDateTime?> = jdbc.sql(
        "SELECT status, finished_at FROM t_import_jobs WHERE workspace_id = :ws"
    ).param("ws", workspaceId)
        .query { rs, _ -> rs.getString("status") to rs.getObject("finished_at", LocalDateTime::class.java) }
        .single()

    private companion object {
        const val IMPORTER_NAME = "mok"
        const val IMPORTER_VERSION = "1.2.3"

        val NO_COUNTS = ImportJobStatistics(accounts = 0, categories = 0, operations = 0, transfers = 0, anchors = 0)

        val CASH: UUID = UUID.fromString("01930000-0000-7000-8000-00000000cca1")
        val CARD: UUID = UUID.fromString("01930000-0000-7000-8000-00000000cca2")
        val FOOD: UUID = UUID.fromString("01930000-0000-7000-8000-0000000000f0")
        val OPENED_AT: LocalDateTime = LocalDateTime.parse("2023-01-01T09:00:00")
        val OCCURRED_AT: LocalDateTime = LocalDateTime.parse("2025-03-10T12:00:00")
    }
}
