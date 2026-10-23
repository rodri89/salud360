package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion

/**
 * El médico dueño de la lista de enfermedades es el de la **sesión** (`PerfilMedico.medicoId`, el mismo que usan
 * `AuthRepository` y `ConfigHcRepository`), no el que recibe la pantalla de la consulta: para la secretaria ese es el
 * médico que eligió, y para el administrador, su propio usuario. Solo un médico tiene lista; el administrador y la
 * secretaria dan `null` aunque tengan un médico cargado, y la API les contestaría `403 sin_permiso` (RF-4).
 */
fun medicoParaEnfermedades(s: Sesion): Id? = if (s.usuario.rol == Rol.MEDICO) s.medico?.medicoId else null

/**
 * RF-35: la consulta se edita si está abierta y es del médico de la sesión. Cerrada, de otro médico o sin médico propio
 * (secretaria, administrador), la sección es de solo lectura.
 */
fun consultaEditable(soloLectura: Boolean, medicoDeLaConsulta: Id, medicoPropio: Id?): Boolean =
    !soloLectura && medicoPropio != null && medicoDeLaConsulta == medicoPropio

/** RF-4: "Enfermedades" solo se muestra con médico propio; las demás secciones, siempre. */
fun visibleParaSesion(idSeccion: String, medicoPropio: Id?): Boolean =
    idSeccion != SECCION_ENFERMEDADES || medicoPropio != null
