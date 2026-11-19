package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.claveDeLoEnviado
import com.salud360.core.data.hcgeneral.loEnviadoComoTexto
import com.salud360.core.data.hcgeneral.loEnviadoDeTexto
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.pacientes.PacienteExtra

/**
 * Lo último que la web confirmó de cada consulta propia de la HC general (spec 005, T25), en `paciente_extra` del paciente
 * (`hce_enviado_<consulta>`): sin columnas nuevas y **sobrevive a cerrar y reabrir la app**. Son datos locales, así que siempre
 * `dirty = 0`: nunca salen hacia turnos ni se tratan como algo pendiente de enviar. Solo guarda y lee: qué se confirmó es de quien
 * envía (`HcClinicaEnvio`). Sus escrituras entran en la transacción de quien las llama.
 */
class LoEnviadoGuardado(private val db: Salud360Db) {
    private val pq get() = db.pacientesQueries

    /** Lo confirmado de [consultaId]; un [LoEnviado] vacío si no hay nada guardado o no se entiende. */
    suspend fun deLaConsulta(pacienteId: Id, consultaId: Id): LoEnviado {
        val clave = claveDeLoEnviado(consultaId)
        val fila = pq.extrasDePaciente(pacienteId, CodigosHc.GENERAL).lista { it.toModel() }.firstOrNull { it.clave == clave }
        return loEnviadoDeTexto(fila?.valor)
    }

    /** Guarda [lo] como lo último confirmado de [consultaId], reemplazando lo anterior. Sin nada confirmado, no escribe. */
    suspend fun guardar(pacienteId: Id, consultaId: Id, lo: LoEnviado, sello: Long = ahoraMillis()) {
        val texto = loEnviadoComoTexto(lo) ?: return
        pq.upsertPacienteExtra(PacienteExtra(pacienteId, CodigosHc.GENERAL, claveDeLoEnviado(consultaId), texto).toRow(sello, dirty = false))
    }
}
