package com.salud360.core.data.repos

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ConsultasClinicaApiRemota
import com.salud360.core.data.network.hc.EjemplosConsultasClinica
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.HcApiSoloLectura
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodoSinMarcas
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
 * La **lectura** de `HcClinicaBackend` no escribe nunca (spec 002, E6.3 y E6.4, RF-47). Desde la spec 005 (T23) el backend ya no es de
 * solo lectura —recibe la API de escritura, ver `HcClinicaBackendEscrituraTest`— pero lo que lee sigue igual, y todo esto vale
 * tal cual: ni por construcción ni por lo que sale a la red.
 * Contra un servidor local de la JVM (`127.0.0.1`, sin dependencias nuevas) que anota método y ruta de cada pedido: la
 * lista, el historial y cada consulta son solo `GET`, cada método de envío lanza sin tocar la red y los errores del
 * servidor llegan con su status, sin reintento y sin escribir nada.
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
                val backend = HcClinicaBackend(db, cliente, HcClinicaPruebas.pacientes(db))
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
    fun la_lectura_se_arma_siempre_con_el_envoltorio_de_solo_lectura_aunque_el_backend_reciba_el_cliente_completo() {
        // T23: el constructor público recibe el cliente completo (también para la escritura, que este backend todavía no usa),
        // pero la lectura la hace `ConsultasClinicaApi`, que solo sabe leer (comprobado en ConsultasClinicaApiSoloLecturaTest).
        assertTrue(HcClinicaBackend::class.java.constructors.any { c -> c.parameterTypes.any { it == HcApiClient::class.java } })
        assertTrue(HcClinicaBackend::class.java.constructors.none { c -> c.parameterTypes.any { it == HcApiSoloLectura::class.java } })
        val lectura = HcClinicaBackend::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }
        assertTrue(lectura.any { it == com.salud360.core.data.network.hc.ConsultasClinicaApi::class.java }, "la lectura es de una API que solo lee")
    }

    @Test
    fun armarlo_no_pide_nada() = conServidor(responder = { _, _ -> 200 to "{}" }) {
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

    // ---- Lo que sale a la red al traer la lista y el historial ----

    private val noEncontrada = """{"ok": false, "mensaje": "Consulta no encontrada.", "codigo": "consulta_no_encontrada"}"""

    /** hclinica con [cabeceras] en la lista, el [historial] del paciente 5001 y las [consultas] por id; lo demás es 404. */
    private fun web(
        cabeceras: List<String>,
        historial: String = EjemplosConsultasClinica.HISTORIA,
        consultas: Map<Long, String> = emptyMap(),
    ): (HttpExchange, String) -> Pair<Int, String> = { x, query ->
        val ruta = x.requestURI.path.removePrefix("/api/salud360/")
        when {
            ruta == "consultas" -> 200 to listaDe(cabeceras, query)
            ruta == "pacientes/5001/historia" -> 200 to historial
            ruta.startsWith("consultas/") -> consultas[ruta.removePrefix("consultas/").toLongOrNull()]?.let { 200 to it } ?: (404 to noEncontrada)
            else -> 404 to "{}"
        }
    }

    @Test
    fun abrir_la_historia_son_dos_get_la_lista_y_el_historial() = conServidor(responder = web(cabeceras(3))) {
        val traidas = backend.traerConsultas(PACIENTE, MEDICO, true)

        assertEquals(3, traidas.size)
        assertEquals(2, pedidos.size)
        pedidos.forEach { it.assertSoloLectura() }
        val lista = pedidos[0]
        assertEquals("/api/salud360/consultas", lista.ruta)
        assertEquals(setOf("paciente_id=5001", "limite=50", "desde=0"), lista.query.split("&").toSet())
        val historial = pedidos[1]
        assertEquals("/api/salud360/pacientes/5001/historia", historial.ruta)
        assertEquals("", historial.query, "el historial no lleva parámetros: es de la ficha")
    }

    @Test
    fun cuatro_paginas_son_cuatro_get_mas_uno_del_historial_y_nada_mas() = conServidor(responder = web(cabeceras(160))) {
        backend.traerConsultas(PACIENTE, MEDICO, true)

        assertEquals(5, pedidos.size)
        pedidos.forEach { it.assertSoloLectura() }
        assertEquals(
            listOf("/api/salud360/consultas", "/api/salud360/consultas", "/api/salud360/consultas", "/api/salud360/consultas", "/api/salud360/pacientes/5001/historia"),
            pedidos.map { it.ruta },
            "ninguna consulta suelta: eso lo pide quien abre una",
        )
        assertEquals(
            listOf("0", "50", "100", "150"),
            pedidos.take(4).map { p -> p.query.split("&").first { it.startsWith("desde=") }.removePrefix("desde=") },
        )
    }

    @Test
    fun la_segunda_llamada_inmediata_no_sale_a_la_red() = conServidor(responder = web(cabeceras(3))) {
        backend.traerConsultas(PACIENTE, MEDICO)
        backend.traerConsultas(PACIENTE, MEDICO)

        assertEquals(2, pedidos.size, "la lista y el historial de la primera; la segunda ve el minuto")
    }

    @Test
    fun un_429_del_historial_no_se_reintenta_y_la_lista_ya_traida_se_queda() = conServidor(
        responder = { x, q ->
            if (x.requestURI.path.endsWith("/historia")) 429 to """{"message": "Too Many Attempts."}""" else 200 to listaDe(cabeceras(3), q)
        },
    ) {
        val e = assertFailsWith<TobbException> { backend.traerConsultas(PACIENTE, MEDICO, true) }

        assertEquals(429, e.status)
        assertNull(e.codigo)
        assertEquals(
            listOf("/api/salud360/consultas", "/api/salud360/pacientes/5001/historia"), pedidos.map { it.ruta },
            "la lista y un solo pedido de historial, sin reintento (RF-58)",
        )
        pedidos.forEach { it.assertSoloLectura() }
        assertEquals(3, textos(driver, "SELECT COUNT(*) FROM consulta").single().toInt(), "la lista quedó aplicada")
    }

    // ---- Lo que sale a la red al abrir una consulta ----

    private val unaDeLaWeb = EjemplosConsultasClinica.CONSULTA_COMPLETA

    @Test
    fun abrir_una_consulta_es_un_solo_get_a_consultas_id() = conServidor(responder = web(cabeceras(3), consultas = mapOf(9003L to unaDeLaWeb.replace("7003", "9003")))) {
        backend.traerConsultas(PACIENTE, MEDICO, true)
        val hastaAca = pedidos.size

        backend.traerConsulta("hcc-c9003", "9003")

        assertEquals(1, pedidos.size - hastaAca, "RF-57: un pedido al abrir cada consulta")
        val p = pedidos.last()
        p.assertSoloLectura()
        assertEquals("/api/salud360/consultas/9003", p.ruta)
        assertEquals("", p.query)
        assertEquals(
            "Prueba Salud360 motivo (a)",
            textos(driver, "SELECT valor FROM seccion_valor WHERE consulta_id = 'hcc-c9003' AND seccion = 'motivo_consulta'").single(),
        )
    }

    private fun conConsultaTraida(responder: (HttpExchange, String) -> Pair<Int, String>, bloque: suspend Contexto.(Int) -> Unit) =
        conServidor(responder = responder) {
            backend.traerConsultas(PACIENTE, MEDICO, true)
            val hastaAca = pedidos.size
            bloque(hastaAca)
        }

    private fun conErrorAlAbrir(estado: Int, cuerpo: String, marcada: Boolean = false, ver: (TobbException) -> Unit) = conConsultaTraida(
        { x, q -> if (x.requestURI.path.endsWith("/9003")) estado to cuerpo else web(cabeceras(3)).invoke(x, q) },
    ) { hastaAca ->
        val antes = volcarTodo(driver)

        val e = assertFailsWith<TobbException> { backend.traerConsulta("hcc-c9003", "9003") }

        ver(e)
        assertEquals(1, pedidos.size - hastaAca, "un solo pedido: el reintento es manual (RF-58)")
        pedidos.forEach { it.assertSoloLectura() }
        assertEquals(antes.mapValues { (_, f) -> f.filterNot { "'hcc_ausente_" in it } }, volcarTodoSinMarcas(driver), "nada nuevo en el dispositivo y nada borrado")
        // Solo el `404 consulta_no_encontrada` deja algo (E6.4b): la marca «Ya no figura en la web»; cualquier otro error, nada.
        if (marcada) {
            assertEquals(listOf("hcc-c9003"), HcClinicaPruebas.marcasVigentes(driver).map { it.substringBefore("=") })
        } else {
            assertEquals(antes, volcarTodo(driver), "ni siquiera una marca")
        }
    }

    @Test
    fun consulta_404_consulta_no_encontrada() = conErrorAlAbrir(404, noEncontrada, marcada = true) {
        assertEquals(404, it.status)
        assertEquals("consulta_no_encontrada", it.codigo)
    }

    @Test
    fun consulta_403_fuera_de_cartera() = conErrorAlAbrir(
        403, """{"ok": false, "mensaje": "Ese paciente no está en tu cartera.", "codigo": "fuera_de_cartera"}""",
    ) {
        assertEquals(403, it.status)
        assertEquals("fuera_de_cartera", it.codigo)
    }

    @Test
    fun consulta_429_sin_reintento() = conErrorAlAbrir(429, """{"message": "Too Many Attempts."}""") {
        assertEquals(429, it.status)
        assertNull(it.codigo)
    }

    @Test
    fun consulta_500() = conErrorAlAbrir(500, """{"ok": false, "mensaje": "No se pudo leer la consulta.", "codigo": "consultas"}""") {
        assertEquals(500, it.status)
    }

    @Test
    fun consulta_con_json_cortado() = conErrorAlAbrir(200, """{"ok": true, "consulta": {"id": 9003, "paci""") { }

    @Test
    fun consulta_sin_un_contenedor_obligatorio_no_se_toma_por_una_consulta_vacia() = conErrorAlAbrir(
        200, """{"ok": true, "consulta": ${EjemplosConsultasClinica.CABECERA_A.replace("7003", "9003")}, "examen_fisico": {}, "no_leidas": []}""", // falta `secciones`
    ) { assertEquals("respuesta", it.codigo) }

    @Test
    fun abrir_una_consulta_sin_ficha_no_sale_ningun_pedido() = conServidor(vinculado = false, responder = { _, _ -> 200 to "{}" }) {
        val antes = volcarTodo(driver)

        backend.traerConsulta("hcc-c9003", "9003")

        assertEquals(emptyList(), pedidos.map { "${it.metodo} ${it.ruta}" })
        assertEquals(antes, volcarTodo(driver))
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
                db, HcApiClient("http://127.0.0.1:$puerto", "clinica"), HcClinicaPruebas.pacientes(db),
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
