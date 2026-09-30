package br.com.kwaitag.hub.adapter

//Implementa a porta TagHubPort do domínio: converte o comando no JSON e chama o hub.
import br.com.kwaitag.domain.model.UploadLabelCommand
import br.com.kwaitag.domain.port.spi.TagHubPort
import br.com.kwaitag.hub.model.UploadUrlRequest
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.slf4j.LoggerFactory


//faz a tradução: recebe o comando do domínio, converte para o formato da Gubee e chama.
@ApplicationScoped
class DefaultTagHubClient(
    @RestClient private val restClient: TagHubRestClient,
) : TagHubPort {

    private val log = LoggerFactory.getLogger(DefaultTagHubClient::class.java)

    override fun uploadLabel(command: UploadLabelCommand) {
        val request = command.toRequest()

        log.info("Uploading label to hub: group={}, orders={}", command.groupId, command.orderIds)

        restClient.uploadUrl(groupId = command.groupId, request = request)
    }

    private fun UploadLabelCommand.toRequest() = UploadUrlRequest(
        sellerId = sellerId,
        orderIds = orderIds.toList(),
        packageType = tagType.name,
        redirectUrl = labelUrl,
    )
}