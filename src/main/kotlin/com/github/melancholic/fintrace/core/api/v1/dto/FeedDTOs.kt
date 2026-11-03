package com.github.melancholic.fintrace.core.api.v1.dto

import com.github.melancholic.fintrace.core.domain.entity.FeedItemType
import com.github.melancholic.fintrace.core.domain.entity.OperationKind
import com.github.melancholic.fintrace.core.domain.entity.TransferSide
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

sealed interface FeedItemDTO {
    val type: FeedItemType
    val cursor: String
}

@Schema(
    oneOf = [OperationFeedItemDTO::class, WorkspaceTransferFeedItemDTO::class],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = "OPERATION", schema = OperationFeedItemDTO::class),
        DiscriminatorMapping(value = "TRANSFER", schema = WorkspaceTransferFeedItemDTO::class),
    ]
)
sealed interface WorkspaceFeedItemDTO : FeedItemDTO

@Schema(
    oneOf = [
        OperationFeedItemDTO::class,
        AccountTransferFeedItemDTO::class,
        BalanceAnchorFeedItemDTO::class,
        DifferenceFeedItemDTO::class,
    ],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = "OPERATION", schema = OperationFeedItemDTO::class),
        DiscriminatorMapping(value = "TRANSFER", schema = AccountTransferFeedItemDTO::class),
        DiscriminatorMapping(value = "BALANCE_ANCHOR", schema = BalanceAnchorFeedItemDTO::class),
        DiscriminatorMapping(value = "DIFFERENCE", schema = DifferenceFeedItemDTO::class),
    ]
)
sealed interface AccountFeedItemDTO : FeedItemDTO

data class OperationFeedItemDTO(
    override val cursor: String,
    val id: UUID,
    val accountId: UUID,
    val currency: String,
    val categoryId: UUID,
    val kind: OperationKind,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val comment: String?
) : WorkspaceFeedItemDTO, AccountFeedItemDTO {
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    override val type get() = FeedItemType.OPERATION
}

data class WorkspaceTransferFeedItemDTO(
    override val cursor: String,
    val id: UUID,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val comment: String?,
    val source: TransferLegResponse,
    val target: TransferLegResponse,
    val rate: BigDecimal
) : WorkspaceFeedItemDTO {
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    override val type get() = FeedItemType.TRANSFER
}

data class AccountTransferFeedItemDTO(
    override val cursor: String,
    val id: UUID,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime,
    val comment: String?,
    val source: TransferLegResponse,
    val target: TransferLegResponse,
    val rate: BigDecimal,
    val side: TransferSide,
    val amount: BigDecimal
) : AccountFeedItemDTO {
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    override val type get() = FeedItemType.TRANSFER
}

data class BalanceAnchorFeedItemDTO(
    override val cursor: String,
    val id: UUID,
    val value: BigDecimal,
    val occurredAt: LocalDateTime,
    val recordedAt: LocalDateTime
) : AccountFeedItemDTO {
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    override val type get() = FeedItemType.BALANCE_ANCHOR
}

data class DifferenceFeedItemDTO(
    override val cursor: String,
    val anchorId: UUID,
    val amount: BigDecimal,
    val occurredAt: LocalDateTime
) : AccountFeedItemDTO {
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    override val type get() = FeedItemType.DIFFERENCE
}
