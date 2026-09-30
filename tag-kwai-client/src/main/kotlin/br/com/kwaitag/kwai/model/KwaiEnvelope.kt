package br.com.kwaitag.kwai.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties


//Toda resposta do Kwai vem no mesmo formato, um "envelope"
@JsonIgnoreProperties(ignoreUnknown = true)
class KwaiEnvelope<T> {
    var result: Int? = null
    var message: String? = null
    var data: T? = null

    val isSuccess: Boolean
        get() = result in SUCCESS_RESULTS

    companion object {
        val SUCCESS_RESULTS = setOf(200, 1)
    }
}