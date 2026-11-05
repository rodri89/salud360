package com.salud360.core.data.repos

/**
 * Historias clínicas habilitadas de un médico, desde el panel del administrador. Reglas puras, sin Compose ni red: el
 * dueño del dato es turnos, que recibe la lista exacta (`PUT medicos/{id}/historias-clinicas`).
 */

/**
 * La lista que se manda a turnos cuando el administrador tilda ([habilitada] = `true`) o destilda una casilla: agrega
 * [codigo] al final sin duplicarlo, o lo quita, y conserva el orden del resto. Si no hay nada que cambiar devuelve lo mismo.
 */
fun nuevasHistoriasClinicas(actuales: List<String>, codigo: String, habilitada: Boolean): List<String> =
    if (habilitada) (actuales + codigo).distinct() else actuales - codigo

/** Aviso al administrador cuando turnos confirmó el cambio. */
fun avisoDeHistoriasClinicasGuardadas(): String = "Cambios guardados"

/** Aviso al administrador cuando el cambio no se guardó: el motivo es el real (el de turnos o el de la red). */
fun avisoDeHistoriasClinicasNoGuardadas(motivo: String?): String =
    "No se pudieron guardar las historias clínicas: ${motivo?.takeIf { it.isNotBlank() } ?: "sin conexión"}"
