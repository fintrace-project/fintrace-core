package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.dao.WorkspaceDAO
import com.github.melancholic.fintrace.core.domain.entity.WorkspacePurgeData
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

interface WorkspaceRetentionService {
    fun findDeletedBefore(cutoff: LocalDateTime): List<WorkspacePurgeData>
    fun purgeWorkspace(workspacePurged: WorkspacePurgeData): Boolean
}

// No caller and no ownership: the system purges what nobody can reach anymore
@Service
@Transactional(propagation = Propagation.MANDATORY)
class WorkspaceRetentionServiceImpl(
    private val workspaceDAO: WorkspaceDAO,
) : WorkspaceRetentionService {

    override fun findDeletedBefore(cutoff: LocalDateTime): List<WorkspacePurgeData> =
        workspaceDAO.findDeletedBefore(cutoff)

    override fun purgeWorkspace(workspacePurged: WorkspacePurgeData): Boolean =
        workspaceDAO.purgeWorkspace(workspacePurged)

}