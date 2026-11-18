package com.salud360.core.data.repos

import com.salud360.core.model.pacientes.Sexo

/**
 * El sexo que informa la historia clínica de pediatría, que lo tiene de todos sus pacientes (turnos no lo
 * tiene, así que toda ficha que llega de turnos nace sin él).
 *
 * Tolerante: la HC lo carga como «M» o «F», pero acá también se entienden las palabras completas, sin
 * distinguir mayúsculas ni espacios. Todo lo que no se reconoce (vacío, `null`, un valor raro) es `null`:
 * ante la duda, mejor sin dato que uno equivocado.
 */
fun sexoDesdeHc(texto: String?): Sexo? = when (texto?.trim()?.lowercase()) {
    "m", "masculino", "varón", "varon" -> Sexo.M
    "f", "femenino" -> Sexo.F
    "x", "otro" -> Sexo.X
    else -> null
}

/**
 * Qué sexo queda en la ficha local. Lo que ya está cargado manda siempre —también lo que el médico puso a
 * mano—: el de la HC solo completa cuando la ficha no tiene ninguno.
 */
fun completarSexo(actual: Sexo?, desdeHc: Sexo?): Sexo? = actual ?: desdeHc
