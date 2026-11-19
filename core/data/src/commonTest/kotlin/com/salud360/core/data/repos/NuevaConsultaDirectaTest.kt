package com.salud360.core.data.repos

import com.salud360.core.data.repos.AlLlegarConNuevaConsulta.CREAR_DEL_UNICO_TIPO
import com.salud360.core.data.repos.AlLlegarConNuevaConsulta.ELEGIR_TIPO
import com.salud360.core.data.repos.AlLlegarConNuevaConsulta.ESPERAR
import com.salud360.core.data.repos.AlLlegarConNuevaConsulta.NADA
import kotlin.test.Test
import kotlin.test.assertEquals

/** El botón «Nueva consulta» de la ficha: qué hace la historia clínica al llegar. Todo puro. */
class NuevaConsultaDirectaTest {

    @Test
    fun sinConsultasCargadasEspera() {
        // Mientras la base no respondió, ni «hay abierta» ni «cuántos tipos» se pueden tomar como datos.
        assertEquals(ESPERAR, alLlegarConNuevaConsulta(consultasCargadas = false, hayAbierta = false, tiposDeConsulta = 6))
        assertEquals(ESPERAR, alLlegarConNuevaConsulta(consultasCargadas = false, hayAbierta = true, tiposDeConsulta = 1))
    }

    @Test
    fun conConsultaAbiertaNoCreaOtra() {
        assertEquals(NADA, alLlegarConNuevaConsulta(consultasCargadas = true, hayAbierta = true, tiposDeConsulta = 1))
        assertEquals(NADA, alLlegarConNuevaConsulta(consultasCargadas = true, hayAbierta = true, tiposDeConsulta = 6))
    }

    @Test
    fun conUnSoloTipoCreaDirecto() {
        assertEquals(CREAR_DEL_UNICO_TIPO, alLlegarConNuevaConsulta(consultasCargadas = true, hayAbierta = false, tiposDeConsulta = 1))
    }

    @Test
    fun conVariosTiposMuestraLaEleccion() {
        assertEquals(ELEGIR_TIPO, alLlegarConNuevaConsulta(consultasCargadas = true, hayAbierta = false, tiposDeConsulta = 2))
        assertEquals(ELEGIR_TIPO, alLlegarConNuevaConsulta(consultasCargadas = true, hayAbierta = false, tiposDeConsulta = 6))
    }

    @Test
    fun sinTiposNoHaceNada() {
        assertEquals(NADA, alLlegarConNuevaConsulta(consultasCargadas = true, hayAbierta = false, tiposDeConsulta = 0))
    }
}
