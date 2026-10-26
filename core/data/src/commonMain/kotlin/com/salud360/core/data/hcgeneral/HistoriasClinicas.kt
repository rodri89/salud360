package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CodigosHc

/**
 * Código de HC que turnos todavía informa para la hepatología. Ya no es una historia clínica aparte: es un perfil de la HC
 * general (spec 001 RF-29), así que la app lo cuenta como [CodigosHc.GENERAL] (tanda D; turnos y hclinica no cambian).
 */
private const val HEPATOLOGIA = "hepatologia"

/**
 * Los códigos de HC habilitados, listos para usar: cada uno sin espacios y en minúsculas, sin vacíos, con `hepatologia`
 * como la HC general, sin repetidos y en el orden de la primera aparición. Idempotente.
 */
fun normalizarHistoriasClinicas(codigos: List<String>): List<String> =
    codigos.mapNotNull { normalizarCodigoHc(it) }.distinct()

/** Lo mismo para un solo código; `null` si no hay (nulo, vacío o solo espacios). */
fun normalizarCodigoHc(codigo: String?): String? {
    val c = codigo?.trim()?.lowercase()?.ifEmpty { null } ?: return null
    return if (c == HEPATOLOGIA) CodigosHc.GENERAL else c
}

/** Sugerencia inicial del código de HC según el nombre de la especialidad de turnos; el admin puede cambiarlo. */
fun codigoHcPorNombre(nombre: String?): String? {
    val n = nombre?.lowercase() ?: return null
    return when {
        "pediatr" in n -> "pediatria"
        "ginec" in n || "obstet" in n -> "gineco"
        "cardio" in n -> "cardiologia"
        "endocrin" in n -> "endocrinologia"
        "hemato" in n -> "hematologia"
        "hepat" in n -> CodigosHc.GENERAL // "hepatología" y "hepatólogo" (con la tilde en la ó no comparte "hepato")
        "clínic" in n || "clinic" in n -> CodigosHc.GENERAL
        "desarrollo" in n || "neurodesarrollo" in n -> "desarrollo_infantil"
        else -> null
    }
}
