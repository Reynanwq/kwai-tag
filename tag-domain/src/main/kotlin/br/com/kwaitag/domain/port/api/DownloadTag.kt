package br.com.kwaitag.domain.port.api

import br.com.kwaitag.domain.model.DownloadTagCommand

interface DownloadTag {
    fun download(command: DownloadTagCommand)
}

