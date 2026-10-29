package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.api.v1.dto.UserProfileResponse
import com.github.melancholic.fintrace.core.api.v1.dto.UserIdResponse
import com.github.melancholic.fintrace.core.api.v1.mapper.UserMapper
import com.github.melancholic.fintrace.core.config.USER_API_PATH
import com.github.melancholic.fintrace.core.facade.UsersFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(USER_API_PATH)
@Tag(name = "Users")
class UsersRestController(
    private val usersFacade: UsersFacade,
    private val mapper: UserMapper
) {

    @Operation(summary = "Fetch the caller's profile")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found; created on the caller's first request"),
    )
    @GetMapping("/current")
    fun getCurrentProfile(): ResponseEntity<UserProfileResponse> {
        val userProfile = usersFacade.getCurrentUserProfile()
        return ResponseEntity
            .ok(mapper.toResponse(userProfile))
    }

    @Operation(summary = "Resolve the caller's id")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Found; created on the caller's first request"),
    )
    @GetMapping("/current/id")
    fun getCurrentUserId(): ResponseEntity<UserIdResponse> {
        val userId = usersFacade.getCurrentUserId()
        return ResponseEntity
            .ok(UserIdResponse(userId))
    }

}