package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.dao.projection.AccountProjectionDAO
import com.github.melancholic.fintrace.core.domain.entity.FeedPage
import com.github.melancholic.fintrace.core.domain.entity.FeedRequest
import com.github.melancholic.fintrace.core.exception.NotFoundEntityException
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.FeedService
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

sealed interface FeedFacade : RestFacade {
    fun getFeedItems(workspaceId: UUID, accountId: UUID? = null, request: FeedRequest): FeedPage
}

@Service
@Transactional
class FeedFacadeImpl(
    private val workspaceService: WorkspaceService,
    private val identityProvider: IdentityProvider,
    private val accountDAO: AccountProjectionDAO,
    private val feedService: FeedService
) : FeedFacade {

    @Transactional(readOnly = true)
    override fun getFeedItems(
        workspaceId: UUID,
        accountId: UUID?,
        request: FeedRequest
    ): FeedPage {
        workspaceService.requireReadable(identityProvider.currentUserId(), workspaceId)
        if (accountId != null && !accountDAO.exists(workspaceId, accountId)) {
            throw NotFoundEntityException("Account with id=$accountId not found in workspace")
        }
        return feedService.getFeed(workspaceId = workspaceId, accountId = accountId, request = request)
    }

}