package com.salud360.core.data.repos

import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.datetime.LocalDate

/**
 * API propia de una historia clínica (hc_pediatria, hclinica, ...).
 *
 * `HcRepository` es genérico y no conoce la red: recibe un mapa de backends por código de especialidad.
 * Una especialidad sin backend sigue funcionando exactamente como hoy, solo contra la base del dispositivo.
 *
 * Todo el guardado es **primero en el dispositivo y después el envío**: el autoguardado de la consulta
 * dispara cada pocos cientos de milisegundos mientras el médico escribe, así que estas operaciones las
 * llama un motor que junta los cambios pendientes, no la pantalla.
 */
/**
 * Backends de historia clínica por código de especialidad.
 * Envuelto en un tipo propio por la misma razón que [com.salud360.core.data.network.hc.HcApiClients].
 */
class HcBackends(val porEspecialidad: Map<String, HcBackend>)

interface HcBackend {
    /** Código de la especialidad que atiende este backend (ej. "pediatria"). */
    val especialidad: String

    /**
     * Id de la consulta en la API, creándola allá si todavía no existía.
     * Devuelve null si no se pudo (por ejemplo, el paciente no tiene documento válido).
     */
    suspend fun asegurarConsulta(consulta: Consulta): String?

    /** Manda las secciones de texto pendientes de una consulta ya creada en la API. */
    suspend fun enviarSecciones(remotoId: String, secciones: Map<String, Map<String, String>>)

    /** Manda el examen físico de una consulta ya creada en la API. */
    suspend fun enviarExamen(remotoId: String, campos: Map<String, String>)

    /**
     * Manda las filas pendientes de las listas (screening, internaciones, interconsultas, exámenes
     * complementarios). Devuelve, por cada id local, el id con el que quedó en la API: hay que
     * guardarlo o el próximo envío la duplica.
     */
    suspend fun enviarRegistros(remotoId: String, registros: List<RegistroClinico>): Map<Id, String>

    /** Refleja en la API el cierre, la reapertura o la anulación de la consulta. */
    suspend fun enviarEstado(consulta: Consulta, remotoId: String)

    /** Trae de la API las consultas del paciente y las deja en la base del dispositivo. */
    suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta>

    /**
     * Trae el contenido de una consulta y lo deja en la base del dispositivo, **sin pisar lo que el
     * médico haya escrito y todavía no se haya enviado**.
     */
    suspend fun traerConsulta(consultaLocalId: Id, remotoId: String)

    /** Fecha con la que la API identifica una consulta, para armar los cuerpos. */
    fun fechaTexto(fecha: LocalDate): String = fecha.toString()
}
