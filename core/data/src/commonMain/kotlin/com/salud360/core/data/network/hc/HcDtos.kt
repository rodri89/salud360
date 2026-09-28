package com.salud360.core.data.network.hc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cuerpos y respuestas de la API de una historia clínica. Mismo estilo que los DTO de turnosonlinebb:
 * nombres en snake_case del lado del servidor y valores tolerantes, porque el Laravel de cada
 * especialidad puede no informar todavía algún campo.
 */

/** Cuerpo de `POST pacientes/resolver`: la ficha que la app ya tiene. */
@Serializable
data class HcPacienteRequest(
    val dni: String,
    val nombre: String,
    val apellido: String,
    @SerialName("paciente_id_tobb") val pacienteIdTobb: Long? = null,
    @SerialName("fecha_nacimiento") val fechaNacimiento: String? = null,
    val sexo: String? = null,
    val telefono: String? = null,
    val mail: String? = null,
    val domicilio: String? = null,
    val localidad: String? = null,
    @SerialName("obra_social") val obraSocial: String? = null,
    @SerialName("numero_afiliado") val numeroAfiliado: String? = null,
    @SerialName("obra_social_plan") val obraSocialPlan: String? = null,
    @SerialName("nombre_padre") val nombrePadre: String? = null,
    @SerialName("nombre_madre") val nombreMadre: String? = null,
    @SerialName("cantidad_hermanos") val cantidadHermanos: Int? = null,
    /** Solo al confirmar una ambigüedad: con cuál de los candidatos quedarse. */
    @SerialName("pediatria_paciente_id") val pacienteElegido: Long? = null,
)

@Serializable
data class HcPaciente(
    val id: Long,
    @SerialName("paciente_id_tobb") val pacienteIdTobb: Long = 0,
    val nombre: String = "",
    val apellido: String = "",
    val dni: String = "",
)

/** Candidato devuelto cuando hay varios pacientes con el mismo documento. */
@Serializable
data class HcPacienteCandidato(
    val id: Long,
    val nombre: String = "",
    val apellido: String = "",
    val dni: String = "",
    @SerialName("fecha_nacimiento") val fechaNacimiento: String? = null,
    @SerialName("cantidad_consultas") val cantidadConsultas: Int = 0,
)

@Serializable
data class HcConsulta(
    val id: Long,
    @SerialName("paciente_id") val pacienteId: Long,
    @SerialName("medico_id") val medicoId: Long,
    @SerialName("medico_nombre") val medicoNombre: String? = null,
    val tipo: String = "control",
    val fecha: String? = null,
    val estado: String = "ABIERTA",
    @SerialName("edad_mostrar") val edadMostrar: String = "",
    val propia: Boolean = true,
)

/** Cuerpo de `POST consultas/abrir`. */
@Serializable
data class HcAbrirConsulta(
    @SerialName("paciente_id") val pacienteId: Long,
    val tipo: String,
    val fecha: String? = null,
    @SerialName("edad_mostrar") val edadMostrar: String = "",
)

/** Cuerpo de `PUT consultas/{id}/secciones`. */
@Serializable
data class HcSeccionesRequest(val secciones: Map<String, Map<String, String>>)

/**
 * Una fila de una lista (screening, internación, interconsulta, examen complementario).
 *
 * `ref` es el id que tiene en el dispositivo y vuelve en la respuesta emparejado con el de pediatría;
 * `id` es el de pediatría, y va solo si esta fila ya se envió alguna vez. Sin ese par, el segundo
 * envío la duplicaría del otro lado.
 */
@Serializable
data class HcRegistro(
    val ref: String,
    val tipo: String,
    val id: String? = null,
    val fecha: String? = null,
    val campos: Map<String, String> = emptyMap(),
    val borrado: Boolean = false,
)

/** Cuerpo de `PUT consultas/{id}/registros`. */
@Serializable
data class HcRegistrosRequest(val registros: List<HcRegistro>)

/** Una fila tal como la devuelve pediatría al leer la consulta. */
@Serializable
data class HcRegistroRemoto(
    val id: String,
    val tipo: String,
    val fecha: String? = null,
    val campos: Map<String, String> = emptyMap(),
    /**
     * Consulta donde se pidió, que no es necesariamente la que se está leyendo: los exámenes
     * complementarios y las interconsultas llegan acumulativos, con toda la historia del paciente.
     * Vacío en las listas que no llevan consulta (los screenings).
     */
    @SerialName("consulta_id") val consultaId: String = "",
    /** Consulta donde se cargó el resultado, o "0" mientras no hay. */
    @SerialName("consulta_respuesta") val consultaRespuesta: String = "",
)

/**
 * Un adjunto tal como lo devuelve pediatría. Puede venir de la web o de otro dispositivo, así que el
 * archivo no está necesariamente en éste: se baja cuando hace falta mostrarlo.
 */
@Serializable
data class HcFotoRemota(
    val id: String,
    val tipo: String,
    val nombre: String = "",
    val url: String = "",
    /** Consulta donde se subió, que no es la del estudio si el resultado llegó después. */
    @SerialName("consulta_id") val consultaId: String = "",
    /** Fila de la lista de la que cuelga (el examen, la internación). */
    @SerialName("padre_id") val padreId: String = "",
)

/** Cuerpo de `PUT consultas/{id}/examen`. */
@Serializable
data class HcExamenRequest(@SerialName("examen_fisico") val examenFisico: Map<String, String>)

/** Cuerpo de `POST consultas/{id}/cerrar`. */
@Serializable
data class HcCerrarConsulta(val fecha: String? = null, @SerialName("edad_mostrar") val edadMostrar: String = "")

/**
 * Una licencia tal como la devuelve la historia clínica: el permiso del médico para entrar.
 *
 * Vive en la base de la especialidad, no en turnos, y se identifica con el **número de médico de
 * turnosonlinebb**, que es como la app conoce a los médicos.
 *
 * `vencida` y `por_vencer` los resuelve la API para que las dos puntas usen la misma regla y la misma
 * fecha de hoy; la app igual las recalcula al mostrar, porque la fecha se puede editar sin recargar.
 */
@Serializable
data class HcLicenciaRemota(
    @SerialName("medico_id_tobb") val medicoIdTobb: Long,
    val vence: String = "",
    @SerialName("aviso_desde") val avisoDesde: String = "",
    val importe: Double = 0.0,
    val activo: Int = 1,
    val vencida: Int = 0,
    @SerialName("por_vencer") val porVencer: Int = 0,
)

/** Cuerpo de `PUT licencias/{medicoIdTobb}`. */
@Serializable
data class HcLicenciaRequest(
    val vence: String,
    @SerialName("aviso_desde") val avisoDesde: String,
    val importe: Double,
    val activo: Int,
)
