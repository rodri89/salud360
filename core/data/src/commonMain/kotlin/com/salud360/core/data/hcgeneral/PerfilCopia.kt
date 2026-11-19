package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.AsignacionPerfil
import com.salud360.core.model.especialidad.PerfilHc

/**
 * Nombre para la copia de un perfil (RF-12): "Copia de X" y, si ya existe, "Copia de X (2)", "Copia de X (3)"...
 * [existentes] se comparan como en [validarPerfil] (sin espacios de más, mayúsculas ni tildes), así la copia nunca
 * choca con otro perfil. Si con el prefijo y el número pasa de [LARGO_NOMBRE_PERFIL], se recorta X (no el número).
 */
fun nombreCopia(nombre: String, existentes: Collection<String>): String {
    val base = nombre.trim().replace(ESPACIOS, " ")
    val ocupados = existentes.mapTo(mutableSetOf()) { claveDeNombre(it) }
    var numero = 1
    while (true) {
        val sufijo = if (numero == 1) "" else " ($numero)"
        val lugar = LARGO_NOMBRE_PERFIL - caracteres(PREFIJO_COPIA) - caracteres(sufijo)
        val candidato = PREFIJO_COPIA + recortar(base, lugar).trimEnd() + sufijo
        if (claveDeNombre(candidato) !in ocupados) return candidato
        numero++
    }
}

/** Copia de [perfil] lista para crear (`id` null): mismas pestañas, secciones, nombres y tipos (RF-12). */
fun duplicarPerfil(perfil: PerfilHc, existentes: Collection<String>): PerfilHc =
    perfil.copy(id = null, nombre = nombreCopia(perfil.nombre, existentes))

/**
 * Si se puede borrar [perfil] (RF-11): ningún médico asignado, activo o no (el servidor cuenta todas las
 * asignaciones y contesta 409 `perfil_con_medicos`). Uno sin guardar no tiene asignaciones.
 */
fun puedeBorrar(perfil: PerfilHc, asignaciones: Collection<AsignacionPerfil>): Boolean =
    perfil.id == null || asignaciones.none { it.perfilId == perfil.id }

private const val PREFIJO_COPIA = "Copia de "

private val ESPACIOS = Regex("\\s+")

/** Los primeros [maximo] caracteres de [texto], contados como [caracteres] (sin partir un par sustituto). */
private fun recortar(texto: String, maximo: Int): String {
    var n = 0
    var i = 0
    while (i < texto.length && n < maximo) {
        i += if (texto[i].isHighSurrogate() && i + 1 < texto.length && texto[i + 1].isLowSurrogate()) 2 else 1
        n++
    }
    return texto.substring(0, i)
}
