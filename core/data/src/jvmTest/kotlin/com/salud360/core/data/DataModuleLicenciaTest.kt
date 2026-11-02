package com.salud360.core.data

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.repos.AuthRepository
import com.salud360.core.data.repos.HcBackends
import com.salud360.core.data.repos.ResultadoLogin
import com.salud360.core.data.repos.hoy
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.auth.Credenciales
import com.salud360.core.model.auth.Licencia
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Spec 004, T6: el módulo de Koin real le pasa al `AuthRepository` los backends de las HC con licencia, y el
 * `login`, `restaurar` y `refrescarPerfil` del repositorio que arma Koin consultan al backend de verdad (pediatría) por red.
 *
 * Tres servidores falsos en `127.0.0.1` (turnos, pediatría y una HC "clinica" que no lleva licencia en la app), SQLite
 * temporal y las preferencias en un nodo propio de `java.util.prefs` que se borra al terminar (no se toca el `Settings()`
 * real de la máquina). Ids, tokens y fechas inventados, las fechas relativas a `hoy()`.
 */
class DataModuleLicenciaTest {
    private val ayer = hoy().minus(DatePeriod(days = 1)).toString()
    private val lejos = hoy().plus(DatePeriod(years = 1)).toString()
    private val avisoYa = hoy().minus(DatePeriod(days = 10)).toString()

    private val vigente = """{"ok":true,"perfil":{"rol":"medico","licencia":{"vence":"$lejos","aviso_desde":"$avisoYa","por_vencer":false}}}"""
    private val vencida = """{"ok":false,"mensaje":"rechazo de prueba","codigo":"licencia_vencida","licencia":{"vence":"$ayer","vencida":true}}"""

    @Test
    fun el_modulo_registra_solo_pediatria_como_backend_con_licencia_y_no_pide_nada_al_armarse() = runBlocking {
        escena { e ->
            val app = e.koin()
            assertEquals(setOf("pediatria"), app.get<HcBackends>().porEspecialidad.keys, "ni clínica ni ninguna otra HC")
            assertNotNull(app.get<AuthRepository>())
            assertEquals(0, e.turnos.pedidos.get() + e.pediatria.pedidos.get() + e.clinica.pedidos.get(), "armar el grafo no pide nada a la red")
        }
    }

    @Test
    fun login_por_koin_consulta_a_pediatria_con_el_token_nuevo_y_no_a_clinica() = runBlocking {
        escena(historias = listOf("pediatria", "clinica")) { e ->
            e.pediatria.cuerpo = vigente
            // El caso reportado: la copia del dispositivo quedó vieja.
            e.copia(vence = ayer, activo = false)

            val r = e.koin().get<AuthRepository>().login(CREDENCIALES)

            val ok = assertIs<ResultadoLogin.Ok>(r)
            assertEquals(lejos, ok.sesion.medico?.licenciaVence)
            assertEquals(listOf("Bearer tok-login"), e.pediatria.autorizaciones.toList(), "un solo pedido, con el token recién emitido")
            assertEquals(0, e.clinica.pedidos.get(), "la HC sin licencia no se consulta")
            assertEquals(Licencia(MEDICO_ID, lejos, avisoYa, importe = 0.0, activo = true), e.copiaLocal())
            assertTrue(e.db.authQueries.licenciasDirty().lista { it.medico_id }.isEmpty())
        }
    }

    @Test
    fun login_por_koin_con_el_servidor_vencida_no_deja_entrar() = runBlocking {
        escena { e ->
            e.pediatria.estado = 403
            e.pediatria.cuerpo = vencida
            e.copia(vence = lejos)

            val repo = e.koin().get<AuthRepository>()
            assertIs<ResultadoLogin.LicenciaVencida>(repo.login(CREDENCIALES))

            assertNull(repo.sesion.value)
            assertNull(e.sesionGuardada())
            assertEquals(1, e.pediatria.pedidos.get())
            assertEquals(false, e.copiaLocal()?.activo)
        }
    }

    @Test
    fun el_medico_sin_pediatria_no_consulta_nada_por_koin() = runBlocking {
        escena(historias = listOf("clinica")) { e ->
            e.pediatria.cuerpo = vencida
            e.pediatria.estado = 403

            assertIs<ResultadoLogin.Ok>(e.koin().get<AuthRepository>().login(CREDENCIALES))

            assertEquals(0, e.pediatria.pedidos.get())
            assertEquals(0, e.clinica.pedidos.get())
        }
    }

    @Test
    fun refrescarPerfil_por_koin_justo_despues_de_entrar_reutiliza_la_respuesta() = runBlocking {
        escena { e ->
            e.pediatria.cuerpo = vigente
            val repo = e.koin().get<AuthRepository>()
            assertIs<ResultadoLogin.Ok>(repo.login(CREDENCIALES))
            // Reloj del dispositivo corrido: la copia dice que venció ayer.
            e.copia(vence = ayer)

            repo.refrescarPerfil()

            assertEquals(1, e.pediatria.pedidos.get(), "el refresco no repite el pedido del ingreso")
            assertNotNull(repo.sesion.value, "la sesión sigue abierta")
            assertNotNull(e.sesionGuardada())
        }
    }

