package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 004, T3b (RF-22): el error de la API de HC conserva el cuerpo JSON de la respuesta en `TobbException.cuerpo`, **sin
 * cambiar** el estado, el código ni el mensaje de ningún error que ya existía. Un servidor falso en `127.0.0.1` contesta lo
 * que dice cada caso; no hay red real ni datos reales (la fecha del cuerpo es inventada).
 */
class HcApiClientErrorTest {
    private val fecha = "2031-06-15"

    // ---- Lo que ya existía no cambia: estado, código y mensaje ----

    @Test
    fun el_403_licencia_vencida_conserva_estado_codigo_y_mensaje() {
        val e = fallo(403, """{"ok":false,"mensaje":"Tu licencia está vencida.","codigo":"licencia_vencida","licencia":{"vence":"$fecha","vencida":true}}""")
        assertEquals(403, e.status)
        assertEquals("licencia_vencida", e.codigo)
        assertEquals("Tu licencia está vencida.", e.message)
    }

    @Test
    fun el_401_token_conserva_estado_codigo_y_mensaje() {
        val e = fallo(401, """{"ok":false,"mensaje":"Sesión vencida. Volvé a iniciar sesión.","codigo":"token"}""")
        assertEquals(401, e.status)
        assertEquals("token", e.codigo)
        assertEquals("Sesión vencida. Volvé a iniciar sesión.", e.message)
    }

    @Test
    fun un_500_sin_json_conserva_estado_y_usa_el_texto_como_mensaje() {
        val e = fallo(500, "<html>Error</html>", "text/html")
        assertEquals(500, e.status)
        assertNull(e.codigo)
        assertEquals("<html>Error</html>", e.message)
    }

    @Test
    fun un_200_con_ok_falso_conserva_estado_codigo_y_mensaje() {
        val e = fallo(200, """{"ok":false,"mensaje":"no se pudo","codigo":"datos"}""")
        assertEquals(200, e.status)
        assertEquals("datos", e.codigo)
        assertEquals("no se pudo", e.message)
    }

    @Test
    fun el_mensaje_de_laravel_sin_codigo_se_sigue_leyendo_de_message() {
        val e = fallo(429, """{"message":"Too Many Attempts."}""")
        assertEquals(429, e.status)
        assertNull(e.codigo)
        assertEquals("Too Many Attempts.", e.message)
    }

    @Test
    fun un_cuerpo_vacio_conserva_el_mensaje_generico() {
        val e = fallo(403, "")
        assertEquals(403, e.status)
        assertNull(e.codigo)
        assertEquals("La historia clínica de pediatria respondió 403", e.message)
    }

    @Test
    fun un_200_que_no_es_json_sigue_siendo_un_error() {
        val e = fallo(200, "hola, esto no es json", "text/plain")
        assertEquals(200, e.status)
        assertNull(e.codigo)
        assertEquals("hola, esto no es json", e.message)
    }

    @Test
    fun la_red_caida_sigue_siendo_codigo_red_status_cero() = runBlocking {
        val puertoCerrado = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val e = assertFailsWith<TobbException> { HcApiClient("http://127.0.0.1:$puertoCerrado", "pediatria").get("auth/perfil") }
        assertEquals(0, e.status)
        assertEquals("red", e.codigo)
        assertTrue(e.message.orEmpty().startsWith("Sin conexión con la historia clínica de pediatria: "))
        assertNull(e.cuerpo, "no hubo respuesta: no hay cuerpo")
    }

    @Test
    fun una_respuesta_correcta_se_devuelve_igual() = runBlocking {
        val r = conServidor(200, """{"ok":true,"perfil":{"rol":"medico"}}""") { HcApiClient(it, "pediatria").get("auth/perfil") }
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertEquals("medico", r["perfil"]!!.jsonObject["rol"]!!.jsonPrimitive.content)
    }

    // ---- El cuerpo ----

    @Test
    fun cuando_el_cuerpo_era_json_la_excepcion_trae_el_objeto() {
        val e = fallo(403, """{"ok":false,"mensaje":"vencida","codigo":"licencia_vencida","licencia":{"vence":"$fecha","vencida":true}}""")
        val cuerpo = assertNotNull(e.cuerpo)
        assertEquals("licencia_vencida", cuerpo["codigo"]!!.jsonPrimitive.content)
        val licencia = cuerpo["licencia"]!!.jsonObject
        assertEquals(fecha, licencia["vence"]!!.jsonPrimitive.content)
        assertEquals("true", licencia["vencida"]!!.jsonPrimitive.content)
    }

