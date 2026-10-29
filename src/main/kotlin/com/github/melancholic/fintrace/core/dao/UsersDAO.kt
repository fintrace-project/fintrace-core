package com.github.melancholic.fintrace.core.dao

import com.github.melancholic.fintrace.core.dao.mapper.UserRowMapper
import com.github.melancholic.fintrace.core.domain.entity.IUserClaims
import com.github.melancholic.fintrace.core.domain.entity.User
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.*

interface UsersDAO {
    fun findUserByUserName(username: String): Optional<User>
    fun getUserIdByExternalId(externalId: String): Optional<UUID>
    fun createIfAbsent(sub: String, username: String)
    fun mergeUser(userId: UUID, claims: IUserClaims): Optional<User>
}

@Repository
class UsersDAOImpl(
    private val jdbc: JdbcClient,
    private val userRowMapper: UserRowMapper,
    private val timestampProvider: TimestampProvider,
    private val uuidGenerator: UUIDGenerator
) : UsersDAO {

    override fun findUserByUserName(username: String): Optional<User> = jdbc.sql(SELECT_BY_USERNAME)
        .param("username", username)
        .query(userRowMapper)
        .optional()

    override fun getUserIdByExternalId(externalId: String): Optional<UUID> = jdbc.sql(SELECT_ID_BY_EXT_ID)
        .param("externalId", externalId)
        .query(UUID::class.java)
        .optional()

    override fun createIfAbsent(sub: String, username: String) {
        jdbc.sql(INSERT_IF_ABSENT)
            .param("id", uuidGenerator.nextUUID())
            .param("externalId", sub)
            .param("username", username)
            .param("createdAt", timestampProvider.now())
            .update()
    }

    override fun mergeUser(
        userId: UUID,
        claims: IUserClaims
    ): Optional<User> = jdbc.sql(MERGE_USER)
        .param("id", userId)
        .param("username", claims.username)
        .query(userRowMapper)
        .optional()

    private companion object {
        const val TABLE_NAME = "t_users"

        const val SELECT_BY_USERNAME = "SELECT id, external_id, username, created_at FROM $TABLE_NAME WHERE username = :username"
        const val SELECT_ID_BY_EXT_ID = "SELECT id FROM $TABLE_NAME WHERE external_id = :externalId"
        const val INSERT_IF_ABSENT = """
            INSERT INTO $TABLE_NAME (id, external_id, username, created_at)
            VALUES (:id, :externalId, :username, :createdAt)
            ON CONFLICT (external_id) DO NOTHING
        """
        const val MERGE_USER = """
            WITH updated AS (
                UPDATE $TABLE_NAME SET username = :username
                WHERE id = :id AND username <> :username
                RETURNING id, external_id, username, created_at
            )
            SELECT id, external_id, username, created_at FROM updated
            UNION ALL
            SELECT id, external_id, username, created_at FROM $TABLE_NAME
            WHERE id = :id AND NOT EXISTS (SELECT 1 FROM updated)
        """
    }
}