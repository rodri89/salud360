package com.salud360.core.data.repos

import com.salud360.core.data.mappers.toRow
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.SeccionValor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Valores de un campo en las consultas del paciente para el historial del motivo (RF-84), sobre una base SQLite de
 * verdad en un archivo temporal: solo ese paciente, especialidad, sección y campo; sin consultas anuladas ni borradas
 * ni valores borrados.
 */
class ValoresDeCampoDePacienteTest {

    private val paciente = "p1"
    private val fecha = LocalDate(2026, 10, 1)

    private fun conBase(bloque: suspend (Salud360Db) -> Unit) {
        val archivo = File.createTempFile("salud360-valores", ".db").also { it.delete() }
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
    fun solo_el_campo_de_las_consultas_vigentes_del_paciente_en_esa_especialidad() = conBase { db ->
        val repo = HcRepository(db)
        val q = db.historiaClinicaQueries
        repo.guardarConsulta(consulta("c1"))
        repo.guardarConsulta(consulta("c2", tipo = "telemedicina", estado = EstadoConsulta.ABIERTA))
        repo.guardarConsulta(consulta("anulada", estado = EstadoConsulta.ANULADA))
        q.upsertConsulta(consulta("borrada").toRow(0, deleted = true))
        repo.guardarConsulta(consulta("deOtro").copy(pacienteId = "p2"))
        repo.guardarConsulta(consulta("deOtraHc").copy(especialidad = "pediatria"))
        repo.guardarConsulta(consulta("valorBorrado"))

        repo.guardarValor("c1", "motivo_consulta", "texto", "motivo 1")
        repo.guardarValor("c2", "motivo_consulta", "texto", "motivo 2")
        repo.guardarValor("anulada", "motivo_consulta", "texto", "de una anulada")
        repo.guardarValor("borrada", "motivo_consulta", "texto", "de una borrada")
        repo.guardarValor("deOtro", "motivo_consulta", "texto", "de otro paciente")
        repo.guardarValor("deOtraHc", "motivo_consulta", "texto", "de otra especialidad")
        q.upsertSeccionValor(SeccionValor("valorBorrado", "motivo_consulta", "texto", "borrado").toRow(0, deleted = true))
        // Otra sección y otro campo de una consulta que sí cuenta.
        repo.guardarValor("c1", "conductas", "texto", "otra sección")
        repo.guardarValor("c1", "motivo_consulta", "otro", "otro campo")

        assertEquals(
            mapOf("c1" to "motivo 1", "c2" to "motivo 2"),
            repo.observarValoresDeCampo(paciente, "clinica", "motivo_consulta", "texto").first(),
        )
    }

    @Test
    fun sin_valores_cargados() = conBase { db ->
        val repo = HcRepository(db)
        repo.guardarConsulta(consulta("c1"))
        assertEquals(emptyMap(), repo.observarValoresDeCampo(paciente, "clinica", "motivo_consulta", "texto").first())
    }

    private fun consulta(id: String, tipo: String = "consulta", estado: EstadoConsulta = EstadoConsulta.CERRADA) =
        Consulta(id, paciente, "m1", "clinica", tipo, fecha, estado)
}
