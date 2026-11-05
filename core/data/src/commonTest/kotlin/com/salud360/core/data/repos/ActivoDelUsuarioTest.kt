package com.salud360.core.data.repos

import com.salud360.core.model.auth.Rol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El botón «Desactivar/Activar» de la pestaña Usuarios: en turnos solo el médico tiene «activo» (el de la secretaria y
 * el del administrador es siempre 1), así que solo ahí se puede guardar. Todo puro: ni red, ni base.
 */
class ActivoDelUsuarioTest {

    @Test
    fun soloElMedicoPuedeCambiarSuActivo() {
        assertTrue(puedeCambiarActivoDelUsuario(Rol.MEDICO))
        assertFalse(puedeCambiarActivoDelUsuario(Rol.SECRETARIA))
        assertFalse(puedeCambiarActivoDelUsuario(Rol.ADMIN))
    }

    @Test
    fun elAvisoDeExitoDiceQueQuedoElMedico() {
        assertEquals("Médico desactivado", avisoDeMedicoActivoGuardado(activo = false))
        assertEquals("Médico activado", avisoDeMedicoActivoGuardado(activo = true))
    }

    @Test
    fun elAvisoDeErrorLlevaElMotivoReal() {
        assertEquals(
            "No se pudo guardar el cambio: Solo el administrador puede activar o desactivar médicos",
            avisoDeMedicoActivoNoGuardado("Solo el administrador puede activar o desactivar médicos"),
        )
    }

    @Test
    fun sinMotivoElAvisoDeErrorDiceSinConexion() {
        assertEquals("No se pudo guardar el cambio: sin conexión", avisoDeMedicoActivoNoGuardado(null))
        assertEquals("No se pudo guardar el cambio: sin conexión", avisoDeMedicoActivoNoGuardado("  "))
    }

    @Test
    fun elAvisoDeMedicoNoEncontradoEsClaro() {
        assertEquals(
            "No se pudo guardar el cambio: no se encontró el médico de este usuario en el dispositivo",
            avisoDeMedicoActivoNoGuardado(MOTIVO_SIN_MEDICO_DEL_USUARIO),
        )
    }
}
