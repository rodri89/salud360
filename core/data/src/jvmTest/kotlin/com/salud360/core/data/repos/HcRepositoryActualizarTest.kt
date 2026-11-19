package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.CLAVE_NOMBRE_MEDICO_WEB
import com.salud360.core.data.hcgeneral.idMedicoWeb
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.repos.HcClinicaPruebas.ApiFalsa
import com.salud360.core.data.repos.HcClinicaPruebas.FICHA
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.MedicoPreferencia
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Actualizar» en la historia del paciente (spec 002, E7.2, RF-57, RF-58): `HcRepository` fuerza la lectura solo de un
 * backend que sabe leer a pedido ([Refrescable]) y sin tocar [HcBackend]; el resto (pediatría, sin backend) no cambia. Y
 * los nombres de los médicos de la web que la pantalla necesita para «Cargada por …». Datos inventados.
 */
class HcRepositoryActualizarTest {

    /** Backend falso: anota cómo lo llaman. Los envíos fallan si alguien los intenta. */
    private open class Falso(override val especialidad: String, override val soloLectura: Boolean) : HcBackend {
        val llamadas = mutableListOf<String>()

        override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> {
            llamadas += "traerConsultas"
            return emptyList()
        }

        override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) {
            llamadas += "traerConsulta"
        }

        override suspend fun asegurarConsulta(consulta: Consulta): String? = error("no es un envío")
        override suspend fun enviarSecciones(remotoId: String, secciones: Map<String, Map<String, String>>) = error("no es un envío")
        override suspend fun enviarExamen(remotoId: String, campos: Map<String, String>) = error("no es un envío")
        override suspend fun enviarRegistros(remotoId: String, registros: List<RegistroClinico>): Map<Id, String> = error("no es un envío")
        override suspend fun enviarEstado(consulta: Consulta, remotoId: String) = error("no es un envío")
        override suspend fun subirArchivo(remotoId: String, archivo: Archivo, bytes: ByteArray): String? = error("no es un envío")
        override suspend fun borrarArchivo(remotoId: String, archivo: Archivo) = error("no es un envío")
        override suspend fun bajarArchivo(remotoId: String, archivo: Archivo): ByteArray? = null
    }

    private class Refrescado(especialidad: String) : Falso(especialidad, soloLectura = true), Refrescable {
        override suspend fun traerConsultas(pacienteId: Id, medicoId: Id, forzar: Boolean): List<Consulta> {
            llamadas += "traerConsultas(forzar=$forzar)"
            return emptyList()
        }
    }

    private fun conBase(bloque: suspend (Salud360Db) -> Unit) {
        val archivo = File.createTempFile("salud360-hcactualizar", ".db").also { it.delete() }
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

    private fun repo(db: Salud360Db, vararg backends: HcBackend) = HcRepository(db, backends.associateBy { it.especialidad })

    // ---- Quién puede actualizar ----

    @Test
    fun solo_un_backend_que_sabe_leer_a_pedido_ofrece_actualizar() = conBase { db ->
        val r = repo(db, Refrescado("clinica"), Falso("pediatria", soloLectura = false))

        assertTrue(r.puedeActualizar("clinica"))
        assertFalse(r.puedeActualizar("pediatria"), "pediatría no cambia: no tiene el botón")
        assertFalse(r.puedeActualizar("hematologia"), "sin backend tampoco")
    }

    @Test
    fun actualizar_fuerza_la_lectura_y_abrir_la_historia_no() = conBase { db ->
        val clinica = Refrescado("clinica")
        val r = repo(db, clinica)

        r.traerConsultasRemotas(PACIENTE, MEDICO, "clinica")
        assertEquals(emptyList(), r.actualizarConsultasRemotas(PACIENTE, MEDICO, "clinica"))

        assertEquals(listOf("traerConsultas", "traerConsultas(forzar=true)"), clinica.llamadas)
    }

    @Test
    fun actualizar_un_backend_que_no_sabe_no_llama_a_nada_y_devuelve_null() = conBase { db ->
        val pediatria = Falso("pediatria", soloLectura = false)
        val r = repo(db, pediatria)

        assertNull(r.actualizarConsultasRemotas(PACIENTE, MEDICO, "pediatria"))
        assertNull(r.actualizarConsultasRemotas(PACIENTE, MEDICO, "hematologia"))
        assertEquals(emptyList(), pediatria.llamadas)
    }

    @Test
    fun el_backend_de_la_hc_general_es_refrescable() = HcClinicaPruebas.conBackend {
        assertTrue((backend as HcBackend) is Refrescable)
    }

    // ---- Con el backend de verdad: el minuto de espera y el «Actualizar» ----

    private fun cab(id: Long) = ConsultaCabeceraDto(
        id = id, pacienteId = FICHA, medicoId = 9, medicoNombre = "Médico Prueba Salud360", tipo = "control", tipoNumero = 1,
        fecha = "2026-03-10", createdAt = "2026-03-10 10:00:00", estado = "CERRADA", edadMostrar = "", propia = true, updatedAt = "2026-03-10 10:00:00",
    )

    @Test
    fun abrir_dos_veces_no_repite_pero_actualizar_si_pide_otra_vez() = HcClinicaPruebas.conBackend(ApiFalsa(listOf(cab(7003)))) {
        val r = HcRepository(db, mapOf(backend.especialidad to backend))

        r.traerConsultasRemotas(PACIENTE, MEDICO, "clinica")
        assertEquals(2, api.totalDePedidos, "la lista y el historial (RF-57)")

        reloj.avanzar(10_000)
        r.traerConsultasRemotas(PACIENTE, MEDICO, "clinica")
        assertEquals(2, api.totalDePedidos, "antes de un minuto no se repite")

        r.actualizarConsultasRemotas(PACIENTE, MEDICO, "clinica")
        assertEquals(4, api.totalDePedidos, "«Actualizar» fuerza la lista y el historial")
    }

    // ---- Los nombres de los médicos de la web (Q4) ----

    @Test
    fun los_nombres_de_los_medicos_de_la_web_se_leen_de_las_preferencias() = conBase { db ->
        val ajeno = idMedicoWeb(14)
        val q = db.historiaClinicaQueries
        q.upsertPreferencia(MedicoPreferencia(ajeno, CLAVE_NOMBRE_MEDICO_WEB, "Otro Médico Prueba Salud360").toRow(1, dirty = false))
        q.upsertPreferencia(MedicoPreferencia(ajeno, "secciones_ocultas_clinica", "habitos").toRow(1, dirty = false))
        q.upsertPreferencia(MedicoPreferencia(MEDICO, CLAVE_NOMBRE_MEDICO_WEB, "No es de la web").toRow(1, dirty = false))
        q.upsertPreferencia(MedicoPreferencia(idMedicoWeb(15), CLAVE_NOMBRE_MEDICO_WEB, "  ").toRow(1, dirty = false))
        q.upsertPreferencia(MedicoPreferencia(idMedicoWeb(16), CLAVE_NOMBRE_MEDICO_WEB, "Dado de baja").toRow(1, deleted = true, dirty = false))

        val nombres = repo(db).observarNombresDeMedicosWeb().first()

        assertEquals(mapOf(ajeno to "Otro Médico Prueba Salud360"), nombres)
    }
}
