package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.PREFIJO_ID_WEB
import com.salud360.core.data.hcgeneral.esDeLaWeb
import com.salud360.core.data.hcgeneral.fusionarConsulta
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `HcRepository` frente a un backend de solo lectura (spec 002, E5.3, RF-46): `esSoloLectura` y `abrirConsulta`, que no
 * puede retomar una consulta abierta que vino de la web —tiene `remoto_id` o un id `hcc-…`— como si fuera del
 * dispositivo. Las consultas locales de clínica siguen siendo posibles y editables (Q14) y pediatría sigue reutilizando
 * la abierta que ya tiene allá. Sobre una base SQLite de verdad en un archivo temporal; datos inventados.
 */
class HcRepositoryTest {

    /** Backend falso: anota las llamadas de lectura y falla si alguien intenta enviar algo. */
    private class BackendFalso(override val especialidad: String, override val soloLectura: Boolean) : HcBackend {
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

    private fun conBase(bloque: suspend (Salud360Db) -> Unit) {
        val archivo = File.createTempFile("salud360-hcrepo", ".db").also { it.delete() }
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

    private fun repo(db: Salud360Db, vararg backends: BackendFalso) = HcRepository(db, backends.associateBy { it.especialidad })

    private val hoy = LocalDate.parse("2026-10-29")
    private val paciente = "p-99000001"
    private val medico = "m-99000001"

    private fun clinica() = BackendFalso("clinica", soloLectura = true)
    private fun pediatria() = BackendFalso("pediatria", soloLectura = false)

    /** Siembra una consulta abierta del médico; [web] la deja como la guardaría la lectura (sin `dirty`). */
    private suspend fun Salud360Db.sembrar(
        id: Id,
        especialidad: String,
        remotoId: String = "",
        actualizada: Long,
        tipo: String = "consulta",
        dirty: Boolean = remotoId.isBlank(),
        propia: Boolean = false,
        paciente: Id = this@HcRepositoryTest.paciente,
        medico: Id = this@HcRepositoryTest.medico,
        estado: EstadoConsulta = EstadoConsulta.ABIERTA,
    ) {
        historiaClinicaQueries.upsertConsulta(
            Consulta(id, paciente, medico, especialidad, tipo, hoy, estado, remotoId = remotoId, propia = propia).toRow(actualizada, dirty = dirty),
        )
    }

    /** La marca tal como quedó en la base, sin pasar por el modelo. */
    private suspend fun Salud360Db.marca(id: Id): Long? = historiaClinicaQueries.consultaPorId(id).lista { it.propia }.singleOrNull()

    private suspend fun Salud360Db.filas() = historiaClinicaQueries.consultasDePacienteTodas(paciente).lista { it }.sortedBy { it.id }

    private suspend fun HcRepository.abrir(especialidad: String, tipo: String = "consulta") =
        abrirConsulta(paciente, medico, especialidad, tipo, hoy, "45 años")

    // ---- esSoloLectura ----

    @Test
    fun esSoloLectura_dice_lo_que_declara_el_backend_y_false_si_no_hay() = conBase { db ->
        val r = repo(db, clinica(), pediatria())

        assertTrue(r.esSoloLectura("clinica"))
        assertFalse(r.esSoloLectura("pediatria"))
        assertFalse(r.esSoloLectura("hematologia"), "sin backend no es de solo lectura: es solo local, como siempre")
    }

    @Test
    fun tieneApi_y_especialidadesConApi_cuentan_al_backend_de_solo_lectura() = conBase { db ->
        // Es lo que hace que la historia y la ficha del paciente traigan la lista de la web (finalidad de la L).
        val r = repo(db, clinica(), pediatria())

        assertTrue(r.tieneApi("clinica"))
        assertEquals(setOf("clinica", "pediatria"), r.especialidadesConApi)
    }

    @Test
    fun traerConsultasRemotas_de_solo_lectura_solo_lee() = conBase { db ->
        val clinica = clinica()
        val r = repo(db, clinica)

        // El backend lanza si algo intenta enviar: traer la lista (ficha del paciente) es una lectura.
        assertEquals(emptyList(), r.traerConsultasRemotas(paciente, medico, "clinica"))
        r.traerConsultaRemota(Consulta("hcc-c1", paciente, medico, "clinica", "consulta", hoy, remotoId = "1"))

        assertEquals(listOf("traerConsultas", "traerConsulta"), clinica.llamadas)
    }

    // ---- abrirConsulta: clínica (solo lectura) ----

    @Test
    fun abrirConsulta_con_una_abierta_de_la_web_crea_una_nueva_local_y_no_toca_la_de_la_web() = conBase { db ->
        db.sembrar("hcc-c77", "clinica", remotoId = "77", actualizada = 500)
        val antes = db.filas()
        val r = repo(db, clinica())

        val abierta = r.abrir("clinica")

        assertNotEquals("hcc-c77", abierta.id)
        assertEquals("", abierta.remotoId)
        assertFalse(abierta.esDeLaWeb())
        assertEquals(EstadoConsulta.ABIERTA, abierta.estado)
        // La de la web sigue exactamente igual (ni `dirty` ni `updated_at`): no se marca para envío ni se edita.
        assertEquals(antes, db.filas().filter { it.id == "hcc-c77" })
        assertEquals(listOf(abierta.id), db.historiaClinicaQueries.consultasDirty().lista { it.id })
        // Y es una consulta nueva de verdad, en la base.
        assertEquals(abierta.id, r.consulta(abierta.id)?.id)
    }

    @Test
    fun abrirConsulta_no_retoma_una_con_remoto_id_aunque_su_id_no_tenga_el_prefijo() = conBase { db ->
        db.sembrar("x-1", "clinica", remotoId = "9", actualizada = 500)
        val r = repo(db, clinica())

        val abierta = r.abrir("clinica")

        assertNotEquals("x-1", abierta.id)
        assertEquals("", abierta.remotoId)
    }

    @Test
    fun abrirConsulta_no_retoma_una_con_prefijo_hcc_aunque_no_tenga_remoto_id() = conBase { db ->
        db.sembrar("hcc-c78", "clinica", remotoId = "", actualizada = 500, dirty = false)
        val r = repo(db, clinica())

        val abierta = r.abrir("clinica")

        assertNotEquals("hcc-c78", abierta.id)
    }

    @Test
    fun abrirConsulta_retoma_la_propia_aunque_haya_una_de_la_web_mas_reciente() = conBase { db ->
        // T16 (RF-11): se retoma solo una propia; antes valía cualquier abierta del dispositivo (hoy sería del histórico).
        db.sembrar("local-1", "clinica", actualizada = 100, propia = true)
        db.sembrar("hcc-c77", "clinica", remotoId = "77", actualizada = 900)
        val antes = db.filas()
        val r = repo(db, clinica())

        val abierta = r.abrir("clinica")

        assertEquals("local-1", abierta.id)
        assertEquals(antes, db.filas(), "retomar no escribe nada")
    }

    @Test
    fun abrirConsulta_de_otro_tipo_crea_una_nueva_como_siempre() = conBase { db ->
        db.sembrar("local-1", "clinica", actualizada = 100, tipo = "telemedicina")
        val r = repo(db, clinica())

        val abierta = r.abrir("clinica", tipo = "consulta")

        assertNotEquals("local-1", abierta.id)
        assertEquals("consulta", abierta.tipo)
    }

    @Test
    fun abrirConsulta_sin_ninguna_abierta_crea_una_local_sucia() = conBase { db ->
        val r = repo(db, clinica())

        val abierta = r.abrir("clinica")

        assertEquals("", abierta.remotoId)
        assertEquals(listOf(abierta.id), db.historiaClinicaQueries.consultasDirty().lista { it.id })
    }

    @Test
    fun abrirConsulta_sigue_sin_retomar_la_abierta_de_otro_medico() = conBase { db ->
        // Las consultas de otro médico (`hcc-m…`) no se retoman por el `medico_id`; el filtro nuevo no lo afloja.
        db.historiaClinicaQueries.upsertConsulta(
            Consulta("local-ajena", paciente, "hcc-m5", "clinica", "consulta", hoy, remotoId = "").toRow(100, dirty = false),
        )
        val r = repo(db, clinica())

        assertNotEquals("local-ajena", r.abrir("clinica").id)
    }

    // ---- abrirConsulta: lo que no cambia ----

    @Test
    fun pediatria_sigue_reutilizando_la_abierta_remota() = conBase { db ->
        db.sembrar("hcp-c55", "pediatria", remotoId = "55", actualizada = 500, dirty = false)
        val r = repo(db, pediatria(), clinica())

        val abierta = r.abrir("pediatria")

        assertEquals("hcp-c55", abierta.id)
        assertEquals("55", abierta.remotoId)
    }

    @Test
    fun una_consulta_local_de_pediatria_ya_enviada_se_sigue_reutilizando() = conBase { db ->
        // En pediatría una consulta creada acá también tiene `remoto_id` una vez enviada: no es «de la web».
        db.sembrar("local-1", "pediatria", remotoId = "56", actualizada = 500, dirty = false)
        val r = repo(db, pediatria(), clinica())

        assertEquals("local-1", r.abrir("pediatria").id)
    }

    @Test
    fun una_especialidad_sin_backend_sigue_como_siempre() = conBase { db ->
        db.sembrar("a-1", "hematologia", remotoId = "7", actualizada = 500, dirty = false)
        val r = repo(db, clinica())

        assertEquals("a-1", r.abrir("hematologia").id)
    }

    @Test
    fun clinica_sin_backend_registrado_tambien_se_rige_por_la_marca() = conBase { db ->
        // Hasta E6.5 la general no tenía backend y se retomaba la abierta más reciente, incluso una de la web. Desde T16 (RF-11)
        // la HC general es la misma con o sin backend: solo se retoma una propia.
        db.sembrar("local-1", "clinica", actualizada = 100, propia = true)
        db.sembrar("hcc-c77", "clinica", remotoId = "77", actualizada = 900)
        val r = repo(db)

        assertEquals("local-1", r.abrir("clinica").id)
        assertEquals(1L, db.marca(r.abrir("clinica", tipo = "telemedicina").id), "y la nueva también se crea propia")
    }

    // ---- el criterio de «consulta de la web» ----

    @Test
    fun esDeLaWeb_es_remoto_id_no_vacio_o_el_prefijo_hcc() {
        fun c(id: Id, remotoId: String) = Consulta(id, paciente, medico, "clinica", "consulta", hoy, remotoId = remotoId)

        assertEquals("hcc-", PREFIJO_ID_WEB)
        assertTrue(c("hcc-c77", "77").esDeLaWeb())
        assertTrue(c("hcc-c77", "").esDeLaWeb())
        assertTrue(c("x-1", "9").esDeLaWeb())
        assertFalse(c("x-1", "").esDeLaWeb())
        assertFalse(c("x-1", "   ").esDeLaWeb(), "un remoto_id en blanco no es un id")
        // Los prefijos de otras HC y del paciente son disjuntos del de la web.
        assertFalse(c("hcp-c55", "").esDeLaWeb())
        assertFalse(c("hc-clinica-p12", "").esDeLaWeb())
        assertFalse(c("hcc", "").esDeLaWeb())
    }

    @Test
    fun la_consulta_sql_y_esDeLaWeb_coinciden_en_cada_caso() {
        // Una sola definición del criterio: de las propias, lo que la consulta SQL descarta es exactamente lo que `esDeLaWeb` dice
        // que es de la web (T16: la propia enviada tiene `remoto_id` y no es de la web; el prefijo `hcc-` sí lo es siempre).
        val casos = listOf(
            "hcc-c1" to "1", "hcc-c2" to "", "x-3" to "3", "x-4" to "", "hcp-c5" to "", "hc-clinica-p6" to "", "hcc" to "", "xhcc-7" to "",
        )
        casos.forEach { (id, remoto) ->
            conBase { db ->
                db.sembrar(id, "clinica", remotoId = remoto, actualizada = 100, propia = true)

                val seRetoma = repo(db, clinica()).abrir("clinica").id == id

                val deLaWeb = Consulta(id, paciente, medico, "clinica", "consulta", hoy, remotoId = remoto, propia = true).esDeLaWeb()
                assertEquals(!deLaWeb, seRetoma, "id=$id remoto=$remoto")
            }
        }
    }

    @Test
    fun una_sin_la_marca_nunca_se_retoma_sea_cual_sea_su_id() {
        // El mismo recorrido sin la marca: del histórico o de la web, ninguna se retoma (RF-11).
        listOf("hcc-c1" to "1", "hcc-c2" to "", "x-3" to "3", "x-4" to "", "hcp-c5" to "", "hc-clinica-p6" to "").forEach { (id, remoto) ->
            conBase { db ->
                db.sembrar(id, "clinica", remotoId = remoto, actualizada = 100)

                assertNotEquals(id, repo(db, clinica()).abrir("clinica").id, "id=$id remoto=$remoto")
            }
        }
    }

    // ---- abrirConsulta: la marca de propia (T16, RF-8, RF-11, D26) ----

    @Test
    fun abrirConsulta_en_clinica_crea_la_consulta_propia() = conBase { db ->
        val r = repo(db, clinica())

        val nueva = r.abrir("clinica")

        assertTrue(nueva.propia)
        assertEquals(1L, db.marca(nueva.id))
        assertTrue(r.consulta(nueva.id)!!.propia, "y se lee propia desde la base")
        assertEquals("", nueva.remotoId)
        assertEquals(EstadoConsulta.ABIERTA, nueva.estado)
    }

    @Test
    fun abrirConsulta_en_clinica_reutiliza_la_propia_abierta_del_mismo_paciente_y_tipo_sin_escribir() = conBase { db ->
        db.sembrar("propia-1", "clinica", actualizada = 100, propia = true)
        val antes = db.filas()
        val r = repo(db, clinica())

        val abierta = r.abrir("clinica")

        assertEquals("propia-1", abierta.id)
        assertTrue(abierta.propia)
        assertEquals(antes, db.filas(), "retomar no escribe nada")
    }

    @Test
    fun una_propia_ya_enviada_y_abierta_se_sigue_reutilizando_RF_62() = conBase { db ->
        db.sembrar("propia-1", "clinica", remotoId = "7011", actualizada = 100, propia = true, dirty = false)
        val r = repo(db, clinica())

        val abierta = r.abrir("clinica")

        assertEquals("propia-1", abierta.id)
        assertEquals("7011", abierta.remotoId)
    }

    @Test
    fun abrirConsulta_nunca_reutiliza_una_del_historico_aunque_este_abierta_y_sea_la_mas_reciente() = conBase { db ->
        db.sembrar("historica-1", "clinica", actualizada = 900)
        val antes = db.filas()
        val r = repo(db, clinica())

        val nueva = r.abrir("clinica")

        assertNotEquals("historica-1", nueva.id)
        assertTrue(nueva.propia)
        assertEquals(antes, db.filas().filter { it.id == "historica-1" }, "la del histórico queda como estaba: sin marca, sin cambios")
        assertEquals(0L, db.marca("historica-1"))
    }

    @Test
    fun abrirConsulta_nunca_reutiliza_una_de_la_web_ni_con_backend_ni_sin_el() = conBase { db ->
        db.sembrar("hcc-c77", "clinica", remotoId = "77", actualizada = 900, dirty = false)
        db.sembrar("hcc-c78", "clinica", remotoId = "", actualizada = 950, dirty = false)

        for (r in listOf(repo(db, clinica()), repo(db))) {
            val nueva = r.abrir("clinica")
            assertTrue(nueva.id != "hcc-c77" && nueva.id != "hcc-c78")
            assertTrue(nueva.propia)
        }
    }

    @Test
    fun abrirConsulta_elige_la_propia_aunque_haya_del_historico_y_de_la_web_mas_recientes() = conBase { db ->
        db.sembrar("propia-1", "clinica", actualizada = 100, propia = true)
        db.sembrar("historica-1", "clinica", actualizada = 500)
        db.sembrar("hcc-c77", "clinica", remotoId = "77", actualizada = 900, dirty = false)
        val r = repo(db, clinica())

        assertEquals("propia-1", r.abrir("clinica").id)
    }

    @Test
    fun la_propia_solo_se_reutiliza_si_coinciden_paciente_medico_tipo_y_esta_abierta() = conBase { db ->
        db.sembrar("otro-tipo", "clinica", actualizada = 100, propia = true, tipo = "telemedicina")
        db.sembrar("otro-paciente", "clinica", actualizada = 200, propia = true, paciente = "p-otro")
        db.sembrar("otro-medico", "clinica", actualizada = 300, propia = true, medico = "m-otro")
        db.sembrar("cerrada", "clinica", actualizada = 400, propia = true, estado = EstadoConsulta.CERRADA)
        db.sembrar("anulada", "clinica", actualizada = 500, propia = true, estado = EstadoConsulta.ANULADA)
        val r = repo(db, clinica())

        val nueva = r.abrir("clinica", tipo = "consulta")

        assertEquals(setOf("otro-tipo", "otro-paciente", "otro-medico", "cerrada", "anulada").intersect(setOf(nueva.id)), emptySet())
        assertTrue(nueva.propia)
        // La de otro tipo sí se reutiliza cuando se pide su tipo.
        assertEquals("otro-tipo", r.abrir("clinica", tipo = "telemedicina").id)
    }

    @Test
    fun con_dos_propias_abiertas_del_mismo_tipo_se_retoma_la_mas_reciente() = conBase { db ->
        db.sembrar("propia-vieja", "clinica", actualizada = 100, propia = true)
        db.sembrar("propia-nueva", "clinica", actualizada = 300, propia = true)

        assertEquals("propia-nueva", repo(db, clinica()).abrir("clinica").id)
    }

    @Test
    fun abrir_dos_veces_seguidas_en_clinica_da_la_misma_consulta() = conBase { db ->
        val r = repo(db, clinica())

        val primera = r.abrir("clinica")
        val segunda = r.abrir("clinica")

        assertEquals(primera.id, segunda.id)
        assertEquals(1, db.filas().size)
    }

    @Test
    fun solo_la_hc_general_lleva_la_marca_pediatria_y_las_demas_siguen_igual() = conBase { db ->
        val r = repo(db, pediatria(), clinica())

        val pediatrica = r.abrir("pediatria")
        val otra = r.abrir("hematologia")

        assertFalse(pediatrica.propia)
        assertFalse(otra.propia)
        assertEquals(0L, db.marca(pediatrica.id))
        assertEquals(0L, db.marca(otra.id))
        // Y siguen reutilizando la abierta sin mirar la marca.
        db.sembrar("a-1", "oftalmologia", actualizada = 500)
        assertEquals("a-1", r.abrir("oftalmologia").id)
    }

    // ---- ninguna operación pierde la marca ----

    @Test
    fun cerrar_reabrir_anular_y_guardar_la_cabecera_conservan_la_marca() = conBase { db ->
        db.sembrar("propia-1", "clinica", actualizada = 100, propia = true)
        db.sembrar("historica-1", "clinica", actualizada = 100)
        db.sembrar("propia-enviada", "clinica", remotoId = "7011", actualizada = 100, propia = true, dirty = false)
        val r = repo(db, clinica())

        for ((id, esperada) in listOf("propia-1" to 1L, "historica-1" to 0L, "propia-enviada" to 1L)) {
            assertTrue(r.cerrarConsulta(id, LocalDate.parse("2026-11-01"), "46 años"), id)
            assertEquals(esperada, db.marca(id), "cerrar $id")
            assertTrue(r.reabrirConsulta(id), id)
            assertEquals(esperada, db.marca(id), "reabrir $id")
            assertTrue(r.guardarConsulta(r.consulta(id)!!.copy(edadMostrar = "47 años")), id)
            assertEquals(esperada, db.marca(id), "guardarConsulta $id")
            assertEquals(esperada == 1L, r.consulta(id)!!.propia, "el modelo $id")
            assertTrue(r.anularConsulta(id), id)
            assertEquals(esperada, db.marca(id), "anular $id")
        }
    }

    @Test
    fun una_propia_ya_enviada_se_edita_aunque_el_backend_sea_de_solo_lectura_y_una_historica_con_remoto_id_no() = conBase { db ->
        // Las guardas de la web miran el `remoto_id` frente a un backend de solo lectura: la marca de propia lo exceptúa (RF-62).
        db.sembrar("propia-enviada", "clinica", remotoId = "7011", actualizada = 100, propia = true, dirty = false)
        db.sembrar("historica-con-remoto", "clinica", remotoId = "9", actualizada = 100, dirty = false)
        val r = repo(db, clinica())

        assertTrue(r.cerrarConsulta("propia-enviada"))
        assertFalse(r.cerrarConsulta("historica-con-remoto"), "sin la marca sigue siendo de la web: no se cierra")
        assertEquals(EstadoConsulta.ABIERTA, r.consulta("historica-con-remoto")!!.estado)
    }

    @Test
    fun fusionarConsulta_con_la_fila_de_la_web_no_pierde_la_marca_de_la_local() = conBase { db ->
        // Lo que la lectura hace con una fila que ya está: `fusionarConsulta(web, local)` y escribir el resultado.
        db.sembrar("hcc-c7011", "clinica", remotoId = "7011", actualizada = 100, propia = true, dirty = false)
        db.sembrar("hcc-c7012", "clinica", remotoId = "7012", actualizada = 100, dirty = false)
        val r = repo(db, clinica())

        for ((id, esperada) in listOf("hcc-c7011" to 1L, "hcc-c7012" to 0L)) {
            val local = r.consulta(id)!!
            val web = local.copy(propia = false, estado = EstadoConsulta.CERRADA, edadMostrar = "50 años", updatedAt = null)
            val fusionada = fusionarConsulta(web, local)!!
            db.historiaClinicaQueries.upsertConsulta(fusionada.toRow(200, dirty = false))
            assertEquals(esperada, db.marca(id), "fusionar $id")
            assertEquals(EstadoConsulta.CERRADA, r.consulta(id)!!.estado)
        }
    }
}
