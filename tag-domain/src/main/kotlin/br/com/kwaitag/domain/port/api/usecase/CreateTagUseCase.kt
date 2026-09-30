package br.com.kwaitag.domain.port.api.usecase

import br.com.kwaitag.domain.model.CreateTagCommand
import br.com.kwaitag.domain.model.CreateTagResponse
import br.com.kwaitag.domain.model.DownloadTagCommand
import br.com.kwaitag.domain.model.FailedOrder
import br.com.kwaitag.domain.port.api.CreateTag
import br.com.kwaitag.domain.port.spi.TagEventPublisher
import org.slf4j.LoggerFactory

//Ele recebe o pedido, faz uma validação rápida, agenda o trabalho para depois
class CreateTagUseCase(
    private val tagEventPublisher: TagEventPublisher, //a classe precise de um tageventpublichser para funcionar
) : CreateTag {
    private val log = LoggerFactory.getLogger(CreateTagUseCase::class.java)

    override fun create(command: CreateTagCommand): CreateTagResponse {
        // O Kwai só aceita orderId numérico.
        val (valid, invalid) = command.orderIds.partition { it.toLongOrNull() != null }

        val failedOrders = invalid.map { FailedOrder(orderId = it, message = INVALID_ORDER_ID) }

        if (valid.isNotEmpty()) {
            tagEventPublisher.publishDownload(
                DownloadTagCommand(
                    sellerId = command.sellerId,
                    accountId = command.accountId,
                    groupId = command.groupId,
                    orderIds = valid.toSet(),
                    tagType = command.tagType,
                )
            )
        }

        log.info("Tag group {} accepted: {} published, {} rejected", command.groupId, valid.size, failedOrders.size)

        return CreateTagResponse(groupId = command.groupId, failedOrders = failedOrders)
    }

    companion object {
        const val INVALID_ORDER_ID = "orderId must be numeric"
    }
}