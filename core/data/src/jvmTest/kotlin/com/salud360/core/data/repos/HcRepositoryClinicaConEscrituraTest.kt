package com.salud360.core.data.repos

import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.sembrar
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005, T23 (RF-1, D1): al pasar `HcClinicaBackend` a `soloLectura = false`, las guardas que miraban ese valor tienen que seguir
 * protegiendo lo que vino de la web. **Una consulta de la web es siempre solo lectura**, escriba o no el backend; lo propio y lo del
 * histórico del dispositivo se editan. Con el backend REAL de la HC general (el que usa la app) sobre una base SQLite temporal; datos
 * inventados.
 */
class HcRepositoryClinicaConEscrituraTest {
    private val fecha = LocalDate.parse("2026-11-06")
    private val deLaWeb: Id = "hcc-c7003"
    private val conRemotoSinPrefijo: Id = "x-remoto-88"
    private val propia: Id = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
    private val propiaEnviada: Id = "8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f"
    private val historica: Id = "a1b2c3d4-0000-4000-8000-000000000003"

    private fun HcClinicaPruebas.Contexto.repo() = HcRepository(db, mapOf(backend.especialidad to backend))

    private suspend fun com.salud360.core.database.Salud360Db.sembrarTodo() {
        sembrar(deLaWeb, remotoId = "7003", estado = EstadoConsulta.ABIERTA)
        sembrar(conRemotoSinPrefijo, remotoId = "88", estado = EstadoConsulta.ABIERTA)
        historiaClinicaQueries.upsertSeccionValor(SeccionValor(deLaWeb, "habitos", "texto", "Texto de la web").toRow(5, dirty = false))
        historiaClinicaQueries.upsertConsulta(Consulta(propia, PACIENTE, MEDICO, "clinica", "consulta", fecha, propia = true).toRow(10, dirty = false))
        historiaClinicaQueries.upsertConsulta(
            Consulta(propiaEnviada, PACIENTE, MEDICO, "clinica", "consulta", fecha, remotoId = "7011", propia = true).toRow(10, dirty = false),
        )
        historiaClinicaQueries.upsertConsulta(Consulta(historica, PACIENTE, MEDICO, "clinica", "consulta", fecha).toRow(10, dirty = false))
    }

    private fun HcClinicaPruebas.Contexto.volcado() = HcClinicaPruebas.volcarTodo(driver)

    // ---- Lo que el repositorio sabe del backend ----

    @Test
    fun el_backend_real_ya_no_es_de_solo_lectura_pero_la_historia_se_sigue_leyendo_de_la_web() = HcClinicaPruebas.conBackend {
        val r = repo()

        assertFalse(backend.soloLectura)
        assertFalse(r.esSoloLectura("clinica"))
        assertTrue(r.leeDeLaWeb("clinica"), "la pantalla de la historia y de la consulta siguen tratándola como una HC que se lee de la web")
        assertTrue(r.puedeActualizar("clinica"))
    }

    @Test
    fun leeDeLaWeb_es_solo_de_la_hc_que_trae_la_web_y_pediatria_no_cambia() = HcClinicaPruebas.conBackend {
        val sinBackend = HcRepository(db)
        val pediatria = HcRepository(db, mapOf("pediatria" to Pediatrica()))
        val deSoloLectura = HcRepository(db, mapOf("otra" to Pediatrica(soloLectura = true)))

        assertFalse(sinBackend.leeDeLaWeb("clinica"))
        assertFalse(pediatria.leeDeLaWeb("pediatria"), "pediatría envía con su motor y no se lee de la web")
        assertTrue(deSoloLectura.leeDeLaWeb("otra"), "un backend de solo lectura sin 'Actualizar' también")
        assertFalse(deSoloLectura.leeDeLaWeb("pediatria"))
    }

    // ---- La consulta de la web sigue siendo solo lectura ----

