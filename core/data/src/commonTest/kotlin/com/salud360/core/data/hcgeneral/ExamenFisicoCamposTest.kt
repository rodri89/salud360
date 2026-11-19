package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL
import com.salud360.core.model.especialidad.CampoExamenFisico.FRECUENCIA_CARDIACA
import com.salud360.core.model.especialidad.CampoExamenFisico.PESO
import com.salud360.core.model.especialidad.CampoExamenFisico.TEMPERATURA
import com.salud360.core.model.especialidad.EspecialidadDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

/** Campos del examen físico según la especialidad y si la consulta se puede editar ("Decidido" 6 del plan, RF-33). */
class ExamenFisicoCamposTest {
    /** Como la HC general: peso se carga; FC y CA solo se leen si ya tienen valor. */
    private val general = EspecialidadDefinition(
        "clinica", "Clínica", "hclinica", emptyList(), emptyList(),
        examenFisicoCampos = setOf(PESO),
        examenFisicoSoloLectura = setOf(FRECUENCIA_CARDIACA, CIRCUNFERENCIA_ABDOMINAL),
    )

    /** Como las demás: sin campos de solo lectura. */
    private val otra = EspecialidadDefinition("otra", "Otra", "otra", emptyList(), emptyList(), examenFisicoCampos = setOf(PESO, FRECUENCIA_CARDIACA))

    @Test
    fun fc_y_ca_con_valor_se_leen_aunque_la_consulta_este_abierta() {
        assertEquals(ModoCampoExamen.LECTURA, modoCampoExamen(general, FRECUENCIA_CARDIACA, "80", soloLectura = false))
        assertEquals(ModoCampoExamen.LECTURA, modoCampoExamen(general, CIRCUNFERENCIA_ABDOMINAL, "94", soloLectura = false))
        assertEquals(ModoCampoExamen.LECTURA, modoCampoExamen(general, FRECUENCIA_CARDIACA, "80", soloLectura = true))
    }

    @Test
    fun fc_y_ca_sin_valor_no_se_muestran() {
        assertEquals(ModoCampoExamen.OCULTO, modoCampoExamen(general, FRECUENCIA_CARDIACA, "", soloLectura = false))
        assertEquals(ModoCampoExamen.OCULTO, modoCampoExamen(general, CIRCUNFERENCIA_ABDOMINAL, "  ", soloLectura = true))
    }

    @Test
    fun campo_de_la_especialidad_se_edita_abierta_y_se_lee_cerrada_si_tiene_valor() {
        assertEquals(ModoCampoExamen.EDITABLE, modoCampoExamen(general, PESO, "", soloLectura = false))
        assertEquals(ModoCampoExamen.EDITABLE, modoCampoExamen(general, PESO, "70", soloLectura = false))
        assertEquals(ModoCampoExamen.LECTURA, modoCampoExamen(general, PESO, "70", soloLectura = true))
        assertEquals(ModoCampoExamen.OCULTO, modoCampoExamen(general, PESO, "", soloLectura = true))
    }

    @Test
    fun campo_que_la_especialidad_no_tiene_no_se_muestra_aunque_tenga_valor() {
        assertEquals(ModoCampoExamen.OCULTO, modoCampoExamen(general, TEMPERATURA, "37", soloLectura = false))
    }

    @Test
    fun las_demas_especialidades_como_siempre() {
        assertEquals(ModoCampoExamen.EDITABLE, modoCampoExamen(otra, FRECUENCIA_CARDIACA, "", soloLectura = false))
        assertEquals(ModoCampoExamen.EDITABLE, modoCampoExamen(otra, FRECUENCIA_CARDIACA, "80", soloLectura = false))
        assertEquals(ModoCampoExamen.OCULTO, modoCampoExamen(otra, CIRCUNFERENCIA_ABDOMINAL, "94", soloLectura = false))
    }
}
