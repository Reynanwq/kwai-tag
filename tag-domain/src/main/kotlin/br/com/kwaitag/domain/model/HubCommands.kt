package br.com.kwaitag.domain.model

// Dados enviados ao hub
data class UploadLabelCommand(
    val sellerId: String,
    val accountId: String,
    val groupId: String,
    val orderIds: Set<String>,
    val tagType: TagType,
    val labelUrl: String,
)

data class OrderFailure(
    val orderId: String,
    val errorMessageCode: String,
    val errorMessageDescription: String?,
) {
    companion object {
        const val DOWNLOAD_FAILED = "tags.error.download"
    }
}

data class TagErrorNotification(
    val sellerId: String,
    val accountId: String,
    val groupId: String,
    val failures: List<OrderFailure>,
) {
    init {
        require(failures.isNotEmpty()) { "an error notification needs at least one failed order" }
    }
}