    @Test
    fun restaurar_por_koin_con_el_servidor_vigente_y_la_copia_vencida_entra() = runBlocking {
        escena { e ->
            e.pediatria.cuerpo = vigente
            // Sesión guardada de un ingreso anterior (otro `AuthRepository`, como al recargar la página).
            assertIs<ResultadoLogin.Ok>(e.koin().get<AuthRepository>().login(CREDENCIALES))
            e.copia(vence = ayer, activo = false)
            e.pediatria.autorizaciones.clear()

            val s = e.koin().get<AuthRepository>().restaurar()

            assertNotNull(s)
            assertEquals(listOf("Bearer tok-login"), e.pediatria.autorizaciones.toList(), "un pedido, con el token de la sesión guardada")
            assertEquals(Licencia(MEDICO_ID, lejos, avisoYa, importe = 0.0, activo = true), e.copiaLocal())
        }
    }

    @Test
    fun restaurar_por_koin_con_el_servidor_vencida_cierra_y_borra_la_sesion_guardada() = runBlocking {
        escena { e ->
            e.pediatria.cuerpo = vigente
            assertIs<ResultadoLogin.Ok>(e.koin().get<AuthRepository>().login(CREDENCIALES))
            e.pediatria.estado = 403
            e.pediatria.cuerpo = vencida
            e.pediatria.autorizaciones.clear()

            val repo = e.koin().get<AuthRepository>()
            assertNull(repo.restaurar())

            assertNull(repo.sesion.value)
            assertNull(e.sesionGuardada())
            assertEquals(1, e.pediatria.autorizaciones.size)
            assertEquals(false, e.copiaLocal()?.activo)
        }
    }

    // ---- Armado ----

    private class Servidor : AutoCloseable {
        val pedidos = AtomicInteger()
        val autorizaciones = CopyOnWriteArrayList<String>()
        @Volatile var estado = 200
        @Volatile var cuerpo = """{"ok":true}"""
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val url: String get() = "http://127.0.0.1:${server.address.port}"

        fun responder(x: HttpExchange, estado: Int, texto: String) {
            val bytes = texto.toByteArray()
            x.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            x.sendResponseHeaders(estado, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }

        fun atender(ruta: String = "/", alPedir: (HttpExchange) -> Unit = { responder(it, estado, cuerpo) }) {
            server.createContext(ruta) { x ->
                pedidos.incrementAndGet()
                x.getRequestHeaders().getFirst("Authorization")?.let { autorizaciones += it }
                x.requestBody.readBytes()
                alPedir(x)
            }
        }

        fun iniciar() = server.start()
        override fun close() = server.stop(0)
    }

    private inner class Escena(
        val db: Salud360Db,
        val config: AppConfig,
        val settings: Settings,
        val turnos: Servidor,
        val pediatria: Servidor,
        val clinica: Servidor,
    ) {
        private val apps = mutableListOf<org.koin.core.KoinApplication>()

        /** Un grafo de Koin nuevo sobre la misma base (como una app recién abierta): `AuthRepository` sin memoria. */
        fun koin(): Koin {
            val app = koinApplication { modules(dataModule(db, config), module { single<Settings> { settings } }) }
            apps += app
            return app.koin
        }

        fun cerrar() = apps.forEach { it.close() }

        suspend fun copia(vence: String, activo: Boolean = true) {
            db.authQueries.upsertLicencia(Licencia(MEDICO_ID, vence, avisoYa, 0.0, activo).toRow(1L, dirty = false))
        }

        suspend fun copiaLocal(): Licencia? = db.authQueries.licenciaPorMedico(MEDICO_ID).uno { it.toModel() }
        suspend fun sesionGuardada() = db.authQueries.sesionActual().uno { it }
    }

    private suspend fun escena(historias: List<String> = listOf("pediatria"), bloque: suspend (Escena) -> Unit) {
        val archivo = File.createTempFile("salud360-koin-licencia", ".db").also { it.delete(); it.deleteOnExit() }
        val db = createDatabase(DriverFactory(archivo.absolutePath))
        val nodo = Preferences.userRoot().node("salud360-test-${UUID.randomUUID()}")
        val turnos = Servidor()
        val pediatria = Servidor()
        val clinica = Servidor()
        val perfilMedico = """
            {"usuario":{"id":12,"nombre":"Medico","email":"medico@ejemplo.test","tipo":2},"rol":"medico",
            "medico":{"id":7,"nombre":"Medico","apellido":"Prueba","consultorio_id":0,
            "historias_clinicas":[${historias.joinToString(",") { "\"$it\"" }}]}}
        """.trimIndent()
        turnos.atender("/") { turnos.responder(it, 200, """{"ok":true,"perfil":$perfilMedico}""") }
        turnos.atender("/api/salud360/auth/login") {
            turnos.responder(it, 200, """{"ok":true,"access_token":"tok-login","expires_at":null,"perfil":$perfilMedico}""")
        }
        pediatria.atender("/api/salud360/auth/perfil")
        clinica.atender("/")
        listOf(turnos, pediatria, clinica).forEach { it.iniciar() }
        val config = AppConfig(
            entorno = "test", turnosUrl = turnos.url,
            hcUrls = mapOf("pediatria" to pediatria.url, "clinica" to clinica.url, "otra" to "http://127.0.0.1:1"),
        )
        val e = Escena(db, config, PreferencesSettings(nodo), turnos, pediatria, clinica)
        try {
            withTimeout(60.seconds) { bloque(e) }
        } finally {
            e.cerrar()
            listOf(turnos, pediatria, clinica).forEach { it.close() }
            nodo.removeNode()
        }
    }

    private companion object {
        const val MEDICO_ID = "tobb-m7"
        val CREDENCIALES = Credenciales("medico@ejemplo.test", "clave-de-prueba")
    }
}
