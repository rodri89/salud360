package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionCatalogoRemota
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc

// ---- Edición del perfil en borrador (RF-5 a RF-8) ----
// Cada operación devuelve el perfil cambiado; con un índice fuera de rango lo devuelve sin cambios. No validan: el
// borrador puede quedar con nombres vacíos o secciones sin tipos y eso lo marca `validarPerfil` al guardar. El `id`
// nunca cambia, y las secciones que la app no conoce viajan como cualquier otra (RF-16): ninguna operación las pierde
// salvo quitarlas a ellas o a su pestaña.

/** Pestaña nueva y vacía al final, llamada "Pestaña N" con el primer N que no repita otra (como [claveDeNombre]). */
fun PerfilHc.agregarPestania(): PerfilHc {
    val ocupados = pestanias.mapTo(mutableSetOf()) { claveDeNombre(it.nombre) }
    val nombre = generateSequence(1) { it + 1 }.map { "$PREFIJO_PESTANIA$it" }.first { claveDeNombre(it) !in ocupados }
    return copy(pestanias = pestanias + PestaniaPerfil(nombre, emptyList()))
}

fun PerfilHc.renombrarPestania(i: Int, nombre: String): PerfilHc =
    cambiarPestania(i) { it.copy(nombre = nombre) }

/** Sube ([delta] -1) o baja (+1) la pestaña [i]; la primera no sube y la última no baja. */
fun PerfilHc.moverPestania(i: Int, delta: Int): PerfilHc =
    pestanias.mover(i, delta)?.let { copy(pestanias = it) } ?: this

/** Quita la pestaña [i] con todas sus secciones. */
fun PerfilHc.quitarPestania(i: Int): PerfilHc =
    if (i in pestanias.indices) copy(pestanias = pestanias.filterIndexed { k, _ -> k != i }) else this

/**
 * Agrega [seccionId] al final de la pestaña [pestania] con [nuevaSeccion] (nombre por defecto y solo Consulta, RF-8).
 * Si no está en [catalogo] o ya está en el perfil (va una sola vez, RF-9), no cambia nada.
 */
fun PerfilHc.agregarSeccion(pestania: Int, seccionId: String, catalogo: List<SeccionCatalogoRemota>): PerfilHc {
    if (catalogo.none { it.id == seccionId }) return this
    if (pestanias.any { p -> p.secciones.any { it.seccion == seccionId } }) return this
    val nueva = nuevaSeccion(seccionId, catalogo)
    return cambiarPestania(pestania) { it.copy(secciones = it.secciones + nueva) }
}

fun PerfilHc.renombrarSeccion(pestania: Int, j: Int, nombre: String): PerfilHc =
    cambiarSeccion(pestania, j) { it.copy(nombre = nombre) }

/** Marca o desmarca la sección para [tipo]. Puede quedar sin tipos: eso lo avisa `validarPerfil`. */
fun PerfilHc.marcarTipo(pestania: Int, j: Int, tipo: TipoConsultaHc, marcado: Boolean): PerfilHc =
    cambiarSeccion(pestania, j) { it.copy(tipos = if (marcado) it.tipos + tipo else it.tipos - tipo) }

/** Sube ([delta] -1) o baja (+1) la sección [j] dentro de su pestaña. */
fun PerfilHc.moverSeccion(pestania: Int, j: Int, delta: Int): PerfilHc {
    val secciones = pestanias.getOrNull(pestania)?.secciones?.mover(j, delta) ?: return this
    return cambiarPestania(pestania) { it.copy(secciones = secciones) }
}

/** Pasa la sección [j] de [origen] al final de [destino], con su nombre y sus tipos. A la misma pestaña, nada. */
fun PerfilHc.moverSeccionAPestania(origen: Int, j: Int, destino: Int): PerfilHc {
    if (origen == destino || destino !in pestanias.indices) return this
    val seccion = pestanias.getOrNull(origen)?.secciones?.getOrNull(j) ?: return this
    return quitarSeccion(origen, j).cambiarPestania(destino) { it.copy(secciones = it.secciones + seccion) }
}

/** Quita la sección [j]; la pestaña queda aunque se quede vacía. */
fun PerfilHc.quitarSeccion(pestania: Int, j: Int): PerfilHc {
    val secciones = pestanias.getOrNull(pestania)?.secciones ?: return this
    if (j !in secciones.indices) return this
    return cambiarPestania(pestania) { p -> p.copy(secciones = secciones.filterIndexed { k, _ -> k != j }) }
}

private const val PREFIJO_PESTANIA = "Pestaña "

private fun PerfilHc.cambiarPestania(i: Int, cambio: (PestaniaPerfil) -> PestaniaPerfil): PerfilHc =
    if (i in pestanias.indices) copy(pestanias = pestanias.mapIndexed { k, p -> if (k == i) cambio(p) else p }) else this

private fun PerfilHc.cambiarSeccion(pestania: Int, j: Int, cambio: (SeccionPerfil) -> SeccionPerfil): PerfilHc {
    if (j !in (pestanias.getOrNull(pestania)?.secciones?.indices ?: return this)) return this
    return cambiarPestania(pestania) { p -> p.copy(secciones = p.secciones.mapIndexed { k, s -> if (k == j) cambio(s) else s }) }
}

/** La lista con el elemento [i] corrido [delta] lugares; null si [i] o el destino quedan fuera de rango. */
private fun <T> List<T>.mover(i: Int, delta: Int): List<T>? {
    val destino = i + delta
    if (i !in indices || destino !in indices) return null
    return toMutableList().apply { add(destino, removeAt(i)) }
}
