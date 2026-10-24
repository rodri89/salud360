package com.salud360.core.data.repos

import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Cierre de sesión con revocación del token (RF-59, C3, C4) sobre una base SQLite de verdad en un archivo temporal y
 * revocadores falsos: sin red ni servidor. Los tokens son cadenas de prueba, nunca reales.
 *
 * Los revocadores se retienen con compuertas que el test abre (o no), nunca con `delay`. El tope de 30 s de
 * [esperar] solo hace fallar un test que se colgaría; no es parte de lo que se prueba.
 */
class AuthRepositoryLogoutTest {

    private val token = "token-de-prueba-1"
    private val otroToken = "token-de-prueba-2"
    private val usuario = Usuario("u-a", "a@prueba.test", "Prueba", "Admin", Rol.ADMIN)

    private fun sesion(token: String) = Sesion(usuario, token)

    /** Un revocador falso: anota el token que recibe y después hace lo que diga [comportamiento]. */
    private class Falso(private val comportamiento: suspend (String) -> Unit = {}) : RevocaSesion {
        val recibio = CompletableDeferred<String>()
        override suspend fun revocar(token: String) {
            recibio.complete(token)
            comportamiento(token)
        }
    }

    /** Un revocador que recibe el token y no contesta nunca (la compuerta no se abre); avisa en `finally` si lo cancelan. */
    private class NoContesta : RevocaSesion {
        val recibio = CompletableDeferred<String>()
        val cancelado = CompletableDeferred<Unit>()
        override suspend fun revocar(token: String) {
            recibio.complete(token)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelado.complete(Unit)
            }
        }
    }

    private fun conBase(bloque: suspend (Salud360Db) -> Unit) {
        val archivo = File.createTempFile("salud360-logout", ".db").also { it.delete() }
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

    private suspend fun <T> esperar(d: CompletableDeferred<T>): T = withTimeout(30.seconds) { d.await() }

    /** Corre la revocación en el hilo de `logout` hasta su primera suspensión: lo que se llama, se llama antes de que `logout` vuelva. */
    private fun enElLugar() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun auth(
        db: Salud360Db,
        revocar: List<RevocaSesion>,
        scope: CoroutineScope = enElLugar(),
        limite: kotlin.time.Duration = 1.hours,
        turnos: TurnosOnlineClient = TurnosOnlineClient(),
        hc: Map<String, HcApiClient> = emptyMap(),
        alCerrarSesion: suspend () -> Unit = {},
    ) = AuthRepository(
        db, ApiClient("http://localhost"), turnos, hcClients = hc, alCerrarSesion = alCerrarSesion,
        revocar = revocar, scopeRevocacion = scope, limite = limite,
    )

    private suspend fun sesionEnBase(db: Salud360Db) = db.authQueries.sesionActual().uno { it }

    // ---------- a quién se revoca y con qué ----------

    @Test
    fun todos_reciben_el_token_de_la_sesion_aunque_los_clientes_ya_lo_olvidaron() = conBase { db ->
        val turnos = TurnosOnlineClient()
        val clinica = HcApiClient("http://localhost", "clinica")
        val pediatria = HcApiClient("http://localhost", "pediatria")
        val viste = mutableMapOf<String, CompletableDeferred<List<String?>>>()
        // Lo que ve cada revocador en el momento de revocar: los clientes sin token, la sesión local ya cerrada.
        fun mirando(nombre: String) = Falso {
            viste.getValue(nombre).complete(listOf(turnos.token, clinica.token, pediatria.token, if (sesionEnBase(db) == null) null else "hay"))
        }
        listOf("turnos", "clinica", "pediatria").forEach { viste[it] = CompletableDeferred() }
        val revocadores = listOf("turnos", "clinica", "pediatria").map { mirando(it) }
        val auth = auth(db, revocadores, turnos = turnos, hc = mapOf("clinica" to clinica, "pediatria" to pediatria))

        auth.guardarSesion(sesion(token))
        assertEquals(token, turnos.token)
        assertEquals(token, clinica.token)

        auth.logout()

        revocadores.forEach { assertEquals(token, esperar(it.recibio)) }
        viste.values.forEach { assertEquals(listOf<String?>(null, null, null, null), esperar(it)) }
        assertNull(auth.sesion.value)
    }

    @Test
    fun un_revocador_que_falla_no_impide_a_los_otros_ni_el_cierre() = conBase { db ->
        val fallan = Falso { throw IllegalStateException("falla de prueba") }
        val tambienFalla = Falso { throw Error("falla de prueba, como el error de red de la web") }
        val bien = Falso()
        val auth = auth(db, listOf(fallan, tambienFalla, bien))
        auth.guardarSesion(sesion(token))

        auth.logout()

        assertEquals(token, esperar(fallan.recibio))
        assertEquals(token, esperar(tambienFalla.recibio))
        assertEquals(token, esperar(bien.recibio))
        assertNull(auth.sesion.value)
        assertNull(sesionEnBase(db))
    }

    @Test
    fun un_revocador_que_no_contesta_no_demora_el_logout() = conBase { db ->
        // El ámbito es el de producción (otro hilo) y el límite no corre: lo único que libera a `logout` es no esperar.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mudo = NoContesta()
            val bien = Falso()
            val auth = auth(db, listOf(mudo, bien), scope = scope)
            auth.guardarSesion(sesion(token))

            withTimeout(30.seconds) { auth.logout() }

            // Cuando `logout` vuelve la sesión local ya está cerrada, aunque el mudo siga colgado.
            assertNull(auth.sesion.value)
            assertNull(sesionEnBase(db))
            assertEquals(token, esperar(mudo.recibio))
            assertEquals(token, esperar(bien.recibio))
            assertFalse(mudo.cancelado.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pasado_el_limite_la_revocacion_se_cancela() = conBase { db ->
        val mudo = NoContesta()
        val bien = Falso()
        val auth = auth(db, listOf(mudo, bien), limite = 200.milliseconds)
        auth.guardarSesion(sesion(token))

        auth.logout()

        // Con el ámbito en el lugar el mudo ya recibió el token cuando `logout` vuelve: el límite lo cancela a él, no antes de empezar.
        assertTrue(mudo.recibio.isCompleted, "el revocador tendría que haber empezado antes de que vuelva logout")
        esperar(mudo.cancelado)
        assertEquals(token, esperar(bien.recibio))
        assertNull(auth.sesion.value)
    }

    // ---------- cuándo no se revoca, y a qué token ----------

    @Test
    fun sin_token_no_se_llama_a_nadie() = conBase { db ->
        val falso = Falso()
        val sinSesion = auth(db, listOf(falso))
        sinSesion.logout()
        assertFalse(falso.recibio.isCompleted)

        // Una sesión con el token vacío (así sale de `resolverPerfil`) tampoco.
        val conVacio = auth(db, listOf(falso))
        conVacio.guardarSesion(sesion(""))
        conVacio.logout()
        assertNull(conVacio.sesion.value)
        assertFalse(falso.recibio.isCompleted)
    }

    @Test
    fun logout_con_token_a_revocar_revoca_ese_y_no_el_de_la_sesion() = conBase { db ->
        // El caso de `login`: el token recién emitido todavía no está en los clientes ni en la sesión.
        val sinSesion = Falso()
        auth(db, listOf(sinSesion)).logout(otroToken)
        assertEquals(otroToken, esperar(sinSesion.recibio))

        val conSesion = Falso()
        val auth = auth(db, listOf(conSesion))
        auth.guardarSesion(sesion(token))
        auth.logout(otroToken)
        assertEquals(otroToken, esperar(conSesion.recibio))
        assertNull(auth.sesion.value)
    }

    @Test
    fun logout_toma_el_token_del_cliente_de_turnos_si_no_hay_sesion() = conBase { db ->
        // `restaurar` cierra por licencia vencida con los clientes ya cargados y la sesión todavía sin publicar.
        val turnos = TurnosOnlineClient().also { it.token = token }
        val falso = Falso()
        auth(db, listOf(falso), turnos = turnos).logout()
        assertEquals(token, esperar(falso.recibio))
        assertNull(turnos.token)
    }

    // ---------- lo de siempre sigue igual (E12, F2.11) ----------

    @Test
    fun sin_revocadores_cierra_como_antes() = conBase { db ->
        val turnos = TurnosOnlineClient()
        val clinica = HcApiClient("http://localhost", "clinica")
        val copiaBorrada = CompletableDeferred<Unit>()
        val auth = AuthRepository(
            db, ApiClient("http://localhost"), turnos, hcClients = mapOf("clinica" to clinica),
            alCerrarSesion = { copiaBorrada.complete(Unit) },
        )
        auth.guardarSesion(sesion(token))
        assertNotNull(sesionEnBase(db))

        auth.logout()

        assertTrue(copiaBorrada.isCompleted)
        assertNull(auth.sesion.value)
        assertNull(sesionEnBase(db))
        assertNull(turnos.token)
        assertNull(clinica.token)
    }

    @Test
    fun alCerrarSesion_va_primero_y_si_falla_no_se_cierra_ni_se_revoca() = conBase { db ->
        val falso = Falso()
        var sesionAlBorrarLaCopia: Boolean? = null
        val auth = auth(db, listOf(falso), alCerrarSesion = { sesionAlBorrarLaCopia = sesionEnBase(db) != null })
        auth.guardarSesion(sesion(token))
        auth.logout()
        // La copia se borra con la sesión todavía en pie, y la revocación va después de todo.
        assertEquals(true, sesionAlBorrarLaCopia)
        assertEquals(token, esperar(falso.recibio))

        val otro = Falso()
        val queFalla = auth(db, listOf(otro), alCerrarSesion = { throw IllegalStateException("el borrado falló") })
        queFalla.guardarSesion(sesion(token))
        assertFailsWith<IllegalStateException> { queFalla.logout() }
        // Igual que antes de F3.8: si el borrado de la copia falla, la sesión sigue y no se revoca nada.
        assertNotNull(queFalla.sesion.value)
        assertNotNull(sesionEnBase(db))
        assertFalse(otro.recibio.isCompleted)
    }

    // ---------- el token no se muestra ----------

    @Test
    fun el_token_no_se_escribe_en_la_salida_ni_queda_en_el_estado() = conBase { db ->
        val fallo = Falso { throw IllegalStateException("falla de prueba") }
        val bien = Falso()
        val turnos = TurnosOnlineClient()
        val auth = auth(db, listOf(fallo, bien), turnos = turnos)
        auth.guardarSesion(sesion(token))

        val salida = ByteArrayOutputStream()
        val salidaOriginal = System.out
        val erroresOriginal = System.err
        System.setOut(PrintStream(salida, true, "UTF-8"))
        System.setErr(PrintStream(salida, true, "UTF-8"))
        try {
            auth.logout()
            esperar(fallo.recibio)
            esperar(bien.recibio)
        } finally {
            System.setOut(salidaOriginal)
            System.setErr(erroresOriginal)
        }

        assertFalse(salida.toString("UTF-8").contains(token), "el token apareció en la salida")
        assertNull(auth.sesion.value)
        assertNull(sesionEnBase(db))
        assertNull(turnos.token)
    }
}
