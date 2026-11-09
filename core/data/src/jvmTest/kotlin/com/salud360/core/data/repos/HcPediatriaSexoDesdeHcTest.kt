package com.salud360.core.data.repos

import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.repos.HcPediatriaBackend.Companion.EXTRA_HC_PACIENTE_ID
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import com.salud360.core.model.pacientes.Sexo
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Al resolver al paciente en pediatría, el sexo que informa la HC completa el de la ficha local si estaba vacío
 * (turnos no tiene sexo, así que toda ficha que viene de turnos nace sin él). Nunca pisa uno ya cargado y no marca la
 * ficha como pendiente de envío: el dato lo tiene la HC.
 *
 * Servidor falso en `127.0.0.1` (nunca el cliente por defecto que apunta a producción). Datos inventados.
 */
class HcPediatriaSexoDesdeHcTest {

    private val paciente = "p-prueba-1"

    // ---- primera vez (sin vínculo): la respuesta de `pacientes/resolver` trae el sexo ----

    @Test
    fun la_hc_dice_f_y_la_ficha_no_tenia_sexo_queda_f_sin_tocar_lo_demas() = conEscena(sexoLocal = null, hc = resolver("F")) { e ->
        val antes = e.ficha()!!
        assertEquals(55L, e.backend.resolverPaciente(paciente))
        val despues = e.ficha()!!
        assertEquals(Sexo.F, despues.sexo)
        assertEquals(antes.copy(sexo = Sexo.F), despues, "solo cambia el sexo")
    }

    @Test
    fun la_ficha_ya_tenia_m_y_la_hc_dice_f_queda_m() = conEscena(sexoLocal = Sexo.M, hc = resolver("F")) { e ->
        assertEquals(55L, e.backend.resolverPaciente(paciente))
        assertEquals(Sexo.M, e.ficha()!!.sexo)
    }

    @Test
    fun la_hc_en_texto_largo_o_en_minuscula_tambien_completa() {
        for ((texto, esperado) in listOf("Femenino" to Sexo.F, "f" to Sexo.F, "masculino" to Sexo.M, " M " to Sexo.M, "Otro" to Sexo.X)) {
            conEscena(sexoLocal = null, hc = resolver(texto)) { e ->
                e.backend.resolverPaciente(paciente)
                assertEquals(esperado, e.ficha()!!.sexo, "«$texto»")
            }
        }
    }

    @Test
    fun sexo_vacio_raro_o_ausente_en_la_hc_no_cambia_nada_y_no_falla() {
        val respuestas = listOf(
            resolver("\"\""),
            resolver("\"   \""),
            resolver("\"quien sabe\""),
            resolver("null"),
            resolver(null), // sin la clave: un servidor viejo
        )
        for (hc in respuestas) {
            conEscena(sexoLocal = null, hc = hc) { e ->
                assertEquals(55L, e.backend.resolverPaciente(paciente))
                assertNull(e.ficha()!!.sexo)
                assertEquals("55", e.idDePediatria())
            }
        }
    }

    @Test
    fun el_cuerpo_que_manda_la_app_sigue_incluyendo_el_sexo_cuando_esta_definido() = conEscena(sexoLocal = Sexo.M, hc = resolver("F")) { e ->
        e.backend.resolverPaciente(paciente)
        val cuerpo = e.servidor.pedidos.single { it.ruta.endsWith("pacientes/resolver") }.cuerpo
        assertContains(cuerpo, "\"sexo\":\"M\"")
    }

    @Test
    fun completar_el_sexo_no_marca_la_ficha_como_pendiente_de_envio() = conEscena(sexoLocal = null, hc = resolver("F"), fichaSucia = false) { e ->
        e.backend.resolverPaciente(paciente)
        assertEquals(Sexo.F, e.ficha()!!.sexo)
        assertFalse(e.fichaSucia(), "el sexo de la HC no es un cambio del médico")
        assertTrue(e.db.pacientesQueries.pacientesDirty().lista { it.id }.isEmpty())
    }

    @Test
    fun una_ficha_que_ya_estaba_pendiente_por_otra_edicion_sigue_pendiente() = conEscena(sexoLocal = null, hc = resolver("F"), fichaSucia = true) { e ->
        e.backend.resolverPaciente(paciente)
        assertEquals(Sexo.F, e.ficha()!!.sexo)
        assertTrue(e.fichaSucia(), "no se le quita lo que el médico todavía no envió")
    }

    @Test
    fun si_pediatria_falla_la_primera_vez_sigue_fallando_como_antes_y_no_toca_la_ficha() =
        conEscena(sexoLocal = null, hc = Respuesta(500, """{"ok":false,"mensaje":"se cayó","codigo":"interno"}""")) { e ->
            assertFailsWith<TobbException> { e.backend.resolverPaciente(paciente) }
            assertNull(e.ficha()!!.sexo)
            assertNull(e.idDePediatria(), "sin respuesta no hay vínculo")
        }

    @Test
    fun un_paciente_que_no_esta_en_el_dispositivo_sigue_dando_null_sin_llamar() = conEscena(sexoLocal = null, hc = resolver("F"), conFicha = false) { e ->
        assertNull(e.backend.resolverPaciente(paciente))
        assertTrue(e.servidor.pedidos.isEmpty())
    }

    // ---- ya vinculado: no hay llamada si ya hay sexo; si falta se pregunta una vez ----

    @Test
    fun ya_vinculado_y_con_sexo_no_llama_a_pediatria() = conEscena(sexoLocal = Sexo.M, hc = resolver("F"), vinculo = "55") { e ->
        assertEquals(55L, e.backend.resolverPaciente(paciente))
        assertTrue(e.servidor.pedidos.isEmpty())
        assertEquals(Sexo.M, e.ficha()!!.sexo)
    }

