package com.github.melancholic.fintrace.core.util

import com.github.melancholic.fintrace.core.domain.entity.FeedPosition
import com.github.melancholic.fintrace.core.exception.ValidationError
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

object FeedPositionCodec {
    private const val SPLITTER = "|"

    fun encode(position: FeedPosition): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buildString {
            append(position.occurredAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            append(SPLITTER)
            append(position.rank)
            append(SPLITTER)
            append(position.sortId)
        }.toByteArray())
    }

    fun decode(cursor: String): FeedPosition {
        try {
            val str = String(Base64.getUrlDecoder().decode(cursor))
            val parts = str.split(SPLITTER)
            if (parts.size != 3) {
                throw IllegalArgumentException("Cursor should contain exactly 3 parts, split by '$SPLITTER'")
            }
            return FeedPosition(
                occurredAt = LocalDateTime.parse(parts[0]),
                rank = parts[1].toInt(),
                sortId = UUID.fromString(parts[2])
            )
        } catch (e: Exception) {
            throw ValidationError("Malformed cursor")
        }
    }

}