package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.HcPacienteCandidato
import kotlinx.datetime.LocalDate

/**
 * Lo que muestra la tarjeta de una ficha candidata al "Elegí la ficha del paciente" (spec 003, RF-9): nombre completo,
 * documento y fecha de nacimiento como `dd/MM/aaaa`, o `null` si la web no la tiene (la tarjeta no muestra esa línea).
 * No lleva la cantidad de consultas: la spec pide nombre, apellido, documento y fecha de nacimiento (P9).
 */
data class LineaCandidata(val nombre: String, val documento: String, val nacimiento: String?)

/** Arma la [LineaCandidata] de [c]; una fecha ausente o ilegible queda en `null`, no se inventa texto. */
fun lineaDeCandidata(c: HcPacienteCandidato): LineaCandidata = LineaCandidata(
    nombre = nombreNormalizado("${c.nombre} ${c.apellido}"),
    documento = c.dni,
    nacimiento = c.fechaNacimiento?.let(::nacimientoLegible),
)

/** La web manda `yyyy-MM-dd` (el servidor ya descarta lo demás); otra forma o una fecha que no existe → `null`. */
private fun nacimientoLegible(fecha: String): String? {
    if (fecha.length != 10 || fecha.indices.any { (it == 4 || it == 7) != (fecha[it] == '-') }) return null
    val d = runCatching { LocalDate.parse(fecha) }.getOrNull() ?: return null
    return "${d.dayOfMonth.dos()}/${d.monthNumber.dos()}/${d.year.toString().padStart(4, '0')}"
}

private fun Int.dos(): String = toString().padStart(2, '0')
