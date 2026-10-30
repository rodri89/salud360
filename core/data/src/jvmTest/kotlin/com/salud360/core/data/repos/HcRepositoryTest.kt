package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.PREFIJO_ID_WEB
import com.salud360.core.data.hcgeneral.esDeLaWeb
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
    ) {
        historiaClinicaQueries.upsertConsulta(
            Consulta(id, paciente, medico, especialidad, tipo, hoy, EstadoConsulta.ABIERTA, remotoId = remotoId).toRow(actualizada, dirty = dirty),
        )
    }

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
    fun abrirConsulta_retoma_la_local_aunque_haya_una_de_la_web_mas_reciente() = conBase { db ->
        db.sembrar("local-1", "clinica", actualizada = 100)
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
    fun clinica_sin_backend_registrado_se_comporta_como_hoy() = conBase { db ->
        // Es el estado de la app hasta E6.5: la general no tiene backend y se retoma la abierta más reciente.
        db.sembrar("local-1", "clinica", actualizada = 100)
        db.sembrar("hcc-c77", "clinica", remotoId = "77", actualizada = 900)
        val r = repo(db)

        assertEquals("hcc-c77", r.abrir("clinica").id)
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
        // Una sola definición del criterio: lo que la consulta SQL descarta es exactamente lo que `esDeLaWeb` dice que es de la web.
        val casos = listOf(
            "hcc-c1" to "1", "hcc-c2" to "", "x-3" to "3", "x-4" to "", "hcp-c5" to "", "hc-clinica-p6" to "", "hcc" to "", "xhcc-7" to "",
        )
        casos.forEach { (id, remoto) ->
            conBase { db ->
                db.sembrar(id, "clinica", remotoId = remoto, actualizada = 100)

                val seRetoma = repo(db, clinica()).abrir("clinica").id == id

                val deLaWeb = Consulta(id, paciente, medico, "clinica", "consulta", hoy, remotoId = remoto).esDeLaWeb()
                assertEquals(!deLaWeb, seRetoma, "id=$id remoto=$remoto")
            }
        }
    }
}
