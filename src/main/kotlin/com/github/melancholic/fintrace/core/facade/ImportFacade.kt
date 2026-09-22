package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.api.v1.dto.ImportEnvelopRequest
import com.github.melancholic.fintrace.core.config.TimeoutConstants.IMPORT_TIMEOUT_SECONDS
import com.github.melancholic.fintrace.core.domain.entity.ImportJob
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.ImportService
import com.github.melancholic.fintrace.core.service.WorkspaceService
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
    private val importService: ImportService
) : ImportFacade {

    @Transactional(timeout = IMPORT_TIMEOUT_SECONDS)
    override fun importWorkspaceData(workspaceId: UUID, importRequest: ImportEnvelopRequest): ImportJob {
        val currentUser = identityProvider.currentUserId()
        val workspace = workspaceService.requireWritable(currentUser, workspaceId)
        val importResult = importService.importWorkspaceData(workspace.id, currentUser, importRequest)
        return importResult
    }


}