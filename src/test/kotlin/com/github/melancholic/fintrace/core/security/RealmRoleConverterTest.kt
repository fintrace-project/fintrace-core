package com.github.melancholic.fintrace.core.security

import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import kotlin.test.assertEquals

/** Which realm roles in a Keycloak token become authorities in Core (§7.4). */
class RealmRoleConverterTest {

    private val converter = RealmRoleConverter()

    @Test
    fun `maps each fintrace realm role to its authority`() {
        assertEquals(
            setOf("ROLE_USER", "ROLE_ADMIN", "ROLE_IMPORTER"),
            authoritiesOf(withRoles("fintrace:user", "fintrace:admin", "fintrace:importer")),
        )
    }

    @Test
    fun `ignores Keycloak's own roles and anything without the fintrace prefix`() {
        assertEquals(
            setOf("ROLE_USER"),
            authoritiesOf(withRoles("fintrace:user", "offline_access", "uma_authorization", "default-roles-fintrace", "user", "admin")),
        )
    }

    @Test
    fun `a token with no fintrace role grants nothing`() {
        assertEquals(emptySet(), authoritiesOf(withRoles("offline_access", "uma_authorization")))
    }

    @Test
    fun `a token without realm_access grants nothing`() {
        assertEquals(emptySet(), authoritiesOf(token { }))
    }

    @Test
    fun `a malformed roles claim grants nothing rather than failing`() {
        assertEquals(emptySet(), authoritiesOf(token { it.claim("realm_access", mapOf("roles" to "fintrace:user")) }))
    }

    private fun authoritiesOf(jwt: Jwt): Set<String> = converter.convert(jwt).map { it.authority!! }.toSet()

    private fun withRoles(vararg roles: String) = token { it.claim("realm_access", mapOf("roles" to roles.toList())) }

    private fun token(claims: (Jwt.Builder) -> Unit): Jwt =
        Jwt.withTokenValue("token").header("alg", "none").subject("subject").also(claims).build()
}
