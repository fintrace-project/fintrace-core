package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.AccountFeedItemDTO
import com.github.melancholic.fintrace.core.api.v1.dto.AccountTransferFeedItemDTO
import com.github.melancholic.fintrace.core.api.v1.dto.BalanceAnchorFeedItemDTO
import com.github.melancholic.fintrace.core.api.v1.dto.DifferenceFeedItemDTO
import com.github.melancholic.fintrace.core.api.v1.dto.OperationFeedItemDTO
import com.github.melancholic.fintrace.core.api.v1.dto.PageCursors
import com.github.melancholic.fintrace.core.api.v1.dto.PagedListResponse
import com.github.melancholic.fintrace.core.api.v1.dto.TransferLegResponse
import com.github.melancholic.fintrace.core.api.v1.dto.WorkspaceFeedItemDTO
import com.github.melancholic.fintrace.core.api.v1.dto.WorkspaceTransferFeedItemDTO
import com.github.melancholic.fintrace.core.domain.entity.AnchorFeedItem
import com.github.melancholic.fintrace.core.domain.entity.DifferenceFeedItem
import com.github.melancholic.fintrace.core.domain.entity.FeedItem
import com.github.melancholic.fintrace.core.domain.entity.FeedPosition
import com.github.melancholic.fintrace.core.domain.entity.OperationFeedItem
import com.github.melancholic.fintrace.core.domain.entity.TransferFeedItem
import com.github.melancholic.fintrace.core.domain.entity.TransferLeg
import com.github.melancholic.fintrace.core.util.FeedPositionCodec
import org.mapstruct.Mapper

@Mapper
interface FeedItemMapper {

    fun toWorkspaceFeed(items: List<FeedItem>, older: FeedPosition?, newer: FeedPosition?) =
        PagedListResponse(items.map { toWorkspaceItem(it) }, toCursors(older, newer))

    fun toAccountFeed(items: List<FeedItem>, older: FeedPosition?, newer: FeedPosition?) =
        PagedListResponse(items.map { toAccountItem(it) }, toCursors(older, newer))

    fun toWorkspaceItem(item: FeedItem): WorkspaceFeedItemDTO = when (item) {
        is OperationFeedItem -> toOperation(item)
        is TransferFeedItem -> WorkspaceTransferFeedItemDTO(
            cursor = FeedPositionCodec.encode(item.position),
            id = item.id,
            occurredAt = item.occurredAt,
            recordedAt = item.recordedAt,
            comment = item.comment,
            source = toLeg(item.source),
            target = toLeg(item.target),
            rate = TransferMapper.rate(item.source, item.target),
        )

        is AnchorFeedItem, is DifferenceFeedItem ->
            throw IllegalStateException("A workspace's feed has no ${item::class.simpleName}")
    }

    fun toAccountItem(item: FeedItem): AccountFeedItemDTO = when (item) {
        is OperationFeedItem -> toOperation(item)
        is TransferFeedItem -> AccountTransferFeedItemDTO(
            cursor = FeedPositionCodec.encode(item.position),
            id = item.id,
            occurredAt = item.occurredAt,
            recordedAt = item.recordedAt,
            comment = item.comment,
            source = toLeg(item.source),
            target = toLeg(item.target),
            rate = TransferMapper.rate(item.source, item.target),
            side = checkNotNull(item.side) { "A transfer in an account's feed has a side" },
            amount = checkNotNull(item.amount) { "A transfer in an account's feed has an amount" }.abs(),
        )

        is AnchorFeedItem -> BalanceAnchorFeedItemDTO(
            cursor = FeedPositionCodec.encode(item.position),
            id = item.id,
            value = item.value,
            occurredAt = item.occurredAt,
            recordedAt = item.recordedAt,
        )

        is DifferenceFeedItem -> DifferenceFeedItemDTO(
            cursor = FeedPositionCodec.encode(item.position),
            anchorId = item.anchorId,
            amount = item.amount,
            occurredAt = item.occurredAt,
        )
    }

    private fun toOperation(item: OperationFeedItem) = OperationFeedItemDTO(
        cursor = FeedPositionCodec.encode(item.position),
        id = item.id,
        accountId = item.accountId,
        currency = item.currency,
        categoryId = item.categoryId,
        kind = item.kind,
        amount = item.amount.abs(),
        occurredAt = item.occurredAt,
        recordedAt = item.recordedAt,
        comment = item.comment,
    )

    private fun toLeg(leg: TransferLeg) = TransferLegResponse(
        operationId = leg.operationId,
        accountId = leg.accountId,
        currency = leg.currency,
        amount = leg.amount.abs(),
    )

    private fun toCursors(older: FeedPosition?, newer: FeedPosition?) = PageCursors(
        older = older?.let { FeedPositionCodec.encode(it) },
        newer = newer?.let { FeedPositionCodec.encode(it) },
    )
}
