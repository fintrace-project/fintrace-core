package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.dao.FeedDAO
import com.github.melancholic.fintrace.core.domain.entity.*
import com.github.melancholic.fintrace.core.exception.ValidationError
import com.github.melancholic.fintrace.core.util.FeedPositionCodec.decode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*

sealed interface FeedService {
    fun getFeed(
        workspaceId: UUID,
        accountId: UUID? = null,
        request: FeedRequest
    ): FeedPage
}

@Service
class FeedServiceImpl(
    private val feedDAO: FeedDAO,
) : FeedService {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun getFeed(
        workspaceId: UUID,
        accountId: UUID?,
        request: FeedRequest
    ): FeedPage {

        validate(request)
        if (accountId == null) {
            validateWorkspaceKinds(request)
        }


        val filter = FeedFilter(
            workspaceId = workspaceId,
            accountId = accountId,
            from = request.from,
            to = request.to,
            types = request.kinds
        )

        val seek = seekOf(request.older, request.newer, request.at)
        val rows = feedDAO.page(filter, seek, request.size + 1)
        val hasExtra = rows.size > request.size

        val items = items(rows, seek, hasExtra)
        val older = older(items, seek, hasExtra, filter)
        val newer = newer(items, seek, hasExtra, filter)

        return FeedPage(items, older, newer)
    }

    private fun validate(request: FeedRequest) {
        if (request.size < 1 || request.size > MAX_PAGE_SIZE) {
            throw ValidationError("`size` should be between 1 and $MAX_PAGE_SIZE", "size")
        }
        if (request.from != null && request.to != null) {
            if (request.from >= request.to) {
                throw ValidationError("`from` must be before `to`",  "from")
            }
        }
    }

    private fun validateWorkspaceKinds(request: FeedRequest) {
        if (request.kinds.isNotEmpty() && !FeedItemType.WORKSPACE_FEED_KINDS.containsAll(request.kinds)) {
            throw ValidationError("Workspace feed supports only kinds: ${FeedItemType.WORKSPACE_FEED_KINDS}", "kinds")
        }
    }

    private fun items(
        rows: List<FeedItem>,
        seek: FeedSeek,
        hasExtra: Boolean
    ) = when {
        !hasExtra -> rows
        seek is FeedSeek.Newer -> rows.drop(1)
        else -> rows.dropLast(1)
    }

    private fun older(
        items: List<FeedItem>,
        seek: FeedSeek,
        hasExtra: Boolean,
        filter: FeedFilter
    ): FeedPosition? = items.lastOrNull()
        ?.position
        ?.takeIf { last ->
            if (seek is FeedSeek.Newer) {
                feedDAO.exists(filter, FeedSeek.Older(last))
            } else {
                hasExtra
            }
        }

    private fun newer(
        items: List<FeedItem>,
        seek: FeedSeek,
        hasExtra: Boolean,
        filter: FeedFilter
    ): FeedPosition? = items.firstOrNull()
        ?.position
        ?.takeIf { first ->
            when (seek) {
                FeedSeek.Newest -> false
                is FeedSeek.Newer -> hasExtra
                else -> feedDAO.exists(filter, FeedSeek.Newer(first))
            }
        }

    private fun seekOf(older: String?, newer: String?, at: String?): FeedSeek {
        if (listOfNotNull(older, newer, at).size > 1) {
            throw ValidationError("Send at most one of older, newer and at")
        }
        return when {
            older != null -> FeedSeek.Older(decode(older))
            newer != null -> FeedSeek.Newer(decode(newer))
            at != null -> FeedSeek.At(decode(at))
            else -> FeedSeek.Newest
        }
    }

    companion object {
        const val MAX_PAGE_SIZE = 100
    }
}