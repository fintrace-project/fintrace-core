package com.github.melancholic.fintrace.core.jobs

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.projection.BalanceAnchorProjection
import com.github.melancholic.fintrace.core.service.WorkspaceRetentionService
import com.github.melancholic.fintrace.core.service.WorkspaceService
import com.github.melancholic.fintrace.core.util.TimestampProvider
import org.junit.jupiter.api.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.5b: a workspace `DELETED` for longer than the window is hard-deleted, and everything it
 * owned goes with it through `ON DELETE CASCADE`. `deleted_at` is back-dated by SQL, so the test
 * does not wait for the window to pass.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class WorkspacesCleanBackgroundJobTest(
    @Autowired private val job: WorkspacesCleanBackgroundJob,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val workspaceService: WorkspaceService,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val categoryDAO: CategoryProjectionDAO,
    @Autowired private val operationDAO: OperationProjectionDAO,
    @Autowired private val anchorDAO: BalanceAnchorProjectionDAO,
    @Value("\${jobs.cleanWorkspacesBackgroundJob.params.persistPeriod}") private val window: Duration,
    @Autowired private val timestampProvider: TimestampProvider,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val retentionService: WorkspaceRetentionService,
) {

    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(WorkspacesCleanBackgroundJob::class.java) as Logger

    @BeforeEach
    fun attach() {
        TestWorkspaces.reset(jdbc)
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    @Test
    fun `purges every row of a workspace deleted longer ago than the window`() {
        val expired = populatedWorkspace("expired")
        markDeleted(expired, at = expiredAt())
        assertTrue(TABLES.all { rowsIn(it, expired) > 0 }, "sanity: every table holds rows for the workspace")

        job.execute()

        assertEquals(TABLES.associateWith { 0 }, TABLES.associateWith { rowsIn(it, expired) })
        assertEquals(0, workspaceRows(expired))
    }

    @Test
    fun `keeps a workspace deleted more recently than the window`() {
        val recent = populatedWorkspace("recent")
        markDeleted(recent, at = LocalDateTime.now().minus(window).plusDays(1))
        val before = TABLES.associateWith { rowsIn(it, recent) }

        job.execute()

        assertEquals(1, workspaceRows(recent))
        assertEquals(before, TABLES.associateWith { rowsIn(it, recent) })
    }

    @Test
    fun `never purges a workspace that is not deleted`() {
        val alive = listOf("NEW", "ACTIVE", "ARCHIVED").associateWith { status ->
            TestWorkspaces.create(workspaceDAO, usersDAO, name = status.lowercase()).also {
                jdbc.sql("UPDATE t_workspaces SET status = :status, created_at = :old, updated_at = :old WHERE id = :id")
                    .param("status", status)
                    .param("old", expiredAt())
                    .param("id", it)
                    .update()
            }
        }

        job.execute()

        assertEquals(alive.mapValues { 1 }, alive.mapValues { workspaceRows(it.value) })
    }

    @Test
    fun `purges past a single batch`() {
        // One more than the DAO's default page, so the job has to fetch again
        val expired = (1..101).map { TestWorkspaces.create(workspaceDAO, usersDAO, name = "expired-$it") }
        expired.forEach { markDeleted(it, at = expiredAt()) }

        job.execute()

        assertEquals(0, jdbc.sql("SELECT count(*) FROM t_workspaces").query(Int::class.java).single())
    }

    @Test
    fun `logs each purge with the rows it removed`() {
        val expired = populatedWorkspace("expired")
        markDeleted(expired, at = expiredAt())
        val counts = TABLES.associateWith { rowsIn(it, expired) }

        job.execute()

        val line = appender.list.map { it.formattedMessage }.single { expired.toString() in it && "Purged" in it }
        assertTrue("events=${counts.getValue("t_events")}" in line, line)
        assertTrue("categories=${counts.getValue("t_categories")}" in line, line)
        assertTrue("operations=${counts.getValue("t_operations")}" in line, line)
        assertTrue("accounts=${counts.getValue("t_accounts")}" in line, line)
        assertTrue("anchors=${counts.getValue("t_balance_anchors")}" in line, line)
    }

    @Test
    fun `refuses a window shorter than a day`() {
        // A zero window would purge every deleted workspace on the next tick — the mistake the window exists to prevent
        for (tooShort in listOf(Duration.ZERO, Duration.ofDays(-30), Duration.ofHours(12))) {
            assertThrows<IllegalArgumentException>(tooShort.toString()) { jobWith(tooShort) }
        }
        assertDoesNotThrow { jobWith(Duration.ofDays(1)) }
    }

    private fun jobWith(window: Duration) =
        WorkspacesCleanBackgroundJob(retentionService, window, timestampProvider, transactionManager)

    // Rows in every table the cascade has to reach: category events and categories from the seed, plus an account, a transfer and an anchor
    private fun populatedWorkspace(name: String): UUID {
        val id = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO, name = name)
        val source = TestWorkspaces.seedAccount(accountDAO, id, name = "source")
        val target = TestWorkspaces.seedAccount(accountDAO, id, name = "target")
        TestWorkspaces.seedCategory(categoryDAO, id)
        TestWorkspaces.seedTransferPair(operationDAO, id, source, target)
        anchorDAO.createOrUpdate(
            BalanceAnchorProjection(
                id = UUID.randomUUID(),
                workspaceId = id,
                accountId = source,
                value = BigDecimal("10.0000"),
                occurredAt = LocalDateTime.now(),
                recordedAt = LocalDateTime.now(),
            )
        )
        return id
    }

    private fun markDeleted(workspaceId: UUID, at: LocalDateTime) =
        jdbc.sql("UPDATE t_workspaces SET status = 'DELETED', deleted_at = :at WHERE id = :id")
            .param("at", at)
            .param("id", workspaceId)
            .update()

    private fun expiredAt(): LocalDateTime = LocalDateTime.now().minus(window).minusDays(1)

    private fun workspaceRows(workspaceId: UUID) =
        jdbc.sql("SELECT count(*) FROM t_workspaces WHERE id = :id").param("id", workspaceId).query(Int::class.java)
            .single()

    private fun rowsIn(table: String, workspaceId: UUID) =
        jdbc.sql("SELECT count(*) FROM $table WHERE workspace_id = :id").param("id", workspaceId).query(Int::class.java)
            .single()

    companion object {
        private val TABLES = listOf("t_events", "t_accounts", "t_categories", "t_operations", "t_balance_anchors")
    }
}
