package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.MedicoPreferencia

// La lista de consultas de la HC general (spec 002, entrega L: RF-38, RF-44 a RF-46, RF-63). Puro: recibe lo que la lectura
// dejó en la base y devuelve lo que la pantalla muestra, ya decidido (constitución, puntos 3 y 4). No escribe nada: la
// pantalla no puede cerrar, reabrir, anular ni editar una consulta de la web con esto.

/** De dónde es la consulta: la trajo la web (solo lectura) o se cargó en el dispositivo y nunca se envió (Q14). */
enum class OrigenConsulta { WEB, SOLO_EN_ESTE_DISPOSITIVO }

/** El origen de una consulta de la HC general: de la web si tiene su identificador ([esDeLaWeb]). */
fun Consulta.origen(): OrigenConsulta = if (esDeLaWeb()) OrigenConsulta.WEB else OrigenConsulta.SOLO_EN_ESTE_DISPOSITIVO

/**
 * El id de la consulta en la web (`hcc-c<id>` o `remoto_id`) o `null` si no es de la web. Con él se busca lo que la lectura
 * guardó de esa consulta (`hcc_no_leidas_<id>`).
 */
fun Consulta.idWeb(): Long? {
    if (!esDeLaWeb()) return null
    val delId = id.takeIf { it.startsWith("${PREFIJO_ID_WEB}c") }?.removePrefix("${PREFIJO_ID_WEB}c")?.toLongOrNull()
    return (delId ?: remotoId.toLongOrNull())?.takeIf { it > 0 }
}

/** Los nombres de los médicos de la web (`medico_preferencia` con `hcc-m<id>` y la clave `nombre`); los vacíos no cuentan. */
fun nombresDeMedicosWeb(preferencias: List<MedicoPreferencia>): Map<Id, String> =
    preferencias
        .filter { it.clave == CLAVE_NOMBRE_MEDICO_WEB && it.medicoId.startsWith("${PREFIJO_ID_WEB}m") && it.valor.isNotBlank() }
        .associate { it.medicoId to it.valor }

/**
 * Una consulta tal como la muestra la lista. [propia] y [autor] solo importan en las de la web: la de otro médico dice que
 * la cargó otro médico (con su nombre si se sabe, Q4); las del dispositivo se muestran como hoy.
 */
data class ConsultaDeLaLista(
    val consulta: Consulta,
    val origen: OrigenConsulta,
    val propia: Boolean,
    /** El nombre de quien la cargó: solo si es de la web, no es propia y la web lo informó. */
    val autor: String?,
    /** «Cargada por <nombre>» o «Cargada por otro médico»; `null` si es propia o del dispositivo. */
    val cargadaPor: String?,
    /** Desde cuándo (`AAAA-MM-DD`) la web dejó de mandarla; `null` si sigue figurando (RF-63). */
    val ausenteDesde: String?,
) {
    /** Una consulta de la web no se edita, no se cierra, no se reabre ni se anula (RF-45). */
    val soloLectura: Boolean get() = origen == OrigenConsulta.WEB

    /** RF-44: de la web y todavía abierta allá. */
    val abiertaEnLaWeb: Boolean get() = origen == OrigenConsulta.WEB && consulta.estado == EstadoConsulta.ABIERTA

    val ausente: Boolean get() = ausenteDesde != null

    /** Las etiquetas de la fila, en orden: el origen, «Ya no figura en la web» y «Cargada por …». */
    val etiquetas: List<String>
        get() = listOfNotNull(
            if (origen == OrigenConsulta.WEB) ETIQUETA_WEB else ETIQUETA_SOLO_EN_ESTE_DISPOSITIVO,
            YA_NO_FIGURA_EN_LA_WEB.takeIf { ausente },
            cargadaPor,
        )
}

/**
 * Los grupos de la historia del paciente (plan, «Pantalla»). Cada consulta está en uno solo; ninguna se oculta, tenga o no
 * la marca «Ya no figura en la web».
 */
data class GruposDeConsultas(
    /** De la web y abiertas allá (RF-44): «Abiertas en la web (solo lectura)». */
    val abiertasEnLaWeb: List<ConsultaDeLaLista>,
    /** Las abiertas del dispositivo (normalmente una): las únicas con Continuar y Anular. */
    val enCurso: List<ConsultaDeLaLista>,
    /** Las cerradas, de la web y del dispositivo juntas, de la más nueva a la más vieja, cada una con su origen. */
    val anteriores: List<ConsultaDeLaLista>,
) {
    /** Las cerradas del dispositivo. No es otro grupo: es una vista de [anteriores], para quien quiera separarlas. */
    val soloEnEsteDispositivo: List<ConsultaDeLaLista> get() = anteriores.filter { it.origen == OrigenConsulta.SOLO_EN_ESTE_DISPOSITIVO }
}

/**
 * Reparte [consultas] (las de la HC general del paciente, como las da la base) en los grupos de la pantalla.
 *
 * - Abierta de la web → [GruposDeConsultas.abiertasEnLaWeb]; abierta del dispositivo → [GruposDeConsultas.enCurso]
 *   (RF-44: una de la web nunca es la «consulta en curso»); cerrada, de donde sea → [GruposDeConsultas.anteriores].
 *   Las anuladas no se listan.
 * - Cada grupo va de la fecha más nueva a la más vieja. La base no guarda `created_at`: dentro de un mismo día manda el
 *   orden en que llegan, que es el de la base (`fecha`, `updated_at` descendentes) y el de escritura de la lectura (de la
 *   más vieja a la más nueva), así que un empate se resuelve como `(created_at, id)` sin comparar ids como texto.
 * - [medicoSesionId] decide cuál es propia; [nombresMedicos] son los de [nombresDeMedicosWeb].
 * - [ausencias] pone la marca «Ya no figura en la web» a las de la web (RF-63): no las saca de su grupo ni las reordena.
 */
fun agruparConsultas(
    consultas: List<Consulta>,
    medicoSesionId: Id,
    nombresMedicos: Map<Id, String> = emptyMap(),
    ausencias: AusenciasEnLaWeb = AusenciasEnLaWeb.NINGUNA,
): GruposDeConsultas {
    val items = consultas
        .filter { it.estado != EstadoConsulta.ANULADA }
        .sortedByDescending { it.fecha }
        .map { c -> deLaLista(c, medicoSesionId, nombresMedicos, ausencias) }
    return GruposDeConsultas(
        abiertasEnLaWeb = items.filter { it.abiertaEnLaWeb },
        enCurso = items.filter { it.origen == OrigenConsulta.SOLO_EN_ESTE_DISPOSITIVO && it.consulta.estado == EstadoConsulta.ABIERTA },
        anteriores = items.filter { it.consulta.estado == EstadoConsulta.CERRADA },
    )
}

private fun deLaLista(c: Consulta, medicoSesionId: Id, nombres: Map<Id, String>, ausencias: AusenciasEnLaWeb): ConsultaDeLaLista {
    val origen = c.origen()
    val propia = c.esPropia(medicoSesionId)
    val autor = if (origen == OrigenConsulta.WEB && !propia) nombres[c.medicoId]?.trim()?.takeIf { it.isNotEmpty() } else null
    return ConsultaDeLaLista(
        consulta = c,
        origen = origen,
        propia = propia,
        autor = autor,
        cargadaPor = if (origen == OrigenConsulta.WEB && !propia) etiquetaCargadaPor(autor) else null,
        ausenteDesde = if (origen == OrigenConsulta.WEB) ausencias.desdeCuando(c.id) else null,
    )
}
