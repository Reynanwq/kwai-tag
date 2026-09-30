package br.com.kwaitag.domain.model

enum class DeliveryLabelStatus {
    ACCEPTED,
    POLLING,
    READY,
    FAILED,
    TIMEOUT;

    val isTerminal: Boolean
        get() = this == READY || this == FAILED || this == TIMEOUT

    fun canTransitionTo(target: DeliveryLabelStatus): Boolean =
        target in allowedTargets()

    private fun allowedTargets(): Set<DeliveryLabelStatus> = when (this) {
        ACCEPTED -> setOf(POLLING, READY, FAILED, TIMEOUT)
        POLLING -> setOf(POLLING, READY, FAILED, TIMEOUT)
        READY -> setOf(READY)
        FAILED -> emptySet()
        TIMEOUT -> emptySet()
    }
}