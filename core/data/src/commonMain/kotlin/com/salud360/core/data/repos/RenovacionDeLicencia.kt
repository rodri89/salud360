package com.salud360.core.data.repos

import com.salud360.core.model.auth.Licencia
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * El botón «Renovar» y los avisos de la pestaña Licencias del panel. Reglas puras, sin Compose ni red.
 */

/**
 * La licencia con «Vence» y «Aviso desde» un mes más adelante (mes calendario: el 31/01 pasa al 28/02, o al 29/02 en año
 * bisiesto). Suma a lo guardado, no a hoy, y deja igual el médico, el importe y el «activo». `null` si alguna de las dos
 * fechas no se puede leer: no se renueva a medias.
 */
fun renovarLicencia(l: Licencia): Licencia? {
    val vence = runCatching { LocalDate.parse(l.fechaExpiracion) }.getOrNull() ?: return null
    val aviso = runCatching { LocalDate.parse(l.fechaAviso) }.getOrNull() ?: return null
    return l.copy(
        fechaExpiracion = vence.plus(1, DateTimeUnit.MONTH).toString(),
        fechaAviso = aviso.plus(1, DateTimeUnit.MONTH).toString(),
    )
}

/** Aviso cuando la licencia se guardó y la historia clínica la aceptó. */
fun avisoDeLicenciaGuardada(): String = "Licencia guardada"

/** Aviso cuando la licencia se renovó y la historia clínica la aceptó. */
fun avisoDeLicenciaRenovada(): String = "Licencia renovada"

/** Aviso cuando la copia del dispositivo quedó guardada pero la historia clínica, que es la que manda, no la aceptó. */
fun avisoDeLicenciaNoAceptada(): String = "La licencia quedó guardada en este dispositivo, pero la historia clínica no la aceptó."

/** Aviso cuando se quiso renovar una licencia cuya fecha no se puede leer. */
fun avisoDeLicenciaConFechaInvalida(): String = "La licencia tiene una fecha inválida; corregila antes de renovar"

/** Aviso cuando el guardado falló: el motivo es el real (el de la historia clínica o el de la red). */
fun avisoDeLicenciaNoGuardada(motivo: String?): String =
    "No se pudo guardar la licencia: ${motivo?.takeIf { it.isNotBlank() } ?: "sin conexión"}"

/** El importe que escribió el administrador, con coma o punto decimal; `null` si todavía no es un número. */
fun importeEscrito(texto: String): Double? = texto.trim().replace(',', '.').toDoubleOrNull()

/**
 * Qué texto muestra el campo «Importe» para el [importe] guardado. Si lo [escrito] ya vale lo mismo se deja como está,
 * así el guardado no le cambia el texto a quien todavía está tipeando; si el importe cambió desde afuera, se muestra el
 * guardado (vacío cuando es cero).
 */
fun textoDelImporte(importe: Double, escrito: String): String =
    if (importeEscrito(escrito) == importe) escrito else if (importe == 0.0) "" else importe.toString()
