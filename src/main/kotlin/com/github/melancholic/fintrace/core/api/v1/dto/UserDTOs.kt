package com.github.melancholic.fintrace.core.api.v1.dto

import java.time.LocalDateTime
import java.util.*

data class UserProfileResponse(
    val id: UUID,
    val username: String,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val createdAt: LocalDateTime,
    val avatar: String?
)

data class UserIdResponse(
    val userId: UUID
)