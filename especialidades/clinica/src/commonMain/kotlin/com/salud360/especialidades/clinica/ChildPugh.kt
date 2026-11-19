package com.salud360.especialidades.clinica

import com.salud360.core.ui.components.alternarSiNo

/** Un parámetro de Child-Pugh: la clave guardada y el criterio de 1, 2 y 3 puntos, en ese orden (RF-74). */
data class ParametroChildPugh(val clave: String, val nombre: String, val criterios: List<String>)

/** Los cinco parámetros, con las claves de siempre (RF-78) y los textos corregidos de la web. */
val parametrosChildPugh = listOf(
    ParametroChildPugh("ascitis", "Ascitis", listOf("Ausente (I)", "Moderada (II)", "Severa (III) o refractaria")),
    ParametroChildPugh("bilirrubina", "Bilirrubina total (mg/dl)", listOf("≤ 2", "2 - 3", "> 3")),
    ParametroChildPugh("albumina", "Albúmina (g/dl)", listOf("> 3,5", "2,8 - 3,5", "< 2,8")),
    ParametroChildPugh("tp", "TP (seg sobre control) / RIN", listOf("1-3 / < 1,8", "4-6 / 1,8-2,3", "> 6 / > 2,3")),
    ParametroChildPugh("encefalopatia", "Encefalopatía", listOf("No", "Grado I/II", "Grado III/IV o refractaria")),
)

/** Puntos de un valor guardado: solo "1", "2" o "3"; vacío o desconocido (un "0" o un "4" viejo) → `null`. */
fun puntosDe(valor: String): Int? = when (valor) {
    "1" -> 1
    "2" -> 2
    "3" -> 3
    else -> null
}

/** Tocar una celda: pone ese puntaje; tocar la elegida deja el parámetro sin cargar (RF-76). */
fun alternarPuntos(actual: String, tocado: String): String = alternarSiNo(actual, tocado)

/** Suma de los parámetros con puntos; 0 sin ninguno (RF-77). */
fun totalChildPugh(valores: Map<String, String>): Int =
    parametrosChildPugh.sumOf { puntosDe(valores[it.clave].orEmpty()) ?: 0 }

/** Cuántos de los cinco tienen puntos. */
fun completosChildPugh(valores: Map<String, String>): Int =
    parametrosChildPugh.count { puntosDe(valores[it.clave].orEmpty()) != null }

/** A ≤ 6, B 7-9, C ≥ 10; "—" mientras falte alguno de los cinco (RF-77). */
fun claseChildPugh(total: Int, completos: Int): String = when {
    completos < parametrosChildPugh.size -> "—"
    total <= 6 -> "A"
    total <= 9 -> "B"
    else -> "C"
}
