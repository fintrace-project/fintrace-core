package com.github.melancholic.fintrace.core.domain.entity

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

enum class FeedItemType {
    OPERATION, TRANSFER, BALANCE_ANCHOR, DIFFERENCE;

    companion object {
        val WORKSPACE_FEED_KINDS = setOf(OPERATION, TRANSFER)
        val ALL_KINDS = entries.toSet()
    }

}

enum class TransferSide { SOURCE, TARGET }

data class FeedPosition(
    val occurredAt: LocalDateTime,
    val rank: Int,
    val sortId: UUID
)

data class FeedFilter(
    val workspaceId: UUID,
    val accountId: UUID? = null,
    val from: LocalDateTime?,
    val to: LocalDateTime?,
    val types: Set<FeedItemType>
)

sealed interface FeedSeek {
    data object Newest : FeedSeek

    data class Older(val than: FeedPosition) : FeedSeek
    data class At(val position: FeedPosition) : FeedSeek
    data class Newer(val than: FeedPosition) : FeedSeek
}

sealed interface FeedItem {
    val position: FeedPosition
}

data class OperationFeedItem(
    override val position: FeedPosition,
    val id: UUID,
    val accountId: UUID,
    val currency: String,
    val categoryId: UUID,
    val kind: OperationKind,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val comment: String?
) : FeedItem

data class TransferFeedItem(
    override val position: FeedPosition,
    val id: UUID,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val comment: String?,
    val source: TransferLeg,
    val target: TransferLeg,
    val side: TransferSide?,
    val amount: BigDecimal?
) : FeedItem

data class AnchorFeedItem(
    override val position: FeedPosition,
    val id: UUID,
    val value: BigDecimal,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime
) : FeedItem

data class DifferenceFeedItem(
    override val position: FeedPosition,
    val anchorId: UUID,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime
) : FeedItem

data class FeedRequest(
    val size: Int,
    val older: String?,
    val newer: String?,
    val at: String?,
    val from: LocalDateTime?,
    val to: LocalDateTime?,
    val kinds: Set<FeedItemType>
)

data class FeedPage(
    val items: List<FeedItem>,
    val older: FeedPosition?,
    val newer: FeedPosition?
)