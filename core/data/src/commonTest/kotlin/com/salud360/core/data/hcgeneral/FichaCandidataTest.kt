package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.HcPacienteCandidato
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Línea de una ficha candidata (spec 003, RF-9, decisión P9): nombre y apellido, documento y fecha de nacimiento
 * (`dd/MM/aaaa`, o nada si la web no la tiene). No se muestra la cantidad de consultas. Datos inventados.
 */
class FichaCandidataTest {
    private fun candidata(
        nombre: String = "ANA",
        apellido: String = "PRUEBA",
        dni: String = "99000002",
        fecha: String? = "1990-01-02",
        consultas: Int = 4,
    ) = HcPacienteCandidato(7, nombre, apellido, dni, fecha, consultas)

    @Test
    fun nombre_y_apellido_juntos_y_el_documento_tal_cual() {
        val l = lineaDeCandidata(candidata())
        assertEquals("ANA PRUEBA", l.nombre)
        assertEquals("99000002", l.documento)
    }

    @Test
    fun la_fecha_pasa_de_aaaa_mm_dd_a_dd_mm_aaaa() {
        assertEquals("02/01/1990", lineaDeCandidata(candidata(fecha = "1990-01-02")).nacimiento)
        assertEquals("31/12/2015", lineaDeCandidata(candidata(fecha = "2015-12-31")).nacimiento)
        assertEquals("29/02/2020", lineaDeCandidata(candidata(fecha = "2020-02-29")).nacimiento)
    }

    @Test
    fun sin_fecha_no_hay_linea_de_nacimiento() {
        assertNull(lineaDeCandidata(candidata(fecha = null)).nacimiento)
        assertNull(lineaDeCandidata(candidata(fecha = "")).nacimiento)
        assertNull(lineaDeCandidata(candidata(fecha = "   ")).nacimiento)
    }

    @Test
    fun una_fecha_ilegible_tampoco_se_muestra_ni_se_inventa() {
        for (ilegible in listOf("02/01/1990", "1990-1-2", "1990-13-01", "1990-02-30", "2021-02-29", "ayer", "1990-01-02T00:00:00")) {
            assertNull(lineaDeCandidata(candidata(fecha = ilegible)).nacimiento, ilegible)
        }
    }

    @Test
    fun nombre_o_apellido_vacio_no_deja_espacios_de_mas() {
        assertEquals("PRUEBA", lineaDeCandidata(candidata(nombre = "")).nombre)
        assertEquals("ANA", lineaDeCandidata(candidata(apellido = "")).nombre)
        assertEquals("ANA", lineaDeCandidata(candidata(apellido = "   ")).nombre)
        assertEquals("", lineaDeCandidata(candidata(nombre = "", apellido = "")).nombre)
    }

    @Test
    fun el_nombre_se_recorta_y_junta_los_espacios() {
        assertEquals("ANA MARÍA PRUEBA", lineaDeCandidata(candidata(nombre = " ANA  MARÍA ", apellido = "\tPRUEBA\n")).nombre)
    }

    @Test
    fun la_cantidad_de_consultas_no_cambia_la_linea() {
        assertEquals(lineaDeCandidata(candidata(consultas = 0)), lineaDeCandidata(candidata(consultas = 40)))
    }
}
