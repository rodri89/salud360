package com.salud360.core.data.repos

import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.createDatabase
import com.salud360.core.model.Id
import com.salud360.core.model.auth.AvisoLicencia
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.RegistroClinico
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Spec 004, T2: el backend de pediatría informa cómo está la licencia del médico, leyendo `auth/perfil` (que ya
 * trae la `licencia`) y el `403 licencia_vencida`. Un servidor falso en `127.0.0.1` contesta lo que dice cada caso;
 * no hay red real ni datos reales (ids y fechas inventados, las fechas relativas a `hoy()`).
 *
 * En esta tarea todo `licencia_vencida` es `Vencida`; separar el caso `licencia: null` (`SinLicencia`) es de T3b.
 */
class HcPediatriaLicenciaServidorTest {
    private val ayer = hoy().minus(DatePeriod(days = 1)).toString()
    private val dentroDeDias = hoy().plus(DatePeriod(days = 10)).toString()
    private val lejos = hoy().plus(DatePeriod(years = 1)).toString()
    private val avisoYa = hoy().minus(DatePeriod(days = 10)).toString()

    // ---- 200: Vigente o sin dato ----

    @Test
    fun un_perfil_con_licencia_es_vigente_con_sus_fechas() {
        val estado = estadoPara(perfil(licencia = """{"vence":"$lejos","aviso_desde":"$avisoYa","por_vencer":false}"""))
        assertEquals(EstadoLicenciaRemoto.Vigente(vence = lejos, avisoDesde = avisoYa, porVencer = false), estado)
    }

    @Test
    fun por_vencer_se_lee_tal_cual() {
        val estado = estadoPara(perfil(licencia = """{"vence":"$dentroDeDias","aviso_desde":"$ayer","por_vencer":true}"""))
        assertEquals(EstadoLicenciaRemoto.Vigente(vence = dentroDeDias, avisoDesde = ayer, porVencer = true), estado)
    }

