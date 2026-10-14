package com.salud360.core.data.repos

import com.salud360.core.data.mappers.toRow
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.hc.Laboratorio
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Orden de las cargas de un laboratorio del paciente (RF-61), sobre una base SQLite de verdad en un archivo temporal:
 * por fecha descendente y, a igual fecha, la última guardada primero; las dadas de baja no salen.
 */
class LaboratoriosDePacienteTest {

    private val paciente = "p1"
    private val tipo = "laboratorio_general"
    private val fecha = LocalDate(2026, 10, 1)

    private fun conBase(bloque: suspend (Salud360Db) -> Unit) {
        val archivo = File.createTempFile("salud360-labs", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    bloque(Salud360Db(driver))
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    @Test
    fun a_igual_fecha_primero_la_ultima_guardada() = conBase { db ->
        val repo = HcRepository(db)
        repo.guardarLaboratorio(carga("l1", fecha))
        Thread.sleep(5)
        repo.guardarLaboratorio(carga("l2", fecha))
        repo.guardarLaboratorio(carga("l0", LocalDate(2026, 9, 1)))
        assertEquals(listOf("l2", "l1", "l0"), repo.observarLaboratorios(paciente, "clinica", tipo).first().map { it.id })
    }

    @Test
    fun a_igual_fecha_manda_updated_at_y_no_el_orden_en_la_tabla() = conBase { db ->
        val repo = HcRepository(db)
        val q = db.historiaClinicaQueries
        // La guardada después entra antes en la tabla (p. ej. llegó así de una sincronización).
        q.upsertLaboratorio(carga("despues", fecha).toRow(2_000))
        q.upsertLaboratorio(carga("antes", fecha).toRow(1_000))
        assertEquals(listOf("despues", "antes"), repo.observarLaboratorios(paciente, "clinica", tipo).first().map { it.id })
    }

    @Test
    fun una_dada_de_baja_no_sale() = conBase { db ->
        val repo = HcRepository(db)
        repo.guardarLaboratorio(carga("l1", fecha))
        repo.guardarLaboratorio(carga("l2", fecha))
        repo.eliminarLaboratorio("l1")
        assertEquals(listOf("l2"), repo.observarLaboratorios(paciente, "clinica", tipo).first().map { it.id })
    }

    private fun carga(id: String, fecha: LocalDate) =
        Laboratorio(id, paciente, "c1", "clinica", tipo, fecha, mapOf("hb" to "13"))
}
