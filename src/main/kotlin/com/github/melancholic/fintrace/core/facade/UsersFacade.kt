package com.github.melancholic.fintrace.core.facade

import com.github.melancholic.fintrace.core.domain.entity.UserProfile
import com.github.melancholic.fintrace.core.security.IdentityProvider
import com.github.melancholic.fintrace.core.service.UserService
import org.springframework.stereotype.Service
import java.util.*

sealed interface UsersFacade : RestFacade {
    fun getCurrentUserProfile(): UserProfile
    fun getCurrentUserId(): UUID
}

@Service
class UsersFacadeImpl(
    private val identityProvider: IdentityProvider,
    private val userService: UserService
) : UsersFacade {


    override fun getCurrentUserProfile(): UserProfile {
        val claims = identityProvider.currentUserClaims()
        val user = userService.mergeUser(identityProvider.currentUserId(), claims)

        return UserProfile(
            id = user.id,
            username = claims.username,
            createdAt = user.createdAt,
            firstName = claims.givenName,
            lastName = claims.familyName,
            email = claims.email,
            emailVerified = claims.emailVerified
        )
    }

    override fun getCurrentUserId(): UUID = identityProvider.currentUserId()

}