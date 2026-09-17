package com.github.melancholic.fintrace.core

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	fun postgresContainer(): PostgreSQLContainer {
		// Must track deploy/docker-compose.yml — testing against a different major is how
		// a version-specific defect reaches the NAS unnoticed.
		return PostgreSQLContainer(DockerImageName.parse("postgres:18"))
	}

	/**
	 * A decoder, so the application starts without an issuer: the resource server requires one, and
	 * only a deployment has a Keycloak. Tests put the caller in place directly — MockMvc's `user()`
	 * and `jwt()` — so no token is ever decoded here. A dynamic property cannot replace this: Boot
	 * evaluates its auto-configuration conditions before a `DynamicPropertyRegistrar` runs, so the
	 * issuer would still look absent and no decoder would be created at all.
	 *
	 * Given an issuer (a `bootTestRun` against Keycloak), the sentinel no longer matches and Boot's
	 * real decoder validates real tokens.
	 */
	@Bean
	@ConditionalOnProperty(
		prefix = "spring.security.oauth2.resourceserver.jwt",
		name = ["issuer-uri"],
		havingValue = "no-issuer-configured",
		matchIfMissing = true,
	)
	fun jwtDecoder(): JwtDecoder = JwtDecoder { throw BadJwtException("Tests carry no real tokens") }

}
