package com.github.melancholic.fintrace.core.security

import com.github.melancholic.fintrace.core.config.SecurityConstants.ROLE_ADMIN
import com.github.melancholic.fintrace.core.config.SecurityConstants.ROLE_IMPORTER
import com.github.melancholic.fintrace.core.config.SecurityConstants.ROLE_USER
import org.springframework.core.convert.converter.Converter
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt

class RealmRoleConverter : Converter<Jwt, Collection<GrantedAuthority>> {

    override fun convert(jwt: Jwt): Collection<GrantedAuthority> =
        (jwt.getClaimAsMap("realm_access")?.get("roles") as? Collection<*>).orEmpty()
            .mapNotNull { AUTHORITY_BY_REALM_ROLE[it] }
            .map(::SimpleGrantedAuthority)

    companion object {
        val AUTHORITY_BY_REALM_ROLE = mapOf(
            "fintrace:user" to "ROLE_$ROLE_USER",
            "fintrace:admin" to "ROLE_$ROLE_ADMIN",
            "fintrace:importer" to "ROLE_$ROLE_IMPORTER",
        )
    }

}