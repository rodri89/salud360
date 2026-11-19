package com.salud360.core.data.repos

import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.network.hc.HcApiClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Los dos pedidos de revocación del cierre de sesión (RF-59) contra un servidor local de la JVM (`127.0.0.1`, puerto
 * libre): sin red ni URLs reales y sin dependencias de prueba nuevas. El token es una cadena de prueba.
 */
class RevocarSesionClientesTest {

    private val token = "token-de-prueba-1"

    private class Pedido(val metodo: String, val ruta: String, val authorization: String?, val tokenSalud360: String?)

    /** Servidor que contesta [estado] a todo y anota lo que le llega. */
    private fun conServidor(estado: Int, bloque: suspend (String, () -> Pedido?) -> Unit) {
        val pedidos = LinkedBlockingQueue<Pedido>()
        val servidor = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        servidor.createContext("/") { x ->
            pedidos.add(
                Pedido(
                    x.requestMethod, x.requestURI.path,
                    x.requestHeaders.getFirst("Authorization"), x.requestHeaders.getFirst("X-Salud360-Token"),
                ),
            )
            x.sendResponseHeaders(estado, -1)
            x.close()
        }
        servidor.start()
        try {
            runBlocking { bloque("http://127.0.0.1:${servidor.address.port}") { pedidos.poll(30, TimeUnit.SECONDS) } }
        } finally {
            servidor.stop(0)
        }
    }

    @Test
    fun turnos_manda_auth_logout_con_el_token_por_parametro() = conServidor(200) { url, pedido ->
        val cliente = TurnosOnlineClient(url) // sin `token` propio: el de la sesión ya se olvidó
        assertNull(cliente.token)
        cliente.cerrarSesion(token)
        val p = assertNotNull(pedido())
        assertEquals("POST", p.metodo)
        assertEquals("/api/salud360/auth/logout", p.ruta)
        assertEquals("Bearer $token", p.authorization)
        assertEquals(token, p.tokenSalud360)
    }

    @Test
    fun la_hc_manda_auth_salir_con_el_token_por_parametro() = conServidor(200) { url, pedido ->
        val cliente = HcApiClient(url, "clinica")
        assertNull(cliente.token)
        cliente.salir(token)
        val p = assertNotNull(pedido())
        assertEquals("POST", p.metodo)
        assertEquals("/api/salud360/auth/salir", p.ruta)
        assertEquals("Bearer $token", p.authorization)
        assertEquals(token, p.tokenSalud360)
    }

    @Test
    fun un_404_de_una_api_vieja_o_un_401_no_son_error() {
        listOf(404, 401, 500).forEach { estado ->
            conServidor(estado) { url, pedido ->
                HcApiClient(url, "clinica").salir(token)
                assertNotNull(pedido())
                TurnosOnlineClient(url).cerrarSesion(token)
                assertNotNull(pedido())
            }
        }
    }
}
