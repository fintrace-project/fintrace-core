package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.domain.entity.AccountBalance
import com.github.melancholic.fintrace.core.domain.entity.CurrencyAmounts
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.StatisticService
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.*

sealed interface StatisticFacade : RestFacade {
    fun getBalancesByAccounts(workspaceId: UUID, asOf: LocalDate, includeArchived: Boolean): List<AccountBalance>
    fun getBalancesByCurrencies(workspaceId: UUID, asOf: LocalDate, includeArchived: Boolean): CurrencyAmounts
}

@Service
@Transactional(readOnly = true)
class StatisticFacadeImpl(
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider,
    private val statisticService: StatisticService
) : StatisticFacade {

    override fun getBalancesByAccounts(
        workspaceId: UUID,
        asOf: LocalDate,
        includeArchived: Boolean
    ): List<AccountBalance> {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return statisticService.getBalancesByAccounts(workspace.id, asOf, includeArchived)
    }

    override fun getBalancesByCurrencies(
        workspaceId: UUID,
        asOf: LocalDate,
        includeArchived: Boolean
    ): CurrencyAmounts {
        val workspace = workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        return statisticService.getBalancesByCurrencies(workspace.id, asOf, includeArchived)
    }

}