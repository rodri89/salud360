package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Consulta

// De dónde es una consulta de la HC general (spec 005, RF-8, RF-62, RF-64, RF-65, D26). Puro: sale de lo que la consulta guarda
// (`propia`, el prefijo `hcc-` y el `remoto_id`), no de la sesión, del reloj ni de la memoria de la pantalla, y no cambia al
// editarla, cerrarla ni enviarla. Solo vale para la HC general: en las demás la marca no existe y `remoto_id` significa otra cosa.

/**
 * - [PROPIA]: la creó esta app con el envío a la web habilitado (o el médico envió una del histórico, RF-63). Es editable,
 *   cerrable y anulable aunque ya tenga su id de la web (RF-62): aparece como «Consulta en curso» mientras esté abierta.
 * - [HISTORICO]: estaba en el dispositivo al migrar (D26) y nunca se envió: «Solo en este dispositivo» (RF-8).
 * - [WEB]: la trajo la lectura (id `hcc-…`, o un `remoto_id` sin la marca). Solo se lee. La propia que creó otro dispositivo del
 *   mismo médico es esto mismo aquí (RF-64, RF-65): la lectura la guarda con id `hcc-…` y sin la marca.
 */
enum class OrigenDeConsulta { PROPIA, HISTORICO, WEB }

/** El origen de una consulta de la HC general. El prefijo `hcc-` manda sobre la marca ([esDeLaWeb]). */
fun Consulta.origenDeConsulta(): OrigenDeConsulta = when {
    esDeLaWeb() -> OrigenDeConsulta.WEB
    propia -> OrigenDeConsulta.PROPIA
    else -> OrigenDeConsulta.HISTORICO
}
