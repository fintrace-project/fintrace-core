package com.github.melancholic.fintrace.core.config

import com.github.melancholic.fintrace.core.config.SecurityConstants.ROLE_ADMIN
import com.github.melancholic.fintrace.core.config.SecurityConstants.ROLE_USER
import com.github.melancholic.fintrace.core.security.RealmRoleConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfiguration {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        return http.authorizeHttpRequests { auth ->
            auth
                .requestMatchers(*PERMITTED_PATHS).permitAll()
                .requestMatchers("/admin/**").hasRole(ROLE_ADMIN)
                .anyRequest().hasRole(ROLE_USER)
        }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .oauth2ResourceServer { rs ->
                rs.jwt {
                    it.jwtAuthenticationConverter(JwtAuthenticationConverter().apply {
                        setJwtGrantedAuthoritiesConverter(RealmRoleConverter())
                    })
                }
            }
            .build()
    }

    companion object {
        val PERMITTED_PATHS = arrayOf(
            "/",
            "/api/public/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/swagger-ui/oauth2-redirect.html"
        )
    }
}