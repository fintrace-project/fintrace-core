package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.api.v1.dto.ImportEnvelopRequest
import com.github.melancholic.fintrace.core.api.v1.mapper.ImportMapper
import com.github.melancholic.fintrace.core.config.TimeoutConstants.IMPORT_TIMEOUT_SECONDS
import com.github.melancholic.fintrace.core.domain.entity.ImportJob
import com.github.melancholic.fintrace.core.domain.entity.ImportProblem
import com.github.melancholic.fintrace.core.exception.ImportCommandFailedException
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.ImportExecutionService
import com.github.melancholic.fintrace.core.service.ImportLifecycleService
import com.github.melancholic.fintrace.core.service.WorkspaceService
import com.github.melancholic.fintrace.core.util.TransactionsUtil.runInNewTransaction
import com.github.melancholic.fintrace.core.validation.ImportValidationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.*

sealed interface ImportFacade : RestFacade {
    fun importWorkspaceData(workspaceId: UUID, importRequest: ImportEnvelopRequest): ImportJob
}

// No @Transactional: transaction management happens manually inside the import method
@Service
class ImportFacadeImpl(
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider,
    private val importLifecycleService: ImportLifecycleService,
    private val importExecutionService: ImportExecutionService,
    private val importValidationService: ImportValidationService,
    private val mapper: ImportMapper,
    private val transactionManager: PlatformTransactionManager,
) : ImportFacade {

    override fun importWorkspaceData(workspaceId: UUID, importRequest: ImportEnvelopRequest): ImportJob {
        val currentUser = identityProvider.currentUserId()
        val workspace = runInNewTransaction(transactionManager) {
            workspaceService.requireWritable(currentUser, workspaceId)
        }
        val diagnostics = mapper.toDomainList(importRequest.payload.diagnostics)

        val jobId = importLifecycleService.begin(currentUser, workspace.id, importRequest.importer())
        try {
            val problems = runInNewTransaction(transactionManager, readOnly = true) {
                importValidationService.validate(workspaceId, importRequest.payload)
            }
            if (problems.isNotEmpty()) {
                // A refused payload is the importer's error, not ours; the detail is logged because Boot's error body hides it
                logger.warn { "Import '$jobId' refused with ${problems.size} problem(s) in the payload" }
                problems.forEach { problem ->
                    logger.warn {
                        "Import '$jobId': ${problem.code} in ${problem.aggregateType}" +
                            (problem.field?.let { " '$it'" } ?: "") +
                            ", ${problem.affectedCount} id(s): ${problem.affectedIDs}"
                    }
                }
                return importLifecycleService.fail(
                    userId = currentUser,
                    workspaceId = workspace.id,
                    jobId = jobId,
                    message = "Import failed with validation errors",
                    diagnostics = diagnostics,
                    problems = problems
                )
            }
            return runInNewTransaction(transactionManager, timeoutSeconds = IMPORT_TIMEOUT_SECONDS) {
                val counts = importExecutionService.importWorkspaceData(workspace.id, currentUser, jobId, importRequest)
                importLifecycleService.succeed(currentUser, workspace.id, jobId, counts, diagnostics)
            }
        } catch (e: ImportCommandFailedException) {
            if (!isRefusal(e.cause)) {
                logger.error(e) { "Import '$jobId' failed with an exception" }
                importLifecycleService.fail(currentUser, workspace.id, jobId, e.message, diagnostics)
                throw e.cause
            }
            logger.warn { "Import '$jobId' refused by the command path: ${e.message}" }
            return importLifecycleService.fail(
                userId = currentUser,
                workspaceId = workspace.id,
                jobId = jobId,
                message = "Import failed with validation errors",
                diagnostics = diagnostics,
                problems = listOf(ImportProblem.refusedByCommand(e.entityType, e.entityId, e.cause.message))
            )
        } catch (e: Exception) {
            logger.error(e) { "Import '$jobId' failed with an exception" }
            importLifecycleService.fail(currentUser, workspace.id, jobId, e.message, diagnostics)
            throw e
        }
    }

    private fun isRefusal(e: Exception): Boolean =
        AnnotatedElementUtils.findMergedAnnotation(e.javaClass, ResponseStatus::class.java)
            ?.code?.is4xxClientError == true

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
