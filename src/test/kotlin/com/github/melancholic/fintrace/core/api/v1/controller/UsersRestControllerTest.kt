package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.security.RealmRoleConverter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.core.oidc.StandardClaimNames
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.*
import kotlin.test.assertEquals

/**
 * The HTTP contract for `/api/v1/users/current` and `/api/v1/users/current/id` (§7.4). Each test
 * uses a fresh subject: rows and the id cache outlive a test, and must not leak between them.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class UsersRestControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
) {

    @Test
    fun `the id endpoint creates the caller on first sight and answers with that row's id`() {
        val subject = subject()

        mvc.perform(get(CURRENT_ID).with(token(subject)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(idOf(subject).toString()))
    }

    @Test
    fun `the id endpoint answers the same id on every call`() {
        val subject = subject()

        repeat(2) {
            mvc.perform(get(CURRENT_ID).with(token(subject)))
                .andExpect(jsonPath("$.userId").value(idOf(subject).toString()))
        }
        assertEquals(1, rowsOf(subject), "resolved, not created again")
    }

    @Test
    fun `each caller resolves to their own id`() {
        val one = subject()
        val other = subject()

        mvc.perform(get(CURRENT_ID).with(token(one))).andExpect(status().isOk)
        mvc.perform(get(CURRENT_ID).with(token(other)))
            .andExpect(jsonPath("$.userId").value(idOf(other).toString()))
    }

    @Test
    fun `the profile is the caller's row, with the email from the token`() {
        val subject = subject()

        mvc.perform(get(CURRENT).with(token(subject, StandardClaimNames.EMAIL to "alice@example.com")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(idOf(subject).toString()))
            .andExpect(jsonPath("$.username").value(USERNAME))
            .andExpect(jsonPath("$.email").value("alice@example.com"))
            .andExpect(jsonPath("$.createdAt").exists())
    }

    @Test
    fun `the profile and the id endpoint name the same user`() {
        val subject = subject()

        mvc.perform(get(CURRENT_ID).with(token(subject))).andExpect(status().isOk)
        mvc.perform(get(CURRENT).with(token(subject)))
            .andExpect(jsonPath("$.id").value(idOf(subject).toString()))
    }

    @Test
    fun `the profile refreshes a username renamed in the IdP`() {
        val subject = subject()
        mvc.perform(get(CURRENT_ID).with(token(subject))).andExpect(status().isOk)

        mvc.perform(get(CURRENT).with(token(subject, StandardClaimNames.PREFERRED_USERNAME to "renamed")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("renamed"))

        assertEquals("renamed", usernameOf(subject))
    }

    @Test
    fun `the id endpoint leaves a renamed username alone`() {
        val subject = subject()
        mvc.perform(get(CURRENT_ID).with(token(subject))).andExpect(status().isOk)

        mvc.perform(get(CURRENT_ID).with(token(subject, StandardClaimNames.PREFERRED_USERNAME to "renamed")))
            .andExpect(status().isOk)

        assertEquals(USERNAME, usernameOf(subject), "the resolution path stays write-free")
    }

    @Test
    fun `a token without an email answers no email`() {
        // The smoke tests' service account, and a Keycloak user created without an address
        mvc.perform(get(CURRENT).with(token(subject())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").doesNotExist())
    }

    @Test
    fun `a token without fintrace user reaches neither endpoint`() {
        mvc.perform(get(CURRENT).with(token(subject(), roles = listOf("offline_access"))))
            .andExpect(status().isForbidden)
        mvc.perform(get(CURRENT_ID).with(token(subject(), roles = listOf("offline_access"))))
            .andExpect(status().isForbidden)
    }

    private fun token(
        subject: String,
        vararg claims: Pair<String, Any>,
        roles: List<String> = listOf("fintrace:user"),
    ) = jwt()
        .jwt { jwt ->
            jwt.subject(subject)
                .claim(StandardClaimNames.PREFERRED_USERNAME, USERNAME)
                .claim("realm_access", mapOf("roles" to roles))
            claims.forEach { (name, value) -> jwt.claim(name, value) }
        }
        .authorities(RealmRoleConverter())

    private fun subject() = UUID.randomUUID().toString()

    private fun idOf(subject: String): UUID = jdbc
        .sql("SELECT id FROM t_users WHERE external_id = :subject")
        .param("subject", subject)
        .query(UUID::class.java)
        .single()

    private fun usernameOf(subject: String): String = jdbc
        .sql("SELECT username FROM t_users WHERE external_id = :subject")
        .param("subject", subject)
        .query(String::class.java)
        .single()

    private fun rowsOf(subject: String): Int = jdbc
        .sql("SELECT count(*) FROM t_users WHERE external_id = :subject")
        .param("subject", subject)
        .query(Int::class.java)
        .single()

    private companion object {
        const val CURRENT = "/api/v1/users/current"
        const val CURRENT_ID = "/api/v1/users/current/id"
        const val USERNAME = "token-user"
    }
}
