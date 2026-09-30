package br.com.kwaitag.kwai.adapter

import br.com.kwaitag.kwai.auth.KwaiSigningFilter
import br.com.kwaitag.kwai.model.KwaiCreateDeliveryRequest
import br.com.kwaitag.kwai.model.KwaiDeliveryDocumentData
import br.com.kwaitag.kwai.model.KwaiDeliveryDocumentRequest
import br.com.kwaitag.kwai.model.KwaiDeliveryInfoData
import br.com.kwaitag.kwai.model.KwaiEnvelope
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

@RegisterRestClient(configKey = "kwai-api")
@RegisterProvider(KwaiSigningFilter::class)
@Path("/rest/open/api/logistics")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
interface KwaiLogisticsRestClient {

    @POST
    @Path("/createDelivery")
    fun createDelivery(
        @HeaderParam(KwaiSigningFilter.ACCOUNT_ID_HEADER) accountId: String,
        @QueryParam("version") version: String,
        request: KwaiCreateDeliveryRequest,
    ): KwaiEnvelope<Any>

    @POST
    @Path("/deliveryDocumentV2")
    fun deliveryDocumentV2(
        @HeaderParam(KwaiSigningFilter.ACCOUNT_ID_HEADER) accountId: String,
        @QueryParam("version") version: String,
        @HeaderParam("lang") lang: String,
        request: KwaiDeliveryDocumentRequest,
    ): KwaiEnvelope<KwaiDeliveryDocumentData>

    @POST
    @Path("/deliveryInfo")
    fun deliveryInfo(
        @HeaderParam(KwaiSigningFilter.ACCOUNT_ID_HEADER) accountId: String,
        @QueryParam("version") version: String,
    ): KwaiEnvelope<KwaiDeliveryInfoData>

    companion object {
        const val VERSION = "1.0"
        const val LANG_PT = "pt"
    }
}