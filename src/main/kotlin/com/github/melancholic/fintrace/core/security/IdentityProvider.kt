package com.github.melancholic.fintrace.core.security


import com.github.melancholic.fintrace.core.exception.NotAuthenticatedException
import com.github.melancholic.fintrace.core.service.UserService
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.util.*


interface IdentityProvider {
    fun currentSubject(): String
    fun currentUserId(): UUID
}

@Component
open class IdentityProviderImpl(
    private val userService: UserService
) : IdentityProvider {

    override fun currentSubject(): String = getAuthentication().name

    override fun currentUserId(): UUID {
        val authentication = getAuthentication()

        val subject = authentication.name
        return userService.getUserIdBySub(subject)
            ?: userService.provision(subject, usernameOf(authentication))
    }

    private fun getAuthentication(): Authentication = SecurityContextHolder.getContext().authentication
        ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        ?: throw NotAuthenticatedException()

    private fun usernameOf(authentication: Authentication): String = (authentication.principal as? Jwt)
        ?.getClaimAsString("preferred_username")
        ?: authentication.name
}