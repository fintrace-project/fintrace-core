package com.github.melancholic.fintrace.core.exception

import com.github.melancholic.fintrace.core.domain.event.EntityType
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.*

sealed class FintraceCoreException(msg: String, cause: Throwable? = null)
    : RuntimeException(msg, cause) {
    abstract val code: CoreErrorCode
}

// BUSINESS EXCEPTIONS
@ResponseStatus(value = HttpStatus.NOT_FOUND)
open class NotFoundEntityException(message: String = "Entity not found") : FintraceCoreException(message) {
    override val code = CoreErrorCode.NOT_FOUND
}

@ResponseStatus(value = HttpStatus.BAD_REQUEST)
class ValidationError(message: String = "Validation error") : FintraceCoreException(message) {
    override val code = CoreErrorCode.VALIDATION_FAILED
}

// Several reasons share the 409, so the throwing site names which one
@ResponseStatus(value = HttpStatus.CONFLICT)
open class ActionConflictException(override val code: CoreErrorCode, message: String = "Conflict") :
    FintraceCoreException(message)

@ResponseStatus(value = HttpStatus.CONFLICT)
class OperationNotAllowedException(code: CoreErrorCode, message: String = "Operation not allowed") :
    ActionConflictException(code, message)

@ResponseStatus(value = HttpStatus.CONFLICT)
open class OperationNotSupported(code: CoreErrorCode, message: String = "Operation not supported") :
    ActionConflictException(code, message)

// SECURITY EXCEPTIONS
@ResponseStatus(value = HttpStatus.UNAUTHORIZED)
class NotAuthenticatedException : FintraceCoreException("Not authenticated") {
    override val code = CoreErrorCode.NOT_AUTHENTICATED
}

// SYSTEM ERRORS
@ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
open class ApplicationException(message: String = "Server error", cause: Throwable? = null) :
    FintraceCoreException(message, cause) {
    override val code = CoreErrorCode.INTERNAL
}

class BrokenTransferException(message: String = "Broken transfer") : ApplicationException(message)

class OperationIsNotApplicableException(message: String = "Operation is not applicable") : ApplicationException(message)

class ImportCommandFailedException(val entityType: EntityType, val entityId: UUID?, override val cause: Exception) :
    ApplicationException("Import of $entityType '$entityId' failed: ${cause.message}", cause)
