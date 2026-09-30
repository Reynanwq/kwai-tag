package br.com.kwaitag.domain.model

import java.time.Duration
import java.time.Instant

data class OrderDeliveryLabel(
    val accountId: String,
    val orderId: String,
    val groupId: String,
    val status: DeliveryLabelStatus,
    val attemptCount: Int,
    val firstAttemptAt: Instant,
    val lastAttemptAt: Instant,
    val deliveryRequestedAt: Instant? = null,
    val trackingNumber: String? = null,
    val shippingLabelUrl: String? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val version: Long = 0,
) {
    init {
        require(accountId.isNotBlank()) { "accountId is required" }
        require(orderId.isNotBlank()) { "orderId is required" }
        require(groupId.isNotBlank()) { "groupId is required" }
        require(attemptCount >= 0) { "attemptCount cannot be negative" }
    }

    val isTerminal: Boolean
        get() = status.isTerminal

    val wasDeliveryRequested: Boolean // se existe uma data de solicitação, é porque a entrega já foi solicitada
        get() = deliveryRequestedAt != null

    fun hasTimedOut(totalTimeout: Duration, now: Instant): Boolean =
        Duration.between(firstAttemptAt, now) > totalTimeout

    fun polled(now: Instant): OrderDeliveryLabel =
        moveTo(DeliveryLabelStatus.POLLING, now).copy(attemptCount = attemptCount + 1)

    fun deliveryRequested(now: Instant): OrderDeliveryLabel {
        check(!wasDeliveryRequested) {
            "createDelivery already requested for order $orderId at $deliveryRequestedAt"
        }
        return moveTo(DeliveryLabelStatus.POLLING, now).copy(deliveryRequestedAt = now)
    }

    fun ready(trackingNumber: String, shippingLabelUrl: String, now: Instant): OrderDeliveryLabel =
        moveTo(DeliveryLabelStatus.READY, now).copy(
            trackingNumber = trackingNumber,
            shippingLabelUrl = shippingLabelUrl,
            failureCode = null,
            failureMessage = null,
        )

    fun failed(code: String, message: String?, now: Instant): OrderDeliveryLabel =
        moveTo(DeliveryLabelStatus.FAILED, now).copy(failureCode = code, failureMessage = message)

    fun timedOut(now: Instant): OrderDeliveryLabel =
        moveTo(DeliveryLabelStatus.TIMEOUT, now).copy(
            failureCode = TIMEOUT_CODE,
            failureMessage = "Polling total timeout exceeded",
        )

    private fun moveTo(target: DeliveryLabelStatus, now: Instant): OrderDeliveryLabel {
        check(status.canTransitionTo(target)) {
            "Invalid transition $status -> $target for order $orderId"
        }
        return copy(status = target, lastAttemptAt = now)
    }

    companion object {
        const val TIMEOUT_CODE = "TIMEOUT"

        fun accepted(accountId: String, orderId: String, groupId: String, now: Instant) =
            OrderDeliveryLabel(
                accountId = accountId,
                orderId = orderId,
                groupId = groupId,
                status = DeliveryLabelStatus.ACCEPTED,
                attemptCount = 0,
                firstAttemptAt = now,
                lastAttemptAt = now,
            )
    }
}
