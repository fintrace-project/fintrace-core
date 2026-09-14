package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.dao.BalanceDAO
import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.BalanceAnchorContainer
import com.github.melancholic.fintrace.core.domain.entity.Transfer
import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import com.github.melancholic.fintrace.core.domain.projection.CategoryProjection
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.WorkspaceService
import com.github.melancholic.fintrace.core.service.transfer.TransferLoader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

sealed interface ProjectionFacade: RestFacade {
    fun getOperation(workspaceId: UUID, operationId: UUID): OperationProjection
    fun getAccount(workspaceId: UUID, accountId: UUID): AccountProjection
    fun getAllAccounts(workspaceId: UUID, includeArchived: Boolean): List<AccountProjection>
    fun getCategory(workspaceId: UUID, categoryId: UUID): CategoryProjection
    fun getAllCategories(workspaceId: UUID, includeArchived: Boolean): List<CategoryProjection>
    fun getTransfer(workspaceId: UUID, transferId: UUID): Transfer
    fun getAllBalanceAnchors(workspaceId: UUID, accountId: UUID): List<BalanceAnchorContainer>
    fun getBalanceAnchor(workspaceId: UUID, accountId: UUID, anchorId: UUID): BalanceAnchorContainer
}

@Service
@Transactional(readOnly = true)
class ProjectionFacadeImpl(
    private val operationDAO: OperationProjectionDAO,
    private val accountDAO: AccountProjectionDAO,
    private val categoryDAO: CategoryProjectionDAO,
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider,
    private val transferLoader: TransferLoader,
    private val balanceAnchorDAO: BalanceAnchorProjectionDAO,
    private val balanceDAO: BalanceDAO
) : ProjectionFacade {

    override fun getOperation(
        workspaceId: UUID,
        operationId: UUID
    ): OperationProjection {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return operationDAO.getById(workspace.id, operationId)
    }

    override fun getAccount(
        workspaceId: UUID,
        accountId: UUID
    ): AccountProjection {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return accountDAO.getById(workspace.id, accountId)
    }

    override fun getAllAccounts(
        workspaceId: UUID,
        includeArchived: Boolean
    ): List<AccountProjection> {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return accountDAO.getAllAccounts(workspace.id, includeArchived)
    }

    override fun getCategory(
        workspaceId: UUID,
        categoryId: UUID
    ): CategoryProjection {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return categoryDAO.getById(workspace.id, categoryId)
    }

    @Transactional(readOnly = true)
    override fun getAllCategories(
        workspaceId: UUID,
        includeArchived: Boolean
    ): List<CategoryProjection> {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return categoryDAO.getAllCategories(workspace.id, includeArchived)
    }

    override fun getTransfer(
        workspaceId: UUID,
        transferId: UUID
    ): Transfer {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return transferLoader.loadTransfer(workspace.id, transferId)
    }

    override fun getAllBalanceAnchors(
        workspaceId: UUID,
        accountId: UUID
    ): List<BalanceAnchorContainer> {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        val projections = balanceAnchorDAO.getAll(workspace.id, accountId)
            .associateBy { it.id }
        val differences = balanceDAO.getUnexplainedDifference(workspace.id, projections.keys)

        return projections.entries
            .map { (id, projection) ->
                BalanceAnchorContainer(projection, differences[id]!!)
            }
    }

    override fun getBalanceAnchor(
        workspaceId: UUID,
        accountId: UUID,
        anchorId: UUID
    ): BalanceAnchorContainer {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        val projection = balanceAnchorDAO.getById(workspace.id, accountId, anchorId)
        return BalanceAnchorContainer(
            projection,
            balanceDAO.getUnexplainedDifference(projection.workspaceId, projection.id)
        )
    }
}