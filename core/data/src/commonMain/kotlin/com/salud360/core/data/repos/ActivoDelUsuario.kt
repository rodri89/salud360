package com.salud360.core.data.repos

import com.salud360.core.model.auth.Rol

/**
 * El botón «Desactivar/Activar» de la pestaña Usuarios del panel. Reglas puras, sin Compose ni red: en turnos la tabla
 * de usuarios no tiene «activo», y `GET usuarios` lo informa con el del médico (siempre 1 para secretarias y
 * administradores). Por eso solo se puede guardar para un médico: `PUT medicos/{id}/activo`.
 */

/** Si el botón se dibuja para un usuario de ese [rol]: solo el médico tiene «activo» en turnos. */
fun puedeCambiarActivoDelUsuario(rol: Rol): Boolean = rol == Rol.MEDICO

/** Motivo cuando el usuario es médico pero su ficha de médico no está en el dispositivo (no hay a quién pedirle el cambio). */
const val MOTIVO_SIN_MEDICO_DEL_USUARIO = "no se encontró el médico de este usuario en el dispositivo"

/** Aviso al administrador cuando turnos confirmó el cambio y dejó al médico en [activo]. */
fun avisoDeMedicoActivoGuardado(activo: Boolean): String = if (activo) "Médico activado" else "Médico desactivado"

/** Aviso al administrador cuando el cambio no se guardó: el motivo es el real (el de turnos o el de la red). */
fun avisoDeMedicoActivoNoGuardado(motivo: String?): String =
    "No se pudo guardar el cambio: ${motivo?.takeIf { it.isNotBlank() } ?: "sin conexión"}"
