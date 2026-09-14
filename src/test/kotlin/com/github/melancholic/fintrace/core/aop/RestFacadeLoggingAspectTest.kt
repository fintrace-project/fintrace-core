package com.github.melancholic.fintrace.core.aop

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.exception.BrokenTransferException
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.facade.AdminFacade
import com.github.melancholic.fintrace.core.facade.ProjectionFacade
import com.github.melancholic.fintrace.core.facade.WorkspaceFacade
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.WithMockUser
import java.util.UUID

/**
 * The facade log as the application produces it. `RestFacade` is sealed, so the calls go through
 * the real facades rather than a test double.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@WithMockUser(username = TestWorkspaces.TEST_SUBJECT)
class RestFacadeLoggingAspectTest(
    @Autowired private val workspaceFacade: WorkspaceFacade,
    @Autowired private val projectionFacade: ProjectionFacade,
    @Autowired private val adminFacade: AdminFacade,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceDAO: WorkspaceDAO,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val accountDAO: AccountProjectionDAO,
    @Autowired private val operationDAO: OperationProjectionDAO,
) {

    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(RestFacadeLoggingAspect::class.java) as Logger

    @BeforeEach
    fun attach() {
        TestWorkspaces.reset(jdbc)
        appender.start()
        // Reads log at DEBUG, so the test must not depend on the configured level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        logger.level = null
    }

    @Test
    fun `logs a write as IN then OUT at INFO, naming argument types and never their values`() {
        workspaceFacade.createWorkspace(CreateWorkspaceRequest("secret-budget", "EUR"))

        val messages = appender.list.map { it.formattedMessage }
        assertEquals(2, messages.size, messages.toString())
        assertTrue(appender.list.all { it.level == Level.INFO })
        assertEquals("[IN] WorkspaceFacadeImpl.createWorkspace(CreateWorkspaceRequest)", messages[0])
        assertTrue(messages[1].startsWith("[OUT] WorkspaceFacadeImpl.createWorkspace(CreateWorkspaceRequest): "), messages[1])
        assertTrue(messages.none { "secret-budget" in it }, messages.toString())
    }

    @Test
    fun `lets a method returning Unit complete`() {
        val workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)

        adminFacade.replayWorkspace(workspaceId)

        val messages = appender.list.map { it.formattedMessage }
        assertEquals(2, messages.size, messages.toString())
        assertTrue(appender.list.all { it.level == Level.INFO })
        assertTrue(messages[0].startsWith("[IN] AdminFacadeImpl.replayWorkspace(UUID)"), messages[0])
        assertTrue(messages[1].startsWith("[OUT] AdminFacadeImpl.replayWorkspace(UUID): "), messages[1])
    }

    @Test
    fun `logs a 4xx between IN and OUT as one WARN line with its message`() {
        val missing = UUID.randomUUID()

        assertThrows<NotFoundEntityException> { workspaceFacade.getWorkspace(missing) }

        val events = appender.list
        assertEquals(3, events.size, events.map { it.formattedMessage }.toString())
        // readOnly on the method overrides the class-level @Transactional, so this is a read
        assertEquals(Level.DEBUG, events[0].level)
        assertEquals(Level.DEBUG, events[2].level)
        assertTrue(events[0].formattedMessage.startsWith("[IN] WorkspaceFacadeImpl.getWorkspace(UUID)"))
        assertEquals(Level.WARN, events[1].level)
        assertTrue(events[1].formattedMessage.startsWith("[ERR] WorkspaceFacadeImpl.getWorkspace(UUID): 404 "), events[1].formattedMessage)
        assertTrue(missing.toString() in events[1].formattedMessage, events[1].formattedMessage)
        assertNull(events[1].throwableProxy)
        assertTrue(events[2].formattedMessage.startsWith("[OUT] WorkspaceFacadeImpl.getWorkspace(UUID): "))
    }

    @Test
    fun `logs a 5xx at ERROR with its stack trace, inheriting the status from the superclass`() {
        val workspaceId = TestWorkspaces.create(workspaceDAO, usersDAO)
        val transferId = UUID.randomUUID()
        TestWorkspaces.seedTransferPair(
            operationDAO,
            workspaceId,
            sourceAccountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "source"),
            targetAccountId = TestWorkspaces.seedAccount(accountDAO, workspaceId, name = "target"),
            transferId = transferId,
        )
        // The legs must agree on their comment; this one no longer does
        jdbc.sql("UPDATE t_operations SET comment = 'drift' WHERE transfer_id = :transferId AND amount > 0")
            .param("transferId", transferId)
            .update()

        assertThrows<BrokenTransferException> { projectionFacade.getTransfer(workspaceId, transferId) }

        val error = appender.list.single { it.formattedMessage.startsWith("[ERR]") }
        assertEquals(Level.ERROR, error.level)
        assertTrue(
            error.formattedMessage.startsWith("[ERR] ProjectionFacadeImpl.getTransfer(UUID, UUID): 500 BrokenTransferException: "),
            error.formattedMessage
        )
        assertNotNull(error.throwableProxy)
        assertTrue(appender.list.last().formattedMessage.startsWith("[OUT] ProjectionFacadeImpl.getTransfer"))
        // readOnly is declared on the class here, not on the method
        assertEquals(Level.DEBUG, appender.list.last().level)
    }
}
