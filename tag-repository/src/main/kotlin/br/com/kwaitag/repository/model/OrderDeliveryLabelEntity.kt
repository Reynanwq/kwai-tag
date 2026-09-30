package br.com.kwaitag.repository.model

import br.com.kwaitag.domain.model.DeliveryLabelStatus
import br.com.kwaitag.domain.model.OrderDeliveryLabel
import io.quarkus.mongodb.panache.common.MongoEntity
import org.bson.codecs.pojo.annotations.BsonId
import java.time.Instant

@MongoEntity(collection = "ORDER_DELIVERY_LABEL")
data class OrderDeliveryLabelEntity(
    @BsonId
    var id: String? = null,
    var accountId: String = "",
    var orderId: String = "",
    var groupId: String = "",
    var status: DeliveryLabelStatus = DeliveryLabelStatus.ACCEPTED,
    var attemptCount: Int = 0,
    var firstAttemptAt: Instant = Instant.EPOCH,
    var lastAttemptAt: Instant = Instant.EPOCH,
    var deliveryRequestedAt: Instant? = null,
    var trackingNumber: String? = null,
    var shippingLabelUrl: String? = null,
    var failureCode: String? = null,
    var failureMessage: String? = null,
    var version: Long = 0,
) {

    fun toDomain() = OrderDeliveryLabel(
        accountId = accountId,
        orderId = orderId,
        groupId = groupId,
        status = status,
        attemptCount = attemptCount,
        firstAttemptAt = firstAttemptAt,
        lastAttemptAt = lastAttemptAt,
        deliveryRequestedAt = deliveryRequestedAt,
        trackingNumber = trackingNumber,
        shippingLabelUrl = shippingLabelUrl,
        failureCode = failureCode,
        failureMessage = failureMessage,
        version = version,
    )

    companion object {
        private const val SEPARATOR = ":"
        private const val ESCAPE = "\\"

        fun from(label: OrderDeliveryLabel, version: Long) = OrderDeliveryLabelEntity(
            id = idOf(label.accountId, label.orderId),
            accountId = label.accountId,
            orderId = label.orderId,
            groupId = label.groupId,
            status = label.status,
            attemptCount = label.attemptCount,
            firstAttemptAt = label.firstAttemptAt,
            lastAttemptAt = label.lastAttemptAt,
            deliveryRequestedAt = label.deliveryRequestedAt,
            trackingNumber = label.trackingNumber,
            shippingLabelUrl = label.shippingLabelUrl,
            failureCode = label.failureCode,
            failureMessage = label.failureMessage,
            version = version,
        )

        fun idOf(accountId: String, orderId: String) = "${escape(accountId)}$SEPARATOR${escape(orderId)}"

        private fun escape(part: String) = part.replace(ESCAPE, ESCAPE + ESCAPE).replace(SEPARATOR, ESCAPE + SEPARATOR)
    }
}