package br.com.kwaitag.domain.port.spi

import br.com.kwaitag.domain.model.OrderDeliveryLabel

interface DeliveryLabelRepository {
    fun findByOrderId(accountId: String, orderId: String): OrderDeliveryLabel?
    fun save(label: OrderDeliveryLabel): OrderDeliveryLabel
}