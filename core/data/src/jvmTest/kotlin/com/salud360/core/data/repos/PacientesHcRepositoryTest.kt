package com.salud360.core.data.repos

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.MotivoHc
import com.salud360.core.data.hcgeneral.motivoHc
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcPacienteFicha
import com.salud360.core.data.network.hc.HcPacienteRequest
import com.salud360.core.data.network.hc.HcResolverRespuesta
import com.salud360.core.data.network.hc.PacientesHcApi
import com.salud360.core.data.network.hc.PacientesHcApis
import com.salud360.core.data.network.hc.PaginaCartera
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cartera del médico en cada historia clínica (spec 002 RF-8, RF-9, RF-13, RF-35, RF-37) sobre una base SQLite de
 * verdad en un archivo temporal, con APIs falsas: sin red ni servidor.
 */
class PacientesHcRepositoryTest {

    private val medico = "tobb-m1"
    private val nombres = mapOf("pediatria" to "Pediatría", "clinica" to "Clínica")

    /** API falsa: pagina [fichas] según `desde`/`limite`, o lanza [fallo]; anota cada pedido. */
    private class ApiFalsa(var fichas: List<HcPacienteFicha> = emptyList()) : PacientesHcApi {
        var fallo: Throwable? = null
        /** Contesta "hay más" siempre, para ver que una página vacía corta igual. */
        var siempreHayMas = false
        val pedidos = mutableListOf<Pair<Int, Int>>()

        override suspend fun pagina(desde: Int, limite: Int): PaginaCartera {
            pedidos += desde to limite
            fallo?.let { throw it }
            val pagina = fichas.drop(desde).take(limite)
            return PaginaCartera(pagina, siempreHayMas || desde + pagina.size < fichas.size)
        }

        override suspend fun resolver(pedido: HcPacienteRequest, elegida: Long?): HcResolverRespuesta =
            error("la cartera no resuelve")
    }

    private fun ficha(id: Long, tobb: Long = 0, apellido: String = "PRUEBA", dni: String = "${30_000_000 + id}") =
        HcPacienteFicha(
            id = id, pacienteIdTobb = tobb, nombre = "SALUD360 $id", apellido = apellido, dni = dni,
            telefono = "telefono de la web", nombreFamiliar = "FAMILIAR $id", telefonoFamiliar = "4000$id",
        )

