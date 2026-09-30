package br.com.kwaitag.kwai.adapter

import br.com.kwaitag.domain.exception.KwaiAuthenticationException
import br.com.kwaitag.domain.exception.KwaiAuthorizationException
import br.com.kwaitag.domain.exception.KwaiBadRequestException
import br.com.kwaitag.domain.exception.KwaiException
import br.com.kwaitag.domain.exception.KwaiUnavailableException
import br.com.kwaitag.domain.exception.KwaiUnexpectedException
import br.com.kwaitag.domain.exception.KwaiUnprocessableException

/** Tabela única: código `result` do Kwai → exceção do domínio. */
object KwaiResultCode {

    fun toException(result: Int?, message: String?): KwaiException {
        val detail = "Kwai result=$result, message=$message"
        return when (result) {
            INVALID_APP_KEY, INVALID_APP_SECRET, INVALID_TIMESTAMP -> KwaiAuthenticationException(result, detail)
            INVALID_MERCHANT_ID, PERMISSION_DENIED -> KwaiAuthorizationException(result, detail)
            INVALID_SIGNATURE, INVALID_VERSION -> KwaiBadRequestException(result, detail)
            EMPTY_BODY -> KwaiUnprocessableException(result, detail)
            SERVER_ERROR, SERVICE_BUSY -> KwaiUnavailableException(result, detail)
            else -> KwaiUnexpectedException(result, detail)
        }
    }

    const val INVALID_APP_KEY = 440
    const val INVALID_APP_SECRET = 441
    const val INVALID_TIMESTAMP = 442
    const val INVALID_SIGNATURE = 443
    const val INVALID_MERCHANT_ID = 445
    const val PERMISSION_DENIED = 446
    const val INVALID_VERSION = 447
    const val EMPTY_BODY = 6001
    const val SERVER_ERROR = 4999
    const val SERVICE_BUSY = 5000
}