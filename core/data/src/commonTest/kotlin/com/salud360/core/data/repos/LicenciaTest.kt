package com.salud360.core.data.repos

import com.salud360.core.model.auth.PerfilMedico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regla que decide si un médico puede entrar: el administrador la controla desde la pestaña Licencias,
 * donde puede vencerla por fecha o darla de baja con el interruptor (falta de pago).
 */
class LicenciaTest {
    private val ayer = hoy().minus(DatePeriod(days = 1)).toString()
    private val manana = hoy().plus(DatePeriod(days = 1)).toString()

    private fun perfil(vence: String?, activa: Boolean = true) =
        PerfilMedico("m1", listOf("pediatria"), tieneTurnos = true, licenciaVence = vence, licenciaActiva = activa)

    @Test
    fun sin_licencia_cargada_no_bloquea() {
        assertFalse(perfil(vence = null).licenciaVencida())
    }

    @Test
    fun licencia_vigente_no_bloquea() {
        assertFalse(perfil(vence = manana).licenciaVencida())
    }

    @Test
    fun licencia_que_vence_hoy_no_bloquea() {
        assertFalse(perfil(vence = hoy().toString()).licenciaVencida())
    }

    @Test
    fun fecha_pasada_bloquea() {
        assertTrue(perfil(vence = ayer).licenciaVencida())
    }

    /** El caso que reportó el administrador: dio de baja la licencia sin tocar la fecha de vencimiento. */
    @Test
    fun dada_de_baja_bloquea_aunque_la_fecha_este_vigente() {
        assertTrue(perfil(vence = manana, activa = false).licenciaVencida())
        assertTrue(perfil(vence = null, activa = false).licenciaVencida())
    }

    @Test
    fun fecha_invalida_no_bloquea() {
        assertFalse(perfil(vence = "sin fecha").licenciaVencida())
    }

    @Test
    fun la_licencia_solo_aplica_al_medico() {
        val admin = Usuario("u1", "admin@salud360.com", "Ana", "Gómez", Rol.ADMIN)
        assertFalse(Sesion(admin, token = "t").licenciaBloqueada())
    }

    @Test
    fun sesion_de_medico_con_licencia_caida_queda_bloqueada() {
        val medico = Usuario("u2", "medico@salud360.com", "Juan", "Pérez", Rol.MEDICO)
        assertTrue(Sesion(medico, token = "t", medico = perfil(vence = ayer)).licenciaBloqueada())
        assertFalse(Sesion(medico, token = "t", medico = perfil(vence = manana)).licenciaBloqueada())
    }
}
