package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.CAMPO_TEXTO
import com.salud360.core.data.hcgeneral.LecturaWeb
import com.salud360.core.data.hcgeneral.PREFIJO_ID_WEB
import com.salud360.core.data.hcgeneral.claveDeCampo
import com.salud360.core.data.hcgeneral.claveDeLaMarca
import com.salud360.core.data.hcgeneral.clavesDelExamenTraido
import com.salud360.core.data.hcgeneral.esDeLaWeb
import com.salud360.core.data.hcgeneral.filaDeLaWeb
import com.salud360.core.data.hcgeneral.idConsultaWeb
import com.salud360.core.data.hcgeneral.marcaAusente
import com.salud360.core.data.hcgeneral.marcarAusentes
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.hc.EstadoConsulta
import kotlinx.datetime.LocalDate

/**
 * Las marcas «Ya no figura en la web» de la HC general (spec 002, Q15 = nunca borrar, RF-63): filas `hcc_ausente_<clave>`
 * de `paciente_extra` con la fecha en que se notó y `dirty = 0`. Es lo único que la lectura de la web escribe sobre lo ya
 * traído: poner la marca, o quitarla cuando el dato vuelve (se da de baja la fila de la marca; el dato clínico nunca). Nada
 * de esto borra, vacía u oculta una fila clínica.
 *
 * [aplicar] decide con `marcarAusentes` (puro, E6.2) y lo llama quien escribe la respuesta, **dentro de su transacción**: o
 * entra todo —lo que la web mandó y las marcas— o no entra nada.
 */
internal class MarcasDeLaWeb(private val db: Salud360Db) {
    private val hq get() = db.historiaClinicaQueries
    private val pq get() = db.pacientesQueries

    /** Las marcas vigentes del paciente: clave de ausencia (`hcc-c7003`, `hcc-c7003:habitos:texto`, ...) → fecha en que se notó. */
    suspend fun delPaciente(pacienteId: Id): Map<String, String> =
        pq.extrasDePaciente(pacienteId, CodigosHc.GENERAL).lista { it }
            .mapNotNull { fila -> claveDeLaMarca(fila.clave)?.let { it to fila.valor } }
            .toMap()

    /**
     * Pone y quita las marcas de [pacienteId] que corresponden a [lectura]. `traido` y `marcadas` son solo del paciente
     * (por `paciente_id`): una respuesta de su ficha no puede marcar ni desmarcar nada de otro paciente aunque los ids de
     * la web coincidan. Lo sucio y lo creado en el dispositivo no entra en `traido`, así que nunca se marca.
     */
    suspend fun aplicar(pacienteId: Id, lectura: LecturaWeb, hoy: LocalDate, sello: Long) {
        val cambios = marcarAusentes(traido(pacienteId, lectura), delPaciente(pacienteId).keys, lectura)
        cambios.marcar.sorted().forEach { pq.upsertPacienteExtra(marcaAusente(pacienteId, it, hoy).filaDeLaWeb(sello)) }
        cambios.desmarcar.sorted().forEach {
            pq.upsertPacienteExtra(marcaAusente(pacienteId, it, hoy).toRow(sello, deleted = true, dirty = false))
        }
    }

    /**
     * Marca «Ya no figura en la web» a la consulta de la web [consultaWebId] (`hcc-c<id>`) porque el envío de una propia ya enviada recibió un
     * `404 consulta_no_encontrada` (RF-33, ClasificacionDeEnvio). Escribe la marca aunque la fila local tenga cambios pendientes (es lo que
     * hace falta decir: la web ya no la tiene) y no toca ninguna fila clínica. La lectura la quita si la consulta vuelve (misma clave).
     */
    suspend fun marcarConsulta(pacienteId: Id, consultaWebId: Long, hoy: LocalDate, sello: Long) {
        val clave = idConsultaWeb(consultaWebId)
        if (clave !in delPaciente(pacienteId).keys) pq.upsertPacienteExtra(marcaAusente(pacienteId, clave, hoy).filaDeLaWeb(sello))
    }
    /** Las claves de ausencia de lo que el dispositivo ya trajo de la web, limpio y de [pacienteId], y que [lectura] podría cubrir. */
    private suspend fun traido(pacienteId: Id, lectura: LecturaWeb): Set<String> = when (lectura) {
        is LecturaWeb.ConError -> emptySet()
        is LecturaWeb.Lista -> consultasTraidas(pacienteId)
        is LecturaWeb.ConsultaNoEncontrada -> consultasTraidas(pacienteId).filterTo(linkedSetOf()) { it == idConsultaWeb(lectura.consultaWebId) }
        is LecturaWeb.UnaConsulta -> contenidoDeLaConsulta(pacienteId, idConsultaWeb(lectura.respuesta.consulta.id))
        is LecturaWeb.Historia -> delHistorial(pacienteId)
    }

