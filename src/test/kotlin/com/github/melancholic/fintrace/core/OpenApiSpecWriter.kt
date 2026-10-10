package com.github.melancholic.fintrace.core

import org.springframework.boot.fromApplication
import org.springframework.boot.with
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

// Run by the generateOpenApi Gradle task: boots Core as bootTestRun does and writes its served spec.
fun main(args: Array<String>) {
    val target = Path.of(args.single())

    fromApplication<FintraceCoreApplication>().with(TestcontainersConfiguration::class)
        .run("--server.port=0", "--spring.main.banner-mode=off")
        .applicationContext.use { context ->
            val port = context.environment.getRequiredProperty("local.server.port")
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://localhost:$port/v3/api-docs")).build(),
                BodyHandlers.ofString(),
            )
            check(response.statusCode() == 200) { "/v3/api-docs answered ${response.statusCode()}" }

            val mapper = context.getBean(ObjectMapper::class.java)
            val spec = mapper.readTree(response.body()) as ObjectNode
            // springdoc derives servers from the request, so the random port would differ on every run.
            spec.remove("servers")
            Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n")
        }

    exitProcess(0)
}
