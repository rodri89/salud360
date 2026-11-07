package com.salud360.core.data.repos

import com.salud360.core.model.Id
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * El turno de cada consulta propia de la HC general (spec 005, RF-54): quien lo tiene es la única que cambia esa consulta en este momento.
 * Lo comparten la cola de envío (`HcClinicaEnvio`, que lo toma mientras envía), `HcRepository` (cerrar, reabrir y anular) y la lectura de una
 * propia (`HcClinicaBackend.traerConsulta`, que lo toma solo para aplicar lo que trajo), así que nunca dos a la vez sobre la misma consulta.
 *
 * Es una clase aparte porque la cola necesita la API de escritura del backend y el backend necesita el turno: compartirlo evita el ciclo.
 * No es reentrante: quien tiene el turno de una consulta no puede pedirlo de nuevo.
 */
class TurnosPorConsulta {
    /** Protege [turnos]; se suelta antes de esperar un turno. */
    private val candado = Mutex()
    private val turnos = mutableMapOf<Id, Mutex>()

    /** Corre [bloque] con el turno de [consultaId]; si lo tiene otro, espera a que termine. */
    suspend fun <T> conTurno(consultaId: Id, bloque: suspend () -> T): T {
        val turno = candado.withLock { turnos.getOrPut(consultaId) { Mutex() } }
        return turno.withLock { bloque() }
    }
}