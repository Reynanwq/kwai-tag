package br.com.kwaitag.domain.port.spi

import br.com.kwaitag.domain.model.DownloadTagCommand
import br.com.kwaitag.domain.model.TagErrorNotification

interface TagEventPublisher {
    fun publishDownload(command: DownloadTagCommand)
    fun publishError(notification: TagErrorNotification)
}