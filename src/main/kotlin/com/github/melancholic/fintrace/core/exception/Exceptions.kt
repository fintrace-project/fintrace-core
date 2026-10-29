package com.github.melancholic.fintrace.core.exception

import com.github.melancholic.fintrace.core.domain.event.EntityType
import java.util.*

sealed class FintraceCoreException(msg: String, cause: Throwable? = null)
    : RuntimeException(msg, cause) {
    abstract val code: CoreErrorCode
}

// BUSINESS EXCEPTIONS
open class NotFoundEntityException(message: String = "Entity not found") : FintraceCoreException(message) {
    override val code = CoreErrorCode.NOT_FOUND
}

class ValidationError(message: String = "Validation error") : FintraceCoreException(message) {
    override val code = CoreErrorCode.VALIDATION_FAILED
}

// Several reasons share the 409, so the throwing site names which one
open class ActionConflictException(override val code: CoreErrorCode, message: String = "Conflict") :
    FintraceCoreException(message)

class OperationNotAllowedException(code: CoreErrorCode, message: String = "Operation not allowed") :
    ActionConflictException(code, message)

open class OperationNotSupported(code: CoreErrorCode, message: String = "Operation not supported") :
    ActionConflictException(code, message)

// SECURITY EXCEPTIONS
class NotAuthenticatedException : FintraceCoreException("Not authenticated") {
    override val code = CoreErrorCode.NOT_AUTHENTICATED
}

// SYSTEM ERRORS
open class ApplicationException(message: String = "Server error", cause: Throwable? = null) :
    FintraceCoreException(message, cause) {
    override val code = CoreErrorCode.INTERNAL
}

class BrokenTransferException(message: String = "Broken transfer") : ApplicationException(message)

class OperationIsNotApplicableException(message: String = "Operation is not applicable") : ApplicationException(message)

class ImportCommandFailedException(val entityType: EntityType, val entityId: UUID?, override val cause: Exception) :
    ApplicationException("Import of $entityType '$entityId' failed: ${cause.message}", cause)
