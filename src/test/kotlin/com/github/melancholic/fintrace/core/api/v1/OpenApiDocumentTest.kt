package com.github.melancholic.fintrace.core.api.v1

import com.github.melancholic.fintrace.core.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import com.github.melancholic.fintrace.core.exception.CoreErrorCode
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.not
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

/**
 * The generated contract, which M4 will use for frontend type generation (task 3.14).
 * Reachable without authentication so the docs are usable before you have a token.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentTest(
	@Autowired private val mvc: MockMvc,
	@Autowired private val mapper: ObjectMapper,
) {

	@Test
	fun `serves the OpenAPI document anonymously`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.openapi").exists())
	}

	@Test
	fun `documents both endpoint families`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/operations'].post.summary").exists())
			.andExpect(
				jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/operations/{operationId}'].get.summary")
					.exists()
			)
			.andExpect(
				jsonPath("$.paths['/admin/api/v1/workspaces/{workspaceId}/replay'].post.summary").exists()
			)
	}

	@Test
	fun `describes the documented response codes`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(
				jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/operations'].post.responses.201")
					.exists()
			)
			.andExpect(
				jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/operations/{operationId}'].get.responses.404")
					.exists()
			)
	}

	@Test
	fun `documents every workspace endpoint`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/workspaces'].post.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].put.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].delete.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/archive'].post.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/archive'].delete.summary").exists())
	}

	@Test
	fun `documents the conflict and version responses`() {
		// A generated client has to know 409 is reachable, or optimistic locking looks like a
		// server error to it.
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].put.responses.409").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].delete.responses.409").exists())
			.andExpect(
				jsonPath("$.paths['/api/v1/workspaces/{workspaceId}'].delete.parameters[?(@.name=='version')]")
					.exists()
			)
	}

	@Test
	fun `documents every account endpoint`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts'].post.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts/{accountId}'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts/{accountId}'].put.summary").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts/{accountId}'].delete.summary").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/accounts/{accountId}/restore'].post.summary").exists())
	}

    @Test
    fun `documents every category endpoint`() {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/categories'].post.summary").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/categories'].get.summary").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/categories/{categoryId}'].get.summary").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/categories/{categoryId}'].put.summary").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/categories/{categoryId}'].delete.summary").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/categories/{categoryId}/restore'].post.summary").exists())
    }

    @Test
    fun `documents the tree conflicts`() {
        // A generated client has to know a move can be refused, or a cross-branch move or a cycle
        // looks like a server fault to it.
        mvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/categories/{categoryId}'].put.responses.409").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/categories/{categoryId}'].delete.responses.409").exists())
    }

    @Test
    fun `documents every transfer endpoint`() {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/transfers'].post.summary").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/transfers/{transferId}'].get.summary").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/transfers/{transferId}'].put.summary").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/transfers/{transferId}'].delete.summary").exists())
    }

	@Test
	fun `documents every balance anchor endpoint`() {
		val anchors = "/api/v1/workspaces/{workspaceId}/accounts/{accountId}/balance-anchors"
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['$anchors'].post.summary").exists())
			.andExpect(jsonPath("$.paths['$anchors'].get.summary").exists())
			.andExpect(jsonPath("$.paths['$anchors/{anchorId}'].get.summary").exists())
			.andExpect(jsonPath("$.paths['$anchors/{anchorId}'].delete.summary").exists())
			// Deleting anything but the newest anchor is refused (§10.4), and a generated client
			// has to expect that or it looks like a server fault.
			.andExpect(jsonPath("$.paths['$anchors/{anchorId}'].delete.responses.409").exists())
            // The unexplained difference (1.25) is part of the contract a generated client builds from.
            .andExpect(jsonPath("$.components.schemas.BalanceAnchorResponse.properties.difference").exists())
	}

    @Test
    fun `documents the pair conflicts on both families`() {
        // Two refusals a generated client has to expect: a leg moving onto an archived account,
        // and a write aimed at a leg through /operations, where the pair is not writable (§10.3).
        mvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/transfers/{transferId}'].put.responses.409").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/operations/{operationId}'].put.responses.409").exists())
            .andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/operations/{operationId}'].delete.responses.409").exists())
    }

	@Test
	fun `documents the balances report`() {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/statistics/balances/accounts'].get.summary").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/statistics/balances/accounts'].get.responses.400").exists())
			.andExpect(jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/statistics/balances/accounts'].get.responses.404").exists())
	}

    @Test
    fun `documents the import endpoint`() {
        val import = "/api/v1/workspaces/{workspaceId}/import"
        mvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.paths['$import'].post.summary").exists())
            // The refusal a client has to expect: the workspace is not NEW, not empty, or already
            // importing (§4.1.1). Without it, a second import looks like a server fault.
            .andExpect(jsonPath("$.paths['$import'].post.responses.409").exists())
            .andExpect(jsonPath("$.paths['$import'].post.responses.404").exists())
            // The importer supplies the ids (§5.1), so a generated client must send them.
            .andExpect(jsonPath("$.components.schemas.ImportAccountRequest.properties.id").exists())
            // importId and the counts are what 2.24 promises back.
            .andExpect(jsonPath("$.components.schemas.ImportJobResponse.properties.id").exists())
            .andExpect(jsonPath("$.components.schemas.ImportJobResponse.properties.accounts").exists())
    }

    @Test
    fun `promises a CoreProblem on every error but the filter chain's`() {
        // The filter chain answers 401 and 403 before the error handler, with no body.
        val body = mvc.perform(get("/v3/api-docs")).andReturn().response.contentAsString
        val paths = mapper.readTree(body).get("paths")
        val wrong = paths.properties().flatMap { (path, operations) ->
            operations.properties().flatMap { (method, operation) ->
                operation.get("responses").properties()
                    .filter { (code, _) -> code.first() in "45" && code != "422" }
                    .filter { (code, response) ->
                        val ref = response.at("/content/application~1problem+json/schema/\$ref").asText("")
                        if (code in setOf("401", "403")) response.has("content") else ref != PROBLEM
                    }
                    .map { (code, _) -> "$code $method $path" }
            }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `describes the problem's code as a named enum of every CoreErrorCode`() {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.components.schemas.CoreProblem.properties.code['\$ref']")
                .value("#/components/schemas/CoreErrorCode"))
            .andExpect(jsonPath("$.components.schemas.CoreErrorCode.enum.length()")
                .value(CoreErrorCode.entries.size))
            .andExpect(jsonPath("$.components.schemas.CoreProblem.properties.errors.items['\$ref']")
                .value("#/components/schemas/FieldError"))
    }

    @Test
    fun `requires what Kotlin requires`() {
        val schemas = "$.components.schemas"
        mvc.perform(get("/v3/api-docs"))
            // Non-null with no default: a client must send it, and Core always sends it back.
            .andExpect(jsonPath("$schemas.CreateBalanceAnchorRequest.required").value(hasItem("value")))
            .andExpect(jsonPath("$schemas.UpdateOperationRequest.required").value(hasItems("amount", "categoryId")))
            .andExpect(jsonPath("$schemas.ImportJobResponse.required").value(hasItems("accounts", "problems")))
            // Nullable, or defaulted on a request: the sender may leave it out.
            .andExpect(jsonPath("$schemas.CreateOperationRequest.required").value(not(hasItem("comment"))))
            .andExpect(jsonPath("$schemas.ImportAccountRequest.required").value(not(hasItem("archived"))))
    }

    @Test
    fun `names every enum instead of inlining it`() {
        val body = mvc.perform(get("/v3/api-docs")).andReturn().response.contentAsString
        val schemas = mapper.readTree(body).get("components").get("schemas")
        val inlined = schemas.properties().flatMap { (name, schema) ->
            schema.path("properties").properties()
                .filter { (_, property) -> property.has("enum") || property.path("items").has("enum") }
                .map { (property, _) -> "$name.$property" }
        }
        assertEquals(emptyList(), inlined)
        assertEquals("#/components/schemas/OperationKind",
            schemas.at("/CreateOperationRequest/properties/kind/\$ref").asText())
    }

    @Test
    fun `documents 401 and 403 on every operation`() {
        // The filter chain answers no token with 401 and a missing role with 403 (§7.4).
        val body = mvc.perform(get("/v3/api-docs")).andReturn().response.contentAsString
        val paths = mapper.readTree(body).get("paths")
        val missing = paths.properties().flatMap { (path, operations) ->
            operations.properties().flatMap { (method, operation) ->
                listOf("401", "403")
                    .filter { !operation.get("responses").has(it) }
                    .map { "$it $method $path" }
            }
        }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `promises the job only where the import endpoint sends one`() {
        // The importer's client is generated from this; a job schema on 404 or 409 is a body it will never get.
        val responses = "$.paths['/api/v1/workspaces/{workspaceId}/import'].post.responses"
        val job = "#/components/schemas/ImportJobResponse"
        val problem = "content['application/problem+json'].schema['\$ref']"
        mvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$responses.200.content['application/json'].schema['\$ref']").value(job))
            .andExpect(jsonPath("$responses.422.content['application/json'].schema['\$ref']").value(job))
            .andExpect(jsonPath("$responses.400.$problem").value(PROBLEM))
            .andExpect(jsonPath("$responses.202").doesNotExist())
            .andExpect(jsonPath("$responses.404.$problem").value(PROBLEM))
            .andExpect(jsonPath("$responses.409.$problem").value(PROBLEM))
            .andExpect(jsonPath("$responses.401.content").doesNotExist())
            .andExpect(jsonPath("$responses.403.content").doesNotExist())
    }

	@Test
	fun `serves the Swagger UI anonymously`() {
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk)
	}

	private companion object {
		const val PROBLEM = "#/components/schemas/CoreProblem"
	}
}
