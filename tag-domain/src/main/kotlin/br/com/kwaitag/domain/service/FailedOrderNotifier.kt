package br.com.kwaitag.domain.service

import br.com.kwaitag.domain.model.DeliveryLabelStatus
import br.com.kwaitag.domain.model.OrderDeliveryLabel
import br.com.kwaitag.domain.model.OrderFailure
import br.com.kwaitag.domain.model.TagErrorNotification
import br.com.kwaitag.domain.port.spi.TagEventPublisher
import org.slf4j.LoggerFactory

//avisa ao hub sobre os pedidos que falharam de vez
class FailedOrderNotifier(
    private val tagEventPublisher: TagEventPublisher,
) {

    private val log = LoggerFactory.getLogger(FailedOrderNotifier::class.java)

    fun notify(sellerId: String, accountId: String, groupId: String, labels: List<OrderDeliveryLabel>) {
        // Só FAILED e TIMEOUT: o hub apaga o pedido do pacote quando recebe o aviso.
        val failures = labels
            .filter { it.status == DeliveryLabelStatus.FAILED || it.status == DeliveryLabelStatus.TIMEOUT }
            .map {
                OrderFailure(
                    orderId = it.orderId,
                    errorMessageCode = OrderFailure.DOWNLOAD_FAILED,
                    errorMessageDescription = it.failureMessage,
                )
            }

        if (failures.isEmpty()) return

        log.info("Notifying hub about {} failed orders in group {}", failures.size, groupId)

        tagEventPublisher.publishError(
            TagErrorNotification(
                sellerId = sellerId,
                accountId = accountId,
                groupId = groupId,
                failures = failures,
            )
        )
    }
}