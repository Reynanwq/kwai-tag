package br.com.kwaitag.kwai.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

//pedido de criação de entrega, NON.NULL é para os nulos não aparecerem no json
@JsonInclude(JsonInclude.Include.NON_NULL)
data class KwaiCreateDeliveryRequest(
    val orderId: Long,
    val senderAddressId: Long,
    val collectionType: Int,
    val pickUpTime: KwaiPickUpTime?,
)

data class KwaiPickUpTime(
    val startTime: Long,
    val endTime: Long,
)

data class KwaiDeliveryDocumentRequest(
    val orderId: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
class KwaiDeliveryDocumentData {
    var deliveryId: Long? = null
    var orderId: Long? = null
    var trackingNumber: String? = null
    var shippingLabelUrl: String? = null
}

@JsonIgnoreProperties(ignoreUnknown = true)
class KwaiDeliveryInfoData {
    var collectionTypeList: List<Int>? = null
    var pickUpTimeList: List<String>? = null
    var merchantAddressList: List<KwaiMerchantAddress>? = null
}


//endereço do vendedor
@JsonIgnoreProperties(ignoreUnknown = true)
class KwaiMerchantAddress {
    var id: Long? = null
    var type: Int? = null
    var pickUpFlag: Boolean? = null

    @field:JsonProperty("isDefault")
    @field:JsonAlias("default")
    var defaultAddress: Boolean? = null
}