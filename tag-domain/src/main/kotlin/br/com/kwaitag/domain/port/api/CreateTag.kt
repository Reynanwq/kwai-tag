package br.com.kwaitag.domain.port.api

import br.com.kwaitag.domain.model.CreateTagCommand
import br.com.kwaitag.domain.model.CreateTagResponse

interface CreateTag {
    fun create(command: CreateTagCommand): CreateTagResponse
}