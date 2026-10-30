package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Pendiente

// Pendientes de la HC general que vinieron de la web (RF-62): la web es su dueña y la app no los resuelve ni los
// reemplaza, porque el cambio no llegaría y los dos lados quedarían distintos. Los locales siguen como siempre.

/** Qué hacer con los pendientes al cerrar una consulta dejando un texto nuevo (ver [cambioDePendientes]). */
data class CambioDePendientes(val resolver: List<Pendiente>, val crear: String?)

/** El pendiente vino de la web (`hcc-p<id>`; la web guarda uno por paciente). */
fun Pendiente.esDeLaWeb(): Boolean = id.startsWith(PREFIJO_ID_WEB)

/** "Resuelto" solo para los locales (RF-62). */
fun Pendiente.puedeResolverse(): Boolean = !esDeLaWeb()

/** Texto de los pendientes locales, uno por línea: el que se prellena al cerrar. Los de la web quedan afuera (RF-62). */
fun textoDePendientesLocales(pendientes: List<Pendiente>): String =
    pendientes.filter { it.puedeResolverse() }.joinToString("\n") { it.texto }.trim()

/**
 * Cierre con pendientes (modelo de la web: un solo texto por paciente). Si el texto no cambió respecto de
 * [textoDePendientesLocales] no se toca nada; si cambió, se resuelven los pendientes locales y, si el texto no está
 * vacío, se crea uno nuevo. Los de la web no se resuelven nunca: siguen sin resolver y siguen contando.
 */
fun cambioDePendientes(pendientes: List<Pendiente>, texto: String): CambioDePendientes {
    val nuevo = texto.trim()
    if (nuevo == textoDePendientesLocales(pendientes)) return CambioDePendientes(emptyList(), null)
    val resolver = pendientes.filter { it.puedeResolverse() }.map { it.copy(resuelto = true) }
    return CambioDePendientes(resolver, nuevo.takeIf { it.isNotBlank() })
}
