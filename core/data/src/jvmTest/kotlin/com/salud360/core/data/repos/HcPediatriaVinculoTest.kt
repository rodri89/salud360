package com.salud360.core.data.repos

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.mensajeDocumentoInvalido
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcPacienteCandidato
import com.salud360.core.data.network.hc.HcPacienteFicha
import com.salud360.core.data.network.hc.HcPacienteRequest
import com.salud360.core.data.network.hc.HcResolverRespuesta
import com.salud360.core.data.network.hc.PacientesHcApi
import com.salud360.core.data.network.hc.PaginaCartera
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import com.salud360.core.model.pacientes.Sexo
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Pediatría delega cartera y vínculo en [PacientesHcRepository] (spec 002 E2.6) sin cambiar lo que ve [HcApiSync]:
 * el id de la ficha, null si el paciente no está en el dispositivo, o la [TobbException] del error. Lo único que
 * cambia es que el vínculo ya no queda `dirty` y que un documento inválido no se pide (D5). Sin red: la API de
 * pacientes es falsa.
 */
class HcPediatriaVinculoTest {

    /** `resolver` contesta en orden lo que hay en [respuestas] (una respuesta o un error para lanzar). */
    private class ApiFalsa(val fichas: List<HcPacienteFicha> = emptyList()) : PacientesHcApi {
        val respuestas = ArrayDeque<Any>()
        val resueltos = mutableListOf<HcPacienteRequest>()

        override suspend fun pagina(desde: Int, limite: Int): PaginaCartera {
            val pagina = fichas.drop(desde).take(limite)
            return PaginaCartera(pagina, desde + pagina.size < fichas.size)
        }

        override suspend fun resolver(pedido: HcPacienteRequest, elegida: Long?): HcResolverRespuesta {
            resueltos += pedido
            return when (val r = respuestas.removeFirstOrNull() ?: error("no había respuesta para resolver")) {
                is Throwable -> throw r
                else -> r as HcResolverRespuesta
            }
        }
    }

    private val paciente = Paciente("tobb-p55", "30.111.222", "Salud360", "Prueba")

    private fun ficha(id: Long, tobb: Long = 0) =
        HcPacienteFicha(id = id, pacienteIdTobb = tobb, nombre = "SALUD360 $id", apellido = "PRUEBA", dni = "${30_000_000 + id}")

