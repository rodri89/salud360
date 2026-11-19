package com.salud360.core.data.hcgeneral

import com.salud360.core.model.TobbIds
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.especialidad.AsignacionPerfil
import kotlin.test.Test
import kotlin.test.assertEquals

/** Médicos de turnos cruzados con las asignaciones de hclinica por número de turnos (RF-17; C8). */
class AsignacionesMedicosTest {
    private fun medico(n: Long, apellido: String, vararg hc: String) =
        Medico(TobbIds.medico(n), TobbIds.usuario(n), "Prueba", apellido, especialidadesHc = hc.toList())

    @Test
    fun solo_los_que_tienen_la_hc_general_aunque_turnos_diga_hepatologia() {
        val medicos = listOf(
            medico(1, "Uno", "pediatria"),
            medico(2, "Dos", "hepatologia"),
            medico(3, "Tres"),
            medico(4, "Cuatro", "pediatria", "clinica"),
        )
        assertEquals(listOf(2L, 4L), medicosParaAsignar(medicos, emptyList()).map { it.medicoIdTobb })
    }

    @Test
    fun cruza_por_numero_de_turnos_y_trae_el_perfil_y_si_esta_vinculado() {
        val medicos = listOf(medico(7, "Siete", "clinica"), medico(8, "Ocho", "clinica"))
        val asignaciones = listOf(
            AsignacionPerfil(8, "Ocho, Prueba", vinculado = true, activo = true, perfilId = 2, perfilWeb = 2),
            AsignacionPerfil(7, vinculado = false, perfilId = 1),
        )
        assertEquals(
            listOf(
                MedicoAsignable(TobbIds.medico(7), 7, "Siete, Prueba", perfilId = 1, vinculado = false),
                MedicoAsignable(TobbIds.medico(8), 8, "Ocho, Prueba", perfilId = 2, vinculado = true),
            ),
            medicosParaAsignar(medicos, asignaciones),
        )
    }

    @Test
    fun sin_fila_en_hclinica_queda_sin_perfil_y_no_vinculado() {
        val r = medicosParaAsignar(listOf(medico(9, "Nueve", "clinica")), emptyList()).single()
        assertEquals(null, r.perfilId)
        assertEquals(false, r.vinculado)
    }

    @Test
    fun vinculado_sin_perfil_sigue_vinculado() {
        val r = medicosParaAsignar(
            listOf(medico(5, "Cinco", "clinica")),
            listOf(AsignacionPerfil(5, "Cinco, Prueba", vinculado = true, activo = true)),
        ).single()
        assertEquals(null, r.perfilId)
        assertEquals(true, r.vinculado)
    }

    @Test
    fun un_medico_que_no_es_de_turnos_no_se_puede_asignar() {
        val local = Medico("medico-local", "u-local", "Prueba", "Local", especialidadesHc = listOf("clinica"))
        assertEquals(emptyList(), medicosParaAsignar(listOf(local), emptyList()))
    }
}
