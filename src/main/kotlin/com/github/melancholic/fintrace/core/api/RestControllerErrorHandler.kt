package com.github.melancholic.fintrace.core.api

import com.github.melancholic.fintrace.core.exception.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class RestControllerErrorHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(FintraceCoreException::class)
    fun handleCoreException(e: FintraceCoreException): ProblemDetail {
        val status = statusOf(e)
        return problem(status, e.code, if (status.is5xxServerError) DEF_MESSAGE else e.message).apply {
            if (e is ValidationError && e.field != null) {
                setProperty("errors", listOf(mapOf("field" to e.field, "message" to e.message)))
            }
        }
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ProblemDetail {
        log.error(e) { "Unhandled ${e.javaClass.simpleName}" }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, CoreErrorCode.INTERNAL, DEF_MESSAGE)
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        log.debug { "${statusCode.value()} ${ex.javaClass.simpleName}: ${ex.message}" }
        val response = super.handleExceptionInternal(ex, body, headers, statusCode, request)
        (response?.body as? ProblemDetail)?.apply {
            setProperty("code", springCodeOf(ex, statusCode))
            traceId()?.let { setProperty("traceId", it) }
            if (ex is MethodArgumentNotValidException) {
                setProperty("errors", ex.bindingResult.fieldErrors.map {
                    mapOf("field" to it.field, "message" to it.defaultMessage)
                })
            }
        }
        return response
    }

    private fun springCodeOf(ex: Exception, statusCode: HttpStatusCode) = when {
        ex is MethodArgumentNotValidException -> CoreErrorCode.VALIDATION_FAILED
        ex is NoResourceFoundException -> CoreErrorCode.NOT_FOUND
        statusCode.is5xxServerError -> CoreErrorCode.INTERNAL
        else -> CoreErrorCode.MALFORMED_REQUEST
    }

    private fun problem(status: HttpStatus, code: CoreErrorCode, detail: String?) =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            setProperty("code", code)
            traceId()?.let { setProperty("traceId", it) }
        }

    private fun traceId(): String? = MDC.get("traceId")

    private companion object {
        private val log = KotlinLogging.logger {}

        const val DEF_MESSAGE = "fintrace-core failed on its side. Quote the traceId when reporting it."
    }
}

fun statusOf(e: FintraceCoreException): HttpStatus = when (e) {
    is NotFoundEntityException -> HttpStatus.NOT_FOUND
    is ValidationError -> HttpStatus.BAD_REQUEST
    is ActionConflictException -> HttpStatus.CONFLICT
    is NotAuthenticatedException -> HttpStatus.UNAUTHORIZED
    is ApplicationException -> HttpStatus.INTERNAL_SERVER_ERROR
}
