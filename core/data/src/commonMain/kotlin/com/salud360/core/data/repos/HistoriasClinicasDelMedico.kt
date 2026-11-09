package com.salud360.core.data.repos

import com.salud360.core.model.auth.Rol

/**
 * Historias clínicas habilitadas de un médico, desde el panel del administrador. Reglas puras, sin Compose ni red: el
 * dueño del dato es turnos, que recibe la lista exacta (`PUT medicos/{id}/historias-clinicas`).
 *
 * También, quién ve y quién abre una historia clínica: solo el médico, y solo las que el administrador le habilitó. La
 * secretaria únicamente ve turnos (decisión del dueño del producto, 2026-11-09; reemplaza el RF-4 de la spec 003).
 */

/**
 * Las historias clínicas que [rol] puede ver, a partir de las [habilitadas] al médico: el médico, las que la app trae
 * ([existentes]); la secretaria y el administrador, ninguna (no abren consultas). Es lo que decide si la ficha del
 * paciente muestra la tarjeta «Historia clínica» y si el menú ofrece sus secciones.
 */
fun historiasClinicasVisibles(rol: Rol, habilitadas: List<String>, existentes: (String) -> Boolean): List<String> =
    if (rol == Rol.MEDICO) habilitadas.filter(existentes) else emptyList()

/** ¿Puede [rol] abrir la historia clínica (o una consulta) de [especialidad]? Solo el médico que la tiene habilitada. */
fun puedeAbrirHistoriaClinica(rol: Rol, habilitadas: List<String>, especialidad: String): Boolean =
    rol == Rol.MEDICO && especialidad in habilitadas

/**
 * Las historias clínicas que quedan guardadas de un médico después de hablar con turnos. [informadas] es lo que turnos
 * mandó: una lista (aunque sea vacía) ES la lista, así una baja del administrador se propaga; `null` es que el servidor
 * no mandó el campo (versión vieja), y entonces se conservan las [previas]. La lista informada se normaliza (sin
 * espacios, en minúsculas y sin repetidos, en el mismo orden).
 */
fun historiasClinicasDeTurnos(informadas: List<String>?, previas: List<String>): List<String> =
    informadas?.map { it.trim().lowercase() }?.distinct() ?: previas

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
