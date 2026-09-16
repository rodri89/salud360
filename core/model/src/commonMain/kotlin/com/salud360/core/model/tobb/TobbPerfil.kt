package com.salud360.core.model.tobb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Estructuras que devuelve la API `/api/salud360/...` de turnosonlinebb (ver API_SALUD360.md en ese repo) ----
// Las usan tanto el servidor (server/TurnosOnlineApi.kt) como el cliente (core/data/network/TurnosOnlineClient.kt),
// que le hablan directo a turnosonlinebb, y el importador que replica el perfil en la base local.

@Serializable
data class TobbPerfil(
    val usuario: TobbUsuario,
    val rol: String,
    val medico: TobbMedico? = null,
    val secretaria: TobbSecretaria? = null,
)

@Serializable
data class TobbUsuario(val id: Long, val nombre: String = "", val email: String, val tipo: Int, val perfil: Int = 0)

@Serializable
data class TobbConsultorio(val id: Long, val nombre: String = "", val direccion: String? = "", val telefono: String? = "")

@Serializable
data class TobbCupoPrimerControl(val id: Long = 0, val dia: Int, val consultorio: Long = 0, val cantidad: Int)

@Serializable
data class TobbMedico(
    val id: Long,
    val nombre: String = "",
    val apellido: String = "",
    val mail: String? = "",
    val telefono: String? = "",
    val sexo: String? = "",
    val foto: String? = null,
    @SerialName("especialidad_id") val especialidadId: Long? = null,
    val especialidad: String? = null,
    @SerialName("consultorio_id") val consultorioId: Long? = null,
    val activo: Int = 1,
    val consultorio: TobbConsultorio? = null,
    val modulos: List<Int> = emptyList(),
    @SerialName("ventana_dias") val ventanaDias: Int = 180,
    @SerialName("cupo_primer_control") val cupoPrimerControl: List<TobbCupoPrimerControl> = emptyList(),
)

@Serializable
data class TobbSecretaria(
    val id: Long,
    val nombre: String = "",
    val apellido: String = "",
    val consultorios: List<TobbConsultorio> = emptyList(),
    val medicos: List<TobbMedico> = emptyList(),
)
