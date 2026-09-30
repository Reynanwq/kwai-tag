package br.com.kwaitag.domain.port.spi

import br.com.kwaitag.domain.model.CreateDeliveryCommand
import br.com.kwaitag.domain.model.DeliveryDocument
import br.com.kwaitag.domain.model.DeliveryOptions

interface KwaiLogisticsPort {
    fun fetchDeliveryDocument(accountId: String, orderId: String): DeliveryDocument
    fun fetchDeliveryOptions(accountId: String): DeliveryOptions
    fun createDelivery(command: CreateDeliveryCommand)
}