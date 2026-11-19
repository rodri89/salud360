package com.salud360.core.data.hcgeneral

import com.salud360.core.data.mappers.splitCsv
import com.salud360.core.model.especialidad.AnalitoDef
import com.salud360.core.model.especialidad.CodigosHc

// Filas ocultas de un laboratorio (RF-43 a RF-49): preferencia del médico en `medico_preferencia`, como las secciones
// ocultas (RF-28). [ocultas] es lo guardado tal cual: puede traer claves que este laboratorio no tiene (de otra versión
// de la app); se ignoran y nunca se podan.

/** Clave de la preferencia: por especialidad y por laboratorio (general y reducido comparten analitos; RF-44). */
fun clavePreferenciaFilasOcultas(codigo: String, laboratorioTipo: String) = "filas_ocultas_${codigo}_$laboratorioTipo"

/** El valor guardado (claves de analito separadas por coma) como conjunto, sin vacíos. */
fun ocultasDePreferencia(valor: String?): Set<String> = valor?.splitCsv()?.toSet() ?: emptySet()

/** "Elegir filas" solo en la HC general y en laboratorios de más de una fila (no en Peso; RF-49, RF-33). */
fun puedeElegirFilas(codigo: String, analitos: List<AnalitoDef>): Boolean =
    codigo == CodigosHc.GENERAL && analitos.size > 1

/** Los analitos sin los ocultos, en su orden; si lo guardado no deja ninguno, todos (RF-45, RF-47). */
fun filasVisibles(analitos: List<AnalitoDef>, ocultas: Set<String>): List<AnalitoDef> =
    analitos.filter { it.clave !in ocultas }.ifEmpty { analitos }

/**
 * Oculta ([visible] = false) o muestra la fila [clave]. Si ocultarla deja el laboratorio sin filas visibles, o el
 * laboratorio no la tiene, devuelve [ocultas] igual (RF-47). Las claves de afuera del laboratorio se conservan.
 */
fun alternarFila(analitos: List<AnalitoDef>, ocultas: Set<String>, clave: String, visible: Boolean): Set<String> {
    if (analitos.none { it.clave == clave }) return ocultas
    // Si lo guardado las oculta todas, se ven todas (filasVisibles): se parte de eso.
    val actuales = if (todasOcultas(analitos, ocultas)) mostrarTodasLasFilas(analitos, ocultas) else ocultas
    if (visible) return actuales - clave
    val nuevas = actuales + clave
    return if (todasOcultas(analitos, nuevas)) ocultas else nuevas
}

/** Si [clave] es la única fila visible: su casilla va deshabilitada (RF-47). */
fun esUltimaVisible(analitos: List<AnalitoDef>, ocultas: Set<String>, clave: String): Boolean =
    filasVisibles(analitos, ocultas).singleOrNull()?.clave == clave

/** [ocultas] sin las claves de este laboratorio; las de afuera se conservan (RF-48). */
fun mostrarTodasLasFilas(analitos: List<AnalitoDef>, ocultas: Set<String>): Set<String> =
    ocultas - analitos.map { it.clave }.toSet()

/** Texto del botón: cuántas filas de este laboratorio no se ven (RF-48). */
fun textoBotonFilas(analitos: List<AnalitoDef>, ocultas: Set<String>): String =
    when (val n = analitos.size - filasVisibles(analitos, ocultas).size) {
        0 -> "Elegir filas"
        1 -> "Elegir filas (1 oculta)"
        else -> "Elegir filas ($n ocultas)"
    }

private fun todasOcultas(analitos: List<AnalitoDef>, ocultas: Set<String>) = analitos.all { it.clave in ocultas }
