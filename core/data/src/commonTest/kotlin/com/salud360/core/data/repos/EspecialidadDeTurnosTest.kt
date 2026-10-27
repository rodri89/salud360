package com.salud360.core.data.repos

import com.salud360.core.data.network.tobb.TobbEspecialidad
import com.salud360.core.model.turnos.EspecialidadTurnos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Cómo se arma la especialidad que se guarda al traer el catálogo de turnos (`sincronizarAdministracion`).
 *
 * Turnos manda `id`, `nombre` y `color`, pero NO el código de la historia clínica: ese dato lo arma la app (al ingresar, por
 * `TurnosImportador`) o lo elige el administrador. Hubo un bug: el color caía en el lugar del código y la tabla de especialidades
 * quedaba con `codigo_hc = "#1e88e5"`. Este test fija que el código ya guardado se conserva y que el color nunca lo pisa.
 */
class EspecialidadDeTurnosTest {

    private fun previa(codigoHc: String?, color: String? = null) =
        EspecialidadTurnos("tobb-e3", "Nombre viejo", codigoHc = codigoHc, color = color)

    @Test
    fun el_color_va_en_color_y_nunca_en_el_codigo_de_la_hc() {
        val e = TobbEspecialidad(3, "Cardiología", "#1e88e5").toEspecialidadTurnos(previa = null)

        assertEquals("#1e88e5", e.color)
        assertNull(e.codigoHc, "turnos no manda código de HC: sin dato previo queda vacío")
    }

    @Test
    fun conserva_el_codigo_de_hc_ya_guardado() {
        val e = TobbEspecialidad(3, "Pediatría", "#43a047").toEspecialidadTurnos(previa(codigoHc = "pediatria"))

        assertEquals("pediatria", e.codigoHc)
        assertEquals("#43a047", e.color)
    }

    @Test
    fun un_codigo_guardado_como_hepatologia_se_conserva_como_la_hc_general() {
        val e = TobbEspecialidad(3, "Hepatología", null).toEspecialidadTurnos(previa(codigoHc = "hepatologia"))

        assertEquals("clinica", e.codigoHc)
    }

    @Test
    fun el_color_que_turnos_no_manda_no_borra_el_guardado() {
        val e = TobbEspecialidad(3, "Cardiología", null).toEspecialidadTurnos(previa(codigoHc = "cardiologia", color = "#e53935"))

        assertEquals("#e53935", e.color)
        assertEquals("cardiologia", e.codigoHc)
    }

    @Test
    fun el_color_nuevo_de_turnos_reemplaza_al_guardado() {
        val e = TobbEspecialidad(3, "Cardiología", "#000000").toEspecialidadTurnos(previa(codigoHc = null, color = "#e53935"))

        assertEquals("#000000", e.color)
        assertNull(e.codigoHc)
    }

    @Test
    fun id_nombre_y_activo_salen_de_turnos() {
        val e = TobbEspecialidad(3, "Endocrinología", "#fb8c00").toEspecialidadTurnos(previa(codigoHc = "endocrinologia"))

        assertEquals("tobb-e3", e.id)
        assertEquals("Endocrinología", e.nombre)
        assertEquals(true, e.activo)
    }
}
