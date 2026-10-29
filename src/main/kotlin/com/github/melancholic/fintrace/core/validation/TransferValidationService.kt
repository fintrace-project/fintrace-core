package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.OperationProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.*
import com.github.melancholic.fintrace.core.domain.projection.OperationProjection
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.CoreErrorCode
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.util.TimestampProvider
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.*

interface TransferValidationService {
    fun validate(command: CreateTransferCommand)
    fun validate(command: ReviseTransferCommand)
    fun validate(command: CancelTransferCommand)
}

@Service
class TransferValidationServiceImpl(
    private val timestampProvider: TimestampProvider,
    private val accountDAO: AccountProjectionDAO,
    private val operationProjectionDAO: OperationProjectionDAO
) : TransferValidationService {

    override fun validate(command: CreateTransferCommand) {
        checkOccurredAt(command)
        checkAmounts(command)
        checkAccounts(command)
        checkAccountOnCreate(command.workspaceId, command.sourceAccountId)
        checkAccountOnCreate(command.workspaceId, command.targetAccountId)
    }

    override fun validate(command: ReviseTransferCommand) {
        val (source, target) = currentLegs(command)
        checkOccurredAt(command)
        checkAmounts(command)
        checkAccounts(command)
        checkAccountOnRevise(command.workspaceId, command.sourceAccountId, source.accountId)
        checkAccountOnRevise(command.workspaceId, command.targetAccountId, target.accountId)
    }

    override fun validate(command: CancelTransferCommand) {
        checkTransfer(command)
    }

    private fun checkOccurredAt(command: TemporalCommand<*>) {
        if (timestampProvider.now().isBefore(command.occurredAt)) {
            throw ValidationError("A transfer cannot be dated in the future", "occurredAt")
        }
    }

    private fun checkAccountOnCreate(workspaceId: UUID, accountId: UUID) {
        val account = accountDAO.getById(workspaceId, accountId)
        if (account.archived) {
            throw ActionConflictException(CoreErrorCode.ACCOUNT_ARCHIVED, "Archived account couldn't be used for new transfer")
        }
    }

    private fun checkAccountOnRevise(workspaceId: UUID, accountId: UUID, currentAccountId: UUID) {
        val account = accountDAO.getById(workspaceId, accountId)
        if (account.archived && accountId != currentAccountId) {
            throw ActionConflictException(CoreErrorCode.ACCOUNT_ARCHIVED, "Archived account couldn't receive a transfer leg")
        }
    }

    private fun currentLegs(command: ExistingTransferCommand<*>): Pair<OperationProjection, OperationProjection> {
        val (sourceId, targetId) = operationProjectionDAO.getTransferPartiesById(
            command.workspaceId,
            command.transferId
        )
        return operationProjectionDAO.getById(command.workspaceId, sourceId) to
                operationProjectionDAO.getById(command.workspaceId, targetId)
    }

    private fun checkTransfer(command: ExistingTransferCommand<*>) {
        if (!operationProjectionDAO.existsTransferById(command.workspaceId, command.transferId)) {
            throw NotFoundEntityException("Transfer not found into workspace (workspaceId='${command.workspaceId}', transferId='${command.transferId}')")
        }
    }

    private fun checkAmounts(command: TransferStateCommand<*>) {
        if (command.sourceAmount <= BigDecimal.ZERO) {
            throw ValidationError("A transfer's source amount must be positive", "source.amount")
        }
        if (command.targetAmount <= BigDecimal.ZERO) {
            throw ValidationError("A transfer's target amount must be positive", "target.amount")
        }
    }

    private fun checkAccounts(command: TransferStateCommand<*>) {
        if (command.sourceAccountId == command.targetAccountId) {
            throw ValidationError("A transfer's source and target accounts must differ", "target.accountId")
        }
    }

}
