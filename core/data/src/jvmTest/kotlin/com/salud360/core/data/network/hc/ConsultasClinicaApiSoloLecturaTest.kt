package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El cliente de las consultas de la web no puede escribir (RF-47): ni por la forma de las clases ni por lo que sale a la
 * red. Dos pruebas: (1) por reflexión, que lo único público de [HcApiSoloLectura], [ConsultasClinicaApi] y
 * [ConsultasClinicaApiRemota] son las lecturas, sin ningún parámetro que sirva para mandar un cuerpo; (2) contra un
 * servidor local de la JVM (`127.0.0.1`, puerto libre, sin dependencias de prueba nuevas) que anota cada pedido: solo
 * `GET`, la query con `paciente_id`/`limite`/`desde`, sin cuerpo ni `Content-Type`, y sin reintento ante un error.
 */
class ConsultasClinicaApiSoloLecturaTest {

    // --- Por construcción ---

    /** Los miembros públicos de una clase: lo que otra clase puede llamar (sin los internos, que llevan `$` en el nombre). */
    private fun publicos(clase: Class<*>): Set<String> = clase.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge && '$' !in it.name }
        .map { it.name }
        .toSet()

    @Test
    fun el_envoltorio_solo_expone_get_y_las_tres_lecturas_de_la_respuesta() {
        assertEquals(setOf("get", "leer", "leerOpcional", "leerTodo"), publicos(HcApiSoloLectura::class.java))
        // Ni un campo público por donde llegar al cliente que sabe escribir.
        val campos = HcApiSoloLectura::class.java.declaredFields.filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
        assertTrue(campos.isEmpty(), "campos públicos: ${campos.map { it.name }}")
    }

    @Test
    fun la_interfaz_y_la_clase_remota_solo_tienen_las_tres_lecturas() {
        val lecturas = setOf("listar", "consulta", "historia")
        assertEquals(lecturas, publicos(ConsultasClinicaApi::class.java))
        assertEquals(lecturas, publicos(ConsultasClinicaApiRemota::class.java))
        val campos = ConsultasClinicaApiRemota::class.java.declaredFields.filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
        assertTrue(campos.isEmpty(), "campos públicos: ${campos.map { it.name }}")
    }

    @Test
    fun la_clase_remota_no_recibe_un_cliente_que_sepa_escribir() {
        val constructores = ConsultasClinicaApiRemota::class.java.constructors
        assertEquals(1, constructores.size)
        assertEquals(listOf(HcApiSoloLectura::class.java), constructores.single().parameterTypes.toList())
    }

    @Test
    fun ninguna_lectura_recibe_un_cuerpo_solo_ids_y_numeros() {
        // listar(Long, Int, Int), consulta(Long), historia(Long): lo único que viaja son ids y números (+ la corrutina).
        val permitidos: Set<Class<*>> = setOf(java.lang.Long.TYPE, Integer.TYPE, kotlin.coroutines.Continuation::class.java)
        for (clase in listOf(ConsultasClinicaApi::class.java, ConsultasClinicaApiRemota::class.java)) {
            for (m in clase.declaredMethods.filter { Modifier.isPublic(it.modifiers) && '$' !in it.name }) {
                assertTrue(m.parameterTypes.all { it in permitidos }, "${clase.simpleName}.${m.name}: ${m.parameterTypes.toList()}")
            }
        }
    }

    // --- Lo que sale a la red ---

    private class Pedido(
        val metodo: String,
        val ruta: String,
        val query: Map<String, String>,
        val cuerpo: ByteArray,
        val contentType: String?,
        val authorization: String?,
    )

    private class Contexto(val api: ConsultasClinicaApi, val pedidos: List<Pedido>)

    /** Servidor que contesta [estado] y [respuesta] a todo y anota lo que le llega. */
    private fun conServidor(estado: Int = 200, respuesta: String = "{}", bloque: suspend Contexto.() -> Unit) {
        val pedidos = CopyOnWriteArrayList<Pedido>()
        val servidor = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        servidor.createContext("/") { x ->
            val query = (x.requestURI.rawQuery ?: "").split("&").filter { it.isNotEmpty() }
                .associate { it.substringBefore("=") to it.substringAfter("=", "") }
            pedidos.add(
                Pedido(
                    x.requestMethod, x.requestURI.path, query, x.requestBody.readBytes(),
                    x.requestHeaders.getFirst("Content-Type"), x.requestHeaders.getFirst("Authorization"),
                ),
            )
            val bytes = respuesta.toByteArray(Charsets.UTF_8)
            x.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            x.sendResponseHeaders(estado, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }
        servidor.start()
        try {
            val cliente = HcApiClient("http://127.0.0.1:${servidor.address.port}", "clinica").apply { token = "token-de-prueba-1" }
            runBlocking { Contexto(ConsultasClinicaApiRemota(HcApiSoloLectura(cliente)), pedidos).bloque() }
        } finally {
            servidor.stop(0)
        }
    }

    private fun Pedido.assertSoloLectura() {
        assertEquals("GET", metodo)
        assertEquals(0, cuerpo.size, "un GET con cuerpo")
        assertNull(contentType, "un GET con Content-Type")
        assertEquals("Bearer token-de-prueba-1", authorization)
    }

    @Test
    fun listar_es_un_get_con_la_query_y_sin_cuerpo() = conServidor(respuesta = EjemplosConsultasClinica.LISTA) {
        val l = api.listar(pacienteWebId = 5001, limite = 3, desde = 0)
        assertEquals(listOf(7005L, 7004L, 7003L), l.consultas.map { it.id })
        val p = pedidos.single()
        p.assertSoloLectura()
        assertEquals("/api/salud360/consultas", p.ruta)
        assertEquals(mapOf("paciente_id" to "5001", "limite" to "3", "desde" to "0"), p.query)
    }

    @Test
    fun listar_sin_limite_ni_desde_pide_los_del_servidor() = conServidor(respuesta = EjemplosConsultasClinica.LISTA) {
        api.listar(5001)
        assertEquals(mapOf("paciente_id" to "5001", "limite" to "50", "desde" to "0"), pedidos.single().query)
    }

    @Test
    fun la_segunda_pagina_manda_desde() = conServidor(respuesta = EjemplosConsultasClinica.LISTA) {
        api.listar(5001, limite = 3, desde = 3)
        assertEquals(mapOf("paciente_id" to "5001", "limite" to "3", "desde" to "3"), pedidos.single().query)
    }

    @Test
    fun consulta_es_un_get_a_la_ruta_de_la_consulta() = conServidor(respuesta = EjemplosConsultasClinica.CONSULTA_COMPLETA) {
        val c = api.consulta(7003)
        assertEquals(7003L, c.consulta.id)
        val p = pedidos.single()
        p.assertSoloLectura()
        assertEquals("/api/salud360/consultas/7003", p.ruta)
        assertTrue(p.query.isEmpty())
    }

    @Test
    fun historia_es_un_get_a_la_ruta_del_paciente() = conServidor(respuesta = EjemplosConsultasClinica.HISTORIA) {
        val h = api.historia(5001)
        assertEquals(5001L, h.pacienteId)
        val p = pedidos.single()
        p.assertSoloLectura()
        assertEquals("/api/salud360/pacientes/5001/historia", p.ruta)
        assertTrue(p.query.isEmpty())
    }

    @Test
    fun las_tres_lecturas_seguidas_son_tres_get_y_nada_mas() = conServidor(respuesta = EjemplosConsultasClinica.LISTA) {
        api.listar(5001, 3, 0)
        runCatching { api.consulta(7003) } // la respuesta de la lista no es una consulta: da igual, lo que cuenta es lo que salió
        runCatching { api.historia(5001) }
        assertEquals(3, pedidos.size)
        pedidos.forEach { it.assertSoloLectura() }
    }

    // --- Errores: llegan con status y código, sin reintento propio (RF-57) ---

    private fun conError(estado: Int, cuerpo: String, bloque: suspend ConsultasClinicaApi.() -> Unit, ver: (TobbException) -> Unit) =
        conServidor(estado, cuerpo) {
            val e = assertFailsWith<TobbException> { api.bloque() }
            ver(e)
            assertEquals(1, pedidos.size, "un solo pedido: el reintento es manual")
        }

    @Test
    fun fuera_de_cartera_403() = conError(
        403, """{"ok": false, "mensaje": "Ese paciente no está en tu cartera.", "codigo": "fuera_de_cartera"}""",
        { listar(5001, 3, 0) },
    ) { e ->
        assertEquals(403, e.status)
        assertEquals("fuera_de_cartera", e.codigo)
        assertEquals("Ese paciente no está en tu cartera.", e.message)
    }

    @Test
    fun consulta_no_encontrada_404() = conError(
        404, """{"ok": false, "mensaje": "Consulta no encontrada.", "codigo": "consulta_no_encontrada"}""",
        { consulta(999) },
    ) { e ->
        assertEquals(404, e.status)
        assertEquals("consulta_no_encontrada", e.codigo)
    }

    @Test
    fun paciente_no_encontrado_404_en_la_historia() = conError(
        404, """{"ok": false, "mensaje": "Paciente no encontrado.", "codigo": "paciente_no_encontrado"}""",
        { historia(999) },
    ) { e ->
        assertEquals(404, e.status)
        assertEquals("paciente_no_encontrado", e.codigo)
    }

    @Test
    fun datos_invalidos_422() = conError(
        422, """{"ok": false, "mensaje": "Falta el paciente o no es válido.", "codigo": "datos_invalidos"}""",
        { listar(0, 3, 0) },
    ) { e ->
        assertEquals(422, e.status)
        assertEquals("datos_invalidos", e.codigo)
    }

    @Test
    fun el_429_de_laravel_llega_sin_codigo_y_no_se_reintenta() = conError(
        429, """{"message": "Too Many Attempts."}""",
        { listar(5001, 3, 0) },
    ) { e ->
        assertEquals(429, e.status)
        assertNull(e.codigo)
        assertEquals("Too Many Attempts.", e.message)
    }

    @Test
    fun el_429_con_cuerpo_que_no_es_json_tambien_llega_con_su_status() = conError(
        429, "Too Many Requests", { historia(5001) },
    ) { e ->
        assertEquals(429, e.status)
        assertNull(e.codigo)
    }

    @Test
    fun error_500_consultas() = conError(
        500, """{"ok": false, "mensaje": "No se pudo leer la historia clínica.", "codigo": "consultas"}""",
        { consulta(7003) },
    ) { e ->
        assertEquals(500, e.status)
        assertEquals("consultas", e.codigo)
        assertEquals("No se pudo leer la historia clínica.", e.message)
    }

    @Test
    fun json_cortado_es_error_y_no_una_lista_vacia() = conError(
        200, """{"ok": true, "consultas": [{"id": 7003, "paci""", { listar(5001, 3, 0) },
    ) { e ->
        assertEquals(200, e.status)
    }

    @Test
    fun respuesta_sin_la_forma_esperada_es_respuesta_inesperada() = conError(
        200, """{"ok": true, "pacientes": []}""", { listar(5001, 3, 0) },
    ) { e ->
        assertEquals("respuesta", e.codigo)
    }

    @Test
    fun servidor_apagado_es_sin_conexion() {
        val servidor = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val puerto = servidor.address.port
        servidor.stop(0) // el puerto queda libre y nadie escucha
        val api = ConsultasClinicaApiRemota(HcApiSoloLectura(HcApiClient("http://127.0.0.1:$puerto", "clinica")))
        val e = assertFailsWith<TobbException> { runBlocking { api.historia(5001) } }
        assertEquals(0, e.status)
        assertEquals("red", e.codigo)
    }
}
