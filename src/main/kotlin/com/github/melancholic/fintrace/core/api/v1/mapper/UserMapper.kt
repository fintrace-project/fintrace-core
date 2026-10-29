package com.github.melancholic.fintrace.core.api.v1.mapper

import com.github.melancholic.fintrace.core.api.v1.dto.UserProfileResponse
import com.github.melancholic.fintrace.core.domain.entity.UserProfile
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")
interface UserMapper {
    fun toResponse(domain: UserProfile): UserProfileResponse
}