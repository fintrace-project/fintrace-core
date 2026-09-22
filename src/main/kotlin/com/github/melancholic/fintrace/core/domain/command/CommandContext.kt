package com.github.melancholic.fintrace.core.domain.command

import com.github.melancholic.fintrace.core.domain.entity.ImporterDetails
import java.util.*

data class CommandContext(
    val initiator: UUID,
    val importer: ImporterDetails? = null,
)