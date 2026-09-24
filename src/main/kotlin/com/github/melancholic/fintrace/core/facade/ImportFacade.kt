package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.api.v1.dto.ImportEnvelopRequest
import com.github.melancholic.fintrace.core.api.v1.mapper.ImportMapper
import com.github.melancholic.fintrace.core.config.TimeoutConstants.IMPORT_TIMEOUT_SECONDS
import com.github.melancholic.fintrace.core.domain.entity.ImportJob
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.ImportExecutionService
import com.github.melancholic.fintrace.core.service.ImportLifecycleService
import com.github.melancholic.fintrace.core.service.WorkspaceService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

sealed interface ImportFacade : RestFacade {
    fun importWorkspaceData(workspaceId: UUID, importRequest: ImportEnvelopRequest): ImportJob
}

@Service
@Transactional
class ImportFacadeImpl(
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider,
    private val importLifecycleService: ImportLifecycleService,
    private val importExecutionService: ImportExecutionService,
    private val mapper: ImportMapper,
) : ImportFacade {

    @Transactional(timeout = IMPORT_TIMEOUT_SECONDS)
    override fun importWorkspaceData(workspaceId: UUID, importRequest: ImportEnvelopRequest): ImportJob {
        val currentUser = identityProvider.currentUserId()
        val workspace = workspaceService.requireWritable(currentUser, workspaceId)
        val diagnostics = mapper.toDomainList(importRequest.payload.diagnostics)

        val jobId = importLifecycleService.begin(currentUser, workspace.id, importRequest.importer())
        try {
            val counts = importExecutionService.importWorkspaceData(workspace.id, currentUser, jobId, importRequest)
            return importLifecycleService.succeed(currentUser, workspace.id, jobId, counts, diagnostics)
        } catch (e: Exception) {
            logger.error(e) { "Import '$jobId' failed with an exception" }
            importLifecycleService.fail(currentUser, workspace.id, jobId, e.message, diagnostics)
            throw e
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