    /**
     * Las consultas de la web (`hcc-c<id>`) que el paciente tiene y no tienen cambios sin enviar, y las **propias** ya enviadas y limpias
     * (RF-33) con la clave de la web de cada una (`hcc-c<remoto_id>`): una propia dada de baja en la web se marca igual que una ajena.
     */
    private suspend fun consultasTraidas(pacienteId: Id): Set<String> =
        (hq.consultasDePaciente(pacienteId, CodigosHc.GENERAL).lista { it }
            .filter { it.dirty == 0L && it.id.startsWith("${PREFIJO_ID_WEB}c") }
            .map { it.id } + propiasTraidas(pacienteId).keys).toCollection(linkedSetOf())

    /**
     * Las propias de esta app ya enviadas, sin cambios pendientes y no anuladas (una baja a propósito no es una ausencia): clave de la web
     * (`hcc-c<remoto_id>`) → id local.
     */
    private suspend fun propiasTraidas(pacienteId: Id): Map<String, Id> =
        hq.consultasDePaciente(pacienteId, CodigosHc.GENERAL).lista { it }.filter { it.dirty == 0L }.map { it.toModel() }
            .filter { it.propia && !it.esDeLaWeb() && it.estado != EstadoConsulta.ANULADA }
            .mapNotNull { c -> c.remotoId.toLongOrNull()?.takeIf { it > 0 }?.let { idConsultaWeb(it) to c.id } }
            .toMap()

    /**
     * Los textos, campos de formulario y datos del examen físico que la consulta [claveWeb] (`hcc-c<id>`) ya trajo (si la consulta es traída y
     * limpia). Si es una propia, los valores están en su fila local y la clave de la marca sigue siendo la de la web.
     */
    private suspend fun contenidoDeLaConsulta(pacienteId: Id, claveWeb: Id): Set<String> {
        if (claveWeb !in consultasTraidas(pacienteId)) return emptySet()
        val consultaId = propiasTraidas(pacienteId)[claveWeb] ?: claveWeb
        val valores = hq.valoresDeConsulta(consultaId).lista { it }
            .filter { it.dirty == 0L && it.valor.isNotBlank() }
            .map { claveDeCampo(claveWeb, it.seccion, it.campo) }
        val examen = hq.examenFisicoDeConsulta(consultaId).uno { it }
            ?.takeIf { it.dirty == 0L }?.toModel()?.let { clavesDelExamenTraido(claveWeb, it) }.orEmpty()
        return (valores + examen).toSet()
    }

    /** Antecedentes, cargas, estudios, exámenes, interconsultas y pendientes de la web del paciente, y el motivo de sus consultas. */
    private suspend fun delHistorial(pacienteId: Id): Set<String> = buildSet {
        val especialidad = CodigosHc.GENERAL
        hq.antecedentesDePaciente(pacienteId, especialidad).lista { it }
            .filter { it.dirty == 0L && it.id.startsWith("${PREFIJO_ID_WEB}a") }.forEach { add(it.id) }
        hq.laboratoriosDePacienteTodos(pacienteId, especialidad).lista { it }
            .filter { it.dirty == 0L && it.id.startsWith("${PREFIJO_ID_WEB}l") }.forEach { add(it.id) }
        hq.registrosDePacienteTodos(pacienteId, especialidad).lista { it }
            .filter { it.dirty == 0L && it.id.startsWith("${PREFIJO_ID_WEB}r") }.forEach { add(it.id) }
        hq.pendientesDePacienteTodos(pacienteId).lista { it }
            .filter { it.dirty == 0L && it.id.startsWith("${PREFIJO_ID_WEB}p") }.forEach { add(it.id) }

        val consultas = consultasTraidas(pacienteId)
        val motivosSucios = hq.seccionValoresDirty().lista { it }
            .filter { it.seccion == SeccionesComunes.MOTIVO_CONSULTA && it.campo == CAMPO_TEXTO }.map { it.consulta_id }.toSet()
        hq.valoresDeCampoDePaciente(pacienteId, especialidad, SeccionesComunes.MOTIVO_CONSULTA, CAMPO_TEXTO).lista { it }
            .filter { it.consulta_id in consultas && it.consulta_id !in motivosSucios && it.valor.isNotBlank() }
            .forEach { add(claveDeCampo(it.consulta_id, SeccionesComunes.MOTIVO_CONSULTA, CAMPO_TEXTO)) }
    }
}
