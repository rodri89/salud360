package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.AVISO_NO_SE_ENVIA
import com.salud360.core.data.hcgeneral.antecedentesQueSePuedenEnviar
import com.salud360.core.data.hcgeneral.avisoDeAntecedente
import com.salud360.core.data.hcgeneral.avisoDeCampoDelExamen
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.hc.Diagnostico
import com.salud360.core.model.hc.ExamenFisico
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 005 RF-3 (T17, D9): lo que la web no guarda —tabaquismo, frecuencia cardíaca, circunferencia abdominal, diagnósticos y
 * antecedentes personales— y que ya estaba cargado en el dispositivo **se conserva** (nada lo borra ni lo reescribe) y se marca
 * «No se envía a la web». `SeccionesEnviables` solo decide qué puede viajar; no escribe nada. Base SQLite real; datos inventados.
 */
class SeccionesEnviablesDatosTest {
    private val paciente = "p-99000001"
    private val medico = "m-99000001"
    private val hoy = LocalDate.parse("2026-11-06")

    private fun conBase(bloque: suspend (Salud360Db) -> Unit) {
        val archivo = File.createTempFile("salud360-enviables", ".db").also { it.delete() }
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
    fun lo_cargado_de_lo_que_la_web_no_guarda_sigue_ahi_y_se_marca_pero_no_puede_viajar() = conBase { db ->
        val r = HcRepository(db)
        val consulta = r.abrirConsulta(paciente, medico, "clinica", "consulta", hoy, "45 años")
        r.guardarAntecedente(paciente, "clinica", "familiares", "tabaquismo", true, "padre, 20 años", consulta.id)
        r.guardarAntecedente(paciente, "clinica", "familiares", "hta", true, "madre", consulta.id)
        r.guardarAntecedente(paciente, "clinica", "personales", "alergias", true, "penicilina", consulta.id)
        r.guardarExamenFisico(ExamenFisico(consulta.id, peso = "70", frecuenciaCardiaca = "72", circunferenciaAbdominal = "95", nota = "ok"))
        r.guardarDiagnostico(Diagnostico("d-1", medico, "Diagnóstico de prueba"))
        r.asignarDiagnostico(consulta.id, "d-1", true)

        val antecedentesAntes = r.observarAntecedentes(paciente, "clinica").first()
        val examenAntes = r.observarExamenFisico(consulta.id).first()
        val diagnosticosAntes = r.observarDiagnosticosDeConsulta(consulta.id).first()
        assertEquals(3, antecedentesAntes.size)

        // Lo que puede viajar deja afuera lo que la web no guarda; lo cargado se marca.
        assertEquals(listOf("hta"), antecedentesQueSePuedenEnviar(antecedentesAntes).map { it.clave })
        assertEquals(
            mapOf("tabaquismo" to AVISO_NO_SE_ENVIA, "hta" to null, "alergias" to AVISO_NO_SE_ENVIA),
            antecedentesAntes.associate { it.clave to avisoDeAntecedente(it) },
        )
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeCampoDelExamen(CampoExamenFisico.FRECUENCIA_CARDIACA))
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeCampoDelExamen(CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL))

        // Todo el uso normal que sigue (editar otra casilla, cerrar, reabrir, abrir otra consulta) no toca lo cargado.
        r.guardarAntecedente(paciente, "clinica", "familiares", "hta", true, "madre y tío", consulta.id)
        r.cerrarConsulta(consulta.id)
        r.reabrirConsulta(consulta.id)
        r.abrirConsulta(paciente, medico, "clinica", "telemedicina", hoy, "45 años")

        val despues = r.observarAntecedentes(paciente, "clinica").first().associateBy { it.clave }
        assertEquals(antecedentesAntes.first { it.clave == "tabaquismo" }, despues.getValue("tabaquismo"))
        assertEquals(antecedentesAntes.first { it.clave == "alergias" }, despues.getValue("alergias"))
        assertEquals("madre y tío", despues.getValue("hta").detalle)
        assertEquals(examenAntes, r.observarExamenFisico(consulta.id).first())
        assertEquals("72", r.observarExamenFisico(consulta.id).first()?.frecuenciaCardiaca)
        assertEquals("95", r.observarExamenFisico(consulta.id).first()?.circunferenciaAbdominal)
        assertEquals(diagnosticosAntes, r.observarDiagnosticosDeConsulta(consulta.id).first())
        assertTrue(diagnosticosAntes.isNotEmpty())
    }
}
