package com.salud360.core.model.auth

import com.salud360.core.model.Id
import kotlinx.serialization.Serializable

@Serializable
data class Medico(
    val id: Id,
    val usuarioId: Id,
    val nombre: String,
    val apellido: String,
    val telefono: String = "",
    val mail: String = "",
    val sexo: String = "",
    val foto: String? = null,
    /** Consultorio principal (agenda). */
    val consultorioId: Id? = null,
    /** Especialidad principal para turnos. */
    val especialidadId: Id? = null,
    /** Códigos de las historias clínicas habilitadas. */
    val especialidadesHc: List<String> = emptyList(),
    /**
     * Tiene agenda de turnos: es una decisión del administrador que informa turnosonlinebb (`tiene_agenda`), no
     * «tiene horarios cargados» ni «tiene consultorio». Si el servidor no la informa se usa `tiene_horarios` como
     * respaldo. Quién ve qué a partir de este dato está en `AgendaDelMedico` (`core:data`). El dueño es turnos:
     * se escribe con `PUT medicos/{id}/agenda` y la copia no queda `dirty`.
     */
    val tieneTurnos: Boolean = false,
    /**
     * Se ofrece al paciente para pedir turno en la web (`visible_en_turnos` de turnos). Lo informa turnosonlinebb
     * junto con [tieneTurnos] y se escribe por el mismo `PUT`; sin agenda no puede estar prendido. Si el servidor
     * no lo informa se conserva el valor previo del dispositivo.
     */
    val visibleEnTurnos: Boolean = true,
    val activo: Boolean = true,
) {
    val nombreCompleto: String get() = "$apellido, $nombre"
}

@Serializable
data class Secretaria(
    val id: Id,
    val usuarioId: Id,
    val nombre: String,
    val apellido: String,
    val consultorioIds: List<Id> = emptyList(),
    val medicoIds: List<Id> = emptyList(),
    val activo: Boolean = true,
)

/**
 * La licencia del médico que entró está por vencer. Lo informa la historia clínica al validar la
 * sesión, porque la fecha vive en su base; la app solo la muestra.
 */
data class AvisoLicencia(val especialidad: String, val vence: String)

@Serializable
data class Licencia(
    val medicoId: Id,
    val fechaExpiracion: String,
    val fechaAviso: String,
    val importe: Double = 0.0,
    val activo: Boolean = true,
)
