package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.config.WorkspaceImportConstants.IMPORT_LEASE_TIME
import com.github.melancholic.fintrace.core.dao.ImportJobDAO
import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.domain.entity.*
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.CoreErrorCode
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.OperationNotAllowedException
import com.github.melancholic.fintrace.core.util.TransactionsUtil.runInNewTransaction
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.util.*


interface ImportLifecycleService {

    fun begin(userId: UUID, workspaceId: UUID, importer: ImporterDetails): UUID

    fun succeed(
        userId: UUID,
        workspaceId: UUID,
        jobId: UUID,
        counts: ImportJobStatistics,
        diagnostics: List<ImportDiagnostic>
    ): ImportJob

    fun fail(
        userId: UUID,
        workspaceId: UUID,
        jobId: UUID,
        message: String?,
        diagnostics: List<ImportDiagnostic> = emptyList(),
        problems: List<ImportProblem> = emptyList()
    ): ImportJob

    fun recoverIfAbandoned(workspace: Workspace): Workspace

    fun recoverAbandoned(chunkSize: Int): List<UUID>
}

@Service
class ImportLifecycleServiceImpl(
    private val workspaceDAO: WorkspaceDAO,
    private val importJobDAO: ImportJobDAO,
    private val platformTransactionManager: PlatformTransactionManager,
) : ImportLifecycleService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun begin(userId: UUID, workspaceId: UUID, importer: ImporterDetails): UUID {
        val version = checkImportable(userId, workspaceId)
        val jobId = importJobDAO.create(workspaceId = workspaceId, startedBy = userId, importer = importer)
        if (!workspaceDAO.startImport(userId, workspaceId, jobId, version)) {
            throw ActionConflictException(
                CoreErrorCode.WORKSPACE_NOT_IMPORTABLE,
                "Couldn't start import: concurrent modification of the workspace"
            )
        }
        return jobId
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun succeed(
        userId: UUID,
        workspaceId: UUID,
        jobId: UUID,
        counts: ImportJobStatistics,
        diagnostics: List<ImportDiagnostic>
    ): ImportJob {
        if (!workspaceDAO.finishImport(userId, workspaceId, jobId, WorkspaceStatus.ACTIVE)) {
            throw ActionConflictException(
                CoreErrorCode.IMPORT_LEASE_LOST,
                "Couldn't finish import '$jobId': the workspace is no longer held by it"
            )
        }
        return importJobDAO.complete(
            workspaceId = workspaceId,
            id = jobId,
            status = ImportJobStatus.SUCCEEDED,
            counts = counts,
            diagnostics = diagnostics,
            problems = emptyList()
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun fail(
        userId: UUID,
        workspaceId: UUID,
        jobId: UUID,
        message: String?,
        diagnostics: List<ImportDiagnostic>,
        problems: List<ImportProblem>
    ) : ImportJob {
        if (!workspaceDAO.finishImport(userId, workspaceId, jobId, WorkspaceStatus.NEW)) {
            logger.warn { "Import '$jobId' failed, but its workspace was no longer held by it - status left as found" }
        }
        return importJobDAO.complete(
            workspaceId = workspaceId,
            id = jobId,
            status = ImportJobStatus.FAILED,
            counts = null,
            diagnostics = diagnostics,
            message = message,
            problems = problems
        )
    }

    override fun recoverIfAbandoned(workspace: Workspace): Workspace {
        if (!isAbandoned(workspace)) return workspace

        val released = runInNewTransaction(platformTransactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
            workspaceDAO.resetWorkspaceStatusIfImportAbandoned(workspace.id, staleBefore())
                .also { if (it.isPresent) failRunningJob(workspace.id) }
        }

        return released.orElseGet {
            workspaceDAO.get(workspace.ownerId, workspace.id)
                .orElseThrow { NotFoundEntityException("Workspace not found (workspaceId='${workspace.id}')") }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun recoverAbandoned(chunkSize: Int): List<UUID> =
        workspaceDAO.resetMultipleWorkspacesWithAbandonedImport(chunkSize, staleBefore())
            .onEach { failRunningJob(it) }

    private fun checkImportable(userId: UUID, workspaceId: UUID): Long {
        val workspace = workspaceDAO.get(userId, workspaceId)
            .orElseThrow { NotFoundEntityException("Workspace not found (workspaceId='$workspaceId')") }

        if (!TO_IMPORT_STATUSES.contains(workspace.status)) {
            throw OperationNotAllowedException(
                CoreErrorCode.WORKSPACE_NOT_IMPORTABLE,
                "Import not allowed into workspace '$workspaceId': workspace is not new"
            )
        }
        if (!workspaceDAO.isEmpty(workspaceId)) {
            throw OperationNotAllowedException(
                CoreErrorCode.WORKSPACE_NOT_IMPORTABLE,
                "Import not allowed into workspace '$workspaceId': workspace is not empty"
            )
        }
        return workspace.version
    }

    private fun failRunningJob(workspaceId: UUID) = importJobDAO.updateStatus(
        workspaceId = workspaceId,
        oldStatus = ImportJobStatus.RUNNING,
        newStatus = ImportJobStatus.FAILED
    ).ifPresent { logger.info { "Import job '${it.id}' marked FAILED: its import was abandoned" } }

    private fun isAbandoned(workspace: Workspace): Boolean {
        if (workspace.status != WorkspaceStatus.IMPORTING) return false
        val startedAt = workspace.importStartedAt ?: return false
        return Duration.between(startedAt, LocalDateTime.now()).toSeconds() > IMPORT_LEASE_TIME
    }

    private fun staleBefore(): LocalDateTime = LocalDateTime.now().minusSeconds(IMPORT_LEASE_TIME)

    companion object {
        private val logger = KotlinLogging.logger {}

        val TO_IMPORT_STATUSES = setOf(WorkspaceStatus.NEW)
    }
}
