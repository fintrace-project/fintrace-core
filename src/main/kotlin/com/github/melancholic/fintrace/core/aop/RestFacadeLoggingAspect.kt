package com.github.melancholic.fintrace.core.aop

import io.github.oshai.kotlinlogging.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Component
import org.springframework.transaction.interceptor.TransactionAttributeSource
import org.springframework.web.bind.annotation.ResponseStatus

@Aspect
@Component
class RestFacadeLoggingAspect(
    private val transactionAttributes: TransactionAttributeSource
) {

    @Pointcut("execution(public * com.github.melancholic.fintrace.core.facade.RestFacade+.*(..))")
    fun facadeMethods() {}

    @Around("facadeMethods()")
    fun aroundFacadeMethods(pjp: ProceedingJoinPoint): Any? {
        val read = isRead(pjp)
        log(read) { "[IN] ${methodName(pjp)}" }
        val time = System.currentTimeMillis()
        try {
            return pjp.proceed()
        } catch (e: Exception) {
            val status = statusOf(e)
            val line = "[ERR] ${methodName(pjp)}: $status ${e.javaClass.simpleName}${messageOf(e)}"
            if (status >= 500) logger.error(e) { line } else logger.warn { line }
            throw e
        } finally {
            log(read) { "[OUT] ${methodName(pjp)}: ${System.currentTimeMillis() - time}ms" }
        }
    }

    private fun isRead(pjp: ProceedingJoinPoint): Boolean =
        transactionAttributes
            .getTransactionAttribute((pjp.signature as MethodSignature).method, pjp.target?.javaClass)
            ?.isReadOnly == true

    private fun log(read: Boolean, message: () -> String) =
        if (read) logger.debug(message) else logger.info(message)

    private fun statusOf(e: Exception): Int =
        AnnotatedElementUtils.findMergedAnnotation(e.javaClass, ResponseStatus::class.java)?.code?.value() ?: 500

    private fun messageOf(e: Exception): String =
        if (e.javaClass.name.startsWith(CORE_PACKAGE)) ": ${e.message}" else ""

    fun methodName(pjp: ProceedingJoinPoint): String =
        "${pjp.signature.declaringType.simpleName}.${pjp.signature.name}(${methodArgs(pjp)})"

    private fun methodArgs(pjp: ProceedingJoinPoint): String {
        val declared = (pjp.signature as MethodSignature).parameterTypes
        return pjp.args.indices.joinToString(", ") { i ->
            (pjp.args[i]?.javaClass ?: declared[i]).simpleName
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val CORE_PACKAGE = "com.github.melancholic.fintrace."
    }
}