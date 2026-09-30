package br.com.kwaitag.domain.port.api.usecase

// para cada pedido do grupo, tenta conseguir a etiqueta no Kwai e mandar para o hub

import br.com.kwaitag.domain.exception.DeliveryDocumentFailedException
import br.com.kwaitag.domain.exception.DeliveryDocumentNotFoundException
import br.com.kwaitag.domain.exception.DeliveryDocumentPendingException
import br.com.kwaitag.domain.exception.DeliveryOptionsUnavailableException
import br.com.kwaitag.domain.model.DownloadTagCommand
import br.com.kwaitag.domain.model.OrderDeliveryLabel
import br.com.kwaitag.domain.model.UploadLabelCommand
import br.com.kwaitag.domain.port.api.DownloadTag
import br.com.kwaitag.domain.port.spi.DeliveryLabelRepository
import br.com.kwaitag.domain.port.spi.KwaiLogisticsPort
import br.com.kwaitag.domain.port.spi.TagHubPort
import br.com.kwaitag.domain.service.DeliveryRequester
import br.com.kwaitag.domain.service.FailedOrderNotifier
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

class DownloadTagUseCase(
    private val kwaiLogisticsPort: KwaiLogisticsPort,
    private val tagHubPort: TagHubPort,
    private val deliveryLabelRepository: DeliveryLabelRepository,
    private val deliveryRequester: DeliveryRequester,
    private val failedOrderNotifier: FailedOrderNotifier,
    private val clock: Clock,
    private val pollTotalTimeout: Duration,
) : DownloadTag {

    private val log = LoggerFactory.getLogger(DownloadTagUseCase::class.java)

    //cuida do grupo inteiro de pedidos
    override fun download(command: DownloadTagCommand) {

        if (command.orderIds.isEmpty()) {
            log.warn("No orders to process for group {}", command.groupId)
            return
        }

        val pending = mutableListOf<String>()
        val failed = mutableListOf<OrderDeliveryLabel>()

        // Processa TODOS os pedidos antes de decidir o resultado do grupo.
        command.orderIds.forEach { orderId ->
            when (val outcome = process(command, orderId)) {
                is Outcome.Done -> Unit
                is Outcome.Pending -> pending += orderId
                is Outcome.Failed -> failed += outcome.label
            }
        }

        failedOrderNotifier.notify(command.sellerId, command.accountId, command.groupId, failed)

        // Ainda tem pendente: lança exceção para o consumer tentar de novo mais tarde.
        if (pending.isNotEmpty()) {
            throw DeliveryDocumentPendingException(
                message = "Delivery document still pending for orders $pending (group ${command.groupId})",
                orderIds = pending,
            )
        }

        log.info("Tag download completed for group {}", command.groupId)
    }

    //cuida de um pedido e devolve o resultado dele
    private fun process(command: DownloadTagCommand, orderId: String): Outcome {
        val now = clock.instant(); //hora atual

        //busca o estado atual do pedido no banco, se não tiver, cria com accepted
        val label = deliveryLabelRepository.findByOrderId(command.accountId, orderId)
            ?: OrderDeliveryLabel.accepted(command.accountId, orderId, command.groupId, now)


        // Já resolvido neste mesmo grupo: não faz nada de novo.
        if (label.isTerminal && label.groupId == command.groupId) {
            return Outcome.Done
        }

        // Passou do tempo total: desiste deste pedido.
        if (label.hasTimedOut(pollTotalTimeout, now)) {
            log.warn("Polling timed out for order {}", orderId)
            return Outcome.Failed(deliveryLabelRepository.save(label.timedOut(now)))
        }

        return try {
            // Etiqueta pronta: envia ao hub e marca READY.
            val document = kwaiLogisticsPort.fetchDeliveryDocument(command.accountId, orderId)
            tagHubPort.uploadLabel(
                UploadLabelCommand(
                    sellerId = command.sellerId,
                    accountId = command.accountId,
                    groupId = command.groupId,
                    orderIds = setOf(orderId),
                    tagType = command.tagType,
                    labelUrl = document.shippingLabelUrl,
                )
            )
            deliveryLabelRepository.save(label.ready(document.trackingNumber, document.shippingLabelUrl, now))
            Outcome.Done
        } catch (e: DeliveryDocumentNotFoundException) {
            // Não existe entrega: pede para criar.
            requestDelivery(label, now)
        } catch (e: DeliveryDocumentPendingException) {
            // Kwai ainda processando: tenta de novo depois.
            deliveryLabelRepository.save(label.polled(now))
            Outcome.Pending
        } catch (e: DeliveryDocumentFailedException) {
            // Kwai desistiu: falha definitiva.
            Outcome.Failed(deliveryLabelRepository.save(label.failed(KWAI_FAILED, e.message, now)))
        }

    }

    private fun requestDelivery(label: OrderDeliveryLabel, now: Instant): Outcome =
        try {
            deliveryRequester.request(label, now)
            Outcome.Pending
        } catch (e: DeliveryOptionsUnavailableException) {
            // Merchant sem endereço: falha definitiva.
            Outcome.Failed(deliveryLabelRepository.save(label.failed(NO_DELIVERY_OPTIONS, e.message, now)))
        }


    private sealed interface Outcome {
        data object Done : Outcome
        data object Pending : Outcome
        data class Failed(val label: OrderDeliveryLabel) : Outcome
    }

    companion object {
        const val KWAI_FAILED = "11012"
        const val NO_DELIVERY_OPTIONS = "NO_DELIVERY_OPTIONS"
    }

}