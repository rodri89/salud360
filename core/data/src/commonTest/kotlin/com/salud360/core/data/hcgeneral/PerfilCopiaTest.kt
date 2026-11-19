package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.AsignacionPerfil
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoConsultaHc.TELEMEDICINA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Duplicar un perfil (RF-12) y cuándo se puede borrar (RF-11). Perfiles y médicos inventados. */
class PerfilCopiaTest {
    private val perfil = PerfilHc(
        id = 7,
        nombre = "Clínica general",
        pestanias = listOf(
            PestaniaPerfil(
                "Consulta",
                listOf(
                    SeccionPerfil("motivo_consulta", "Motivo", setOf(CONSULTA)),
                    SeccionPerfil("examen_fisico", "Examen", setOf(CONSULTA, TELEMEDICINA)),
                ),
            ),
            PestaniaPerfil("Estudios", listOf(SeccionPerfil("laboratorio_general", "Laboratorio", setOf(TELEMEDICINA)))),
        ),
    )
    private val catalogoIds = listOf("motivo_consulta", "examen_fisico", "laboratorio_general")

    @Test
    fun copia_simple() {
        assertEquals("Copia de Clínica general", nombreCopia("Clínica general", listOf("Clínica general", "Pediatría")))
    }

    @Test
    fun copia_con_2_y_3_ocupados_sale_con_4() {
        val existentes = listOf("Clínica general", "Copia de Clínica general", "Copia de Clínica general (2)", "Copia de Clínica general (3)")
        assertEquals("Copia de Clínica general (4)", nombreCopia("Clínica general", existentes))
    }

    @Test
    fun ocupados_se_comparan_como_validar_perfil() {
        // Sin tildes, otra capitalización y espacios de más: para `validarPerfil` es el mismo nombre.
        val existentes = listOf("  copia de  CLINICA general ", "COPIA DE clinica general (2)")
        assertEquals("Copia de Clínica general (3)", nombreCopia("Clínica general", existentes))
    }

    @Test
    fun nombre_de_60_se_recorta_para_no_pasar_60_con_el_sufijo() {
        val largo = "á".repeat(60)
        val copia = nombreCopia(largo, listOf(largo))
        assertEquals("Copia de " + "á".repeat(51), copia)

        val segunda = nombreCopia(largo, listOf(largo, copia))
        assertEquals("Copia de " + "á".repeat(47) + " (2)", segunda)
    }

    @Test
    fun recorte_cuenta_caracteres_y_no_deja_espacios_al_final() {
        // "𝄞" son dos unidades UTF-16 pero un carácter (como `mb_strlen`); el corte cae justo después de un espacio.
        val nombre = "𝄞".repeat(50) + " " + "b".repeat(9)
        assertEquals("Copia de " + "𝄞".repeat(50), nombreCopia(nombre, emptyList()))
    }

    @Test
    fun duplicar_copia_todo_menos_id_y_nombre() {
        val copia = duplicarPerfil(perfil, listOf("Clínica general", "Copia de Clínica general"))
        assertNull(copia.id)
        assertEquals("Copia de Clínica general (2)", copia.nombre)
        assertEquals(perfil.pestanias, copia.pestanias)
    }

    @Test
    fun la_copia_pasa_validar_perfil() {
        val largo = perfil.copy(nombre = "n".repeat(60))
        val existentes = listOf(largo.nombre, "Copia de " + "n".repeat(51))
        val copia = duplicarPerfil(largo, existentes)
        assertEquals(emptyList(), validarPerfil(copia, existentes, catalogoIds))
    }

    @Test
    fun borrar_sin_medicos_asignados() {
        val asignaciones = listOf(
            AsignacionPerfil(medicoIdTobb = 101, nombre = "Médico Uno", vinculado = true, activo = true, perfilId = 8),
            AsignacionPerfil(medicoIdTobb = 102, vinculado = false),
        )
        assertTrue(puedeBorrar(perfil, asignaciones))
        assertTrue(puedeBorrar(perfil, emptyList()))
    }

    @Test
    fun no_se_borra_con_medicos_asignados_aunque_esten_inactivos() {
        // El servidor cuenta toda asignación (409 `perfil_con_medicos`), activa o no.
        val asignaciones = listOf(
            AsignacionPerfil(medicoIdTobb = 101, nombre = "Médico Uno", vinculado = true, activo = true, perfilId = 8),
            AsignacionPerfil(medicoIdTobb = 103, nombre = "Médico Tres", vinculado = true, activo = false, perfilId = 7),
        )
        assertFalse(puedeBorrar(perfil, asignaciones))
    }
}
