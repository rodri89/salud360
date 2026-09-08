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
    val tieneTurnos: Boolean = false,
    /** Se muestra al público en la web de turnos. */
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

@Serializable
data class Licencia(
    val medicoId: Id,
    val fechaExpiracion: String,
    val fechaAviso: String,
    val importe: Double = 0.0,
    val activo: Boolean = true,
)
