package br.com.kwaitag.kwai.adapter

import br.com.kwaitag.domain.exception.DeliveryDocumentErrorException
import br.com.kwaitag.domain.exception.DeliveryDocumentFailedException
import br.com.kwaitag.domain.exception.DeliveryDocumentNotFoundException
import br.com.kwaitag.domain.exception.DeliveryDocumentPendingException
import br.com.kwaitag.domain.exception.DeliveryOptionsUnavailableException
import br.com.kwaitag.domain.exception.KwaiUnprocessableException
import br.com.kwaitag.domain.model.CollectionType
import br.com.kwaitag.domain.model.CreateDeliveryCommand
import br.com.kwaitag.domain.model.DeliveryDocument
import br.com.kwaitag.domain.model.DeliveryOptions
import br.com.kwaitag.domain.port.spi.KwaiLogisticsPort
import br.com.kwaitag.kwai.model.KwaiCreateDeliveryRequest
import br.com.kwaitag.kwai.model.KwaiDeliveryDocumentRequest
import br.com.kwaitag.kwai.model.KwaiDeliveryInfoData
import br.com.kwaitag.kwai.model.KwaiEnvelope
import br.com.kwaitag.kwai.model.KwaiPickUpTime
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.slf4j.LoggerFactory

/**
 * Implementa KwaiLogisticsPort. Decide sucesso pelo campo `result`, nunca pelo status HTTP.
 * Não há retry aqui de propósito: o createDelivery não é idempotente.
 */
@ApplicationScoped
class DefaultKwaiLogisticsClient(
    @RestClient private val client: KwaiLogisticsRestClient,
    private val pickUpWindowResolver: PickUpWindowResolver,
) : KwaiLogisticsPort {

    private val log = LoggerFactory.getLogger(DefaultKwaiLogisticsClient::class.java)

    override fun fetchDeliveryDocument(accountId: String, orderId: String): DeliveryDocument {
        val envelope = client.deliveryDocumentV2(
            accountId = accountId,
            version = KwaiLogisticsRestClient.VERSION,
            lang = KwaiLogisticsRestClient.LANG_PT,
            request = KwaiDeliveryDocumentRequest(orderId = orderId.toKwaiOrderId()),
        )

        // 11011..11014 são o ANDAMENTO da etiqueta: tratados antes da tabela genérica de erros.
        when (envelope.result) {
            DOING -> throw DeliveryDocumentPendingException("Label still processing for order $orderId")
            FAILED -> throw DeliveryDocumentFailedException("Kwai failed the delivery for order $orderId: ${envelope.message}")
            NOT_FOUND -> throw DeliveryDocumentNotFoundException("No delivery for order $orderId")
            ERROR -> throw DeliveryDocumentErrorException("Kwai error (11014) for order $orderId: ${envelope.message}")
        }
        envelope.ensureSuccess()

        val data = envelope.data
        val url = data?.shippingLabelUrl?.takeIf { it.isNotBlank() }
            ?: throw DeliveryDocumentPendingException("Kwai returned success without label url for order $orderId")

        return DeliveryDocument(
            deliveryId = data.deliveryId ?: throw DeliveryDocumentErrorException("Missing deliveryId for order $orderId"),
            orderId = orderId,
            trackingNumber = data.trackingNumber.orEmpty(),
            shippingLabelUrl = url,
        )
    }

    override fun fetchDeliveryOptions(accountId: String): DeliveryOptions {
        val envelope = client.deliveryInfo(accountId = accountId, version = KwaiLogisticsRestClient.VERSION)
        envelope.ensureSuccess()

        val data = envelope.data
            ?: throw DeliveryOptionsUnavailableException("Empty deliveryInfo for account $accountId")

        return data.toDeliveryOptions(accountId)
    }

    override fun createDelivery(command: CreateDeliveryCommand) {
        val options = command.options
        val request = KwaiCreateDeliveryRequest(
            orderId = command.orderId.toKwaiOrderId(),
            senderAddressId = options.senderAddressId,
            collectionType = options.collectionType.code,
            pickUpTime = options.pickUpWindow?.let {
                KwaiPickUpTime(startTime = it.startTime.toEpochMilli(), endTime = it.endTime.toEpochMilli())
            },
        )

        log.info("Calling createDelivery for order {}", command.orderId)
        client.createDelivery(
            accountId = command.accountId,
            version = KwaiLogisticsRestClient.VERSION,
            request = request,
        ).ensureSuccess()
    }

    private fun KwaiDeliveryInfoData.toDeliveryOptions(accountId: String): DeliveryOptions {
        val addresses = merchantAddressList.orEmpty().filter { it.type == SEND_ADDRESS_TYPE && it.id != null }
        if (addresses.isEmpty()) {
            throw DeliveryOptionsUnavailableException("Account $accountId has no sender address (type=$SEND_ADDRESS_TYPE)")
        }

        val modes = collectionTypeList.orEmpty().mapNotNull { CollectionType.fromCode(it) }
        val window = pickUpWindowResolver.resolve(pickUpTimeList)
        val pickUpAddress = addresses.firstOrNull { it.pickUpFlag == true }

        // PICK_UP só com endereço de coleta E janela; senão DROP_OFF.
        return when {
            CollectionType.PICK_UP in modes && pickUpAddress != null && window != null ->
                DeliveryOptions(pickUpAddress.id!!, CollectionType.PICK_UP, window)

            CollectionType.DROP_OFF in modes -> {
                val address = addresses.firstOrNull { it.defaultAddress == true } ?: addresses.first()
                DeliveryOptions(address.id!!, CollectionType.DROP_OFF, null)
            }

            else -> throw DeliveryOptionsUnavailableException("Account $accountId has no usable collection type")
        }
    }

    private fun KwaiEnvelope<*>.ensureSuccess() {
        if (!isSuccess) throw KwaiResultCode.toException(result, message)
    }

    private fun String.toKwaiOrderId(): Long =
        toLongOrNull() ?: throw KwaiUnprocessableException(null, "orderId '$this' is not numeric")

    companion object {
        const val SEND_ADDRESS_TYPE = 1

        const val DOING = 11011
        const val FAILED = 11012
        const val NOT_FOUND = 11013
        const val ERROR = 11014
    }
}