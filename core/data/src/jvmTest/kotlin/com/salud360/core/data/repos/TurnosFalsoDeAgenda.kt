package com.salud360.core.data.repos

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Servidor falso de turnosonlinebb para el panel de administración (spec 005, T4 y T5).
 *
 * Escucha en `127.0.0.1` con un puerto libre y anota cada pedido (método, ruta, token y cuerpo) para que los tests
 * puedan comprobar qué sale hacia turnos. Contesta `GET catalogos`, `GET usuarios` y `GET secretarias` con datos
 * inventados y deja configurables el `PUT medicos/{n}/agenda` (T5) y el `PUT medicos/{n}/historias-clinicas`.
 *
 * Nunca se usa el `TurnosOnlineClient()` por omisión: ese apunta a producción.
 */
class TurnosFalsoDeAgenda : AutoCloseable {
    /** Un pedido recibido: lo que el test necesita para comprobar método, ruta, token y cuerpo. */
    data class Pedido(val metodo: String, val ruta: String, val token: String?, val cuerpo: String)

    /** Médico inventado del catálogo. Un campo en `null` no viaja: es el servidor que todavía no lo informa. */
    data class MedicoFalso(
        val id: Long,
        val nombre: String = "Medico$id",
        val apellido: String = "Prueba",
        val tieneHorarios: Int? = 1,
        val tieneAgenda: Int? = null,
        val visibleEnTurnos: Int? = null,
        val consultorioId: Long = 0,
        val historiasClinicas: List<String> = emptyList(),
        val activo: Int = 1,
    )

    /** Médicos que informa `GET catalogos` (y con ellos se arman los usuarios de `GET usuarios`). */
    @Volatile
    var medicos: List<MedicoFalso> = emptyList()

    /** Respuesta del `PUT medicos/{n}/agenda` (T5): `(estado, cuerpo)`. */
    @Volatile
    var respuestaDeAgenda: Pair<Int, String> = 404 to """{"ok":false,"mensaje":"la ruta de la agenda no está en el servidor falso"}"""

    /** Respuesta del `PUT medicos/{n}/historias-clinicas`: `(estado, cuerpo)`. */
    @Volatile
    var respuestaDeHistoriasClinicas: Pair<Int, String> = 404 to """{"ok":false,"mensaje":"la ruta de las historias clínicas no está en el servidor falso"}"""

    private val anotados = mutableListOf<Pedido>()

    fun pedidos(): List<Pedido> = synchronized(anotados) { anotados.toList() }

    fun pedidosA(ruta: String): List<Pedido> = pedidos().filter { it.ruta == ruta }

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { x -> atender(x) }
        server.start()
    }

    private fun atender(x: HttpExchange) {
        val cuerpo = x.requestBody.readBytes().decodeToString()
        synchronized(anotados) {
            anotados += Pedido(
                metodo = x.requestMethod,
                ruta = x.requestURI.path,
                token = x.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer "),
                cuerpo = cuerpo,
            )
        }
        val ruta = x.requestURI.path.removePrefix(PREFIJO)
        when {
            x.requestMethod == "GET" && ruta == "catalogos" -> responder(x, 200, catalogos())
            x.requestMethod == "GET" && ruta == "usuarios" -> responder(x, 200, usuarios())
            x.requestMethod == "GET" && ruta == "secretarias" -> responder(x, 200, """{"secretarias":[]}""")
            x.requestMethod == "PUT" && ruta.startsWith("medicos/") && ruta.endsWith("/agenda") ->
                responder(x, respuestaDeAgenda.first, respuestaDeAgenda.second)
            x.requestMethod == "PUT" && ruta.startsWith("medicos/") && ruta.endsWith("/historias-clinicas") ->
                responder(x, respuestaDeHistoriasClinicas.first, respuestaDeHistoriasClinicas.second)
            else -> responder(x, 404, """{"ok":false,"mensaje":"la ruta $ruta no está en el servidor falso"}""")
        }
    }

    private fun responder(x: HttpExchange, estado: Int, texto: String) {
        val cuerpo = texto.toByteArray()
        x.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        x.sendResponseHeaders(estado, cuerpo.size.toLong())
        x.responseBody.use { it.write(cuerpo) }
    }

    private fun catalogos(): String {
        val lista = medicos.joinToString(",") { medicoJson(it) }
        val consultorio = """{"id":5,"nombre":"Consultorio de prueba"}"""
        return """{"medicos":[$lista],"consultorios":[$consultorio],"especialidades":[],"obras_sociales":[]}"""
    }

    private fun usuarios(): String =
        """{"usuarios":[${medicos.joinToString(",") { usuarioJson(it) }}]}"""

    private fun usuarioJson(m: MedicoFalso): String {
        val mail = "medico${m.id}@ejemplo.test"
        return """{"id":${1000 + m.id},"nombre":"${m.nombre} ${m.apellido}","email":"$mail","rol":"medico","medico_id":${m.id},"activo":1}"""
    }

    /** El médico tal como lo manda el catálogo; los campos en `null` se omiten a propósito. */
    private fun medicoJson(m: MedicoFalso): String {
        val campos = mutableListOf(
            "\"id\":${m.id}",
            "\"nombre\":\"${m.nombre}\"",
            "\"apellido\":\"${m.apellido}\"",
            "\"consultorio_id\":${m.consultorioId}",
            "\"activo\":${m.activo}",
        )
        m.tieneHorarios?.let { campos += "\"tiene_horarios\":$it" }
        m.tieneAgenda?.let { campos += "\"tiene_agenda\":$it" }
        m.visibleEnTurnos?.let { campos += "\"visible_en_turnos\":$it" }
        if (m.historiasClinicas.isNotEmpty()) {
            campos += "\"historias_clinicas\":[${m.historiasClinicas.joinToString(",") { "\"$it\"" }}]"
        }
        return "{" + campos.joinToString(",") + "}"
    }

    override fun close() = server.stop(0)

    private companion object {
        const val PREFIJO = "/api/salud360/"
    }
}
