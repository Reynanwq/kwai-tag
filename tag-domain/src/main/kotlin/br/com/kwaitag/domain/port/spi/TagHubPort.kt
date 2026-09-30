package br.com.kwaitag.domain.port.spi

import br.com.kwaitag.domain.model.UploadLabelCommand

interface TagHubPort {
    fun uploadLabel(command: UploadLabelCommand)
}