package br.com.kwaitag.domain.model

import java.time.Instant

enum class CollectionType(val code: Int) {
    PICK_UP(1),
    DROP_OFF(2);

    companion object {
        fun fromCode(code: Int?): CollectionType? = entries.firstOrNull { it.code == code }
    }
}

data class PickUpWindow(
    val startTime: Instant,
    val endTime: Instant,
) {
    init {
        require(endTime.isAfter(startTime)) { "pick-up window must end after it starts" }
    }
}

data class DeliveryOptions(
    val senderAddressId: Long,
    val collectionType: CollectionType,
    val pickUpWindow: PickUpWindow?,
) {
    init {
        require(collectionType != CollectionType.PICK_UP || pickUpWindow != null) {
            "PICK_UP requires a pick-up window"
        }
    }
}

data class CreateDeliveryCommand(
    val accountId: String,
    val orderId: String,
    val options: DeliveryOptions,
)

data class DeliveryDocument(
    val deliveryId: Long,
    val orderId: String,
    val trackingNumber: String,
    val shippingLabelUrl: String,
) {
    init {
        require(shippingLabelUrl.isNotBlank()) { "shippingLabelUrl is required" }
    }
}