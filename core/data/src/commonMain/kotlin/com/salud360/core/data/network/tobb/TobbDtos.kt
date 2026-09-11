package com.salud360.core.data.network.tobb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Estructuras que devuelve la API `/api/salud360/...` de turnosonlinebb (ver `API_SALUD360.md` en ese repo).
 *
 * Varios campos llegan con tipos distintos según la columna MySQL de origen (el DNI es un entero,
 * los importes `decimal` vienen como texto, etc.), por eso algunos se leen como [JsonPrimitive] y se
 * normalizan en los mappers.
 */
val tobbJson: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; encodeDefaults = false }

@Serializable
data class TobbError(val ok: Boolean = false, val mensaje: String? = null, val codigo: String? = null, val message: String? = null)

@Serializable
data class TobbPacienteResumen(
    val id: Long,
    val nombre: String? = null,
    val apellido: String? = null,
    val dni: JsonPrimitive? = null,
    val telefono: JsonPrimitive? = null,
    val mail: String? = null,
    @SerialName("obra_social") val obraSocial: String? = null,
    @SerialName("numero_afiliado") val numeroAfiliado: JsonPrimitive? = null,
)

/** Turno tal como lo devuelve `AgendaService::formatearTurno`. */
@Serializable
data class TobbTurno(
    val id: Long,
    @SerialName("paciente_id") val pacienteId: Long = 0,
    @SerialName("medico_id") val medicoId: Long,
    @SerialName("consultorio_id") val consultorioId: Long,
    val dia: Int = 0,
    val horario: String,
    val fecha: String,
    val asistio: Int = 0,
    val sobreturno: Int = 0,
    @SerialName("primer_control") val primerControl: Boolean = false,
    val caja: Double = 0.0,
    val comentario: String? = "",
    @SerialName("tipo_turno") val tipoTurno: Int = 1,
    val especialidad: String? = null,
    @SerialName("otorgado_por") val otorgadoPor: String? = null,
    @SerialName("cancelado_por") val canceladoPor: String? = null,
    /** 1 activo, 0 cancelado, 2 bloqueado por pago pendiente. */
    val activo: Int = 1,
    val pago: Int = 0,
    @SerialName("importe_reserva") val importeReserva: JsonPrimitive? = null,
    val paciente: TobbPacienteResumen? = null,
)

/** `{horario, libre}`; con primer control doble también `horario2`. */
@Serializable
data class TobbSlot(val horario: String, val horario2: String? = null, val libre: Int = 0)

@Serializable
data class TobbAgendaDia(
    val fecha: String,
    val dia: Int = 0,
    @SerialName("es_feriado") val esFeriado: Boolean = false,
    val turnos: List<TobbTurno> = emptyList(),
    val slots: List<TobbSlot> = emptyList(),
    @SerialName("cantidad_sobreturnos") val cantidadSobreturnos: Int = 0,
    @SerialName("modulo_caja_comentario") val moduloCajaComentario: Int = 0,
)

@Serializable
data class TobbAgendaSemana(val desde: String = "", val dias: List<TobbAgendaDia> = emptyList())

@Serializable
data class TobbDisponibilidad(
    val fecha: String = "",
    @SerialName("es_feriado") val esFeriado: Boolean = false,
    @SerialName("primer_control_doble") val primerControlDoble: Boolean = false,
    @SerialName("cupo_primer_control_disponible") val cupoPrimerControlDisponible: Boolean? = null,
    val slots: List<TobbSlot> = emptyList(),
)

@Serializable
data class TobbFechas(val fechas: List<String> = emptyList())

@Serializable
data class TobbDiasAtencion(
    @SerialName("dias_semana") val diasSemana: List<Int> = emptyList(),
    @SerialName("fechas_especiales") val fechasEspeciales: List<String> = emptyList(),
)

/** Respuesta de `GET turnos`, `POST turnos` y `POST turnos/bloquear-dia`. */
@Serializable
data class TobbTurnosResponse(
    val turnos: List<TobbTurno> = emptyList(),
    val slots: List<TobbSlot> = emptyList(),
    val cantidad: Int = 0,
    val bloqueados: Int = 0,
)

/** Respuesta de sobreturno, cancelar, asistencia, caja y comentario. */
@Serializable
data class TobbTurnoResponse(
    val turno: TobbTurno? = null,
    @SerialName("cantidad_sobreturnos") val cantidadSobreturnos: Int = 0,
    @SerialName("ya_cancelado") val yaCancelado: Boolean = false,
)

/** Paciente tal como lo devuelve `Salud360Controller::formatearPaciente`. */
@Serializable
data class TobbPaciente(
    val id: Long,
    val nombre: String? = null,
    val apellido: String? = null,
    val dni: JsonPrimitive? = null,
    val telefono: JsonPrimitive? = null,
    val domicilio: String? = null,
    val localidad: String? = null,
    val mail: String? = null,
    @SerialName("fecha_nacimiento") val fechaNacimiento: String? = null,
    @SerialName("obra_social") val obraSocial: String? = null,
    @SerialName("numero_afiliado") val numeroAfiliado: JsonPrimitive? = null,
    @SerialName("obra_social_plan") val obraSocialPlan: String? = null,
    @SerialName("afiliado_obligatorio") val afiliadoObligatorio: Int = 0,
    val activo: Int = 1,
)

@Serializable
data class TobbPacientesResponse(val pacientes: List<TobbPaciente> = emptyList(), @SerialName("hay_mas") val hayMas: Boolean = false)

@Serializable
data class TobbPacienteResponse(val paciente: TobbPaciente, val creado: Boolean = false)

/** Cuerpo de `POST turnos` / `POST turnos/sobreturno`. */
@Serializable
data class TobbNuevoTurno(
    @SerialName("medico_id") val medicoId: Long,
    @SerialName("consultorio_id") val consultorioId: Long,
    @SerialName("paciente_id") val pacienteId: Long,
    val fecha: String,
    val horario: String,
    val horario2: String? = null,
    @SerialName("tipo_turno") val tipoTurno: Int = 1,
    @SerialName("primer_control") val primerControl: Int = 0,
    val comentario: String = "",
)

/** Cuerpo de `POST pacientes` (solo se mandan los campos con valor para no pisar datos de la web). */
@Serializable
data class TobbNuevoPaciente(
    @SerialName("medico_id") val medicoId: Long,
    @SerialName("consultorio_id") val consultorioId: Long? = null,
    val dni: String,
    val nombre: String,
    val apellido: String,
    val telefono: String? = null,
    val mail: String? = null,
    @SerialName("fecha_nacimiento") val fechaNacimiento: String? = null,
    val domicilio: String? = null,
    val localidad: String? = null,
    @SerialName("obra_social") val obraSocial: String? = null,
    @SerialName("numero_afiliado") val numeroAfiliado: String? = null,
    @SerialName("obra_social_plan") val obraSocialPlan: String? = null,
)
