package com.github.melancholic.fintrace.core.config

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.security.RealmRoleConverter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.*
import kotlin.test.assertEquals

/**
 * The access rules as a Keycloak token meets them (§7.4): `fintrace:user` opens the API,
 * `fintrace:admin` the admin surface, and a first request creates the caller's user.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TokenAuthorizationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
) {

    @Test
    fun `a request without a token is 401 with a Bearer challenge`() {
        mvc.perform(get(WORKSPACES))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
    }

    @Test
    fun `a token without fintrace user is 403`() {
        mvc.perform(get(WORKSPACES).with(token(subject(), "offline_access", "uma_authorization")))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `a token with fintrace user reaches the API and creates its user on the way`() {
        val subject = subject()

        mvc.perform(get(WORKSPACES).with(token(subject, "fintrace:user")))
            .andExpect(status().isOk)

        assertEquals(listOf("token-user"), usernamesOf(subject), "one row, named from preferred_username")
    }

    @Test
    fun `the admin surface needs fintrace admin, not merely fintrace user`() {
        val replay = "/admin/api/v1/workspaces/${UUID.randomUUID()}/replay"

        mvc.perform(post(replay).with(token(subject(), "fintrace:user")))
            .andExpect(status().isForbidden)
        mvc.perform(post(replay).with(token(subject(), "fintrace:user", "fintrace:admin")))
            .andExpect(status().isOk)
    }

    private fun token(subject: String, vararg realmRoles: String) = jwt()
        .jwt {
            it.subject(subject)
                .claim("preferred_username", "token-user")
                .claim("realm_access", mapOf("roles" to realmRoles.toList()))
        }
        // The application's own mapping, so the rules are tested against what Core derives from a token
        .authorities(RealmRoleConverter())

    private fun subject() = UUID.randomUUID().toString()

    private fun usernamesOf(subject: String): List<String> = jdbc
        .sql("SELECT username FROM t_users WHERE external_id = :subject")
        .param("subject", subject)
        .query(String::class.java)
        .list()
        .filterNotNull()

    private companion object {
        const val WORKSPACES = "/api/v1/workspaces"
    }
}
