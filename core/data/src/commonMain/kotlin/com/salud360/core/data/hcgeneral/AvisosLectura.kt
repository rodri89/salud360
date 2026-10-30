package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.OmitidasConsultasDto
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.pacientes.PacienteExtra

// Textos y estado de los avisos de la lectura de la HC general (spec 002, entrega L: RF-43 a RF-46, RF-49, RF-63). Puro:
// la pantalla muestra lo que esto devuelve y no decide nada (constitución, punto 4). Los textos son los de la spec y del
// plan, tal cual; no se agrega ninguno que no estén ahí (los que faltaban están anotados en la tarea E7.1).

// ---------- Textos fijos ----------

/** Origen de una consulta traída de la web (plan, «Pantalla»). */
const val ETIQUETA_WEB = "Web"

/** Origen de una consulta cargada en el dispositivo y nunca enviada (RF-46, Q14). */
const val ETIQUETA_SOLO_EN_ESTE_DISPOSITIVO = "Solo en este dispositivo"

/** La misma marca con la aclaración de Q14 («marca visible»), para donde hay lugar: la consulta abierta en el dispositivo. */
const val MARCA_SOLO_EN_ESTE_DISPOSITIVO = "Solo en este dispositivo (no se envía a la web)"

/** RF-63: la etiqueta de una consulta, sección, carga, estudio, pendiente o antecedente que la web dejó de mandar. */
const val YA_NO_FIGURA_EN_LA_WEB = "Ya no figura en la web"

/** Plan, «Lo que ya no figura en la web»: el aviso arriba de una consulta marcada. */
const val CONSULTA_YA_NO_FIGURA_EN_LA_WEB = "Esta consulta ya no figura en la web: lo que ves es lo último que se trajo"

/** RF-49. */
const val PACIENTE_SIN_VINCULO = "Este paciente todavía no está vinculado con la historia clínica de la web"

/** Plan, «Pantalla»: el título de la tarjeta de las consultas abiertas en la web (RF-44). */
const val TITULO_ABIERTAS_EN_LA_WEB = "Abiertas en la web (solo lectura)"

/** Plan, «Pantalla»: el banner de toda consulta de la web (RF-45). */
const val BANNER_CONSULTA_DE_LA_WEB = "Consulta de la web — solo lectura"

/** RF-44 y plan: el aviso de una consulta de la web que sigue abierta. */
const val CONSULTA_ABIERTA_EN_LA_WEB = "Abierta en la web: lo pendiente de confirmar no se ve hasta que se cierre"

/** RF-45 y plan: cuando la web no informó quién cargó la consulta. */
const val OTRO_MEDICO = "otro médico"

/** RF-45, Q4: «Cargada por <nombre>», o «Cargada por otro médico» si no se sabe el nombre. */
fun etiquetaCargadaPor(nombre: String?): String = "Cargada por ${nombre?.trim()?.takeIf { it.isNotEmpty() } ?: OTRO_MEDICO}"

// ---------- Lo que la web tiene y esta versión no muestra ----------

/** RF-43 y plan: la línea de la lista con las consultas de un tipo que esta versión no muestra; `null` si no hay ninguna. */
fun avisoConsultasOmitidas(cantidad: Int): String? = when {
    cantidad <= 0 -> null
    cantidad == 1 -> "1 consulta de la web de un tipo que esta versión no muestra"
    else -> "$cantidad consultas de la web de un tipo que esta versión no muestra"
}

/** D1: las cargas de laboratorio y de peso que la web guarda sin fecha no llegan; se dice cuántas. `null` si no hay ninguna. */
fun avisoCargasSinFecha(cantidad: Int): String? = when {
    cantidad <= 0 -> null
    cantidad == 1 -> "1 carga de laboratorio/peso sin fecha no se muestra"
    else -> "$cantidad cargas de laboratorio/peso sin fecha no se muestran"
}

