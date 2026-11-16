package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.domain.command.CreateAccountCommand
import com.github.melancholic.fintrace.core.domain.command.ReviseAccountCommand
import com.github.melancholic.fintrace.core.domain.command.SetAccountArchivedCommand
import org.springframework.stereotype.Service

interface AccountCommandValidationService {
    fun validate(account: CreateAccountCommand)
    fun validate(account: ReviseAccountCommand)
    fun validate(account: SetAccountArchivedCommand)
}

@Service
class AccountCommandValidationServiceImpl(
    private val accountValidationService: AccountValidationService
) : AccountCommandValidationService {

    override fun validate(account: CreateAccountCommand) {
        accountValidationService.checkName(account.name)
        accountValidationService.checkIcon(account.icon)
        CurrencyValidator.requireKnown(account.currency, "currency")
    }

    override fun validate(account: ReviseAccountCommand) {
        accountValidationService.checkExists(account.workspaceId, account.accountId)
        accountValidationService.checkName(account.name)
        accountValidationService.checkIcon(account.icon)
    }

    override fun validate(account: SetAccountArchivedCommand) {
        accountValidationService.checkExists(account.workspaceId, account.accountId)
    }

}
