package com.github.melancholic.fintrace.core.api

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.exception.*
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RestControllerErrorHandlerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val handler: RestControllerErrorHandler,
) {

    @BeforeEach
    fun clean() {
        TestWorkspaces.reset(jdbc)
    }

    @Test
    fun `a missing entity is NOT_FOUND`() {
        mvc.perform(get("$WORKSPACES/${UUID.randomUUID()}").with(user(USER)))
            .andExpect(status().isNotFound)
            .problem("NOT_FOUND")
    }

    @Test
    fun `a conflict names its reason`() {
        val id = createdId()

        mvc.perform(post("$WORKSPACES/$id/archive").with(user(USER)).with(csrf()))
            .andExpect(status().isConflict)
            .problem("WORKSPACE_STATUS_CONFLICT")
    }

    @Test
    fun `a hand-written ValidationError names its field`() {
        // ZZZ passes the DTO's pattern; only the validation service knows it is no currency.
        mvc.perform(create("""{"workspaceName":"budget","defaultCurrency":"ZZZ"}"""))
            .andExpect(status().isBadRequest)
            .problem("VALIDATION_FAILED")
            .andExpect(jsonPath("$.errors[0].field").value("defaultCurrency"))
            .andExpect(jsonPath("$.errors[0].message").value(startsWith("Unknown currency code")))
    }

    @Test
    fun `a Bean Validation 400 names its field`() {
        mvc.perform(create("""{"workspaceName":"","defaultCurrency":"EUR"}"""))
            .andExpect(status().isBadRequest)
            .problem("VALIDATION_FAILED")
            .andExpect(jsonPath("$.errors[?(@.field == 'workspaceName')]").exists())
    }

    @Test
    fun `an unreadable body is MALFORMED_REQUEST`() {
        mvc.perform(create("""{"workspaceName":"""))
            .andExpect(status().isBadRequest)
            .problem("MALFORMED_REQUEST")
    }

    @Test
    fun `a path id that is not a UUID is MALFORMED_REQUEST`() {
        mvc.perform(get("$WORKSPACES/not-a-uuid").with(user(USER)))
            .andExpect(status().isBadRequest)
            .problem("MALFORMED_REQUEST")
    }

    @Test
    fun `a missing required parameter is MALFORMED_REQUEST`() {
        val id = createdId()

        mvc.perform(delete("$WORKSPACES/$id").with(user(USER)).with(csrf()))
            .andExpect(status().isBadRequest)
            .problem("MALFORMED_REQUEST")
    }

    @Test
    fun `an ApplicationException never leaks its message`() {
        val body = handler.handleCoreException(ApplicationException("secret internals"))

        assertEquals(500, body.status)
        assertEquals(CoreErrorCode.INTERNAL, body.properties?.get("code"))
        assertTrue(body.detail!!.startsWith(GENERIC_DETAIL), body.detail)
    }

    @Test
    fun `an unexpected exception is INTERNAL and never leaks its message`() {
        val body = handler.handleUnexpected(IllegalStateException("secret internals"))

        assertEquals(500, body.status)
        assertEquals(CoreErrorCode.INTERNAL, body.properties?.get("code"))
        assertTrue(body.detail!!.startsWith(GENERIC_DETAIL), body.detail)
    }

    @Test
    fun `carries the trace id when tracing has one`() {
        MDC.putCloseable("traceId", "4bf92f3577b34da6a3ce929d0e0e4736").use {
            val body = handler.handleCoreException(NotFoundEntityException("gone"))
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", body.properties?.get("traceId"))
        }
    }

    @Test
    fun `statusOf gives each family its status`() {
        assertEquals(HttpStatus.NOT_FOUND, statusOf(NotFoundEntityException()))
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(ValidationError()))
        assertEquals(HttpStatus.CONFLICT, statusOf(ActionConflictException(CoreErrorCode.ANCHOR_ORDER)))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(NotAuthenticatedException()))
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, statusOf(ApplicationException()))
    }

    private fun ResultActions.problem(code: String): ResultActions =
        andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.detail").value(not(startsWith(GENERIC_DETAIL))))

    private fun create(body: String) = post(WORKSPACES).with(user(USER)).with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)

    private fun createdId(): UUID {
        val json = mvc.perform(create("""{"workspaceName":"budget","defaultCurrency":"EUR"}"""))
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return UUID.fromString(Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1])
    }

    private companion object {
        val USER = TestWorkspaces.TEST_SUBJECT
        const val WORKSPACES = "/api/v1/workspaces"
        const val GENERIC_DETAIL = "fintrace-core failed on its side"
    }
}
