package br.com.kwaitag.kwai.auth

import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.UriBuilder
import org.eclipse.microprofile.config.ConfigProvider
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Acrescenta em toda chamada ao Kwai os parâmetros comuns e a assinatura:
 * appKey, merchantId, ts, version (já vem do client), sign e accessToken (fora da assinatura).
 */
class KwaiSigningFilter : ClientRequestFilter {

    override fun filter(context: ClientRequestContext) {
        val accountId = context.headers.getFirst(ACCOUNT_ID_HEADER)?.toString()
            ?: throw IllegalStateException("Header $ACCOUNT_ID_HEADER is required to sign Kwai requests")
        context.headers.remove(ACCOUNT_ID_HEADER)

        val config = ConfigProvider.getConfig()
        val appKey = config.getValue("kwai.app-key", String::class.java)
        val signSecret = config.getValue("kwai.sign-secret", String::class.java)
        val accessToken = config.getValue("kwai.access-token", String::class.java)

        val params = sortedMapOf<String, String>()
        params.putAll(currentQueryParams(context))
        params["appKey"] = appKey
        // Na simulação o accountId é o próprio merchantId (no real vem do cadastro da conta).
        params["merchantId"] = accountId
        params["ts"] = System.currentTimeMillis().toString()

        val canonical = context.uri.path + params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val sign = sha256Hex(canonical + signSecret)

        val builder = UriBuilder.fromUri(context.uri).replaceQuery(null)
        params.forEach { (key, value) -> builder.queryParam(key, value) }
        builder.queryParam("sign", sign)
        builder.queryParam("accessToken", accessToken)
        context.uri = builder.build()
    }

    private fun currentQueryParams(context: ClientRequestContext): Map<String, String> =
        context.uri.rawQuery
            ?.split("&")
            ?.filter { it.contains("=") }
            ?.associate {
                val (key, value) = it.split("=", limit = 2)
                decode(key) to decode(value)
            }
            ?: emptyMap()

    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val ACCOUNT_ID_HEADER = "X-Kwai-Account-Id"
    }
}