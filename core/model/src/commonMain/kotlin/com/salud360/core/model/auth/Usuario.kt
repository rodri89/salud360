package com.salud360.core.model.auth

import com.salud360.core.model.Id
import kotlinx.serialization.Serializable

/** Roles del sistema. Los pacientes no tienen usuario: la app es para el equipo de salud. */
@Serializable
enum class Rol { ADMIN, MEDICO, SECRETARIA }

@Serializable
data class Usuario(
    val id: Id,
    val email: String,
    val nombre: String,
    val apellido: String,
    val rol: Rol,
    val foto: String? = null,
    val activo: Boolean = true,
) {
    val nombreCompleto: String get() = "$apellido, $nombre"
}

/**
 * Sesión resuelta al iniciar sesión. A partir del email se determina el rol y,
 * si es médico, sus especialidades (puede tener más de una historia clínica),
 * si tiene agenda de turnos y si tiene historia clínica.
 */
@Serializable
data class Sesion(
    val usuario: Usuario,
    val token: String,
    val medico: PerfilMedico? = null,
    val secretaria: PerfilSecretaria? = null,
)

@Serializable
data class PerfilMedico(
    val medicoId: Id,
    /** Códigos de especialidad con historia clínica habilitada (ej. "pediatria", "gineco"). */
    val especialidades: List<String>,
    /** El médico tiene agenda de turnos en el sistema. */
    val tieneTurnos: Boolean,
    /** Consultorio principal para la agenda. */
    val consultorioId: Id? = null,
    val licenciaVence: String? = null,
    val licenciaAviso: String? = null,
) {
    val tieneHistoriaClinica: Boolean get() = especialidades.isNotEmpty()
}

@Serializable
data class PerfilSecretaria(
    val secretariaId: Id,
    /** Consultorios que administra (turnos). */
    val consultorioIds: List<Id>,
    /** Médicos a los que asiste (turnos y/o pacientes de la HC). */
    val medicoIds: List<Id>,
)

@Serializable
data class Credenciales(val email: String, val password: String)
