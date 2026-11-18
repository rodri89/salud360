package com.salud360.core.data.repos

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** El modal «¿Qué secciones querés ver?» al abrir una consulta por primera vez en una historia clínica. Todo puro. */
class PreguntaSeccionesTest {

    @Test
    fun sinConfigGuardadaEnConsultaAbiertaPregunta() {
        assertTrue(debePreguntarSecciones(configGuardada = false, soloLectura = false, haySeccionesParaElegir = true))
    }

    @Test
    fun conConfigGuardadaNoVuelveAPreguntar() {
        // También si lo guardado es «todas visibles» (valor vacío): lo que cuenta es que el médico ya eligió.
        assertFalse(debePreguntarSecciones(configGuardada = true, soloLectura = false, haySeccionesParaElegir = true))
    }

    @Test
    fun enConsultaCerradaNuncaPregunta() {
        assertFalse(debePreguntarSecciones(configGuardada = false, soloLectura = true, haySeccionesParaElegir = true))
        assertFalse(debePreguntarSecciones(configGuardada = true, soloLectura = true, haySeccionesParaElegir = true))
    }

    @Test
    fun sinSeccionesParaElegirNoPregunta() {
        assertFalse(debePreguntarSecciones(configGuardada = false, soloLectura = false, haySeccionesParaElegir = false))
    }

    @Test
    fun hayConfigGuardadaSiExisteLaClaveAunqueEsteVacia() {
        val clave = "secciones_ocultas_clinica"
        assertFalse(hayConfigDeSecciones(emptyMap(), clave))
        assertFalse(hayConfigDeSecciones(mapOf("otra_clave" to "x"), clave))
        assertTrue(hayConfigDeSecciones(mapOf(clave to ""), clave))
        assertTrue(hayConfigDeSecciones(mapOf(clave to "examen_fisico,estudios"), clave))
    }
}
