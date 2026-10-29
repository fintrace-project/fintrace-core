package com.github.melancholic.fintrace.core.security


import com.github.melancholic.fintrace.core.domain.entity.IUserClaims
import com.github.melancholic.fintrace.core.domain.entity.UserClaims
import com.github.melancholic.fintrace.core.exception.NotAuthenticatedException
import com.github.melancholic.fintrace.core.service.UserService
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.StandardClaimNames
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.util.*


interface IdentityProvider {
    fun currentSubject(): String
    fun currentUserId(): UUID
    fun currentUserClaims(): IUserClaims
}

@Component
open class IdentityProviderImpl(
    private val userService: UserService
) : IdentityProvider {

    override fun currentSubject(): String = getAuthentication().name

    override fun currentUserId(): UUID {
        val authentication = getAuthentication()

        val subject = currentSubject()
        return userService.getUserIdBySub(subject)
            ?: userService.registerUser(subject, usernameOf(authentication))
    }

    override fun currentUserClaims() = UserClaims(
        sub = currentSubject(),
        username = usernameOf(getAuthentication()),
        email = getStringClaim(claim = StandardClaimNames.EMAIL),
        emailVerified = getBooleanClaim(claim = StandardClaimNames.EMAIL_VERIFIED),
        givenName = getStringClaim(claim = StandardClaimNames.GIVEN_NAME),
        familyName = getStringClaim(claim = StandardClaimNames.FAMILY_NAME),
    )

    private fun getAuthentication(): Authentication = SecurityContextHolder.getContext().authentication
        ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        ?: throw NotAuthenticatedException()

    private fun usernameOf(authentication: Authentication): String =
        getStringClaim(authentication, StandardClaimNames.PREFERRED_USERNAME)
            ?: authentication.name

    private fun getStringClaim(authentication: Authentication = getAuthentication(), claim: String): String? =
        (authentication.principal as? Jwt)
            ?.getClaimAsString(claim)

    private fun getBooleanClaim(authentication: Authentication = getAuthentication(), claim: String): Boolean? =
        (authentication.principal as? Jwt)
            ?.getClaimAsBoolean(claim)
}