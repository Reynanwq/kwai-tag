package br.com.kwaitag.domain.exception

class DeliveryDocumentPendingException(
    message: String,
    val orderIds: List<String> = emptyList(),
    cause: Throwable? = null,
) : TagDomainException(message, cause), RetryableException

class DeliveryDocumentFailedException(
    message: String,
    cause: Throwable? = null,
) : TagDomainException(message, cause), NonRetryableException

class DeliveryDocumentNotFoundException(
    message: String,
    cause: Throwable? = null,
) : TagDomainException(message, cause), NonRetryableException

class DeliveryDocumentErrorException(
    message: String,
    cause: Throwable? = null,
) : TagDomainException(message, cause), RetryableException

class DeliveryOptionsUnavailableException(
    message: String,
    cause: Throwable? = null,
) : TagDomainException(message, cause), NonRetryableException