    @Test
    fun un_perfil_sin_licencia_es_sin_dato() {
        // El administrador, o el servidor en su gracia cuando turnos no le contesta: no hay nada que decidir.
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(perfil(licencia = "null")))
    }

    @Test
    fun un_perfil_sin_la_clave_licencia_es_sin_dato() {
        // Un servidor que todavía no informa la licencia (RF-19).
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(200, """{"ok":true,"perfil":{"rol":"medico"}}""")))
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(200, """{"ok":true}""")))
    }

    @Test
    fun una_licencia_con_el_vencimiento_vacio_es_sin_dato() {
        assertEquals(
            EstadoLicenciaRemoto.SinDato,
            estadoPara(perfil(licencia = """{"vence":"","aviso_desde":"$avisoYa","por_vencer":false}""")),
        )
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(perfil(licencia = """{"por_vencer":true}""")))
    }

    @Test
    fun una_licencia_que_no_se_puede_leer_es_sin_dato() {
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(perfil(licencia = """["no","es","un","objeto"]""")))
    }

    // ---- 403 licencia_vencida ----

    @Test
    fun licencia_vencida_con_la_licencia_en_el_cuerpo_es_vencida() {
        val cuerpo = rechazo("licencia_vencida", """"licencia":{"vence":"$ayer","vencida":true}""")
        assertEquals(EstadoLicenciaRemoto.Vencida, estadoPara(Respuesta(403, cuerpo)))
    }

    /**
     * Hoy todo `licencia_vencida` es `Vencida`, también el de `licencia: null` (sin fila). T3b conserva el cuerpo del
     * error y separa ese caso en `SinLicencia`: entonces este test pasa a pedir `SinLicencia` para `licencia: null`.
     */
    @Test
    fun en_esta_tarea_licencia_vencida_sin_licencia_en_el_cuerpo_tambien_es_vencida() {
        assertEquals(
            EstadoLicenciaRemoto.Vencida,
            estadoPara(Respuesta(403, rechazo("licencia_vencida", """"licencia":null"""))),
        )
        assertEquals(EstadoLicenciaRemoto.Vencida, estadoPara(Respuesta(403, rechazo("licencia_vencida"))))
    }

    // ---- Sin respuesta concluyente ----

    @Test
    fun los_otros_rechazos_403_son_sin_dato() {
        for (codigo in listOf("hc_no_habilitada", "medico_no_vinculado", "sin_permiso")) {
            assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(403, rechazo(codigo))), codigo)
        }
    }

    @Test
    fun un_403_sin_codigo_es_sin_dato() {
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(403, "<html>Forbidden</html>", "text/html")))
        assertEquals(
            EstadoLicenciaRemoto.SinDato,
            estadoPara(Respuesta(403, """{"ok":false,"mensaje":"no"}""")),
        )
    }

    @Test
    fun la_sesion_vencida_401_es_sin_dato() {
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(401, rechazo("token"))))
    }

    @Test
    fun demasiados_pedidos_429_es_sin_dato() {
        assertEquals(
            EstadoLicenciaRemoto.SinDato,
            estadoPara(Respuesta(429, """{"message":"Too Many Attempts."}""")),
        )
    }

    @Test
    fun un_error_del_servidor_500_es_sin_dato() {
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(500, "<html>Error</html>", "text/html")))
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(500, """{"ok":false,"mensaje":"falló"}""")))
    }

    @Test
    fun turnos_caido_503_es_sin_dato() {
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(503, rechazo("tobb_caido"))))
    }

    @Test
    fun un_cuerpo_que_no_es_json_es_sin_dato() {
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(200, "hola, esto no es json", "text/plain")))
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(200, "", "text/plain")))
    }

    @Test
    fun un_200_con_ok_falso_es_sin_dato() {
        assertEquals(EstadoLicenciaRemoto.SinDato, estadoPara(Respuesta(200, rechazo("algo"))))
    }

    @Test
    fun un_puerto_cerrado_es_sin_dato() = runBlocking {
        val puertoCerrado = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        withBackend("http://127.0.0.1:$puertoCerrado") { backend ->
            assertEquals(EstadoLicenciaRemoto.SinDato, backend.licenciaDelServidor())
            assertNull(backend.avisoDeLicencia())
        }
    }

    // ---- Cómo se pide ----

    @Test
    fun cada_lectura_es_un_solo_get_a_auth_perfil_con_el_token_y_sin_reintentos() {
        val casos = listOf(
            perfil(licencia = """{"vence":"$lejos","aviso_desde":"$avisoYa","por_vencer":false}"""),
            Respuesta(403, rechazo("licencia_vencida", """"licencia":{"vence":"$ayer","vencida":true}""")),
            Respuesta(429, """{"message":"Too Many Attempts."}"""),
            Respuesta(500, "<html>Error</html>", "text/html"),
            Respuesta(503, rechazo("tobb_caido")),
        )
        for (caso in casos) {
            conServidor(caso) { servidor ->
                runBlocking {
                    withBackend(servidor.url) { backend ->
                        backend.licenciaDelServidor()
                        assertEquals(1, servidor.pedidos.get(), "un solo pedido por lectura, sin reintento (${caso.status})")
                        assertEquals(listOf("GET /api/salud360/auth/perfil"), servidor.lineas.toList(), "solo lectura")
                        assertEquals(listOf("Bearer token-de-prueba"), servidor.autorizaciones.toList())
                    }
                }
            }
        }
    }

    @Test
    fun la_cancelacion_no_se_traga() {
        val retener = CountDownLatch(1)
        conServidor(perfil(licencia = """{"vence":"$lejos","aviso_desde":"$avisoYa","por_vencer":false}"""), retener) { servidor ->
            runBlocking {
                withBackend(servidor.url) { backend ->
                    var devolvio = false
                    val trabajo = launch(start = CoroutineStart.UNDISPATCHED) {
                        backend.licenciaDelServidor()
                        devolvio = true
                    }
                    // Esperando la respuesta: el servidor ya recibió el pedido y lo retiene.
                    withTimeout(10_000) { while (servidor.pedidos.get() == 0) delay(10) }
                    trabajo.cancelAndJoin()
                    assertFalse(devolvio, "una corrutina cancelada mientras espera no puede volver con un resultado")
                    assertEquals(true, trabajo.isCancelled)
                }
            }
            retener.countDown()
        }
    }

    @Test
    fun la_cancelacion_tampoco_se_traga_en_el_aviso() {
        val retener = CountDownLatch(1)
        conServidor(perfil(licencia = """{"vence":"$lejos","aviso_desde":"$avisoYa","por_vencer":true}"""), retener) { servidor ->
            runBlocking {
                withBackend(servidor.url) { backend ->
                    var devolvio = false
                    val trabajo = launch(start = CoroutineStart.UNDISPATCHED) {
                        backend.avisoDeLicencia()
                        devolvio = true
                    }
                    withTimeout(10_000) { while (servidor.pedidos.get() == 0) delay(10) }
                    trabajo.cancelAndJoin()
                    assertFalse(devolvio, "una corrutina cancelada mientras espera no puede volver con un resultado")
                }
            }
            retener.countDown()
        }
    }

    // ---- avisoDeLicencia: mismo resultado que antes de leer por la misma vía ----

    @Test
    fun el_aviso_con_por_vencer_trae_la_fecha() {
        val aviso = avisoPara(perfil(licencia = """{"vence":"$dentroDeDias","aviso_desde":"$ayer","por_vencer":true}"""))
        assertEquals(AvisoLicencia("pediatria", dentroDeDias), aviso)
    }

    @Test
    fun el_aviso_sin_por_vencer_es_null() {
        assertNull(avisoPara(perfil(licencia = """{"vence":"$lejos","aviso_desde":"$lejos","por_vencer":false}""")))
    }

    @Test
    fun el_aviso_sin_licencia_o_con_vencimiento_vacio_es_null() {
        assertNull(avisoPara(perfil(licencia = "null")))
        assertNull(avisoPara(Respuesta(200, """{"ok":true,"perfil":{}}""")))
        assertNull(avisoPara(perfil(licencia = """{"vence":"","aviso_desde":"","por_vencer":true}""")))
    }

    @Test
    fun el_aviso_ante_cualquier_fallo_es_null() {
        assertNull(avisoPara(Respuesta(403, rechazo("licencia_vencida", """"licencia":{"vence":"$ayer","vencida":true}"""))))
        assertNull(avisoPara(Respuesta(403, rechazo("hc_no_habilitada"))))
        assertNull(avisoPara(Respuesta(401, rechazo("token"))))
        assertNull(avisoPara(Respuesta(429, """{"message":"Too Many Attempts."}""")))
        assertNull(avisoPara(Respuesta(500, "<html>Error</html>", "text/html")))
        assertNull(avisoPara(Respuesta(503, rechazo("tobb_caido"))))
        assertNull(avisoPara(Respuesta(200, "no es json", "text/plain")))
    }

    // ---- Las HC sin licencia no molestan ----

    @Test
    fun una_historia_clinica_sin_licencia_contesta_sin_dato_por_defecto() = runBlocking {
        assertEquals(EstadoLicenciaRemoto.SinDato, HcSinLicencia.licenciaDelServidor())
        assertNull(HcSinLicencia.avisoDeLicencia())
    }

    // ---- Armado ----

    private class Respuesta(val status: Int, val cuerpo: String, val tipo: String = "application/json; charset=utf-8")

    private fun perfil(licencia: String) = Respuesta(200, """{"ok":true,"perfil":{"rol":"medico","licencia":$licencia}}""")

    private fun rechazo(codigo: String, extra: String? = null) =
        """{"ok":false,"mensaje":"rechazo de prueba","codigo":"$codigo"${extra?.let { ",$it" }.orEmpty()}}"""

    private fun estadoPara(respuesta: Respuesta): EstadoLicenciaRemoto = conServidor(respuesta) { servidor ->
        runBlocking { withBackend(servidor.url) { it.licenciaDelServidor() } }
    }

    private fun avisoPara(respuesta: Respuesta): AvisoLicencia? = conServidor(respuesta) { servidor ->
        runBlocking { withBackend(servidor.url) { it.avisoDeLicencia() } }
    }

    private suspend fun <T> withBackend(url: String, bloque: suspend (HcPediatriaBackend) -> T): T {
        val archivo = File.createTempFile("salud360-licencia-servidor", ".db").also { it.delete(); it.deleteOnExit() }
        val db = createDatabase(DriverFactory(archivo.absolutePath))
        val api = HcApiClient(url, "pediatria").also { it.token = "token-de-prueba" }
        return bloque(HcPediatriaBackend(db, api))
    }

    private fun <T> conServidor(respuesta: Respuesta, retener: CountDownLatch? = null, bloque: (ServidorFalso) -> T): T {
        val servidor = ServidorFalso(respuesta, retener)
        try {
            return bloque(servidor)
        } finally {
            retener?.countDown()
            servidor.close()
        }
    }

    /** Un `HttpServer` en `127.0.0.1` que contesta siempre [respuesta] y anota qué le pidieron. */
    private class ServidorFalso(respuesta: Respuesta, retener: CountDownLatch?) : AutoCloseable {
        val pedidos = AtomicInteger()
        val lineas = CopyOnWriteArrayList<String>()
        val autorizaciones = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        private val hilos = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }

        init {
            server.executor = hilos
            server.createContext("/") { x ->
                try {
                    lineas += "${x.requestMethod} ${x.requestURI.path}"
                    x.requestHeaders.getFirst("Authorization")?.let { autorizaciones += it }
                    pedidos.incrementAndGet()
                    retener?.await(30, TimeUnit.SECONDS)
                    val bytes = respuesta.cuerpo.toByteArray(Charsets.UTF_8)
                    x.responseHeaders.add("Content-Type", respuesta.tipo)
                    x.sendResponseHeaders(respuesta.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) x.responseBody.use { it.write(bytes) }
                } catch (_: IOException) {
                    // El cliente se fue (cancelación del test): no hay a quién contestarle.
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

    /** Una HC cualquiera que no lleva licencia: no sobreescribe nada de lo de licencias. */
    private object HcSinLicencia : HcBackend {
        override val especialidad = "sin-licencia"
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
}