/** Cómo se nombra, en el aviso, cada cosa que el servidor informa en `no_leidas` (RF-43); lo que no conoce, tal como vino. */
fun nombreLegibleNoLeida(id: String): String = when (id) {
    "documentos" -> "adjuntos PDF"
    "audio" -> "audio"
    "fotos" -> "fotos"
    "diagnosticos" -> "diagnósticos"
    "antecedentes_personales" -> "antecedentes personales"
    else -> id
}

/**
 * RF-43 y plan: «La web tiene <adjuntos…> que esta versión no muestra», nombrando qué es ("adjuntos PDF, audio y fotos").
 * `null` si no hay nada (sin fila: no se sabe; o ya no tiene).
 */
fun avisoNoLeidas(ids: List<String>?): String? {
    val nombres = ids.orEmpty().filter { it.isNotBlank() }.distinct().map(::nombreLegibleNoLeida)
    if (nombres.isEmpty()) return null
    val lista = if (nombres.size == 1) nombres.first() else nombres.dropLast(1).joinToString(", ") + " y " + nombres.last()
    return "La web tiene $lista que esta versión no muestra"
}

// ---------- Lo que dejó la lectura en `paciente_extra` ----------

/**
 * Lo que la lectura guardó del paciente en `paciente_extra` (especialidad `clinica`): las consultas que la web no mandó
 * ([omitidasConsultas], `hcc_omitidas`), las cargas sin fecha ([cargasSinFecha], `hcc_omitidas_laboratorio`), lo que cada
 * consulta tiene y no se muestra ([noLeidas], por id de la web) y las marcas «Ya no figura en la web» ([ausencias]).
 * `null` y mapas vacíos quieren decir «todavía no se leyó / no se sabe».
 */
data class ExtrasDeLectura(
    val omitidasConsultas: OmitidasConsultasDto?,
    val cargasSinFecha: Int?,
    val noLeidas: Map<Long, List<String>>,
    val ausencias: AusenciasEnLaWeb,
) {
    companion object {
        val VACIO = ExtrasDeLectura(null, null, emptyMap(), AusenciasEnLaWeb.NINGUNA)
    }
}

/** Lee las filas de `paciente_extra` del paciente ([extras]); lo que no se entiende o es de otra historia clínica no cuenta. */
fun extrasDeLectura(extras: List<PacienteExtra>): ExtrasDeLectura {
    val filas = extras.filter { it.especialidad == CodigosHc.GENERAL }
    val porClave = filas.associate { it.clave to it.valor }
    return ExtrasDeLectura(
        omitidasConsultas = omitidasDeTexto(porClave[CLAVE_OMITIDAS]),
        cargasSinFecha = omitidasLaboratorioDeTexto(porClave[CLAVE_OMITIDAS_LABORATORIO]),
        noLeidas = filas.mapNotNull { fila ->
            if (!fila.clave.startsWith(PREFIJO_CLAVE_NO_LEIDAS)) return@mapNotNull null
            val idWeb = fila.clave.removePrefix(PREFIJO_CLAVE_NO_LEIDAS).toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            noLeidasDeTexto(fila.valor)?.let { idWeb to it }
        }.toMap(),
        ausencias = AusenciasEnLaWeb(
            filas.mapNotNull { fila -> claveDeLaMarca(fila.clave)?.let { it to fila.valor } }.toMap(),
        ),
    )
}

// ---------- Avisos de la historia del paciente ----------

/** Los avisos de la lista, cada uno `null` si no corresponde (solo se avisa si hay algo que avisar). */
data class AvisosDeLaHistoria(
    /** RF-49. */
    val sinVinculo: String?,
    /** RF-43, Q2: foto + otro + sin fecha de `hcc_omitidas`. */
    val consultasOmitidas: String?,
    /** D1: `hcc_omitidas_laboratorio`. Son cargas, no consultas: no se suman a [consultasOmitidas]. */
    val cargasSinFecha: String?,
) {
    /** Los que corresponden, en orden. */
    val todos: List<String> get() = listOfNotNull(sinVinculo, consultasOmitidas, cargasSinFecha)
}

