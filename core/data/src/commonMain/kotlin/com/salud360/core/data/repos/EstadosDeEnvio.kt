package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.EstadoGuardado
import com.salud360.core.data.hcgeneral.claveDeEstadoDeEnvio
import com.salud360.core.data.hcgeneral.estadosDeEnvio
import com.salud360.core.data.hcgeneral.filaDeEstadoDeEnvio
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.LocalDate

/**
 * El estado de envío de cada parte de las consultas propias de la HC general (spec 005, RF-6, RF-30), en `paciente_extra` del
 * paciente (`hce_estado_<consulta>:<parte>`): sin columnas nuevas y **sobrevive a cerrar y reabrir la app**. Son datos locales,
 * así que siempre `dirty = 0`: nunca salen hacia turnos ni se tratan como algo pendiente de enviar. Quitar un estado es dar de baja su
 * fila (`deleted = 1`), nunca un `DELETE`. Solo guarda y lee: decidir el estado es de `ClasificacionDeEnvio` y quien envía.
 */
class EstadosDeEnvio(private val db: Salud360Db) {
    private val pq get() = db.pacientesQueries

    /**
     * Guarda el estado de [parte] de [consultaId], vigente desde [desde]; reemplaza el que había. [EstadoEnvio.Pendiente] no se
     * guarda: quita el estado (es la ausencia de uno).
     */
    suspend fun guardar(pacienteId: Id, consultaId: Id, parte: String, estado: EstadoEnvio, desde: LocalDate, sello: Long = ahoraMillis()) {
        val fila = filaDeEstadoDeEnvio(pacienteId, consultaId, parte, estado, desde)
        if (fila == null) quitar(pacienteId, consultaId, parte, sello) else pq.upsertPacienteExtra(fila.toRow(sello, dirty = false))
    }

    /** Quita el estado de [parte] (se da de baja su fila). Si no había, no escribe nada. */
    suspend fun quitar(pacienteId: Id, consultaId: Id, parte: String, sello: Long = ahoraMillis()) {
        val clave = claveDeEstadoDeEnvio(consultaId, parte)
        val existente = pq.extrasDePaciente(pacienteId, CodigosHc.GENERAL).lista { it.toModel() }.firstOrNull { it.clave == clave } ?: return
        pq.upsertPacienteExtra(existente.toRow(sello, deleted = true, dirty = false))
    }

    /** Todos los estados del paciente, por consulta y parte. */
    suspend fun delPaciente(pacienteId: Id): Map<Id, Map<String, EstadoGuardado>> =
        estadosDeEnvio(pq.extrasDePaciente(pacienteId, CodigosHc.GENERAL).lista<_, PacienteExtra> { it.toModel() })

    /** Los estados de una consulta, por parte; vacío si no tiene ninguno. */
    suspend fun deLaConsulta(pacienteId: Id, consultaId: Id): Map<String, EstadoGuardado> = delPaciente(pacienteId)[consultaId].orEmpty()
}
