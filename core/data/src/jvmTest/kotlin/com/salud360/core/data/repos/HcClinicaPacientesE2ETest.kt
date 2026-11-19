package com.salud360.core.data.repos

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.ResultadoVinculo
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcPacienteRequest
import com.salud360.core.data.network.hc.HcResolverRespuesta
import com.salud360.core.data.network.hc.PacientesHcApi
import com.salud360.core.data.network.hc.PacientesHcApiRemota
import com.salud360.core.data.network.hc.PacientesHcApis
import com.salud360.core.data.network.hc.PaginaCartera
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.TobbIds
import com.salud360.core.model.pacientes.Paciente
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pacientes de la HC general (spec 002 RF-8, RF-10, RF-12) contra una API de hclinica de verdad: la cartera del
 * médico llega al dispositivo y un paciente nuevo queda vinculado con su ficha, que la web crea recién cuando se pide.
 *
 * **Escribe** en la base de hclinica: crea la ficha del paciente de prueba y su `medico_pacientes`. Por eso pide el
 * documento explícito (uno libre, verificado antes con `SELECT COUNT(*) FROM pacientes WHERE dni = <dni>`) y se
 * saltea sola sin las propiedades. Nunca contra producción.
 *
 * ```
 * ./gradlew :core:data:jvmTest --rerun --tests '*HcClinicaPacientesE2ETest*' \
 *   "-Psalud360.test.hc.clinica=http://localhost/HClinica-salud360/public/index.php" \
 *   "-Psalud360.test.hc.clinica.token=<token de prueba del médico>" \
 *   "-Psalud360.test.hc.dni=<documento libre>" "-Psalud360.test.hc.nombre=PRUEBA" \
 *   "-Psalud360.test.hc.apellido=SALUD360"
 * ```
 *
 * El paciente de turnos es `tobb-p9<dni>` (o `-Psalud360.test.hc.paciente`). Al terminar, en `hc_clinica`:
 *
 * ```
 * DELETE FROM medico_pacientes WHERE paciente_id IN (SELECT id FROM pacientes WHERE dni = <dni>);
 * DELETE FROM pacientes WHERE dni = <dni>;
 * ```
 */
class HcClinicaPacientesE2ETest {

    private val url = System.getProperty("salud360.test.hc.clinica").orEmpty()
    private val token = System.getProperty("salud360.test.hc.clinica.token").orEmpty()
    private val dni = System.getProperty("salud360.test.hc.dni").orEmpty()

    /** La API de verdad, anotando cada `resolver` y lo que contestó (el repositorio no expone `vinculado_por`). */
    private class Grabadora(private val api: PacientesHcApi) : PacientesHcApi {
        val respuestas = mutableListOf<HcResolverRespuesta>()
        var pedidos = 0

        override suspend fun pagina(desde: Int, limite: Int): PaginaCartera = api.pagina(desde, limite)

        override suspend fun resolver(pedido: HcPacienteRequest, elegida: Long?): HcResolverRespuesta {
            pedidos++
            return api.resolver(pedido, elegida).also { respuestas += it }
        }
    }

