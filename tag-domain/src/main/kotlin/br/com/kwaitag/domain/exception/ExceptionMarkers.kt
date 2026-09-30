package br.com.kwaitag.domain.exception

//são interfaces sem método, servem somente para etiquetar uma exceção
interface RetryableException
interface NonRetryableException

abstract class TagDomainException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)