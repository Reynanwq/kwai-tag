package br.com.kwaitag.hub.adapter

import br.com.kwaitag.hub.model.UploadUrlRequest
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

@RegisterRestClient(configKey = "gubee-tag-api") //diz ao quarkus que essa interface é um  cliente http
@Path("/tag/package/upload")
interface TagHubRestClient {

    @POST
    @Path("/url/{groupId}")
    @Consumes(MediaType.APPLICATION_JSON)
    fun uploadUrl(
        @PathParam("groupId") groupId: String,
        request: UploadUrlRequest,
    )
}