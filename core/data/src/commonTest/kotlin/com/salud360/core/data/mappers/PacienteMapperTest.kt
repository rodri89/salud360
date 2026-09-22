package com.salud360.core.data.mappers

import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.Sexo
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Paciente.toRow` pasa los valores por posición sobre la fila entera, y `upsertPaciente` es un
 * `INSERT ... VALUES ?` igual de posicional. Una columna fuera de lugar escribiría datos corridos sin
 * error de compilación ni de ejecución, así que la ida y vuelta se verifica acá.
 */
class PacienteMapperTest {
    private val paciente = Paciente(
        id = "p1", dni = "30111222", nombre = "ANA", apellido = "PEREZ",
        sexo = Sexo.F, fechaNacimiento = LocalDate(1990, 5, 20),
        telefono = "291555", mail = "ana@mail.com", domicilio = "Calle 1", localidad = "Bahía Blanca",
        obraSocial = "OSDE", numeroAfiliado = "77", obraSocialPlan = "210",
        cantidadHermanos = 2, afiliadoObligatorio = true, activo = false,
        nota = "no cobrar, es familiar del médico",
    )

    @Test
    fun la_nota_sobrevive_la_ida_y_vuelta() {
        assertEquals("no cobrar, es familiar del médico", paciente.toRow(1L).toModel().nota)
    }

    @Test
    fun sin_nota_queda_vacia_y_no_null() {
        assertEquals("", paciente.copy(nota = "").toRow(1L).toModel().nota)
    }

    /** Si la nota quedara mal ubicada, los campos vecinos se corren: se verifican los del final de la fila. */
    @Test
    fun los_campos_vecinos_no_se_corren() {
        val vuelta = paciente.toRow(1L).toModel()
        assertEquals(paciente, vuelta)
        assertEquals(false, vuelta.activo)
        assertEquals(true, vuelta.afiliadoObligatorio)
        assertEquals("30111222", vuelta.dni)
        assertEquals(2, vuelta.cantidadHermanos)
    }
}
