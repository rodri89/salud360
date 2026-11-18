package com.salud360.core.data.repos

import com.salud360.core.model.auth.Rol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Quién ve y quién abre una historia clínica: solo el médico, y solo las que el administrador le habilitó en turnos.
 * La secretaria únicamente ve turnos (decisión del dueño del producto, 2026-11-09; reemplaza el RF-4 de la spec 003).
 * Todo puro: ni red, ni base, ni pantallas.
 */
class HistoriasClinicasVisiblesTest {
    private val todasExisten: (String) -> Boolean = { true }

    // ---- historiasClinicasVisibles ----

    @Test
    fun laSecretariaNoVeNingunaHistoriaClinicaAunqueElMedicoLasTenga() {
        assertEquals(emptyList(), historiasClinicasVisibles(Rol.SECRETARIA, listOf("pediatria", "clinica"), todasExisten))
    }

    @Test
    fun elAdministradorNoAbreConsultasAsiQueNoVeNinguna() {
        assertEquals(emptyList(), historiasClinicasVisibles(Rol.ADMIN, listOf("pediatria"), todasExisten))
    }

    @Test
    fun elMedicoVeLasHabilitadasQueExistenEnLaApp() {
        val existen = setOf("pediatria", "clinica")
        assertEquals(
            listOf("pediatria", "clinica"),
            historiasClinicasVisibles(Rol.MEDICO, listOf("pediatria", "clinica", "no_compilada"), existen::contains),
            "una habilitada que la app no trae se descarta; el resto conserva el orden",
        )
    }

    @Test
    fun elMedicoSinHistoriasHabilitadasNoVeNinguna() {
        assertEquals(emptyList(), historiasClinicasVisibles(Rol.MEDICO, emptyList(), todasExisten))
    }

    @Test
    fun laTablaPorRolCoincideConPuedeAbrirHistoriaClinica() {
        val habilitadas = listOf("pediatria")
        Rol.entries.forEach { rol ->
            val visibles = historiasClinicasVisibles(rol, habilitadas, todasExisten)
            assertEquals(puedeAbrirHistoriaClinica(rol, habilitadas, "pediatria"), "pediatria" in visibles, "rol $rol")
        }
    }

    // ---- puedeAbrirHistoriaClinica ----

    @Test
    fun soloElMedicoConLaHistoriaHabilitadaPuedeAbrirla() {
        assertTrue(puedeAbrirHistoriaClinica(Rol.MEDICO, listOf("pediatria", "clinica"), "clinica"))
    }

    @Test
    fun elMedicoSinEsaHistoriaHabilitadaNoPuedeAbrirla() {
        assertFalse(puedeAbrirHistoriaClinica(Rol.MEDICO, listOf("pediatria"), "clinica"))
        assertFalse(puedeAbrirHistoriaClinica(Rol.MEDICO, emptyList(), "pediatria"))
    }

    @Test
    fun laSecretariaYElAdministradorNuncaPuedenAbrirla() {
        assertFalse(puedeAbrirHistoriaClinica(Rol.SECRETARIA, listOf("pediatria"), "pediatria"))
        assertFalse(puedeAbrirHistoriaClinica(Rol.ADMIN, listOf("pediatria"), "pediatria"))
    }

    @Test
    fun unaEspecialidadVaciaONuncaHabilitadaNoSeAbre() {
        assertFalse(puedeAbrirHistoriaClinica(Rol.MEDICO, listOf("pediatria"), ""))
    }

    // ---- historiasClinicasDeTurnos ----

    @Test
    fun siTurnosNoMandaElCampoSeConservanLasPrevias() {
        assertEquals(listOf("clinica"), historiasClinicasDeTurnos(informadas = null, previas = listOf("clinica")))
        assertEquals(emptyList(), historiasClinicasDeTurnos(informadas = null, previas = emptyList()))
    }

    @Test
    fun siTurnosMandaLaListaVaciaEsaEsLaListaYLaBajaDelAdminSePropaga() {
        assertEquals(emptyList(), historiasClinicasDeTurnos(informadas = emptyList(), previas = listOf("pediatria")))
    }

    @Test
    fun siTurnosMandaUnaListaReemplazaALasPrevias() {
        assertEquals(listOf("clinica"), historiasClinicasDeTurnos(informadas = listOf("clinica"), previas = listOf("pediatria")))
    }

    @Test
    fun laListaInformadaSeNormalizaSinDuplicadosYConservaElOrden() {
        assertEquals(
            listOf("pediatria", "clinica"),
            historiasClinicasDeTurnos(informadas = listOf(" Pediatria ", "clinica", "PEDIATRIA"), previas = emptyList()),
        )
    }
}
