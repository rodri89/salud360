package com.salud360.core.model.sync

import com.salud360.core.model.Id
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Sincronización offline-first.
 *
 * Cada cambio local se encola en [CambioLocal] con el registro completo serializado en JSON.
 * Cuando hay conexión, el cliente envía la cola con `POST /sync/push` y luego trae los cambios
 * del servidor con `GET /sync/pull?desde=<marca>`. El servidor resuelve conflictos por
 * "última escritura gana" usando `updatedAt`, salvo para turnos, donde el servidor valida la
 * disponibilidad del horario y puede rechazar el cambio.
 */
@Serializable
enum class Operacion { UPSERT, DELETE }

@Serializable
data class CambioLocal(
    val id: Long = 0,
    val tabla: String,
    val registroId: Id,
    val operacion: Operacion,
    val payload: JsonObject,
    val creadoEn: Long,
    val intentos: Int = 0,
    val ultimoError: String? = null,
)

@Serializable
data class CambioRemoto(
    val tabla: String,
    val registroId: Id,
    val operacion: Operacion,
    val payload: JsonObject,
    /** Marca de tiempo del servidor (epoch millis). */
    val version: Long,
)

@Serializable
data class PushRequest(val cambios: List<CambioLocal>, val dispositivoId: String)

@Serializable
data class PushResponse(
    val aceptados: List<Id>,
    val rechazados: List<Rechazo> = emptyList(),
)

@Serializable
data class Rechazo(val registroId: Id, val tabla: String, val motivo: String)

@Serializable
data class PullResponse(
    val cambios: List<CambioRemoto>,
    /** Marca hasta la que se sincronizó; se envía en el próximo pull. */
    val hasta: Long,
    val hayMas: Boolean = false,
)

/** Nombres de tablas sincronizables, compartidos por cliente y servidor. */
object Tablas {
    const val USUARIOS = "usuarios"
    const val MEDICOS = "medicos"
    const val SECRETARIAS = "secretarias"
    const val ESPECIALIDADES = "especialidades"
    const val CONSULTORIOS = "consultorios"
    const val PACIENTES = "pacientes"
    const val PACIENTE_EXTRAS = "paciente_extras"
    const val MEDICO_PACIENTES = "medico_pacientes"
    const val CONSULTAS = "consultas"
    const val SECCION_VALORES = "seccion_valores"
    const val EXAMEN_FISICO = "examen_fisico"
    const val ANTECEDENTES = "antecedentes"
    const val REGISTROS = "registros_clinicos"
    const val LABORATORIOS = "laboratorios"
    const val ARCHIVOS = "archivos"
    const val PENDIENTES = "pendientes"
    const val DIAGNOSTICOS = "diagnosticos"
    const val CONSULTA_DIAGNOSTICOS = "consulta_diagnosticos"
    const val INTERCONSULTORES = "interconsultores"
    const val VACUNAS = "vacunas_aplicadas"
    const val PREFERENCIAS = "medico_preferencias"
    const val HORARIOS = "horarios"
    const val HORARIO_RANGOS = "horario_rangos"
    const val FECHAS_AGREGADAS = "fechas_agregadas"
    const val TURNOS = "turnos"
    const val FERIADOS = "feriados"
    const val OBRAS_SOCIALES = "obras_sociales"
    const val OBRA_SOCIAL_MEDICOS = "obra_social_medicos"
    const val MEDICO_MODULOS = "medico_modulos"
    const val CONFIG_AGENDA = "config_agenda"
    const val MENSAJES_ESPECIALES = "mensajes_especiales"
    const val RECETAS = "recetas"
    const val LICENCIAS = "licencias"

    val todas: List<String> = listOf(
        USUARIOS, MEDICOS, SECRETARIAS, ESPECIALIDADES, CONSULTORIOS, PACIENTES, PACIENTE_EXTRAS, MEDICO_PACIENTES,
        CONSULTAS, SECCION_VALORES, EXAMEN_FISICO, ANTECEDENTES, REGISTROS, LABORATORIOS, ARCHIVOS, PENDIENTES,
        DIAGNOSTICOS, CONSULTA_DIAGNOSTICOS, INTERCONSULTORES, VACUNAS, PREFERENCIAS, HORARIOS, HORARIO_RANGOS,
        FECHAS_AGREGADAS, TURNOS, FERIADOS, OBRAS_SOCIALES, OBRA_SOCIAL_MEDICOS, MEDICO_MODULOS, CONFIG_AGENDA,
        MENSAJES_ESPECIALES, RECETAS, LICENCIAS,
    )
}