    @Test
    fun una_licencia_nula_en_el_cuerpo_se_distingue_de_una_licencia_ausente() {
        val nula = assertNotNull(fallo(403, """{"ok":false,"mensaje":"m","codigo":"licencia_vencida","licencia":null}""").cuerpo)
        assertTrue(nula.containsKey("licencia"), "la clave está")
        assertEquals(JsonNull, nula["licencia"], "y es nula de verdad")

        val ausente = assertNotNull(fallo(403, """{"ok":false,"mensaje":"m","codigo":"licencia_vencida"}""").cuerpo)
        assertFalse(ausente.containsKey("licencia"), "la clave no está")
    }

    @Test
    fun el_cuerpo_tambien_llega_en_un_200_con_ok_falso_y_en_un_401() {
        assertNotNull(fallo(200, """{"ok":false,"mensaje":"m","codigo":"datos"}""").cuerpo)
        assertNotNull(fallo(401, """{"ok":false,"mensaje":"m","codigo":"token"}""").cuerpo)
    }

    @Test
    fun cuando_el_cuerpo_no_era_json_el_cuerpo_es_null() {
        assertNull(fallo(500, "<html>Error</html>", "text/html").cuerpo)
        assertNull(fallo(403, "").cuerpo)
        assertNull(fallo(200, "hola, esto no es json", "text/plain").cuerpo)
    }

    @Test
    fun un_json_que_no_es_un_objeto_no_es_un_cuerpo() {
        assertNull(fallo(500, """["no","es","un","objeto"]""").cuerpo)
        assertNull(fallo(500, "null").cuerpo)
    }

    @Test
    fun el_cuerpo_no_se_cuela_en_el_mensaje_ni_en_el_texto_de_la_excepcion() {
        // Las fechas y demás datos del cuerpo no tienen que aparecer donde alguien pueda registrarlos por casualidad.
        val e = fallo(403, """{"ok":false,"mensaje":"vencida","codigo":"licencia_vencida","licencia":{"vence":"$fecha","vencida":true}}""")
        assertEquals("vencida", e.message)
        assertFalse(e.toString().contains(fecha), e.toString())
        assertFalse(e.message.orEmpty().contains(fecha))
    }

    // ---- La forma de construir TobbException no cambia ----

    @Test
    fun una_tobb_exception_armada_con_tres_argumentos_no_tiene_cuerpo() {
        val e = TobbException(409, "ocupado", "El turno está ocupado")
        assertEquals(409, e.status)
        assertEquals("ocupado", e.codigo)
        assertEquals("El turno está ocupado", e.message)
        assertNull(e.cuerpo)
    }

    @Test
    fun el_cuarto_argumento_es_opcional_y_se_puede_pasar_por_nombre() {
        val cuerpo = JsonObject(mapOf("licencia" to JsonNull))
        val e = TobbException(403, "licencia_vencida", "m", cuerpo = cuerpo)
        assertEquals(cuerpo, e.cuerpo)
        assertEquals(cuerpo, TobbException(403, "licencia_vencida", "m", cuerpo).cuerpo)
    }

    // ---- Armado ----

    private fun fallo(status: Int, cuerpo: String, tipo: String = "application/json; charset=utf-8"): TobbException =
        conServidor(status, cuerpo, tipo) { url ->
            assertFailsWith<TobbException> { HcApiClient(url, "pediatria").get("auth/perfil") }
        }

    /** Un `HttpServer` en `127.0.0.1` que contesta siempre lo mismo; se apaga al terminar el bloque. */
    private fun <T> conServidor(status: Int, cuerpo: String, tipo: String = "application/json; charset=utf-8", bloque: suspend (String) -> T): T {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val hilos = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
        server.executor = hilos
        server.createContext("/") { x ->
            try {
                val bytes = cuerpo.toByteArray(Charsets.UTF_8)
                x.responseHeaders.add("Content-Type", tipo)
                x.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) x.responseBody.use { it.write(bytes) }
            } catch (_: IOException) {
                // El cliente se fue: no hay a quién contestarle.
            } finally {
                x.close()
            }
        }
        server.start()
        try {
            return runBlocking { bloque("http://127.0.0.1:${server.address.port}") }
        } finally {
            server.stop(0)
            hilos.shutdownNow()
        }
    }
}
