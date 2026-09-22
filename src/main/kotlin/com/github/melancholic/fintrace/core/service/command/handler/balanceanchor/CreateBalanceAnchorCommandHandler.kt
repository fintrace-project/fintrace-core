package com.github.melancholic.fintrace.core.service.command.handler.balanceanchor

import com.github.melancholic.fintrace.core.dao.EventsDAO
import com.github.melancholic.fintrace.core.domain.command.CommandContext
import com.github.melancholic.fintrace.core.domain.command.CreateBalanceAnchorCommand
import com.github.melancholic.fintrace.core.domain.entity.BalanceAnchorContainer
import com.github.melancholic.fintrace.core.domain.event.payload.BalanceAnchorCreated
import com.github.melancholic.fintrace.core.domain.event.payload.BalanceAnchorCreatedV1
import com.github.melancholic.fintrace.core.service.BalanceAnchorContainerFactory
import com.github.melancholic.fintrace.core.service.projection.ProjectionApplier
import com.github.melancholic.fintrace.core.util.TimestampProvider
import com.github.melancholic.fintrace.core.util.UUIDGenerator
import com.github.melancholic.fintrace.core.validation.BalanceAnchorValidationService
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
class CreateBalanceAnchorCommandHandler(
    private val timestampProvider: TimestampProvider,
    private val uuidGenerator: UUIDGenerator,
    private val projectionApplier: ProjectionApplier,
    private val validationService: BalanceAnchorValidationService,
    private val anchorContainerFactory: BalanceAnchorContainerFactory,
    eventsDAO: EventsDAO,
) : AbstractBalanceAnchorCommandHandler<CreateBalanceAnchorCommand, BalanceAnchorContainer, BalanceAnchorCreated>(
    eventsDAO
) {
    override val commandType: KClass<out CreateBalanceAnchorCommand> = CreateBalanceAnchorCommand::class

    override fun handle(command: CreateBalanceAnchorCommand, context: CommandContext): BalanceAnchorContainer {
        validationService.validate(command)
        val event = registerEvent(command, context, buildEventPayload(command))
        projectionApplier.apply(event.payload.projectionChange())
        return anchorContainerFactory.buildBalanceAnchorContainer((event.payload as BalanceAnchorCreated).projection())
    }

    private fun buildEventPayload(command: CreateBalanceAnchorCommand): BalanceAnchorCreated {
        val now = timestampProvider.now()
        return BalanceAnchorCreatedV1(
            id = command.id ?: uuidGenerator.nextUUID(),
            workspaceId = command.workspaceId,
            accountId = command.accountId,
            value = command.value,
            occurredAt = command.occurredAt,
            externalRef = command.externalRef,
            recordedAt = now
        )
    }
}