    private fun conBase(bloque: suspend (Salud360Db, SqlDriver) -> Unit) {
        val archivo = File.createTempFile("salud360-cartera", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    bloque(Salud360Db(driver), driver)
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    private fun repo(db: Salud360Db, vararg apis: Pair<String, PacientesHcApi>) =
        PacientesHcRepository(db, PacientesHcApis(apis.toMap()), nombres)

    private suspend fun guardarMedico(db: Salud360Db, vararg hc: String) {
        db.authQueries.upsertMedico(
            Medico(medico, "u1", "Prueba", "Medico", especialidadesHc = hc.toList()).toRow(0, dirty = false),
        )
    }

    @Test
    fun dos_paginas_llegan_todas_de_a_300() = conBase { db, driver ->
        val api = ApiFalsa((1L..450L).map { ficha(it) })
        assertEquals(450, repo(db, "clinica" to api).traerCartera(medico, "clinica"))
        assertEquals(listOf(0 to 300, 300 to 300), api.pedidos)
        assertEquals(450L, contar(driver, "SELECT count(*) FROM paciente"))
        assertEquals(450L, contar(driver, "SELECT count(*) FROM medico_paciente WHERE medico_id = '$medico'"))
        assertEquals(450L, contar(driver, "SELECT count(*) FROM paciente_extra WHERE especialidad = 'clinica' AND clave = 'hc_paciente_id'"))
    }

    @Test
    fun una_pagina_vacia_corta_aunque_diga_que_hay_mas() = conBase { db, _ ->
        val api = ApiFalsa(listOf(ficha(1), ficha(2))).apply { siempreHayMas = true }
        assertEquals(2, repo(db, "clinica" to api).traerCartera(medico, "clinica"))
        assertEquals(listOf(0 to 300, 2 to 300), api.pedidos)
    }

    @Test
    fun repetir_no_duplica() = conBase { db, driver ->
        val api = ApiFalsa((1L..320L).map { ficha(it, tobb = if (it % 2 == 0L) 1000 + it else 0) })
        val repo = repo(db, "clinica" to api)
        repo.traerCartera(medico, "clinica")
        repo.traerCartera(medico, "clinica")
        assertEquals(320L, contar(driver, "SELECT count(*) FROM paciente"))
        assertEquals(320L, contar(driver, "SELECT count(*) FROM medico_paciente"))
        assertEquals(320L, contar(driver, "SELECT count(*) FROM paciente_extra"))
    }

    @Test
    fun un_paciente_local_no_se_pisa() = conBase { db, driver ->
        val local = Paciente("tobb-p55", "30111222", "Cargado", "En Turnos", telefono = "telefono de turnos", obraSocialPlan = "plan")
        db.pacientesQueries.upsertPaciente(local.toRow(0, dirty = false))
        val api = ApiFalsa(listOf(ficha(9, tobb = 55, apellido = "OTRO APELLIDO", dni = "30111222")))
        val repo = repo(db, "clinica" to api)
        repo.traerCartera(medico, "clinica")

        assertEquals("En Turnos|Cargado|telefono de turnos|plan|", texto(driver, "SELECT apellido || '|' || nombre || '|' || telefono || '|' || obra_social_plan || '|' || nombre_familiar FROM paciente WHERE id = 'tobb-p55'"))
        // Igual queda en la lista del médico y vinculado con su ficha.
        assertEquals(1L, contar(driver, "SELECT count(*) FROM medico_paciente WHERE medico_id = '$medico' AND paciente_id = 'tobb-p55'"))
        assertEquals(9L, repo.fichaConocida("tobb-p55", "clinica"))
    }

    @Test
    fun el_paciente_nuevo_llega_con_el_familiar_de_contacto() = conBase { db, driver ->
        repo(db, "clinica" to ApiFalsa(listOf(ficha(3)))).traerCartera(medico, "clinica")
        assertEquals("FAMILIAR 3|40003", texto(driver, "SELECT nombre_familiar || '|' || telefono_familiar FROM paciente WHERE id = 'hc-clinica-p3'"))
    }

    @Test
    fun el_mismo_paciente_de_turnos_en_dos_carteras_es_una_sola_ficha_con_los_dos_vinculos() = conBase { db, driver ->
        val repo = repo(db, "pediatria" to ApiFalsa(listOf(ficha(10, tobb = 77))), "clinica" to ApiFalsa(listOf(ficha(20, tobb = 77))))
        repo.traerCartera(medico, "pediatria")
        repo.traerCartera(medico, "clinica")
        assertEquals(1L, contar(driver, "SELECT count(*) FROM paciente"))
        assertEquals(10L, repo.fichaConocida("tobb-p77", "pediatria"))
        assertEquals(20L, repo.fichaConocida("tobb-p77", "clinica"))
    }

    @Test
    fun sin_vinculo_con_turnos_el_id_es_propio_de_la_hc_y_estable() = conBase { db, driver ->
        // Dos fichas del mismo documento sin vínculo: no se emparejan por documento (RF-37).
        val repo = repo(db, "clinica" to ApiFalsa(listOf(ficha(5, dni = "30555666"), ficha(6, dni = "30555666"))))
        repo.traerCartera(medico, "clinica")
        repo.traerCartera(medico, "clinica")
        assertEquals(listOf("hc-clinica-p5", "hc-clinica-p6"), textos(driver, "SELECT id FROM paciente ORDER BY id"))
        assertEquals(5L, repo.fichaConocida("hc-clinica-p5", "clinica"))
        assertEquals(null, repo.fichaConocida("hc-clinica-p5", "pediatria"))
    }

    @Test
    fun nada_queda_dirty() = conBase { db, driver ->
        // Un vínculo viejo de pediatría guardado como dirty (antes de la 002) se limpia al traer la cartera.
        db.pacientesQueries.upsertPacienteExtra(
            PacienteExtra("tobb-p77", "pediatria", "hc_paciente_id", "10").toRow(0, dirty = true),
        )
        val repo = repo(db, "pediatria" to ApiFalsa(listOf(ficha(10, tobb = 77), ficha(11))), "clinica" to ApiFalsa(listOf(ficha(20, tobb = 77), ficha(21))))
        repo.traerCartera(medico, "pediatria")
        repo.traerCartera(medico, "clinica")
        assertEquals(0L, contar(driver, "SELECT count(*) FROM paciente WHERE dirty = 1"))
        assertEquals(0L, contar(driver, "SELECT count(*) FROM medico_paciente WHERE dirty = 1"))
        assertEquals(0L, contar(driver, "SELECT count(*) FROM paciente_extra WHERE dirty = 1"))
        assertEquals(4L, contar(driver, "SELECT count(*) FROM paciente_extra"))
    }

    @Test
    fun una_cartera_que_falla_da_su_motivo_y_la_otra_llega() = conBase { db, driver ->
        guardarMedico(db, "pediatria", "clinica")
        val fallo = TobbException(403, "licencia_vencida", "Tu licencia está vencida")
        val pediatria = ApiFalsa(listOf(ficha(1))).apply { this.fallo = fallo }
        val clinica = ApiFalsa(listOf(ficha(2), ficha(3)))
        val r = repo(db, "pediatria" to pediatria, "clinica" to clinica).sincronizarCarteras(medico)

        assertEquals(2, r.traidos)
        assertEquals(listOf(motivoHc("Pediatría", fallo)), r.motivos)
        assertEquals(MotivoHc.Tipo.LICENCIA_VENCIDA, r.motivos.single().tipo)
        assertEquals(2L, contar(driver, "SELECT count(*) FROM paciente"))
    }

    @Test
    fun un_error_que_no_es_de_la_api_tambien_da_motivo() = conBase { db, _ ->
        guardarMedico(db, "clinica")
        val clinica = ApiFalsa().apply { fallo = IllegalStateException("json roto") }
        val r = repo(db, "clinica" to clinica).sincronizarCarteras(medico)
        assertEquals(0, r.traidos)
        assertEquals(MotivoHc.Tipo.OTRO, r.motivos.single().tipo)
        assertTrue(r.motivos.single().texto.startsWith("Historia clínica de Clínica:"), r.motivos.single().texto)
    }

    @Test
    fun la_hc_no_habilitada_no_se_pide() = conBase { db, _ ->
        guardarMedico(db, "clinica")
        val pediatria = ApiFalsa(listOf(ficha(1)))
        val clinica = ApiFalsa(listOf(ficha(2)))
        val r = repo(db, "pediatria" to pediatria, "clinica" to clinica).sincronizarCarteras(medico)
        assertEquals(1, r.traidos)
        assertEquals(emptyList(), pediatria.pedidos)
        assertEquals(1, clinica.pedidos.size)
    }

    @Test
    fun hepatologia_pide_la_cartera_de_clinica() = conBase { db, _ ->
        guardarMedico(db, "hepatologia")
        val clinica = ApiFalsa(listOf(ficha(2)))
        val r = repo(db, "clinica" to clinica).sincronizarCarteras(medico)
        assertEquals(ResultadoCarteras(1, emptyList()), r)
        assertEquals(1, clinica.pedidos.size)
    }

    @Test
    fun medico_que_no_esta_en_el_dispositivo_no_pide_nada() = conBase { db, _ ->
        val clinica = ApiFalsa(listOf(ficha(2)))
        assertEquals(ResultadoCarteras(0, emptyList()), repo(db, "clinica" to clinica).sincronizarCarteras(medico))
        assertEquals(emptyList(), clinica.pedidos)
    }

    private fun contar(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0).value

    private fun texto(driver: SqlDriver, sql: String): String? = textos(driver, sql).firstOrNull()

    private fun textos(driver: SqlDriver, sql: String): List<String> =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(buildList { while (c.next().value) add(c.getString(0).orEmpty()) }) }, 0).value
}
