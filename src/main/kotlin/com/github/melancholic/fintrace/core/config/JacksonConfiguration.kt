package com.github.melancholic.fintrace.core.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer
import tools.jackson.databind.module.SimpleModule
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Configuration
class JacksonConfiguration {

    @Bean
    fun microsecondTimestamps(): SimpleModule = SimpleModule("fintrace-microsecond-timestamps")
        .addDeserializer(LocalDateTime::class.java, MicrosecondLocalDateTimeDeserializer)

    private object MicrosecondLocalDateTimeDeserializer : ValueDeserializer<LocalDateTime>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): LocalDateTime =
            LocalDateTimeDeserializer.INSTANCE.deserialize(p, ctxt).truncatedTo(ChronoUnit.MICROS)
    }
}
