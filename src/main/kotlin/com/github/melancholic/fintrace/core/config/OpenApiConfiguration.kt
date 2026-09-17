package com.github.melancholic.fintrace.core.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.security.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration(
    @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") private val issuer: String
) {
    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .components(
            Components().addSecuritySchemes(
                SCHEME,
                SecurityScheme()
                    .type(SecurityScheme.Type.OAUTH2)
                    .flows(
                        OAuthFlows().authorizationCode(
                            OAuthFlow()
                                .authorizationUrl("$issuer/protocol/openid-connect/auth")
                                .tokenUrl("$issuer/protocol/openid-connect/token")
                                .scopes(Scopes().addString("openid", "Sign in"))
                        )
                    )
            )
        )
        .addSecurityItem(SecurityRequirement().addList(SCHEME))

    private companion object {
        const val SCHEME = "keycloak"
    }
}