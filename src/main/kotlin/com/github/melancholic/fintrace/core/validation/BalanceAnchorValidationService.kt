package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.dao.projection.BalanceAnchorProjectionDAO
import com.github.melancholic.fintrace.core.domain.command.BalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.command.CancelBalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.command.CreateBalanceAnchorCommand
import com.github.melancholic.fintrace.core.exception.ActionConflictException
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.util.TimestampProvider
import org.springframework.stereotype.Service

interface BalanceAnchorValidationService {
    fun validate(command: CreateBalanceAnchorCommand)
    fun validate(command: CancelBalanceAnchorCommand)
}

@Service
class BalanceAnchorValidationServiceImpl(
    private val projectionDAO: BalanceAnchorProjectionDAO,
    private val accountDAO: AccountProjectionDAO,
    private val timestampProvider: TimestampProvider
) : BalanceAnchorValidationService {
    override fun validate(command: CreateBalanceAnchorCommand) {
        checkAccount(command)
        checkOccurredAt(command)
    }

    private fun checkOccurredAt(command: CreateBalanceAnchorCommand) {
        if (timestampProvider.now().isBefore(command.occurredAt)) {
            throw ValidationError("Couldn't record balance anchor: 'occurredAt' is in the future")
        }

        projectionDAO.latestOccurredAt(command.workspaceId, command.accountId)
            .filter { command.occurredAt.isBefore(it) }
            .ifPresent {
                throw ActionConflictException(
                    "Couldn't record balance anchor: it precedes the account's newest anchor ($it)"
                )
            }
    }

    override fun validate(command: CancelBalanceAnchorCommand) {
        checkAccount(command)
        checkAnchorId(command)
    }

    private fun checkAnchorId(command: CancelBalanceAnchorCommand) {
        val requested = projectionDAO.getById(command.workspaceId, command.accountId, command.anchorId)
        if (!projectionDAO.isLast(requested)) {
            throw ActionConflictException("Couldn't remove balance anchor: only last anchor may be deleted.")
        }
    }

    private fun checkAccount(command: BalanceAnchorCommand<*>) {
        val account = accountDAO.getById(command.workspaceId, command.accountId)
        if (account.archived) {
            throw ActionConflictException("Couldn't manage balance anchor: account shouldn't be archived")
        }
    }

}