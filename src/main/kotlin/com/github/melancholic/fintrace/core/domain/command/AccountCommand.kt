package com.github.melancholic.fintrace.core.domain.command

import com.github.melancholic.fintrace.core.domain.projection.AccountProjection
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface AccountCommand<R> : Command<R>

sealed interface ExistingAccountCommand<R> : AccountCommand<R> {
    val accountId: UUID
}

data class CreateAccountCommand(
    override val id: UUID? = null,
    override val externalRef: String? = null,
    override val workspaceId: UUID,
    val name: String,
    val currency: String,
    val icon: String?,
    val initialBalance: BigDecimal? = null,
    val initialBalanceAt: LocalDateTime? = null
) : AccountCommand<AccountProjection>, CreateCommand<AccountProjection>

data class ReviseAccountCommand(
    override val workspaceId: UUID,
    override val accountId: UUID,
    val name: String,
    val icon: String?,
) : ExistingAccountCommand<AccountProjection>, ReviseCommand<AccountProjection>

data class SetAccountArchivedCommand(
    override val workspaceId: UUID,
    override val accountId: UUID,
    val archived: Boolean,
) : ExistingAccountCommand<AccountProjection>, ReviseCommand<AccountProjection>