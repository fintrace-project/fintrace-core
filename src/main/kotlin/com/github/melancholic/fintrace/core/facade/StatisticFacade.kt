package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.dao.BalanceDAO
import com.github.melancholic.fintrace.core.domain.entity.AccountBalance
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.*

sealed interface StatisticFacade: RestFacade {
    fun getAllBalancesOf(workspaceId: UUID, asOf: LocalDate, includeArchived: Boolean): List<AccountBalance>
}

@Service
@Transactional(readOnly = true)
class StatisticFacadeImpl(
    private val workspaceService: WorkspaceService,
    private val balanceDAO: BalanceDAO,
    private val identityProvider: IdentityProvider,
) : StatisticFacade {

    override fun getAllBalancesOf(
        workspaceId: UUID,
        asOf: LocalDate,
        includeArchived: Boolean
    ): List<AccountBalance> {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return balanceDAO.getAllBalancesOf(workspace.id, asOf, includeArchived)
    }

}