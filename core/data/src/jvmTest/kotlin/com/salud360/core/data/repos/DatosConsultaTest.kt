package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.DatosConsulta
import com.salud360.core.data.mappers.toRow
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.ConsultaDiagnostico
import com.salud360.core.model.hc.Diagnostico
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Lo cargado en una consulta para el bloque de solo lectura (RF-22), sobre una base SQLite de verdad en un archivo
 * temporal: solo cuenta lo de esa consulta, activo y no borrado; lo del paciente queda afuera.
 */
class DatosConsultaTest {

    private val paciente = "p1"
    private val fecha = LocalDate(2026, 10, 1)
    private val consulta = Consulta("c1", paciente, "m1", "clinica", "control", fecha, EstadoConsulta.ABIERTA)
    private val otra = Consulta("c2", paciente, "m1", "clinica", "telemedicina", fecha, EstadoConsulta.CERRADA)

    private fun conBase(bloque: suspend (Salud360Db) -> Unit) {
        val archivo = File.createTempFile("salud360-datos", ".db").also { it.delete() }
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
    fun solo_lo_activo_y_no_borrado_de_esa_consulta() = conBase { db ->
        val repo = HcRepository(db)
        val q = db.historiaClinicaQueries
        repo.guardarConsulta(consulta)
        repo.guardarConsulta(otra)

        repo.guardarValor(consulta.id, "motivo_consulta", "texto", "Control")
        repo.guardarValor(otra.id, "nota", "texto", "De otra consulta")
        repo.guardarExamenFisico(ExamenFisico(consulta.id, peso = "70"))
        repo.guardarExamenFisico(ExamenFisico(otra.id, talla = "170"))

        // Registros: dos de un mismo tipo (sale una vez), uno borrado, uno inactivo, uno de otra consulta y uno por paciente.
        repo.guardarRegistro(registro("r1", consulta.id, "interconsulta"))
        repo.guardarRegistro(registro("r2", consulta.id, "interconsulta"))
        q.upsertRegistro(registro("r3", consulta.id, "veda").toRow(0, deleted = true))
        repo.guardarRegistro(registro("r4", consulta.id, "biopsia_hepatica").copy(activo = false))
        repo.guardarRegistro(registro("r5", otra.id, "tc_rmn"))
        repo.guardarRegistro(registro("r6", null, "screening"))

        repo.guardarLaboratorio(laboratorio("l1", consulta.id, "laboratorio_general"))
        repo.guardarLaboratorio(laboratorio("l2", consulta.id, "laboratorio_general"))
        q.upsertLaboratorio(laboratorio("l3", consulta.id, "peso").toRow(0, deleted = true))
        repo.guardarLaboratorio(laboratorio("l4", consulta.id, "laboratorio_reducido").copy(activo = false))
        repo.guardarLaboratorio(laboratorio("l5", otra.id, "laboratorio_hepatico"))

        repo.guardarArchivo(archivo("a1", consulta.id, "documentos"))
        repo.guardarArchivo(archivo("a2", consulta.id, "documentos"))
        q.upsertArchivo(archivo("a3", consulta.id, "audio").toRow(0, deleted = true))
        repo.guardarArchivo(archivo("a4", consulta.id, "fotos").copy(activo = false))
        repo.guardarArchivo(archivo("a5", otra.id, "eco_abdominal"))
        repo.guardarArchivo(archivo("a6", null, "familigrama"))

        listOf("d1", "d2", "d3", "d4").forEach { repo.guardarDiagnostico(Diagnostico(it, "m1", "Diagnóstico $it")) }
        repo.asignarDiagnostico(consulta.id, "d1", true)
        repo.asignarDiagnostico(consulta.id, "d2", true)
        repo.asignarDiagnostico(consulta.id, "d3", true)
        repo.asignarDiagnostico(consulta.id, "d3", false)
        repo.asignarDiagnostico(otra.id, "d4", true)
        // Diagnóstico dado de baja del catálogo: no cuenta aunque siga asignado.
        q.upsertDiagnostico(Diagnostico("d5", "m1", "Viejo").toRow(0, deleted = true))
        q.upsertConsultaDiagnostico(ConsultaDiagnostico(consulta.id, "d5").toRow(0))

        // Antecedente cargado en esta consulta: es del paciente, va aparte.
        q.upsertAntecedente(Antecedente("an1", paciente, "clinica", "personales", "alergias", true, consultaId = consulta.id).toRow(0))

        assertEquals(
            DatosConsulta(
                valores = mapOf("motivo_consulta" to mapOf("texto" to "Control")),
                examenFisico = ExamenFisico(consulta.id, peso = "70"),
                tiposRegistro = setOf("interconsulta"),
                tiposLaboratorio = setOf("laboratorio_general"),
                seccionesArchivo = setOf("documentos"),
                diagnosticos = 2,
            ),
            repo.observarDatosConsulta(consulta.id).first(),
        )
        // Los antecedentes del paciente, aparte.
        assertEquals(listOf("alergias"), repo.observarAntecedentes(paciente, "clinica").first().map { it.clave })
    }

    @Test
    fun consulta_sin_nada_cargado() = conBase { db ->
        val repo = HcRepository(db)
        repo.guardarConsulta(consulta)
        assertEquals(DatosConsulta(), repo.observarDatosConsulta(consulta.id).first())
    }

    private fun registro(id: String, consultaId: String?, tipo: String) =
        RegistroClinico(id, paciente, consultaId, "clinica", tipo, fecha, mapOf("detalle" to "algo"))

    private fun laboratorio(id: String, consultaId: String?, tipo: String) =
        Laboratorio(id, paciente, consultaId, "clinica", tipo, fecha, mapOf("hb" to "13"))

    private fun archivo(id: String, consultaId: String?, seccion: String) =
        Archivo(id, paciente, consultaId, null, seccion, "$id.pdf", "application/pdf")
}
