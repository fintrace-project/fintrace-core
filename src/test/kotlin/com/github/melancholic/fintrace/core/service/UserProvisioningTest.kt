package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.exception.NotAuthenticatedException
import com.github.melancholic.fintrace.core.security.IdentityProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * A Keycloak user gets a `t_users` row on their first request (§7.4). Each test uses a fresh
 * subject: rows and the id cache outlive a test, and must not leak between them.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class UserProvisioningTest(
    @Autowired private val userService: UserService,
    @Autowired private val identityProvider: IdentityProvider,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {

    @AfterEach
    fun clearCaller() = SecurityContextHolder.clearContext()

    @Test
    fun `creates a user on first sight and returns the same one afterwards`() {
        val subject = subject()

        val first = userService.provision(subject, "alice")
        val again = userService.provision(subject, "alice-renamed")

        assertEquals(first, again)
        assertEquals(listOf("alice"), usernamesOf(subject), "one row; a repeat creates nothing")
    }

    @Test
    fun `a lookup that found nothing does not hide the user once created`() {
        val subject = subject()
        assertNull(userService.getUserIdBySub(subject), "sanity: unknown before")

        val id = userService.provision(subject, "bob")

        // Caching the first miss would keep this null for the cache's whole lifetime
        assertEquals(id, userService.getUserIdBySub(subject))
    }

    @Test
    fun `creates the user from inside a read-only transaction`() {
        // Identity is resolved inside facade transactions, and many of those are read-only
        val subject = subject()
        val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

        readOnly.execute { userService.provision(subject, "carol") }

        assertEquals(listOf("carol"), usernamesOf(subject), "committed despite the read-only caller")
    }

    @Test
    fun `usernames may repeat across users`() {
        val one = userService.provision(subject(), "same-name")
        val other = userService.provision(subject(), "same-name")

        assertNotEquals(one, other)
    }

    @Test
    fun `the caller behind a token is created under its preferred_username`() {
        val subject = subject()
        // With authorities, as Spring builds it for a validated token; without them the token is unauthenticated
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(
            Jwt.withTokenValue("token").header("alg", "none").subject(subject).claim("preferred_username", "dave").build(),
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )

        val id = identityProvider.currentUserId()

        assertEquals(id, identityProvider.currentUserId(), "resolved, not created again")
        assertEquals(listOf("dave"), usernamesOf(subject))
    }

    @Test
    fun `no caller is not authenticated`() {
        assertThrows<NotAuthenticatedException> { identityProvider.currentUserId() }
    }

    private fun subject() = UUID.randomUUID().toString()

    private fun usernamesOf(subject: String): List<String> = jdbc
        .sql("SELECT username FROM t_users WHERE external_id = :subject")
        .param("subject", subject)
        .query(String::class.java)
        .list()
        .filterNotNull()
}
