package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.domain.entity.*
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.*

interface FeedDAO {
    fun page(filter: FeedFilter, seek: FeedSeek, limit: Int): List<FeedItem>
    fun exists(filter: FeedFilter, seek: FeedSeek): Boolean
}

@Repository
class FeedDAOImpl(
    private val jdbc: JdbcClient
) : FeedDAO {

    override fun page(filter: FeedFilter, seek: FeedSeek, limit: Int): List<FeedItem> {
        val newer = seek is FeedSeek.Newer
        val items = statement(
            filter,
            seek,
            if (newer) ORDER_ASC else ORDER_DESC,
            limit
        ).query { rs, _ -> toItem(rs) }.list()
        return if (newer) items.reversed() else items
    }

    override fun exists(filter: FeedFilter, seek: FeedSeek): Boolean = statement(
        filter,
        seek,
        "",
        1
    ).query { _, _ -> true }
        .optional().isPresent

    private fun statement(filter: FeedFilter, seek: FeedSeek, orderBy: String, limit: Int): JdbcClient.StatementSpec {
        val conditions = mutableListOf("TRUE")
        val params = mutableMapOf<String, Any?>(
            "workspaceId" to filter.workspaceId,
            "accountId" to filter.accountId,
            "limit" to limit,
        )

        filter.from?.let { conditions += "occurred_at >= :from"; params["from"] = it }
        filter.to?.let { conditions += "occurred_at < :to"; params["to"] = it }
        if (filter.types.isNotEmpty()) {
            conditions += "item_type IN (:types)"
            params["types"] = filter.types.map { it.name }
        }

        val position = when (seek) {
            FeedSeek.Newest -> null
            is FeedSeek.Older -> seek.than.also { conditions += "$POSITION < $CURSOR" }
            is FeedSeek.At -> seek.position.also { conditions += "$POSITION <= $CURSOR" }
            is FeedSeek.Newer -> seek.than.also { conditions += "$POSITION > $CURSOR" }
        }
        position?.let {
            params["cursorAt"] = it.occurredAt
            params["cursorRank"] = it.rank
            params["cursorId"] = it.sortId
        }

        return jdbc.sql(SELECT_FEED.format(conditions.joinToString(" AND "), orderBy)).params(params)
    }

    private fun toItem(rs: ResultSet): FeedItem {
        val position = FeedPosition(
            occurredAt = rs.getLocalDateTime("occurred_at"),
            rank = rs.getInt("item_rank"),
            sortId = rs.getUuid("sort_id")!!,
        )
        return when (FeedItemType.valueOf(rs.getString("item_type"))) {
            FeedItemType.OPERATION -> OperationFeedItem(
                position = position,
                id = position.sortId,
                accountId = rs.getUuid("account_id")!!,
                currency = rs.getString("currency"),
                categoryId = rs.getUuid("category_id")!!,
                kind = OperationKind.valueOf(rs.getString("kind")),
                amount = rs.getBigDecimal("amount"),
                occurredAt = position.occurredAt,
                recordedAt = rs.getLocalDateTime("recorded_at"),
                comment = rs.getString("comment"),
            )

            FeedItemType.TRANSFER -> TransferFeedItem(
                position = position,
                id = position.sortId,
                occurredAt = position.occurredAt,
                recordedAt = rs.getLocalDateTime("recorded_at"),
                comment = rs.getString("comment"),
                source = rs.getLeg("source"),
                target = rs.getLeg("target"),
                side = rs.getString("side")?.let { TransferSide.valueOf(it) },
                amount = rs.getBigDecimal("amount"),
            )

            FeedItemType.BALANCE_ANCHOR -> AnchorFeedItem(
                position = position,
                id = position.sortId,
                value = rs.getBigDecimal("anchor_value"),
                occurredAt = position.occurredAt,
                recordedAt = rs.getLocalDateTime("recorded_at"),
            )

            FeedItemType.DIFFERENCE -> DifferenceFeedItem(
                position = position,
                anchorId = position.sortId,
                amount = rs.getBigDecimal("amount"),
                occurredAt = position.occurredAt,
            )
        }
    }

    private fun ResultSet.getLocalDateTime(column: String): LocalDateTime = getObject(column, LocalDateTime::class.java)

    private fun ResultSet.getUuid(column: String): UUID? = getObject(column, UUID::class.java)

    private fun ResultSet.getLeg(prefix: String) = TransferLeg(
        operationId = getUuid("${prefix}_operation_id")!!,
        accountId = getUuid("${prefix}_account_id")!!,
        currency = getString("${prefix}_currency"),
        amount = getBigDecimal("${prefix}_amount"),
    )

    companion object {
        private const val POSITION = "(occurred_at, item_rank, sort_id)"
        private const val CURSOR = "(:cursorAt, :cursorRank, :cursorId)"

        const val ORDER_DESC = "ORDER BY occurred_at DESC, item_rank DESC, sort_id DESC"
        const val ORDER_ASC = "ORDER BY occurred_at, item_rank, sort_id"

        const val SELECT_FEED = """
            SELECT *
            FROM fn_load_feed(:workspaceId, CAST(:accountId AS uuid))
            WHERE %s
            %s
            LIMIT :limit
        """
    }
}
