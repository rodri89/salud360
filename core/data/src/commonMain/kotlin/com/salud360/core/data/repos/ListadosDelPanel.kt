package com.salud360.core.data.repos

import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Usuario

/**
 * Qué muestra el panel del administrador. Reglas puras, sin Compose ni red.
 */

/**
 * Los médicos que el panel muestra en «Médicos» y «Licencias»: quedan los que están activos ([Medico.activo], de turnos)
 * y cuyo usuario ([Medico.usuarioId]) también lo está. Si el médico no tiene usuario vinculado o ese usuario no está en
 * [usuarios], no hay dato del usuario y decide solo [Medico.activo]. Conserva el orden.
 */
fun medicosVisiblesEnElPanel(medicos: List<Medico>, usuarios: List<Usuario>): List<Medico> {
    val usuarioInactivo = usuarios.filter { !it.activo }.map { it.id }.toSet()
    return medicos.filter { it.activo && it.usuarioId !in usuarioInactivo }
}

/** Las licencias de [medicosVisibles]: la de un médico que el panel oculta tampoco se muestra. Conserva el orden. */
fun licenciasVisiblesEnElPanel(licencias: List<Licencia>, medicosVisibles: List<Medico>): List<Licencia> {
    val ids = medicosVisibles.map { it.id }.toSet()
    return licencias.filter { it.medicoId in ids }
}
