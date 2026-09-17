package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.config.CacheConfiguration.Companion.USERID_BY_SUB_CACHE
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.exception.ApplicationException
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface UserService {
    fun getUserIdBySub(sub: String): UUID?
    fun provision(sub: String, username: String): UUID
}

@Service
class UserServiceImpl(
    private val usersDAO: UsersDAO
) : UserService {

    @Transactional(readOnly = true)
    @Cacheable(USERID_BY_SUB_CACHE, unless = "#result == null")
    override fun getUserIdBySub(sub: String): UUID? = usersDAO.getUserIdByExternalId(sub)
        .orElseGet { null }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @CachePut(USERID_BY_SUB_CACHE, key = "#sub")
    override fun provision(sub: String, username: String): UUID {
        usersDAO.createIfAbsent(sub, username)
        return usersDAO.getUserIdByExternalId(sub)
            .orElseThrow { ApplicationException("Illegal state: user can't be resolved after creation") }
    }

}