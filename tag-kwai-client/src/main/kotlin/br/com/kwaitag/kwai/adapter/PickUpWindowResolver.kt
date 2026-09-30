package br.com.kwaitag.kwai.adapter

import br.com.kwaitag.domain.model.PickUpWindow
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Converte as janelas de coleta do Kwai ("8:00-12:00") em horário real.
 * A janela vale para hoje se ainda não terminou; senão, para amanhã.
 * O fuso é explícito: com o fuso do servidor (UTC) a coleta sairia 3h errada.
 */
@ApplicationScoped
class PickUpWindowResolver(
    private val clock: Clock,
    private val zone: ZoneId,
) {

    constructor() : this(Clock.systemUTC(), ZoneId.of(DEFAULT_ZONE))

    fun resolve(windows: List<String>?): PickUpWindow? {
        val now = ZonedDateTime.now(clock).withZoneSameInstant(zone)

        return windows.orEmpty()
            .firstNotNullOfOrNull { parse(it) }
            ?.let { (start, end) ->
                val date = if (now.toLocalTime().isBefore(end)) now.toLocalDate() else now.toLocalDate().plusDays(1)
                PickUpWindow(
                    startTime = date.atTime(start).atZone(zone).toInstant(),
                    endTime = date.atTime(end).atZone(zone).toInstant(),
                )
            }
    }

    private fun parse(window: String): Pair<LocalTime, LocalTime>? {
        val parts = window.split("-")
        if (parts.size != 2) return null
        val start = parseTime(parts[0]) ?: return null
        val end = parseTime(parts[1]) ?: return null
        return if (end.isAfter(start)) start to end else null
    }

    // O Kwai não põe zero à esquerda ("8:00"), então LocalTime.parse não serve.
    private fun parseTime(value: String): LocalTime? {
        val parts = value.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    companion object {
        const val DEFAULT_ZONE = "America/Sao_Paulo"
    }
}