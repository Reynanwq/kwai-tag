package br.com.kwaitag.domain.service

import br.com.kwaitag.domain.model.CreateDeliveryCommand
import br.com.kwaitag.domain.model.OrderDeliveryLabel
import br.com.kwaitag.domain.port.spi.DeliveryLabelRepository
import br.com.kwaitag.domain.port.spi.KwaiLogisticsPort
import org.slf4j.LoggerFactory
import java.time.Instant

//Pede ao Kwai para criar a entrega de um pedido, garantindo que isso aconteça uma única vez.
class DeliveryRequester(
    private val kwaiLogisticsPort: KwaiLogisticsPort,
    private val deliveryLabelRepository: DeliveryLabelRepository,
) {
    private val log = LoggerFactory.getLogger(DeliveryRequester::class.java)

    fun request(label: OrderDeliveryLabel, now: Instant): OrderDeliveryLabel {
        // Já pedido antes: não pede de novo, só espera.
        if (label.wasDeliveryRequested) {
            log.info("createDelivery already requested for order {}; waiting", label.orderId)
            return deliveryLabelRepository.save(label.polled(now))
        }

        //busca opções de entrefa
        val options = kwaiLogisticsPort.fetchDeliveryOptions(label.accountId)

        // Grava ANTES de chamar o Kwai: garante que nunca chama duas vezes.
        val marked = deliveryLabelRepository.save(label.deliveryRequested(now))

        log.info("Requesting delivery for order {}", label.orderId)
        kwaiLogisticsPort.createDelivery(
            CreateDeliveryCommand(accountId = label.accountId, orderId = label.orderId, options = options)
        )

        return marked
    }
}