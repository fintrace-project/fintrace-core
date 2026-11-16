package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.validation.ValidationConstants.ACCOUNT_NAME_PATTERN
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_ICON_LENGTH
import com.github.melancholic.fintrace.core.validation.ValidationConstants.MAX_NAME_LENGTH
import org.springframework.stereotype.Service
import java.util.*

interface AccountValidationService {

    fun checkExists(workspaceId: UUID, accountId: UUID)
    fun checkExists(workspaceId: UUID, accountIds: Set<UUID>, includeArchived: Boolean, fieldName: String)
    fun checkName(name: String?)
    fun checkIcon(icon: String?)
}

@Service
class AccountValidationServiceImpl(
    private val accountDAO: AccountProjectionDAO
) : AccountValidationService {

    override fun checkExists(workspaceId: UUID, accountId: UUID) {
        if (!accountDAO.exists(workspaceId, accountId)) {
            throw NotFoundEntityException("Account not found (workspaceId='${workspaceId}', accountId='${accountId}')")
        }
    }

    override fun checkExists(
        workspaceId: UUID,
        accountIds: Set<UUID>,
        includeArchived: Boolean,
        fieldName: String
    ) {
        if (!accountDAO.allExists(workspaceId, accountIds, includeArchived)) {
            throw ValidationError("Not all accounts found (workspaceId='${workspaceId}', accountIds=${accountIds})", fieldName)
        }
    }

    override fun checkName(name: String?) {
        if (name.isNullOrBlank()) {
            throw ValidationError("Account name must not be blank", "name")
        }
        if (name.length > MAX_NAME_LENGTH) {
            throw ValidationError(
                "Account name must be at most $MAX_NAME_LENGTH characters, was ${name.length}",
                "name"
            )
        }
        if (!NAME_REGEX.matches(name)) {
            throw ValidationError("Account name '$name' contains characters that are not permitted", "name")
        }
    }

    override fun checkIcon(icon: String?) {
        if (icon != null && icon.length > MAX_ICON_LENGTH) {
            throw ValidationError(
                "Account icon must be at most $MAX_ICON_LENGTH characters, was ${icon.length}",
                "icon"
            )
        }
    }

    private companion object {
        val NAME_REGEX = Regex(ACCOUNT_NAME_PATTERN)
    }
}
