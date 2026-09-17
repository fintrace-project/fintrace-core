package com.github.melancholic.fintrace.core.api

import com.github.melancholic.fintrace.core.config.MDCConstants.MDC_USER_ID
import com.github.melancholic.fintrace.core.config.MDCConstants.MDC_WORKSPACE_ID
import com.github.melancholic.fintrace.core.exception.NotAuthenticatedException
import com.github.melancholic.fintrace.core.security.IdentityProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

class LoggingContextInterceptorTest {

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID().toString()
    private val response = MockHttpServletResponse()

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `puts the caller and the workspace from the path`() {
        val request = requestWithPathVariables(mapOf("workspaceId" to workspaceId))

        interceptorFor { userId }.preHandle(request, response, Any())

        assertEquals(userId.toString(), MDC.get(MDC_USER_ID))
        assertEquals(workspaceId, MDC.get(MDC_WORKSPACE_ID))
    }

    @Test
    fun `leaves the workspace out when the path has none`() {
        interceptorFor { userId }.preHandle(requestWithPathVariables(emptyMap()), response, Any())

        assertEquals(userId.toString(), MDC.get(MDC_USER_ID))
        assertNull(MDC.get(MDC_WORKSPACE_ID))
    }

    @Test
    fun `lets an anonymous request through without a user`() {
        val request = requestWithPathVariables(emptyMap())

        val proceeds = interceptorFor { throw NotAuthenticatedException() }.preHandle(request, response, Any())

        assertEquals(true, proceeds)
        assertNull(MDC.get(MDC_USER_ID))
    }

    @Test
    fun `clears both keys once the request completes`() {
        val interceptor = interceptorFor { userId }
        val request = requestWithPathVariables(mapOf("workspaceId" to workspaceId))
        interceptor.preHandle(request, response, Any())

        interceptor.afterCompletion(request, response, Any(), RuntimeException("handler failed"))

        assertNull(MDC.get(MDC_USER_ID))
        assertNull(MDC.get(MDC_WORKSPACE_ID))
    }

    private fun requestWithPathVariables(variables: Map<String, String>) = MockHttpServletRequest().apply {
        setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables)
    }

    private fun interceptorFor(currentUserId: () -> UUID) = LoggingContextInterceptor(object : IdentityProvider {
        override fun currentSubject(): String = throw UnsupportedOperationException()
        override fun currentUserId(): UUID = currentUserId()
    })
}
