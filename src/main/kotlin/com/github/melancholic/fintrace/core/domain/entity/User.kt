package com.github.melancholic.fintrace.core.domain.entity

import java.time.LocalDateTime
import java.util.*

data class User(
    val id: UUID,
    val username: String,
    val externalId: String,
    val createdAt: LocalDateTime
)

data class UserProfile(
    val id: UUID,
    val username: String,
    val createdAt: LocalDateTime,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val emailVerified: Boolean? = null,
    val avatar: String? = null,
)

sealed interface IUserClaims {
    val sub: String
    val username: String
    val email: String?
    val emailVerified: Boolean?
    val givenName: String?
    val familyName: String?
}

data class UserClaims(
    override val sub: String,
    override val username: String,
    override val email: String?,
    override val emailVerified: Boolean?,
    override val givenName: String?,
    override val familyName: String?
) : IUserClaims