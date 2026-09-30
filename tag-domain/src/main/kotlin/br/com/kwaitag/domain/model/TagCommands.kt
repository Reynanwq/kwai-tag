package br.com.kwaitag.domain.model

//dado de entrada do pedidos de etiqueteas
enum class TagType {
    PDF,
    ZPL;

    companion object {
        fun from(value: String?): TagType =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: PDF
    }
}

data class CreateTagCommand(
    val sellerId: String,
    val accountId: String,
    val groupId: String,
    val orderIds: Set<String>,
    val tagType: TagType,
) {
    init {
        require(sellerId.isNotBlank()) { "sellerId is required" }
        require(accountId.isNotBlank()) { "accountId is required" }
        require(groupId.isNotBlank()) { "groupId is required" }
        require(orderIds.isNotEmpty()) { "orderIds cannot be empty" }
    }
}

data class CreateTagResponse(
    val groupId: String,
    val failedOrders: List<FailedOrder>,
)

data class FailedOrder(
    val orderId: String,
    val message: String,
)

data class DownloadTagCommand(
    val sellerId: String,
    val accountId: String,
    val groupId: String,
    val orderIds: Set<String>,
    val tagType: TagType,
)