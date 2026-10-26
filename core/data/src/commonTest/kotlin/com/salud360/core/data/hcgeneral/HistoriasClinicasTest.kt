package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CodigosHc
import kotlin.test.Test
import kotlin.test.assertEquals

/** La hepatología no es una HC aparte: turnos la sigue informando y la app la cuenta como la HC general (RF-29; tanda D). */
class HistoriasClinicasTest {
    @Test
    fun una_lista_vacia_queda_vacia() {
        assertEquals(emptyList(), normalizarHistoriasClinicas(emptyList()))
    }

    @Test
    fun hepatologia_pasa_a_la_hc_general() {
        assertEquals(listOf(CodigosHc.GENERAL), normalizarHistoriasClinicas(listOf("hepatologia")))
    }

    @Test
    fun hepatologia_y_clinica_juntas_quedan_una_sola_vez() {
        assertEquals(listOf("clinica"), normalizarHistoriasClinicas(listOf("clinica", "hepatologia")))
        assertEquals(listOf("clinica"), normalizarHistoriasClinicas(listOf("hepatologia", "clinica")))
    }

    @Test
    fun hepatologia_ocupa_el_lugar_de_su_primera_aparicion() {
        assertEquals(
            listOf("pediatria", "clinica", "hematologia"),
            normalizarHistoriasClinicas(listOf("pediatria", "hepatologia", "hematologia")),
        )
        assertEquals(
            listOf("pediatria", "clinica", "hematologia"),
            normalizarHistoriasClinicas(listOf("pediatria", "clinica", "hematologia", "hepatologia")),
        )
    }

    @Test
    fun recorta_espacios_y_pasa_a_minusculas() {
        assertEquals(listOf("clinica"), normalizarHistoriasClinicas(listOf(" Hepatologia ", "HEPATOLOGIA")))
        assertEquals(listOf("pediatria"), normalizarHistoriasClinicas(listOf(" Pediatria", "PEDIATRIA ")))
    }

    @Test
    fun los_vacios_se_descartan() {
        assertEquals(emptyList(), normalizarHistoriasClinicas(listOf("", "  ")))
        assertEquals(listOf("pediatria"), normalizarHistoriasClinicas(listOf("", "pediatria", "  ")))
    }

    @Test
    fun los_codigos_que_no_son_hepatologia_quedan_intactos_y_en_su_orden() {
        val codigos = listOf("pediatria", "gineco", "cardiologia", "desarrollo_infantil", "hematologia")
        assertEquals(codigos, normalizarHistoriasClinicas(codigos))
    }

    @Test
    fun es_idempotente() {
        val entradas = listOf(
            emptyList(),
            listOf("hepatologia"),
            listOf("hepatologia", "clinica", "pediatria"),
            listOf(" Hepatologia ", "", "PEDIATRIA", "pediatria"),
        )
        for (x in entradas) {
            val una = normalizarHistoriasClinicas(x)
            assertEquals(una, normalizarHistoriasClinicas(una), "entrada $x")
        }
    }

    @Test
    fun un_codigo_suelto_se_normaliza_igual() {
        assertEquals(null, normalizarCodigoHc(null))
        assertEquals(null, normalizarCodigoHc(""))
        assertEquals(null, normalizarCodigoHc("   "))
        assertEquals("clinica", normalizarCodigoHc("hepatologia"))
        assertEquals("clinica", normalizarCodigoHc(" Hepatologia "))
        assertEquals("clinica", normalizarCodigoHc("clinica"))
        assertEquals("pediatria", normalizarCodigoHc("pediatria"))
    }

    @Test
    fun el_nombre_de_la_especialidad_de_turnos_sugiere_la_hc() {
        assertEquals("clinica", codigoHcPorNombre("Hepatología"))
        assertEquals("clinica", codigoHcPorNombre("Hepatólogo"))
        assertEquals("clinica", codigoHcPorNombre("Clínica médica"))
        assertEquals("pediatria", codigoHcPorNombre("Pediatría"))
        assertEquals("gineco", codigoHcPorNombre("Ginecología y Obstetricia"))
        assertEquals("cardiologia", codigoHcPorNombre("Cardiología"))
        assertEquals("endocrinologia", codigoHcPorNombre("Endocrinología"))
        assertEquals("hematologia", codigoHcPorNombre("Hematología"))
        assertEquals("desarrollo_infantil", codigoHcPorNombre("Desarrollo infantil"))
        assertEquals(null, codigoHcPorNombre("Otra"))
        assertEquals(null, codigoHcPorNombre(null))
    }
}
