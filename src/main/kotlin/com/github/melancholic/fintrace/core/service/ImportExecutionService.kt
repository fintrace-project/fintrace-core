package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.api.v1.dto.ImportEnvelopRequest
import com.github.melancholic.fintrace.core.dao.projection.CategoryProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.*
import com.github.melancholic.fintrace.core.domain.entity.*
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.service.command.CommandDispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface ImportExecutionService {
    fun importWorkspaceData(
        workspaceId: UUID,
        userId: UUID,
        jobId: UUID,
        request: ImportEnvelopRequest
    ): ImportJobStatistics
}

@Service
@Transactional(propagation = Propagation.MANDATORY)
class ImportExecutionServiceImpl(
    private val commandDispatcher: CommandDispatcher,
    private val categoryProjectionDAO: CategoryProjectionDAO,
) : ImportExecutionService {

    override fun importWorkspaceData(
        workspaceId: UUID,
        userId: UUID,
        jobId: UUID,
        request: ImportEnvelopRequest
    ): ImportJobStatistics {
        val statistics = ImportJobStatistics(
            accounts = importAccounts(workspaceId, jobId, userId, request),
            categories = importCategories(workspaceId, jobId, userId, request),
            operations = importOperations(workspaceId, jobId, userId, request),
            transfers = importTransfers(workspaceId, jobId, userId, request),
            anchors = importAnchors(workspaceId, jobId, userId, request),
        )
        archiveAccounts(workspaceId, jobId, userId, request)
        return statistics
    }

    private fun importAccounts(workspaceId: UUID, jobId: UUID, userId: UUID, request: ImportEnvelopRequest): Int {
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
            }
            .map { dispatch(it, userId, request) }
            .count()
        logger.info { "[importId=$jobId] Imported $imported accounts into workspace" }
        return imported
    }

    private fun importCategories(workspaceId: UUID, jobId: UUID, userId: UUID, request: ImportEnvelopRequest): Int {
        val roots = mapOf(
            CategoryKind.INCOME to categoryProjectionDAO.getBySystemCode(workspaceId, CategorySystemCode.INCOME_ROOT).id,
            CategoryKind.EXPENSE to categoryProjectionDAO.getBySystemCode(workspaceId, CategorySystemCode.EXPENSE_ROOT).id
        )
        val imported = request.payload.categories.asSequence()
            .map {
                CreateCategoryCommand.custom(
                    id = it.id,
                    externalRef = it.externalRef,
                    workspaceId = workspaceId,
                    parentId = it.parentId ?: roots[it.kind]
                    ?: throw ApplicationException("Couldn't perform category import: parent category couldn't be resolved"),
                    name = it.name,
                    kind = it.kind,
                    icon = it.icon
                )
            }
            .map { dispatch(it, userId, request) }
            .count()
        logger.info { "[importId=$jobId] Imported $imported categories into workspace" }
        return imported
    }

    private fun importOperations(workspaceId: UUID, jobId: UUID, userId: UUID, request: ImportEnvelopRequest): Int {
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
            }
            .map { dispatch(it, userId, request) }
            .count()
        logger.info { "[importId=$jobId] Imported $imported operations into workspace" }
        return imported
    }

    private fun importTransfers(workspaceId: UUID, jobId: UUID, userId: UUID, request: ImportEnvelopRequest): Int {
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
            }
            .map { dispatch(it, userId, request) }
            .count()
        logger.info { "[importId=$jobId] Imported $imported transfers into workspace" }
        return imported
    }

    private fun importAnchors(workspaceId: UUID, jobId: UUID, userId: UUID, request: ImportEnvelopRequest): Int {
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
            }
            .map { dispatch(it, userId, request) }
            .count()
        logger.info { "[importId=$jobId] Imported $imported balance anchors into workspace" }
        return imported
    }

    private fun archiveAccounts(workspaceId: UUID, jobId: UUID, userId: UUID, request: ImportEnvelopRequest) {
        val archived = request.payload.accounts.asSequence()
            .filter { it.archived }
            .map {
                SetAccountArchivedCommand(
                    workspaceId = workspaceId,
                    accountId = it.id,
                    archived = true,
                )
            }
            .map { dispatch(it, userId, request) }
            .count()
        logger.info { "[importId=$jobId] Archived $archived accounts" }
    }

    private fun <R> dispatch(command: Command<R>, userId: UUID, request: ImportEnvelopRequest): R =
        commandDispatcher.dispatch(command, CommandContext(initiator = userId, importer = request.importer()))

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}