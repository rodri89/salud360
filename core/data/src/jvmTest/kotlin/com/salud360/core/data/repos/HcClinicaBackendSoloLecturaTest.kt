package com.salud360.core.data.repos

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ConsultasClinicaApiRemota
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcApiSoloLectura
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.RegistroClinico
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.datetime.LocalDate
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `HcClinicaBackend` no escribe nunca (spec 002, E6.3, RF-47): ni por construcción ni por lo que sale a la red. Contra un
 * servidor local de la JVM (`127.0.0.1`, sin dependencias nuevas) que anota método y ruta de cada pedido: la lista son
 * solo `GET`, cada método de envío lanza sin tocar la red y los errores del servidor llegan con su status, sin reintento
 * y sin escribir nada.
 */
class HcClinicaBackendSoloLecturaTest {

    private class Pedido(val metodo: String, val ruta: String, val query: String, val cuerpo: ByteArray)

    private fun cabecera(id: Long, dia: String) =
        """{"id": $id, "paciente_id": 5001, "medico_id": 9, "medico_nombre": "Médico Prueba Salud360", "tipo": "control", "tipo_numero": 1,
            "fecha": "$dia", "created_at": "$dia 10:00:00", "estado": "CERRADA", "edad_mostrar": "", "propia": true, "updated_at": "$dia 10:30:00"}"""

    /** [n] consultas de a una por día, de la más nueva a la más vieja. */
    private fun cabeceras(n: Int): List<String> =
        (0 until n).map { i -> cabecera(9000L + n - i, java.time.LocalDate.parse("2026-06-30").minusDays(i.toLong()).toString()) }

    /** Lista paginada como la contesta hclinica: `limite` y `desde` de la query. */
    private fun listaDe(todas: List<String>, query: String): String {
        val params = query.split("&").filter { it.isNotEmpty() }.associate { it.substringBefore("=") to it.substringAfter("=", "") }
        val limite = params["limite"]?.toIntOrNull() ?: 50
        val desde = params["desde"]?.toIntOrNull() ?: 0
        val pagina = todas.drop(desde).take(limite)
        val hayMas = if (desde + pagina.size < todas.size) 1 else 0
        return """{"ok": true, "consultas": [${pagina.joinToString(",")}], "total": ${todas.size}, "desde": $desde, "hay_mas": $hayMas,
            "omitidas": {"foto": 1, "otro": 0, "sin_fecha": 0}}"""
    }

    private class Contexto(val backend: HcClinicaBackend, val pedidos: List<Pedido>, val driver: app.cash.sqldelight.db.SqlDriver)

