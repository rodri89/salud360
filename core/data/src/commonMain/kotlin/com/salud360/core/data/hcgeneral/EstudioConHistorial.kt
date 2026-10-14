package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.datetime.LocalDate

// Estudios con historial (`ModoRegistro.UNO_POR_CONSULTA`, RF-38, RF-39): como la web, una fila por consulta con
// fecha + `detalle`. Los registros son los del paciente en la HC tal como los da `registrosDePaciente` (fecha DESC y,
// a igual fecha, updated_at DESC); acá se reordenan por fecha sin romper ese desempate, que el modelo no trae.

private const val DETALLE = "detalle"

private fun porFecha(registros: List<RegistroClinico>) = registros.sortedByDescending { it.fecha }

/** La entrada de [consultaId] que se edita: la de fecha más reciente si quedó más de una; `null` si no tiene. */
fun entradaDeConsulta(registros: List<RegistroClinico>, consultaId: Id): RegistroClinico? =
    porFecha(registros).firstOrNull { it.consultaId == consultaId }

/**
 * "Consultas previas": todas las entradas del paciente menos [editada] (también las posteriores, como la web), sin
 * las de texto en blanco, de la fecha más reciente a la más antigua.
 */
fun historialEstudio(registros: List<RegistroClinico>, editada: RegistroClinico?): List<RegistroClinico> =
    porFecha(registros).filter { it.id != editada?.id && !it.campos[DETALLE].isNullOrBlank() }

/** Qué hacer en el dispositivo cuando el médico escribe el texto o cambia la fecha de la entrada de su consulta. */
sealed interface CambioEstudio {
    data class Crear(val fecha: LocalDate, val texto: String) : CambioEstudio
    data class Actualizar(val registro: RegistroClinico) : CambioEstudio
    data class Borrar(val registro: RegistroClinico) : CambioEstudio
    data object Nada : CambioEstudio
}

/** [actual] es la entrada de la consulta ([entradaDeConsulta]); texto en blanco = la consulta queda sin entrada. */
fun cambioEstudio(actual: RegistroClinico?, fecha: LocalDate, texto: String): CambioEstudio = when {
    texto.isBlank() -> if (actual == null) CambioEstudio.Nada else CambioEstudio.Borrar(actual)
    actual == null -> CambioEstudio.Crear(fecha, texto)
    actual.fecha == fecha && actual.campos[DETALLE] == texto -> CambioEstudio.Nada
    else -> CambioEstudio.Actualizar(actual.copy(fecha = fecha, campos = actual.campos + (DETALLE to texto)))
}
