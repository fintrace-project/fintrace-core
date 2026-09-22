package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.api.v1.dto.ImportEnvelopRequest
import com.github.melancholic.fintrace.core.api.v1.mapper.ImportMapper
import com.github.melancholic.fintrace.core.dao.ImportJobDAO
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.*
import com.github.melancholic.fintrace.core.domain.entity.*
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.service.command.CommandDispatcher
import com.github.melancholic.fintrace.core.util.TransactionsUtil.runInNewTransaction
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*


interface ImportService {
    fun importWorkspaceData(workspaceId: UUID, userId: UUID, importRequest: ImportEnvelopRequest): ImportJob
}

@Service
@Transactional(propagation = Propagation.MANDATORY)
class ImportServiceImpl(
    private val importJobDAO: ImportJobDAO,
    private val commandDispatcher: CommandDispatcher,
    private val platformTransactionManager: PlatformTransactionManager,
    private val mapper: ImportMapper,
    private val categoryProjectionDAO: CategoryProjectionDAO,
    private val workspaceService: WorkspaceService
) : ImportService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun importWorkspaceData(
        workspaceId: UUID,
        userId: UUID,
        importRequest: ImportEnvelopRequest
    ): ImportJob {
        val importId: UUID = initWorkspaceDataImport(userId, workspaceId, importRequest)

        try {
            val accounts = importAccounts(workspaceId, importId, userId, importRequest)
            val categories = importCategories(workspaceId, importId, userId, importRequest)
            val operations = importOperations(workspaceId, importId, userId, importRequest)
            val transfers = importTransfers(workspaceId, importId, userId, importRequest)
            val anchors = importAnchors(workspaceId, importId, userId, importRequest)
            archivingAccounts(workspaceId, importId, userId, importRequest)

            val statistics = ImportJobStatistics(
                accounts = accounts,
                categories = categories,
                operations = operations,
                transfers = transfers,
                anchors = anchors,
            )

            return completeWithSuccessWorkspaceDataImport(userId, workspaceId, importId, statistics, importRequest)
        } catch (e: Exception) {
            logger.error(e) { "Import `$importId` failed with an exception." }
            completeWithErrorWorkspaceDataImport(userId, workspaceId, importId, importRequest)
            throw e
        }
    }

    private fun completeWithErrorWorkspaceDataImport(
        userId: UUID,
        workspaceId: UUID,
        importId: UUID,
        importRequest: ImportEnvelopRequest
    ) {
        runInNewTransaction(platformTransactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
            workspaceService.repairAfterFailedImport(userId = userId, workspaceId = workspaceId)
            importJobDAO.complete(
                workspaceId = workspaceId,
                id = importId,
                status = ImportJobStatus.FAILED,
                counts = null,
                diagnostics = mapper.toDomainList(importRequest.payload.diagnostics)
            )
        }
    }

    private fun initWorkspaceDataImport(
        userId: UUID,
        workspaceId: UUID,
        importRequest: ImportEnvelopRequest
    ): UUID = runInNewTransaction(platformTransactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
        workspaceService.initImport(userId = userId, workspaceId = workspaceId)
        importJobDAO.create(
            workspaceId = workspaceId,
            startedBy = userId,
            importer = importRequest.importer()
        )
    }

    fun importAccounts(workspaceId: UUID, importId: UUID, userId: UUID, request: ImportEnvelopRequest): Int {
        val imported = request.payload.accounts.asSequence()
            .map {
                CreateAccountCommand(
                    id = it.id,
                    externalRef = it.externalRef,
                    workspaceId = workspaceId,
                    name = it.name,
                    currency = it.currency,
                    icon = it.icon,
                    initialBalance = it.initialBalance,
                    initialBalanceAt = it.initialBalanceAt,
                )
            }.map {
                commandDispatcher.dispatch(
                    it, CommandContext(
                        initiator = userId,
                        importer = request.importer()
                    )
                )
            }
            .count()
        logger.info { "[importId=$importId] Imported $imported accounts into workspace" }
        return imported
    }

    private fun importCategories(workspaceId: UUID, importId: UUID, userId: UUID, request: ImportEnvelopRequest): Int {
        val defaultCategories = mapOf(
            CategoryKind.INCOME to categoryProjectionDAO.getBySystemCode(
                workspaceId,
                CategorySystemCode.INCOME_ROOT
            ).id,
            CategoryKind.EXPENSE to categoryProjectionDAO.getBySystemCode(
                workspaceId,
                CategorySystemCode.EXPENSE_ROOT
            ).id
        )
        val imported = request.payload.categories.asSequence()
            .map {
                CreateCategoryCommand.custom(
                    id = it.id,
                    externalRef = it.externalRef,
                    workspaceId = workspaceId,
                    parentId = it.parentId ?: defaultCategories[it.kind]
                    ?: throw ApplicationException("Couldn't perform category import: parent category couldn't be resolved"),
                    name = it.name,
                    kind = it.kind,
                    icon = it.icon
                )
            }.map {
                commandDispatcher.dispatch(
                    it, CommandContext(
                        initiator = userId,
                        importer = request.importer()
                    )
                )
            }
            .count()
        logger.info { "[importId=$importId] Imported $imported accounts into workspace" }
        return imported
    }

    private fun importOperations(
        workspaceId: UUID,
        importId: UUID,
        userId: UUID,
        request: ImportEnvelopRequest
    ): Int {
        val imported = request.payload.operations.asSequence()
            .map {
                CreateOperationCommand(
                    id = it.id,
                    externalRef = it.externalRef,
                    workspaceId = workspaceId,
                    occurredAt = it.occurredAt,
                    amount = it.amount,
                    accountId = it.accountId,
                    kind = it.kind,
                    categoryId = it.categoryId,
                    comment = it.comment
                )
            }.map {
                commandDispatcher.dispatch(
                    it, CommandContext(
                        initiator = userId,
                        importer = request.importer()
                    )
                )
            }
            .count()
        logger.info { "[importId=$importId] Imported $imported operations into workspace" }
        return imported
    }

    private fun importTransfers(
        workspaceId: UUID,
        importId: UUID,
        userId: UUID,
        request: ImportEnvelopRequest
    ): Int {
        val imported = request.payload.transfers.asSequence()
            .map {
                CreateTransferCommand(
                    id = it.id,
                    externalRef = it.externalRef,
                    workspaceId = workspaceId,
                    occurredAt = it.occurredAt,
                    sourceAccountId = it.source.accountId,
                    sourceAmount = it.source.amount,
                    targetAccountId = it.target.accountId,
                    targetAmount = it.target.amount,
                    comment = it.comment,
                )
            }.map {
                commandDispatcher.dispatch(
                    it, CommandContext(
                        initiator = userId,
                        importer = request.importer()
                    )
                )
            }
            .count()
        logger.info { "[importId=$importId] Imported $imported transfers into workspace" }
        return imported
    }

    private fun importAnchors(
        workspaceId: UUID,
        importId: UUID,
        userId: UUID,
        request: ImportEnvelopRequest
    ): Int {
        val imported = request.payload.balanceAnchors.asSequence()
            .map {
                CreateBalanceAnchorCommand(
                    id = it.id,
                    externalRef = it.externalRef,
                    workspaceId = workspaceId,
                    accountId = it.accountId,
                    value = it.value,
                    occurredAt = it.occurredAt
                )
            }.map {
                commandDispatcher.dispatch(
                    it, CommandContext(
                        initiator = userId,
                        importer = request.importer()
                    )
                )
            }
            .count()
        logger.info { "[importId=$importId] Imported $imported balance anchors into workspace" }
        return imported
    }

    private fun archivingAccounts(
        workspaceId: UUID,
        importId: UUID,
        userId: UUID,
        request: ImportEnvelopRequest
    ) {
        val archived = request.payload.accounts.asSequence()
            .filter { it.archived }
            .map {
                SetAccountArchivedCommand(
                    workspaceId = workspaceId,
                    accountId = it.id,
                    archived = true,
                )
            }
            .map {
                commandDispatcher.dispatch(
                    it, CommandContext(
                        initiator = userId,
                        importer = request.importer()
                    )
                )
            }.count()
        logger.info { "[importId=$importId] Archived $archived accounts" }
    }

    private fun completeWithSuccessWorkspaceDataImport(
        userId: UUID,
        workspaceId: UUID,
        importId: UUID,
        statistics: ImportJobStatistics,
        importRequest: ImportEnvelopRequest
    ): ImportJob {
        workspaceService.activateWorkspace(userId, workspaceId)
        return runInNewTransaction(platformTransactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
            importJobDAO.complete(
                workspaceId = workspaceId,
                id = importId,
                status = ImportJobStatus.SUCCEEDED,
                counts = statistics,
                diagnostics = mapper.toDomainList(importRequest.payload.diagnostics)
            )
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }

}