    @Test
    fun laCarteraLlegaYUnPacienteNuevoQuedaVinculadoConSuFicha() {
        if (url.isBlank() || token.isBlank() || dni.isBlank()) {
            println("HcClinicaPacientesE2ETest: sin -Psalud360.test.hc.clinica / .clinica.token / .dni, no se corre.")
            return
        }
        val medicoId = "tobb-m1"
        val pacienteId = propiedad("salud360.test.hc.paciente", TobbIds.paciente("9$dni".toLong()))
        val paciente = Paciente(
            id = pacienteId, dni = dni,
            nombre = propiedad("salud360.test.hc.nombre", "PRUEBA"),
            apellido = propiedad("salud360.test.hc.apellido", "SALUD360"),
        )

        conBase { db, driver, api ->
            val repo = repo(db, api)

            // --- la cartera, dos veces: es lo que pasa cada vez que se abre la lista ---
            val traidos = repo.traerCartera(medicoId, "clinica")
            assertTrue(traidos > 0, "el médico de prueba tiene pacientes en la HC general")
            val enBase = contar(driver, "SELECT count(*) FROM medico_paciente WHERE medico_id = '$medicoId'")
            assertEquals(traidos.toLong(), enBase, "todos los que llegaron tienen que quedar vinculados al médico")
            assertEquals(0L, contar(driver, "SELECT count(*) FROM paciente WHERE dni = '$dni'"), "el documento de prueba tiene que estar libre")
            assertEquals(traidos, repo.traerCartera(medicoId, "clinica"))
            assertEquals(enBase, contar(driver, "SELECT count(*) FROM medico_paciente WHERE medico_id = '$medicoId'"))
            assertEquals(enBase, contar(driver, "SELECT count(*) FROM paciente_extra WHERE especialidad = 'clinica' AND clave = 'hc_paciente_id'"))
            assertNadaDirty(driver)

            // --- un documento que no sirve no llega a la web (RF-12) ---
            val invalido = Paciente(id = "local-invalido", dni = "12345678A", nombre = paciente.nombre, apellido = paciente.apellido)
            db.pacientesQueries.upsertPaciente(invalido.toRow(0, dirty = false))
            val r0 = repo.vinculo(invalido.id, "clinica")
            assertTrue(r0 is ResultadoVinculo.DocumentoInvalido, r0.toString())
            assertEquals(0, api.pedidos, "con un documento inválido no se pide nada")

            // --- un paciente nuevo: la web crea su ficha recién ahora (RF-10) ---
            db.pacientesQueries.upsertPaciente(paciente.toRow(0, dirty = false))
            val r1 = repo.vinculo(pacienteId, "clinica")
            assertTrue(r1 is ResultadoVinculo.Vinculado, r1.toString())
            val creada = api.respuestas.single()
            assertTrue(creada.creado, "la ficha tenía que crearse")
            assertEquals("nuevo", creada.vinculadoPor)
            assertEquals(creada.paciente.id, r1.fichaId)
            assertEquals(creada.paciente.id, repo.fichaConocida(pacienteId, "clinica"))

            // Otra vez en el mismo dispositivo: sale del vínculo guardado, sin pedido.
            assertEquals(ResultadoVinculo.Vinculado(r1.fichaId), repo.vinculo(pacienteId, "clinica"))
            assertEquals(1, api.pedidos)

            // Y ahora está en la cartera, con el mismo id local: no aparece duplicado.
            assertEquals(traidos + 1, repo.traerCartera(medicoId, "clinica"))
            assertEquals(1L, contar(driver, "SELECT count(*) FROM paciente WHERE dni = '$dni'"))
            assertEquals(1L, contar(driver, "SELECT count(*) FROM medico_paciente WHERE medico_id = '$medicoId' AND paciente_id = '$pacienteId'"))
            assertEquals(r1.fichaId, repo.fichaConocida(pacienteId, "clinica"))
            assertNadaDirty(driver)

            // --- otro dispositivo, sin nada guardado: la web lo encuentra por el paciente de turnos ---
            conBase { db2, driver2, api2 ->
                db2.pacientesQueries.upsertPaciente(paciente.toRow(0, dirty = false))
                val r2 = repo(db2, api2).vinculo(pacienteId, "clinica")
                assertEquals(ResultadoVinculo.Vinculado(r1.fichaId), r2)
                val otra = api2.respuestas.single()
                assertEquals(false, otra.creado)
                assertEquals("tobb_id", otra.vinculadoPor)
                assertNadaDirty(driver2)
            }
        }
    }

    private fun repo(db: Salud360Db, api: PacientesHcApi) =
        PacientesHcRepository(db, PacientesHcApis(mapOf("clinica" to api)), mapOf("clinica" to "Clínica"))

    /** Una base SQLite nueva en un archivo temporal (un dispositivo) y la API de verdad con el token. */
    private fun conBase(bloque: suspend (Salud360Db, SqlDriver, Grabadora) -> Unit) {
        val archivo = File.createTempFile("salud360-clinica", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    val cliente = HcApiClient(url, "clinica").also { it.token = token }
                    bloque(Salud360Db(driver), driver, Grabadora(PacientesHcApiRemota(cliente, "hc_paciente_id")))
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    /** El dueño de cartera y vínculo es la web: nada queda pendiente de enviar. */
    private fun assertNadaDirty(driver: SqlDriver) {
        assertEquals(0L, contar(driver, "SELECT count(*) FROM paciente WHERE dirty = 1"))
        assertEquals(0L, contar(driver, "SELECT count(*) FROM medico_paciente WHERE dirty = 1"))
        assertEquals(0L, contar(driver, "SELECT count(*) FROM paciente_extra WHERE dirty = 1"))
    }

    private fun contar(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0).value

    private fun propiedad(clave: String, porDefecto: String) =
        System.getProperty(clave).orEmpty().ifBlank { porDefecto }
}
