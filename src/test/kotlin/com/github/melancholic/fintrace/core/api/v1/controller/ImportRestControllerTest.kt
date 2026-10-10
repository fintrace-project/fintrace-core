package com.github.melancholic.fintrace.core.api.v1.controller

import com.github.melancholic.fintrace.core.TestWorkspaces
import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import com.github.melancholic.fintrace.core.dao.UsersDAO
import com.github.melancholic.fintrace.core.service.WorkspaceService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.util.*
import kotlin.test.assertEquals

/**
 * The HTTP contract for `POST /workspaces/{id}/import` (2.18, 2.20).
 *
 * The import itself is covered at the facade level; what only a real request reaches is the part
 * this checks — that a rejected payload answers **400** rather than 200-with-a-failure-inside, and
 * that the body says what was wrong. The second half matters because Boot's error body hides
 * exception messages, so the response DTO is the only place a client learns the reason.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ImportRestControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val workspaceService: WorkspaceService,
    @Autowired private val usersDAO: UsersDAO,
    @Autowired private val transactions: TransactionTemplate,
) {

    private lateinit var workspaceId: UUID

    private val importPath get() = "/api/v1/workspaces/$workspaceId/import"

    @BeforeEach
    fun seed() {
        TestWorkspaces.reset(jdbc)
        workspaceId = TestWorkspaces.createWithCategories(transactions, workspaceService, usersDAO)
    }

    @Test
    fun `imports a payload and answers with the job`() {
        mvc.perform(importing(VALID_PAYLOAD))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.accounts").value(1))
            .andExpect(jsonPath("$.importerName").value("mok"))
            .andExpect(jsonPath("$.problems").isEmpty)
    }

    @Test
    fun `answers 400 when the payload is refused`() {
        mvc.perform(importing(V4_ID_PAYLOAD))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value("FAILED"))
    }

    /** The reason has to survive into the body, or a 400 tells the importer author nothing. */
    @Test
    fun `the refusal names the offending id and its section`() {
        mvc.perform(importing(V4_ID_PAYLOAD))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.problems.length()").value(1))
            .andExpect(jsonPath("$.problems[0].code").value("WRONG_UUID_VERSION"))
            .andExpect(jsonPath("$.problems[0].aggregateType").value("ACCOUNT"))
            .andExpect(jsonPath("$.problems[0].affectedIDs[0]").value(V4_ACCOUNT))
            .andExpect(jsonPath("$.problems[0].message").exists())
    }

    @Test
    fun `a refused payload leaves the workspace importable again`() {
        mvc.perform(importing(V4_ID_PAYLOAD)).andExpect(status().isBadRequest)

        mvc.perform(importing(VALID_PAYLOAD))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
    }

    @Test
    fun `a section field breaking its constraint is refused before the import starts`() {
        mvc.perform(importing(VALID_PAYLOAD.replace("\"EUR\"", "\"eur\"")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").doesNotExist())

        assertEquals(0, jdbc.sql("SELECT count(*) FROM t_import_jobs").query(Int::class.java).single())
    }

    @Test
    fun `an envelope without a payload is refused`() {
        mvc.perform(importing("""{"importerName": "mok", "importerVersion": "1.2.3"}"""))
            .andExpect(status().isBadRequest)
    }

    private fun importing(body: String) = post(importPath)
        .with(user(USER))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)

    private companion object {
        val USER = TestWorkspaces.TEST_SUBJECT

        const val V7_ACCOUNT = "01930000-0000-7000-8000-00000000cca1"
        const val V4_ACCOUNT = "01930000-0000-4000-8000-00000000dead"

        /** Hand-written JSON, since no importer exists to produce it yet. */
        fun payloadWithAccount(id: String) = """
            {
              "importerName": "mok",
              "importerVersion": "1.2.3",
              "payload": {
                "accounts": [
                  {
                    "id": "$id",
                    "externalRef": "mok-account:1",
                    "name": "Мои деньги",
                    "currency": "EUR",
                    "icon": null,
                    "initialBalance": null,
                    "initialBalanceAt": null
                  }
                ]
              }
            }
        """

        val VALID_PAYLOAD = payloadWithAccount(V7_ACCOUNT)
        val V4_ID_PAYLOAD = payloadWithAccount(V4_ACCOUNT)
    }
}