    /**
     * Servidor que contesta con [responder] (estado y cuerpo, según la query) y anota lo que le llega. La base tiene al
     * paciente de prueba vinculado con la ficha 5001.
     */
    private fun conServidor(
        vinculado: Boolean = true,
        responder: (HttpExchange, String) -> Pair<Int, String>,
        bloque: suspend Contexto.() -> Unit,
    ) {
        val pedidos = CopyOnWriteArrayList<Pedido>()
        val servidor = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        servidor.createContext("/") { x ->
            val query = x.requestURI.rawQuery ?: ""
            pedidos.add(Pedido(x.requestMethod, x.requestURI.path, query, x.requestBody.readBytes()))
            val (estado, cuerpo) = responder(x, query)
            val bytes = cuerpo.toByteArray(Charsets.UTF_8)
            x.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            x.sendResponseHeaders(estado, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }
        servidor.start()
        try {
            HcClinicaPruebas.conBase(vinculado) { db, driver ->
                val cliente = HcApiClient("http://127.0.0.1:${servidor.address.port}", "clinica").apply { token = "token-de-prueba-1" }
                val backend = HcClinicaBackend(db, HcApiSoloLectura(cliente), HcClinicaPruebas.pacientes(db))
                Contexto(backend, pedidos, driver).bloque()
            }
        } finally {
            servidor.stop(0)
        }
    }

    private fun Pedido.assertSoloLectura() {
        assertEquals("GET", metodo, "$metodo $ruta")
        assertEquals(0, cuerpo.size, "un GET con cuerpo")
    }

    private val unaConsulta = Consulta("u-1", PACIENTE, MEDICO, "clinica", "consulta", LocalDate.parse("2026-10-30"), EstadoConsulta.ABIERTA)

    // ---- Por construcción ----

    @Test
    fun ningun_constructor_recibe_el_cliente_que_sabe_escribir() {
        for (c in HcClinicaBackend::class.java.constructors) {
            assertTrue(c.parameterTypes.none { it == HcApiClient::class.java }, c.toString())
        }
        // El público recibe el envoltorio de solo lectura.
        assertTrue(HcClinicaBackend::class.java.constructors.any { c -> c.parameterTypes.any { it == HcApiSoloLectura::class.java } })
    }

    @Test
    fun declara_solo_lectura() = conServidor(responder = { _, _ -> 200 to "{}" }) {
        assertTrue(backend.soloLectura)
        assertEquals("clinica", backend.especialidad)
        assertEquals(emptyList(), pedidos)
    }

    // ---- Los métodos de envío lanzan y no tocan la red ----

    @Test
    fun todo_metodo_de_envio_lanza_y_no_sale_ningun_pedido() = conServidor(responder = { _, _ -> 200 to "{}" }) {
        val registro = RegistroClinico("r-1", PACIENTE, "u-1", "clinica", "veda")
        val archivo = Archivo("a-1", PACIENTE, "u-1", null, "fotos", "foto.jpg", "image/jpeg")

        assertFailsWith<UnsupportedOperationException> { backend.asegurarConsulta(unaConsulta) }
        assertFailsWith<UnsupportedOperationException> { backend.asegurarConsulta(unaConsulta.copy(remotoId = "7003")) }
        assertFailsWith<UnsupportedOperationException> { backend.enviarSecciones("7003", mapOf("nota" to mapOf("texto" to "x"))) }
        assertFailsWith<UnsupportedOperationException> { backend.enviarSecciones("7003", emptyMap()) }
        assertFailsWith<UnsupportedOperationException> { backend.enviarExamen("7003", mapOf("peso" to "70")) }
        assertFailsWith<UnsupportedOperationException> { backend.enviarRegistros("7003", listOf(registro)) }
        assertFailsWith<UnsupportedOperationException> { backend.enviarEstado(unaConsulta, "7003") }
        assertFailsWith<UnsupportedOperationException> { backend.subirArchivo("7003", archivo, ByteArray(3)) }
        assertFailsWith<UnsupportedOperationException> { backend.borrarArchivo("7003", archivo) }

        assertEquals(emptyList(), pedidos.map { "${it.metodo} ${it.ruta}" }, "ni siquiera un GET: no llega a la red")
    }

    @Test
    fun las_licencias_el_aviso_y_los_adjuntos_no_hacen_nada_ni_piden_nada() = conServidor(responder = { _, _ -> 200 to "{}" }) {
        val archivo = Archivo("a-1", PACIENTE, "u-1", null, "fotos", "foto.jpg", "image/jpeg", remotoId = "5")

        assertEquals(emptyList(), backend.traerLicencias())
        assertFalse(backend.guardarLicencia(Licencia("tobb-m9", "2027-01-01", "2026-12-01", 0.0, true)))
        assertNull(backend.avisoDeLicencia())
        assertNull(backend.bajarArchivo("7003", archivo))

        assertEquals(emptyList(), pedidos.map { "${it.metodo} ${it.ruta}" })
    }

    @Test
    fun los_metodos_de_envio_no_cambian_la_base() = conServidor(responder = { _, _ -> 200 to "{}" }) {
        val antes = volcarTodo(driver)

        runCatching { backend.asegurarConsulta(unaConsulta) }
        runCatching { backend.enviarEstado(unaConsulta, "7003") }
        runCatching { backend.enviarSecciones("7003", mapOf("nota" to mapOf("texto" to "x"))) }

        assertEquals(antes, volcarTodo(driver))
    }

    // ---- Lo que sale a la red al traer la lista ----

    @Test
    fun una_pagina_es_un_solo_get_a_la_lista_con_la_ficha_el_limite_y_el_desde() = conServidor(
        responder = { _, q -> 200 to listaDe(cabeceras(3), q) },
    ) {
        val traidas = backend.traerConsultas(PACIENTE, MEDICO, true)

        assertEquals(3, traidas.size)
        val p = pedidos.single()
        p.assertSoloLectura()
        assertEquals("/api/salud360/consultas", p.ruta)
        assertEquals(setOf("paciente_id=5001", "limite=50", "desde=0"), p.query.split("&").toSet())
    }

    @Test
    fun cuatro_paginas_son_cuatro_get_y_nada_mas() = conServidor(responder = { _, q -> 200 to listaDe(cabeceras(160), q) }) {
        backend.traerConsultas(PACIENTE, MEDICO, true)

        assertEquals(4, pedidos.size)
        pedidos.forEach { it.assertSoloLectura() }
        assertEquals(setOf("/api/salud360/consultas"), pedidos.map { it.ruta }.toSet(), "ni el historial ni una consulta (eso es de E6.4)")
        assertEquals(listOf("0", "50", "100", "150"), pedidos.map { p -> p.query.split("&").first { it.startsWith("desde=") }.removePrefix("desde=") })
    }

    @Test
    fun la_segunda_llamada_inmediata_no_sale_a_la_red() = conServidor(responder = { _, q -> 200 to listaDe(cabeceras(3), q) }) {
        backend.traerConsultas(PACIENTE, MEDICO)
        backend.traerConsultas(PACIENTE, MEDICO)

        assertEquals(1, pedidos.size)
    }

    @Test
    fun sin_vinculo_no_sale_ningun_pedido_ni_a_resolver() = conServidor(vinculado = false, responder = { _, _ -> 200 to "{}" }) {
        val antes = volcarTodo(driver)

        assertEquals(emptyList(), backend.traerConsultas(PACIENTE, MEDICO, true))

        assertEquals(emptyList(), pedidos.map { "${it.metodo} ${it.ruta}" }, "RF-49: ni consultas ni pacientes/resolver")
        assertEquals(antes, volcarTodo(driver))
    }

    // ---- Errores del servidor: con su status, sin reintento y sin escribir ----

    private fun conError(estado: Int, cuerpo: String, ver: (TobbException) -> Unit) =
        conServidor(responder = { _, _ -> estado to cuerpo }) {
            val antes = volcarTodo(driver)

            val e = assertFailsWith<TobbException> { backend.traerConsultas(PACIENTE, MEDICO, true) }

            ver(e)
            assertEquals(1, pedidos.size, "un solo pedido: el reintento es manual (RF-58)")
            pedidos.forEach { it.assertSoloLectura() }
            assertEquals(antes, volcarTodo(driver), "nada nuevo en el dispositivo")
        }

    @Test
    fun error_401() = conError(401, """{"ok": false, "mensaje": "Sesión vencida.", "codigo": "no_autorizado"}""") { assertEquals(401, it.status) }

    @Test
    fun error_403_fuera_de_cartera() = conError(
        403, """{"ok": false, "mensaje": "Ese paciente no está en tu cartera.", "codigo": "fuera_de_cartera"}""",
    ) {
        assertEquals(403, it.status)
        assertEquals("fuera_de_cartera", it.codigo)
    }

    @Test
    fun error_404() = conError(404, """{"ok": false, "mensaje": "Paciente no encontrado.", "codigo": "paciente_no_encontrado"}""") {
        assertEquals(404, it.status)
    }

    @Test
    fun error_422() = conError(422, """{"ok": false, "mensaje": "Falta el paciente o no es válido.", "codigo": "datos_invalidos"}""") {
        assertEquals(422, it.status)
        assertEquals("datos_invalidos", it.codigo)
    }

    @Test
    fun error_429_sin_reintento() = conError(429, """{"message": "Too Many Attempts."}""") {
        assertEquals(429, it.status)
        assertNull(it.codigo)
    }

    @Test
    fun error_500() = conError(500, """{"ok": false, "mensaje": "No se pudo leer la historia clínica.", "codigo": "consultas"}""") {
        assertEquals(500, it.status)
        assertEquals("consultas", it.codigo)
    }

    @Test
    fun json_cortado() = conError(200, """{"ok": true, "consultas": [{"id": 7003, "paci""") { }

    @Test
    fun json_sin_un_contenedor_obligatorio_no_se_aplica() = conError(
        200, """{"ok": true, "consultas": [${cabecera(7003, "2026-03-10")}], "desde": 0, "hay_mas": 0}""", // falta `total`
    ) { assertEquals("respuesta", it.codigo) }

    @Test
    fun json_sin_la_lista_no_se_toma_por_una_lista_vacia() = conError(200, """{"ok": true, "total": 0, "desde": 0, "hay_mas": 0}""") {
        assertEquals("respuesta", it.codigo)
    }

    @Test
    fun servidor_apagado_es_sin_conexion_y_no_escribe() {
        val servidor = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val puerto = servidor.address.port
        servidor.stop(0) // el puerto queda libre y nadie escucha
        HcClinicaPruebas.conBase(true) { db, driver ->
            val backend = HcClinicaBackend(
                db, HcApiSoloLectura(HcApiClient("http://127.0.0.1:$puerto", "clinica")), HcClinicaPruebas.pacientes(db),
            )
            val antes = volcarTodo(driver)

            val e = assertFailsWith<TobbException> { backend.traerConsultas(PACIENTE, MEDICO, true) }

            assertEquals(0, e.status)
            assertEquals(antes, volcarTodo(driver))
        }
    }

    @Test
    fun la_api_remota_se_arma_con_el_envoltorio_de_solo_lectura() {
        // La clase que usa el constructor público no puede recibir otra cosa (comprobado también en ConsultasClinicaApiSoloLecturaTest).
        assertEquals(listOf(HcApiSoloLectura::class.java), ConsultasClinicaApiRemota::class.java.constructors.single().parameterTypes.toList())
    }
}