    @Test
    fun ya_vinculado_y_sin_sexo_lo_completa_con_el_de_pediatria() = conEscena(sexoLocal = null, hc = resolver("F"), vinculo = "55") { e ->
        assertEquals(55L, e.backend.resolverPaciente(paciente))
        assertEquals(Sexo.F, e.ficha()!!.sexo)
        assertFalse(e.fichaSucia())
        assertEquals("55", e.idDePediatria())
    }

    @Test
    fun ya_vinculado_y_sin_sexo_pregunta_una_sola_vez_por_sesion() = conEscena(sexoLocal = null, hc = resolver(null), vinculo = "55") { e ->
        repeat(4) { assertEquals(55L, e.backend.resolverPaciente(paciente)) }
        assertEquals(1, e.servidor.pedidos.size, "si pediatría no tiene el dato no se insiste en cada apertura")
    }

    @Test
    fun ya_vinculado_si_pediatria_no_contesta_devuelve_el_id_conocido_sin_fallar() =
        conEscena(sexoLocal = null, hc = Respuesta(503, "no hay servicio", "text/plain"), vinculo = "55") { e ->
            assertEquals(55L, e.backend.resolverPaciente(paciente))
            assertNull(e.ficha()!!.sexo)
            assertEquals("55", e.idDePediatria())
        }

    @Test
    fun ya_vinculado_el_id_no_cambia_aunque_la_respuesta_traiga_otro() = conEscena(sexoLocal = null, hc = resolver("F", id = 99), vinculo = "55") { e ->
        assertEquals(55L, e.backend.resolverPaciente(paciente))
        assertEquals("55", e.idDePediatria(), "el vínculo guardado manda, como hasta ahora")
        assertEquals(Sexo.F, e.ficha()!!.sexo)
    }

    // ---- armado ----

    private class Respuesta(val status: Int, val cuerpo: String, val tipo: String = "application/json; charset=utf-8")

    /** Respuesta de `pacientes/resolver`; [sexo] es el JSON del valor (con comillas si es texto) o null para omitir la clave. */
    private fun resolver(sexo: String?, id: Int = 55): Respuesta {
        val valor = when {
            sexo == null -> ""
            sexo.startsWith("\"") || sexo == "null" -> ""","sexo":$sexo"""
            else -> ""","sexo":"$sexo""""
        }
        return Respuesta(200, """{"ok":true,"paciente":{"id":$id,"paciente_id_tobb":0,"nombre":"Ana","apellido":"Prueba","dni":"30000001"$valor}}""")
    }

    private class Pedido(val metodo: String, val ruta: String, val cuerpo: String)

    private class Escena(val db: Salud360Db, val backend: HcPediatriaBackend, val servidor: ServidorFalso, private val id: String) {
        fun ficha(): Paciente? = runBlocking { db.pacientesQueries.pacientePorId(id).uno { it.toModel() } }
        fun fichaSucia(): Boolean = runBlocking { db.pacientesQueries.pacientePorId(id).uno { it.dirty } } == 1L
        fun idDePediatria(): String? = runBlocking {
            db.pacientesQueries.extrasDePaciente(id, "pediatria").lista { it.toModel() }.firstOrNull { it.clave == EXTRA_HC_PACIENTE_ID }?.valor
        }
    }

    private fun conEscena(
        sexoLocal: Sexo?,
        hc: Respuesta,
        vinculo: String? = null,
        fichaSucia: Boolean = false,
        conFicha: Boolean = true,
        bloque: suspend (Escena) -> Unit,
    ) {
        val archivo = File.createTempFile("salud360-sexo-desde-hc", ".db").also { it.delete(); it.deleteOnExit() }
        ServidorFalso(hc).use { servidor ->
            runBlocking {
                val db = createDatabase(DriverFactory(archivo.absolutePath))
                if (conFicha) {
                    val ficha = Paciente(
                        id = paciente, dni = "30000001", nombre = "ANA", apellido = "PRUEBA", sexo = sexoLocal,
                        telefono = "1100000000", obraSocial = "OS de prueba", nombrePadre = "Padre de prueba", cantidadHermanos = 2,
                    )
                    db.pacientesQueries.upsertPaciente(ficha.toRow(1_000L, dirty = fichaSucia))
                }
                if (vinculo != null) {
                    db.pacientesQueries.upsertPacienteExtra(PacienteExtra(paciente, "pediatria", EXTRA_HC_PACIENTE_ID, vinculo).toRow(1_000L, dirty = false))
                }
                val api = HcApiClient(servidor.url, "pediatria").also { it.token = "token-de-prueba" }
                bloque(Escena(db, HcPediatriaBackend(db, api), servidor, paciente))
            }
        }
    }

    /** Un `HttpServer` en `127.0.0.1` que contesta siempre [respuesta] y anota método, ruta y cuerpo de cada pedido. */
    private class ServidorFalso(respuesta: Respuesta) : AutoCloseable {
        val pedidos = CopyOnWriteArrayList<Pedido>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        private val hilos = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }

        init {
            server.executor = hilos
            server.createContext("/") { x ->
                try {
                    val cuerpo = x.requestBody.readBytes().toString(Charsets.UTF_8)
                    pedidos += Pedido(x.requestMethod, x.requestURI.path, cuerpo)
                    val bytes = respuesta.cuerpo.toByteArray(Charsets.UTF_8)
                    x.responseHeaders.add("Content-Type", respuesta.tipo)
                    x.sendResponseHeaders(respuesta.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) x.responseBody.use { it.write(bytes) }
                } catch (_: IOException) {
                    // El cliente se fue: no hay a quién contestarle.
                } finally {
                    x.close()
                }
            }
            server.start()
        }

        val url get() = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
            hilos.shutdownNow()
        }
    }
}
