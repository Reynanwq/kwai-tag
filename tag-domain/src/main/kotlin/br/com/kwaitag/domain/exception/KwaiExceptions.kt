package br.com.kwaitag.domain.exception

abstract class KwaiException(
    val resultCode: Int?,
    message: String,
    cause: Throwable? = null,
) : TagDomainException(message, cause)

class KwaiAuthenticationException(resultCode: Int?, message: String, cause: Throwable? = null) :
    KwaiException(resultCode, message, cause), RetryableException

class KwaiAuthorizationException(resultCode: Int?, message: String, cause: Throwable? = null) :
    KwaiException(resultCode, message, cause), NonRetryableException

class KwaiBadRequestException(resultCode: Int?, message: String, cause: Throwable? = null) :
    KwaiException(resultCode, message, cause), NonRetryableException

class KwaiUnprocessableException(resultCode: Int?, message: String, cause: Throwable? = null) :
    KwaiException(resultCode, message, cause), NonRetryableException

class KwaiUnavailableException(resultCode: Int?, message: String, cause: Throwable? = null) :
    KwaiException(resultCode, message, cause), RetryableException

class KwaiUnexpectedException(resultCode: Int?, message: String, cause: Throwable? = null) :
    KwaiException(resultCode, message, cause), RetryableException