    private fun conBackend(api: ApiFalsa, bloque: suspend (HcPediatriaBackend, Salud360Db, SqlDriver) -> Unit) {
        val archivo = File.createTempFile("salud360-pediatria", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    val db = Salud360Db(driver)
                    // El cliente HTTP no se usa: lo de pacientes va por la API falsa.
                    bloque(HcPediatriaBackend(db, HcApiClient("http://localhost:1", "pediatria"), "pediatria", api), db, driver)
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    private suspend fun guardarPaciente(db: Salud360Db, p: Paciente = paciente) =
        db.pacientesQueries.upsertPaciente(p.toRow(0, dirty = false))

    @Test
    fun un_vinculo_guardado_no_se_vuelve_a_pedir() {
        val api = ApiFalsa()
        conBackend(api) { backend, db, _ ->
            // Con el sexo cargado no hay nada que completar: el vínculo guardado basta (con el sexo vacío se pregunta
            // una vez por sesión, ver `HcPediatriaSexoDesdeHcTest`).
            guardarPaciente(db, paciente.copy(sexo = Sexo.M))
            db.pacientesQueries.upsertPacienteExtra(PacienteExtra("tobb-p55", "pediatria", "hc_paciente_id", "10").toRow(0, dirty = false))
            assertEquals(10L, backend.resolverPaciente("tobb-p55"))
            assertEquals(emptyList(), api.resueltos)
        }
    }

    @Test
    fun el_paciente_que_no_esta_en_el_dispositivo_da_null_sin_pedir() {
        val api = ApiFalsa()
        conBackend(api) { backend, _, _ ->
            assertEquals(null, backend.resolverPaciente("tobb-p404"))
            assertEquals(emptyList(), api.resueltos)
        }
    }

    @Test
    fun resuelto_queda_guardado_sin_dirty() {
        // La respuesta trae el sexo (como pediatría): se completa la ficha local y no hace falta preguntar de nuevo.
        val api = ApiFalsa().apply { respuestas += HcResolverRespuesta(ficha(20).copy(sexo = "M"), creado = true, vinculadoPor = "nuevo") }
        conBackend(api) { backend, db, driver ->
            guardarPaciente(db)
            assertEquals(20L, backend.resolverPaciente("tobb-p55"))
            assertEquals(55L, api.resueltos.single().pacienteIdTobb)
            assertEquals(listOf("pediatria|hc_paciente_id|20|0"), textos(driver, "SELECT especialidad || '|' || clave || '|' || valor || '|' || dirty FROM paciente_extra"))
            assertEquals(listOf("M"), textos(driver, "SELECT sexo FROM paciente"))
            // Ya guardado: la segunda vez no pide.
            assertEquals(20L, backend.resolverPaciente("tobb-p55"))
            assertEquals(1, api.resueltos.size)
        }
    }

    @Test
    fun un_documento_invalido_no_se_pide_y_lanza_dni_invalido() {
        val api = ApiFalsa()
        conBackend(api) { backend, db, driver ->
            guardarPaciente(db, paciente.copy(dni = "12345678A"))
            val e = assertFailsWith<TobbException> { backend.resolverPaciente("tobb-p55") }
            assertEquals(422, e.status)
            assertEquals("dni_invalido", e.codigo)
            assertEquals(mensajeDocumentoInvalido("12345678A"), e.message)
            assertEquals(emptyList(), api.resueltos)
            assertEquals(emptyList(), textos(driver, "SELECT clave FROM paciente_extra"))
        }
    }

    @Test
    fun los_errores_que_no_vinculan_salen_tal_cual() {
        for (original in listOf(
            TobbException(0, "red", "Sin conexión con la historia clínica de pediatria"),
            TobbException(403, "licencia_vencida", "Tu licencia está vencida"),
        )) {
            val api = ApiFalsa().apply { respuestas += original }
            conBackend(api) { backend, db, driver ->
                guardarPaciente(db)
                assertSame(original, assertFailsWith<TobbException>(original.codigo) { backend.resolverPaciente("tobb-p55") })
                assertEquals(emptyList(), textos(driver, "SELECT clave FROM paciente_extra"), original.codigo)
            }
        }
    }

    @Test
    fun los_errores_del_vinculo_salen_con_el_mismo_status_y_codigo() {
        val candidatas = listOf(HcPacienteCandidato(20, "A", "B", "30111222"), HcPacienteCandidato(21, "A", "B", "30111222"))
        for (original in listOf(
            TobbException(409, "paciente_ambiguo", "Hay más de una ficha con ese documento", candidatos = candidatas),
            TobbException(409, "paciente_ya_vinculado", "La ficha ya está vinculada con otro paciente"),
            TobbException(422, "dni_invalido", "El documento no es válido"),
        )) {
            val api = ApiFalsa().apply { respuestas += original }
            conBackend(api) { backend, db, driver ->
                guardarPaciente(db)
                val e = assertFailsWith<TobbException>(original.codigo) { backend.resolverPaciente("tobb-p55") }
                assertEquals(original.status, e.status, original.codigo)
                assertEquals(original.codigo, e.codigo)
                assertEquals(original.candidatos, e.candidatos, original.codigo)
                assertEquals(emptyList(), textos(driver, "SELECT clave FROM paciente_extra"), original.codigo)
            }
        }
    }

    @Test
    fun el_dni_invalido_de_la_web_conserva_su_mensaje() {
        val api = ApiFalsa().apply { respuestas += TobbException(422, "dni_invalido", "El documento no es válido") }
        conBackend(api) { backend, db, _ ->
            guardarPaciente(db)
            assertEquals("El documento no es válido", assertFailsWith<TobbException> { backend.resolverPaciente("tobb-p55") }.message)
        }
    }

    @Test
    fun un_error_que_no_es_de_la_api_sigue_de_largo() {
        val roto = IllegalStateException("json roto")
        val api = ApiFalsa().apply { respuestas += roto }
        conBackend(api) { backend, db, _ ->
            guardarPaciente(db)
            assertSame(roto, assertFailsWith<IllegalStateException> { backend.resolverPaciente("tobb-p55") })
        }
    }

    @Test
    fun la_cartera_de_pediatria_llega_por_el_repositorio_sin_dirty() {
        val api = ApiFalsa((1L..320L).map { ficha(it, tobb = if (it % 2 == 0L) 1000 + it else 0) })
        conBackend(api) { backend, _, driver ->
            assertEquals(320, backend.traerPacientes("tobb-m1"))
            assertEquals(320, backend.traerPacientes("tobb-m1"))
            assertEquals(320L, contar(driver, "SELECT count(*) FROM paciente"))
            assertEquals(320L, contar(driver, "SELECT count(*) FROM paciente_extra WHERE especialidad = 'pediatria' AND clave = '${HcPediatriaBackend.EXTRA_HC_PACIENTE_ID}' AND dirty = 0"))
            assertEquals(1L, contar(driver, "SELECT count(*) FROM paciente WHERE id = 'hc-pediatria-p1'"))
        }
    }

    private fun contar(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0).value

    private fun textos(driver: SqlDriver, sql: String): List<String> =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(buildList { while (c.next().value) add(c.getString(0).orEmpty()) }) }, 0).value
}
