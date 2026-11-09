package com.salud360.core.data.repos

import com.salud360.core.model.pacientes.Sexo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reglas puras para completar el sexo de la ficha local con el que informa la historia clínica de pediatría. */
class SexoDesdeHcTest {

    // ---- sexoDesdeHc: tolerante, con trim ----

    @Test
    fun masculino_en_sus_variantes() {
        for (texto in listOf("M", "m", "Masculino", "masculino", "MASCULINO", "varón", "Varón", "varon", "VARON", " M ", "\tmasculino\n")) {
            assertEquals(Sexo.M, sexoDesdeHc(texto), "«$texto» debería ser M")
        }
    }

    @Test
    fun femenino_en_sus_variantes() {
        for (texto in listOf("F", "f", "Femenino", "femenino", "FEMENINO", " F ", "  femenino  ")) {
            assertEquals(Sexo.F, sexoDesdeHc(texto), "«$texto» debería ser F")
        }
    }

    @Test
    fun otro_en_sus_variantes() {
        for (texto in listOf("X", "x", "Otro", "otro", "OTRO", " x ")) {
            assertEquals(Sexo.X, sexoDesdeHc(texto), "«$texto» debería ser X")
        }
    }

    @Test
    fun sin_dato_o_valor_raro_es_null() {
        for (texto in listOf(null, "", " ", "   ", "\t\n", "?", "Z", "0", "1", "MF", "Masc", "femeninoo", "null", "desconocido", "M F")) {
            assertNull(sexoDesdeHc(texto), "«$texto» no debería dar sexo")
        }
    }

    // ---- completarSexo: nunca pisa uno ya definido ----

    @Test
    fun si_ya_hay_sexo_local_no_se_pisa() {
        for (actual in Sexo.entries) {
            for (hc in listOf(null) + Sexo.entries) {
                assertEquals(actual, completarSexo(actual, hc), "local $actual, HC $hc")
            }
        }
    }

    @Test
    fun si_no_hay_sexo_local_se_completa_con_el_de_la_hc() {
        for (hc in Sexo.entries) {
            assertEquals(hc, completarSexo(null, hc))
        }
    }

    @Test
    fun sin_sexo_en_ningun_lado_sigue_null() {
        assertNull(completarSexo(null, null))
    }

    @Test
    fun completar_el_texto_crudo_de_la_hc() {
        assertEquals(Sexo.F, completarSexo(null, sexoDesdeHc("Femenino")))
        assertEquals(Sexo.M, completarSexo(Sexo.M, sexoDesdeHc("F")))
        assertNull(completarSexo(null, sexoDesdeHc("raro")))
    }
}