    @Test
    fun una_consulta_de_la_web_se_sigue_rechazando_con_el_backend_que_escribe() = HcClinicaPruebas.conBackend {
        db.sembrarTodo()
        val r = repo()
        val antes = volcado()

        for (id in listOf(deLaWeb, conRemotoSinPrefijo)) {
            assertFalse(r.cerrarConsulta(id), "cerrar $id")
            assertFalse(r.reabrirConsulta(id), "reabrir $id")
            assertFalse(r.anularConsulta(id), "anular $id")
            assertFalse(r.guardarConsulta(r.consulta(id)!!.copy(edadMostrar = "otra")), "guardar la cabecera de $id")
            assertTrue(r.esConsultaDeLaWeb(r.consulta(id)!!), id)
        }
        assertFalse(r.guardarValor(deLaWeb, "habitos", "texto", "Otro"))
        assertFalse(r.guardarSeccion(deLaWeb, "habitos", mapOf("texto" to "Otro")))

        assertEquals(antes, volcado(), "la base no cambia")
        assertEquals(0, HcClinicaPruebas.filasSucias(driver), "y nada queda pendiente de envío")
    }

    @Test
    fun una_con_remoto_id_sin_la_marca_es_de_la_web_aunque_su_id_no_tenga_el_prefijo() = HcClinicaPruebas.conBackend {
        db.sembrarTodo()
        val r = repo()

        assertTrue(r.esConsultaDeLaWeb(r.consulta(conRemotoSinPrefijo)!!), "es el caso de antes: tiene remoto_id y nadie la marcó propia")
        assertFalse(r.cerrarConsulta(conRemotoSinPrefijo))
    }

    // ---- Lo propio y lo del histórico se editan ----

    @Test
    fun la_propia_aunque_ya_este_enviada_y_la_del_historico_se_editan() = HcClinicaPruebas.conBackend {
        db.sembrarTodo()
        val r = repo()

        for (id in listOf(propia, propiaEnviada, historica)) {
            assertFalse(r.esConsultaDeLaWeb(r.consulta(id)!!), id)
            assertTrue(r.guardarValor(id, "habitos", "texto", "texto de $id"), "guardarValor $id")
            assertTrue(r.guardarConsulta(r.consulta(id)!!.copy(edadMostrar = "47 años")), "guardar la cabecera de $id")
            assertTrue(r.cerrarConsulta(id), "cerrar $id")
            assertTrue(r.reabrirConsulta(id), "reabrir $id")
            assertTrue(r.anularConsulta(id), "anular $id")
        }
    }

    @Test
    fun abrir_una_consulta_nueva_sigue_creandola_propia_con_el_backend_que_escribe() = HcClinicaPruebas.conBackend {
        db.sembrarTodo()
        val r = repo()

        val reutilizada = r.abrirConsulta(PACIENTE, MEDICO, "clinica", "consulta", fecha, "45 años")

        // La propia abierta del mismo tipo se reutiliza; la de la web y la del histórico nunca (RF-11).
        assertTrue(reutilizada.id == propia || reutilizada.id == propiaEnviada, reutilizada.id)
        assertTrue(reutilizada.propia)
    }

    // ---- Pediatría y las demás no cambian ----

    /** Un backend que no es de la HC general: [soloLectura] a gusto, sin nada más. */
    private class Pediatrica(override val soloLectura: Boolean = false) : HcBackend {
        override val especialidad = "pediatria"
        override suspend fun asegurarConsulta(consulta: Consulta): String? = null
        override suspend fun enviarSecciones(remotoId: String, secciones: Map<String, Map<String, String>>) = Unit
        override suspend fun enviarExamen(remotoId: String, campos: Map<String, String>) = Unit
        override suspend fun enviarRegistros(remotoId: String, registros: List<RegistroClinico>): Map<Id, String> = emptyMap()
        override suspend fun enviarEstado(consulta: Consulta, remotoId: String) = Unit
        override suspend fun subirArchivo(remotoId: String, archivo: Archivo, bytes: ByteArray): String? = null
        override suspend fun borrarArchivo(remotoId: String, archivo: Archivo) = Unit
        override suspend fun bajarArchivo(remotoId: String, archivo: Archivo): ByteArray? = null
        override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> = emptyList()
        override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) = Unit
    }

    @Test
    fun una_consulta_de_pediatria_con_remoto_id_se_sigue_editando() = HcClinicaPruebas.conBackend {
        db.historiaClinicaQueries.upsertConsulta(
            Consulta("hcp-c55", PACIENTE, MEDICO, "pediatria", "control", fecha, remotoId = "55").toRow(10, dirty = false),
        )
        val r = HcRepository(db, mapOf("pediatria" to Pediatrica()))

        assertFalse(r.esConsultaDeLaWeb(r.consulta("hcp-c55")!!), "en pediatría una consulta enviada tiene remoto_id y no es de la web")
        assertTrue(r.cerrarConsulta("hcp-c55"))
    }
}
