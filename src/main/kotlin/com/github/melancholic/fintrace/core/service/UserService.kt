package com.github.melancholic.fintrace.core.service

import com.github.melancholic.fintrace.core.config.CacheConfiguration.Companion.USERID_BY_SUB_CACHE
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.domain.entity.IUserClaims
import com.github.melancholic.fintrace.core.domain.entity.User
import com.github.melancholic.fintrace.core.exception.ApplicationException
import com.github.melancholic.fintrace.core.exception.UserNotFoundException
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*

interface UserService {
    fun getUserIdBySub(sub: String): UUID?
    fun registerUser(sub: String, username: String): UUID
    fun mergeUser(userId: UUID, claims: IUserClaims): User
}

@Service
class UserServiceImpl(
    private val usersDAO: UsersDAO
) : UserService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun mergeUser(
        userId: UUID,
        claims: IUserClaims
    ): User = usersDAO.mergeUser(userId, claims)
        .orElseThrow { UserNotFoundException("User with id $userId not found in database") }

    @Transactional(readOnly = true)
    @Cacheable(USERID_BY_SUB_CACHE, unless = "#result == null")
    override fun getUserIdBySub(sub: String): UUID? = usersDAO.getUserIdByExternalId(sub)
        .orElseGet { null }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @CachePut(USERID_BY_SUB_CACHE, key = "#sub")
    override fun registerUser(sub: String, username: String): UUID {
        usersDAO.createIfAbsent(sub, username)
        return usersDAO.getUserIdByExternalId(sub)
            .orElseThrow { ApplicationException("Illegal state: user can't be resolved after creation") }
    }

}