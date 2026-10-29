package com.github.melancholic.fintrace.core.config

import com.github.melancholic.fintrace.core.exception.CoreErrorCode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.*
import io.swagger.v3.oas.models.responses.ApiResponse as ResponseModel
import io.swagger.v3.oas.models.security.*
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration(
    @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") private val issuer: String
) {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .components(
            Components()
                .addSecuritySchemes(
                    SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .flows(
                            OAuthFlows().authorizationCode(
                                OAuthFlow()
                                    .authorizationUrl("$issuer/protocol/openid-connect/auth")
                                    .tokenUrl("$issuer/protocol/openid-connect/token")
                                    .scopes(Scopes().addString("openid", "Sign in"))
                            )
                        )
                )
                .addSchemas(ERROR_CODE, StringSchema()._enum(CoreErrorCode.entries.map { it.name }))
                .addSchemas(
                    FIELD_ERROR,
                    ObjectSchema()
                        .addProperty("field", StringSchema())
                        .addProperty("message", StringSchema())
                        .required(listOf("field", "message"))
                )
                .addSchemas(
                    PROBLEM,
                    ObjectSchema()
                        .addProperty("type", StringSchema())
                        .addProperty("title", StringSchema())
                        .addProperty("status", IntegerSchema())
                        .addProperty("detail", StringSchema())
                        .addProperty("instance", StringSchema())
                        .addProperty("code", Schema<Any>().`$ref`(ERROR_CODE))
                        .addProperty("traceId", StringSchema())
                        .addProperty("errors", ArraySchema().items(Schema<Any>().`$ref`(FIELD_ERROR)))
                        .required(listOf("status", "code"))
                )
        )
        .addSecurityItem(SecurityRequirement().addList(SCHEME))

    @Bean
    fun errorResponseBodies(): OperationCustomizer = OperationCustomizer { operation, handlerMethod ->
        val declared = AnnotatedElementUtils
            .findMergedRepeatableAnnotations(handlerMethod.method, ApiResponse::class.java, ApiResponses::class.java)
            .filter { response -> response.content.any { it.schema.implementation != Void::class.java } }
            .map { it.responseCode }
            .toSet()
        operation.responses?.forEach { (code, response) ->
            if (code.first() in "45" && code !in declared) {
                response.content = Content().addMediaType(
                    org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    MediaType().schema(Schema<Any>().`$ref`(PROBLEM))
                )
            }
        }
        operation
    }

    @Bean
    fun securityResponses(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        openApi.paths?.forEach { (_, item) ->
            item.readOperations().forEach { operation ->
                operation.responses
                    .addApiResponse("401", ResponseModel().description("Not authenticated"))
                    .addApiResponse("403", ResponseModel().description("Not authorized"))
            }
        }
    }

    private companion object {
        const val SCHEME = "keycloak"
        const val PROBLEM = "CoreProblem"
        const val ERROR_CODE = "CoreErrorCode"
        const val FIELD_ERROR = "FieldError"
    }
}