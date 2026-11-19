package com.salud360.core.data.sync

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Un reloj falso para las esperas de [HcClinicaEnvio]: [esperar] suspende hasta que el test lo mueve con [avanzar], y las esperas
 * vencen en el orden de su vencimiento (y, a igual vencimiento, en el orden en que se pidieron). No usa el tiempo real ni hilos:
 * dos corridas dan siempre lo mismo. Hace falta porque `core:data` no tiene `kotlinx-coroutines-test` (y esta tarea no suma
 * dependencias). Pensado para correr el motor en `Dispatchers.Unconfined`: al avanzar, lo que vence se reanuda en el acto.
 * [esperarAQueHaya] deja a un test esperar a que la cola (que programa desde otra corrutina) termine de anotar una espera.
 */
internal class RelojFalso {
    private class Espera(val vence: Long, val orden: Long, val seguir: CancellableContinuation<Unit>)

    /** Un test esperando a que haya [minimo] esperas en pie (ver [esperarAQueHaya]). */
    private class Observador(val minimo: Int, val seguir: CancellableContinuation<Unit>)

    private val candado = Any()
    private val esperas = mutableListOf<Espera>()
    private val observadores = mutableListOf<Observador>()
    private var contador = 0L

    /** El instante actual, en ms desde que arrancó el reloj. */
    var ahora: Long = 0L
        private set

    /** Cuántas esperas hay en pie. Una espera cancelada deja de contar. */
    val esperando: Int get() = synchronized(candado) { esperas.size }

    suspend fun esperar(ms: Long) = suspendCancellableCoroutine<Unit> { seguir ->
        val espera = synchronized(candado) { Espera(ahora + ms, contador++, seguir).also { esperas += it } }
        seguir.invokeOnCancellation { synchronized(candado) { esperas.remove(espera) } }
        avisarObservadores()
    }

    /**
     * Suspende hasta que haya al menos [cantidad] esperas en pie. La cola programa sus esperas desde otra corrutina (un envío que leyó
     * un documento cambia de hilo en `Dispatchers.IO`), así que una espera puede quedar anotada después de que termine el `join()` del
     * envío: esto espera ese hecho observable, sin dormir ni mirar el tiempo real, antes de mover el reloj con [avanzar]. Si ya hay
     * [cantidad] esperas, vuelve en el acto.
     */
    suspend fun esperarAQueHaya(cantidad: Int = 1) {
        if (esperando >= cantidad) return
        suspendCancellableCoroutine<Unit> { seguir ->
            val observador = Observador(cantidad, seguir)
            seguir.invokeOnCancellation { synchronized(candado) { observadores.remove(observador) } }
            val yaHay = synchronized(candado) {
                if (esperas.size >= cantidad) true else { observadores += observador; false }
            }
            if (yaHay) seguir.resume(Unit)
        }
    }

    /** Despierta a los tests que esperaban a que hubiera suficientes esperas en pie (se avisa fuera del candado). */
    private fun avisarObservadores() {
        val listos = synchronized(candado) {
            observadores.filter { esperas.size >= it.minimo }.also { observadores.removeAll(it) }
        }
        listos.forEach { it.seguir.resume(Unit) }
    }

    /** Mueve el reloj [ms] hacia adelante y reanuda, de a una y en orden, las esperas que vencen en ese tramo. */
    fun avanzar(ms: Long) {
        val meta = ahora + ms
        while (true) {
            val vencida = synchronized(candado) {
                esperas.filter { it.vence <= meta }.minWithOrNull(compareBy({ it.vence }, { it.orden }))?.also {
                    esperas.remove(it)
                    ahora = maxOf(ahora, it.vence)
                }
            } ?: break
            vencida.seguir.resume(Unit)
        }
        synchronized(candado) { ahora = meta }
    }
}
