package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoEnvio

// Cuánto se espera para volver a intentar lo que quedó «En espera» (spec 005, RF-6, RF-29). Puro: sin reloj ni red; quien programa el
// intento es `HcClinicaEnvio`. Dos familias de causas ajenas al dato, que se arreglan a distinto ritmo:
// - **Pasajeras** (sin conexión, la web caída o que no se entiende, «demasiados pedidos»): se reintentan a los 15 s, al minuto y a los
//   5 minutos —las esperas del envío diferido que ya usa pediatría ([com.salud360.core.data.sync.HcApiSync])— y después cada 5 minutos.
// - **De cuenta o de alguien** (sesión vencida, HC no habilitada, médico sin vincular, licencia, paciente fuera de la cartera o de baja,
//   sin permiso, ficha por elegir, documento inválido, ficha de otro paciente): no se arreglan en segundos —alguien tiene que volver a
//   iniciar sesión, pagar, elegir o avisar—. Se vuelven a mirar cada 5 minutos, para salir solas cuando cambia la causa (los
//   disparadores de RF-57 las adelantan) sin gastar pedidos de balde.
// Nunca más rápido que cada 5 minutos una vez pasados los tres primeros: con 60 pedidos por minuto por IP, un consultorio entero
// comparte el límite.

/** Las esperas de los primeros reintentos de una causa pasajera: 15 s, 60 s y 300 s (las de `HcApiSync.ESPERAS`). */
val ESPERAS_DE_REINTENTO: List<Long> = listOf(15_000L, 60_000L, 300_000L)

/** Cada cuánto se vuelve a mirar una causa de cuenta o de alguien, y una pasajera después de los primeros reintentos: 5 minutos. */
const val ESPERA_PERIODICA: Long = 300_000L

private val CAUSAS_PASAJERAS = setOf(CausaEnEspera.SIN_CONEXION, CausaEnEspera.DEMASIADOS_PEDIDOS, CausaEnEspera.SERVIDOR)

/**
 * Cuánto esperar antes del próximo intento de lo que quedó en [estado]. [intento] es cuántos intentos seguidos fallaron antes de éste
 * (0 la primera vez); una causa de cuenta o de alguien no depende de él.
 */
fun esperaDeReintento(estado: EstadoEnvio.EnEspera, intento: Int): Long =
    if (estado.causa in CAUSAS_PASAJERAS) ESPERAS_DE_REINTENTO[intento.coerceIn(0, ESPERAS_DE_REINTENTO.lastIndex)] else ESPERA_PERIODICA
