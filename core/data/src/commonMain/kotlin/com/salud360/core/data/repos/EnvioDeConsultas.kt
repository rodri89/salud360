package com.salud360.core.data.repos

import com.salud360.core.model.Id
import kotlinx.coroutines.Job

/**
 * Lo que [HcRepository] necesita de la cola de envío de la HC general (spec 005, T28): saber si el cierre de una consulta está viajando
 * (RF-55), tomar su turno para decidir sin que un envío cambie las cosas en el medio (RF-54) y pedir que se envíe. Es una interfaz para
 * que el repositorio no dependa de la cola y se pruebe con o sin ella; la implementa `HcClinicaEnvio`. Sin cola, cerrar, reabrir y anular
 * guardan en el dispositivo y no mandan nada.
 */
interface EnvioDeConsultas {
    /** El cierre de [consultaId] está viajando ahora mismo: «Enviando el cierre.» No se puede reabrir ni anular hasta saber el resultado. */
    fun enviandoElCierre(consultaId: Id): Boolean

    /** Corre [bloque] con el turno de la consulta: nunca a la vez que un envío de la misma. Si hay uno en curso, espera a que termine. */
    suspend fun <T> conTurno(consultaId: Id, bloque: suspend () -> T): T

    /** Envía ya lo pendiente de la consulta (cerrar, anular), sin la espera del autoguardado; si hay un envío en curso, espera a que termine. */
    fun empujarYa(consultaId: Id): Job
}