package br.com.kwaitag.hub.model

//formato do JSON que o hub espera receber.
data class UploadUrlRequest(
    val sellerId: String,
    val orderIds: List<String>,
    val packageType: String,
    val redirectUrl: String,
)