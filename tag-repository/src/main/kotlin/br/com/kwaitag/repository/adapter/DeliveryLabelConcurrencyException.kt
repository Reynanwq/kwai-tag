package br.com.kwaitag.repository.adapter

import br.com.kwaitag.domain.exception.RetryableException

// erro lançado quando o mesmo processo gravou o registor antes, retryable
class DeliveryLabelConcurrencyException(message: String) : RuntimeException(message), RetryableException