package com.github.melancholic.fintrace.core.api

import com.github.melancholic.fintrace.core.config.MDCConstants.MDC_USER_ID
import com.github.melancholic.fintrace.core.config.MDCConstants.MDC_WORKSPACE_ID
import com.github.melancholic.fintrace.core.security.IdentityProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

@Component
class LoggingContextInterceptor(
    private val identityProvider: IdentityProvider
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        runCatching { identityProvider.currentUserId() }.getOrNull()
            ?.let { MDC.put(MDC_USER_ID, it.toString()) }

        @Suppress("UNCHECKED_CAST")
        (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>)
            ?.get(MDC_WORKSPACE_ID)
            ?.let { MDC.put(MDC_WORKSPACE_ID, it) }

        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        MDC.remove(MDC_USER_ID)
        MDC.remove(MDC_WORKSPACE_ID)
    }
}
