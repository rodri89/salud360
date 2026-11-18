package com.salud360.core.data.repos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La lista de historias clínicas que sale hacia turnos cuando el administrador tilda o destilda una casilla, y los
 * avisos que ve después. Todo puro: ni red, ni base.
 */
class HistoriasClinicasDelMedicoTest {

    @Test
    fun habilitarAgregaAlFinalSinTocarLoDemas() {
        assertEquals(listOf("pediatria"), nuevasHistoriasClinicas(emptyList(), "pediatria", habilitada = true))
        assertEquals(listOf("clinica", "pediatria"), nuevasHistoriasClinicas(listOf("clinica"), "pediatria", habilitada = true))
    }

    @Test
    fun habilitarUnaYaHabilitadaNoLaDuplica() {
        assertEquals(listOf("pediatria", "clinica"), nuevasHistoriasClinicas(listOf("pediatria", "clinica"), "pediatria", habilitada = true))
    }

    @Test
    fun deshabilitarQuitaSoloEsaYConservaElOrden() {
        assertEquals(listOf("pediatria", "gineco"), nuevasHistoriasClinicas(listOf("pediatria", "clinica", "gineco"), "clinica", habilitada = false))
    }

    @Test
    fun deshabilitarUnaQueNoEstabaNoCambiaNada() {
        assertEquals(listOf("pediatria"), nuevasHistoriasClinicas(listOf("pediatria"), "clinica", habilitada = false))
        assertEquals(emptyList(), nuevasHistoriasClinicas(emptyList(), "clinica", habilitada = false))
    }

    @Test
    fun quitarLaUltimaDejaLaListaVacia() {
        // La lista vacía es un pedido válido: «ninguna historia clínica habilitada».
        assertEquals(emptyList(), nuevasHistoriasClinicas(listOf("pediatria"), "pediatria", habilitada = false))
    }

    @Test
    fun noModificaLaListaOriginal() {
        val actuales = listOf("pediatria")
        nuevasHistoriasClinicas(actuales, "clinica", habilitada = true)
        assertEquals(listOf("pediatria"), actuales)
    }

    @Test
    fun elAvisoDeExitoEsCortoYClaro() {
        assertEquals("Cambios guardados", avisoDeHistoriasClinicasGuardadas())
    }

    @Test
    fun elAvisoDeErrorLlevaElMotivoRealDelServidor() {
        val aviso = avisoDeHistoriasClinicasNoGuardadas("Códigos desconocidos: xx. Códigos válidos: pediatria, clinica.")
        assertEquals("No se pudieron guardar las historias clínicas: Códigos desconocidos: xx. Códigos válidos: pediatria, clinica.", aviso)
    }

    @Test
    fun elAvisoDeErrorSinMotivoDiceSinConexion() {
        assertTrue(avisoDeHistoriasClinicasNoGuardadas(null).endsWith("sin conexión"))
        assertTrue(avisoDeHistoriasClinicasNoGuardadas("  ").endsWith("sin conexión"))
    }
}