/** Los avisos de la historia: [vinculado] es si el paciente tiene ficha en la web (`fichaConocida`). */
fun avisosDeLaHistoria(vinculado: Boolean, lectura: ExtrasDeLectura): AvisosDeLaHistoria {
    val consultas = lectura.omitidasConsultas?.let { it.foto + it.otro + it.sinFecha } ?: 0
    return AvisosDeLaHistoria(
        sinVinculo = PACIENTE_SIN_VINCULO.takeUnless { vinculado },
        consultasOmitidas = avisoConsultasOmitidas(consultas),
        cargasSinFecha = avisoCargasSinFecha(lectura.cargasSinFecha ?: 0),
    )
}

// ---------- Avisos de una consulta ----------

/**
 * Los avisos de una consulta de la web, cada uno `null` si no corresponde. Una consulta solo del dispositivo no recibe
 * ninguno. [soloLecturaWeb] dice que no se edita, no se cierra, no se reabre y no se anula (RF-45).
 */
data class AvisosDeConsulta(
    val soloLecturaWeb: Boolean,
    /** RF-45: «Consulta de la web — solo lectura». */
    val banner: String?,
    /** RF-45, Q4: «Cargada por …» si la cargó otro médico. */
    val cargadaPor: String?,
    /** RF-63: la consulta ya no figura en la web. */
    val ausente: String?,
    /** RF-44: sigue abierta en la web. */
    val abiertaEnLaWeb: String?,
    /** RF-43: lo que la web tiene en esta consulta y esta versión no muestra. */
    val noLeidas: String?,
) {
    /** Los que corresponden, en el orden en que van arriba de la consulta. */
    val todos: List<String> get() = listOfNotNull(banner, cargadaPor, ausente, abiertaEnLaWeb, noLeidas)

    companion object {
        /** Una consulta del dispositivo o de otra HC: se ve como siempre. */
        val NINGUNO = AvisosDeConsulta(false, null, null, null, null, null)
    }
}

/** Los avisos de [consulta] para quien la mira ([medicoSesionId]); [nombresMedicos] son los de [nombresDeMedicosWeb]. */
fun avisosDeConsulta(consulta: Consulta, medicoSesionId: Id, nombresMedicos: Map<Id, String>, lectura: ExtrasDeLectura): AvisosDeConsulta {
    if (!consulta.esDeLaWeb()) return AvisosDeConsulta.NINGUNO
    return AvisosDeConsulta(
        soloLecturaWeb = true,
        banner = BANNER_CONSULTA_DE_LA_WEB,
        cargadaPor = if (consulta.esPropia(medicoSesionId)) null else etiquetaCargadaPor(nombresMedicos[consulta.medicoId]),
        ausente = CONSULTA_YA_NO_FIGURA_EN_LA_WEB.takeIf { lectura.ausencias.consulta(consulta.id) },
        abiertaEnLaWeb = CONSULTA_ABIERTA_EN_LA_WEB.takeIf { consulta.estado == EstadoConsulta.ABIERTA },
        noLeidas = consulta.idWeb()?.let { lectura.noLeidas[it] }?.let(::avisoNoLeidas),
    )
}

// ---------- Nota del perfil 4 (D2) ----------

/**
 * Dónde se muestra la nota de la pestaña de antecedentes familiares del perfil 4 (D2): en la misma sección
 * «Antecedentes familiares», como nota aparte de las casillas y de la nota común.
 */
const val SECCION_DE_LA_NOTA_PERFIL4 = SeccionesComunes.ANTECEDENTES_FAMILIARES

/**
 * El texto de la nota del perfil 4, tal como vino, o `null` si no hay (la sección no la muestra). Sale del antecedente
 * `familiares` / [CLAVE_NOTA_PERFIL4] del paciente; una nota vacía, en blanco o dada de baja no cuenta. Nunca lleva la
 * marca «Ya no figura en la web»: `marcarAusentes` no la marca.
 */
fun notaPerfil4(antecedentes: List<Antecedente>): String? =
    antecedentes
        .firstOrNull { it.categoria == CATEGORIA_FAMILIARES && it.clave == CLAVE_NOTA_PERFIL4 && it.activo && it.detalle.isNotBlank() }
        ?.detalle
