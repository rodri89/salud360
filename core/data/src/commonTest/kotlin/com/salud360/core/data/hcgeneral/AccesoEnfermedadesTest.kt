package com.salud360.core.data.hcgeneral

import com.salud360.core.model.auth.PerfilMedico
import com.salud360.core.model.auth.PerfilSecretaria
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quién puede ver y editar la sección "Enfermedades" según la sesión (spec 003, RF-4 y RF-35). El médico de la sección
 * es el de la **sesión**, no el que recibe la pantalla (para la secretaria es el médico que eligió). Datos inventados.
 */
class AccesoEnfermedadesTest {
    private fun usuario(rol: Rol) = Usuario("u-1", "prueba@prueba.test", "Prueba", "Salud360", rol)

    private val perfilMedico = PerfilMedico(medicoId = "m-propio", especialidades = listOf("clinica"), tieneTurnos = true)

    private fun sesionMedico() = Sesion(usuario(Rol.MEDICO), token = "t", medico = perfilMedico)

    private fun sesionSecretaria(medico: PerfilMedico? = null) = Sesion(
        usuario(Rol.SECRETARIA), token = "t", medico = medico,
        secretaria = PerfilSecretaria("s-1", listOf("c-1"), listOf("m-elegido")),
    )

    private fun sesionAdmin(medico: PerfilMedico? = null) = Sesion(usuario(Rol.ADMIN), token = "t", medico = medico)

    // ---- medicoParaEnfermedades (RF-4) ----

    @Test
    fun el_medico_usa_su_propio_medico_id() {
        assertEquals("m-propio", medicoParaEnfermedades(sesionMedico()))
    }

    @Test
    fun la_secretaria_no_tiene_medico_propio_con_o_sin_medico_cargado() {
        assertNull(medicoParaEnfermedades(sesionSecretaria()))
        assertNull(medicoParaEnfermedades(sesionSecretaria(medico = PerfilMedico("m-elegido", listOf("clinica"), true))))
    }

    @Test
    fun el_administrador_no_tiene_medico_propio_aunque_tenga_un_medico_cargado() {
        assertNull(medicoParaEnfermedades(sesionAdmin()))
        assertNull(medicoParaEnfermedades(sesionAdmin(medico = PerfilMedico("m-elegido", listOf("clinica"), true))))
    }

    @Test
    fun un_medico_sin_perfil_de_medico_no_tiene_medico_propio() {
        assertNull(medicoParaEnfermedades(Sesion(usuario(Rol.MEDICO), token = "t", medico = null)))
    }

    // ---- consultaEditable (RF-35) ----

    @Test
    fun abierta_y_propia_se_edita() {
        assertTrue(consultaEditable(soloLectura = false, medicoDeLaConsulta = "m-propio", medicoPropio = "m-propio"))
    }

    @Test
    fun cerrada_no_se_edita_aunque_sea_propia() {
        assertFalse(consultaEditable(soloLectura = true, medicoDeLaConsulta = "m-propio", medicoPropio = "m-propio"))
    }

    @Test
    fun la_de_otro_medico_no_se_edita() {
        assertFalse(consultaEditable(soloLectura = false, medicoDeLaConsulta = "m-otro", medicoPropio = "m-propio"))
    }

    @Test
    fun sin_medico_propio_no_se_edita_ni_con_la_consulta_abierta() {
        assertFalse(consultaEditable(soloLectura = false, medicoDeLaConsulta = "m-elegido", medicoPropio = null))
    }

    // ---- visibleParaSesion (RF-4) ----

    @Test
    fun enfermedades_solo_se_ve_con_medico_propio() {
        assertTrue(visibleParaSesion(SECCION_ENFERMEDADES, "m-propio"))
        assertFalse(visibleParaSesion(SECCION_ENFERMEDADES, null))
    }

    @Test
    fun las_demas_secciones_se_ven_siempre() {
        for (id in listOf("antecedentes_familiares", "motivo_consulta", "diagnostico", "child_pugh", "enfermedades_legada")) {
            assertTrue(visibleParaSesion(id, "m-propio"), id)
            assertTrue(visibleParaSesion(id, null), id)
        }
    }

    @Test
    fun el_id_de_la_seccion_es_el_del_catalogo() {
        assertEquals("enfermedades", SECCION_ENFERMEDADES)
    }
}
