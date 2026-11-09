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
 * inventados y deja configurables el `PUT medicos/{n}/agenda` (T5), el `PUT medicos/{n}/historias-clinicas` y el
 * `PUT medicos/{n}/activo`; también las cuatro rutas de los tipos de turno (`GET`/`POST tipos-turno` y `GET`/`PUT
 * medicos/{n}/tipos-turno`, 404 por omisión) y el `POST pacientes` y el `PUT pacientes/{n}` de la ficha del paciente. Un médico con `activo = 0` sale en `GET usuarios` (con su `medico_id` y `activo:0`) pero
 * no en `GET catalogos`, igual que en turnos.
 *
 * Nunca se usa el `TurnosOnlineClient()` por omisión: ese apunta a producción.
 */
class TurnosFalsoDeAgenda : AutoCloseable {
    /** Un pedido recibido: lo que el test necesita para comprobar método, ruta, token y cuerpo. */
    data class Pedido(val metodo: String, val ruta: String, val token: String?, val cuerpo: String, val consulta: String = "") {
        /** Los parámetros de la URL (`?a=1&b=2`) como mapa; sin ellos, vacío. */
        val parametros: Map<String, String>
            get() = consulta.split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /**
     * Médico inventado del catálogo. Un campo en `null` no viaja: es el servidor que todavía no lo informa
     * (`historiasClinicas = emptyList()` sí viaja, como `[]`: el administrador no le habilitó ninguna).
     */
    data class MedicoFalso(
        val id: Long,
        val nombre: String = "Medico$id",
        val apellido: String = "Prueba",
        val tieneHorarios: Int? = 1,
        val tieneAgenda: Int? = null,
        val visibleEnTurnos: Int? = null,
        val consultorioId: Long = 0,
        val historiasClinicas: List<String>? = null,
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

    /** Respuesta del `PUT medicos/{n}/activo`: `(estado, cuerpo)`. */
    @Volatile
    var respuestaDeActivo: Pair<Int, String> = 404 to """{"ok":false,"mensaje":"la ruta de activo no está en el servidor falso"}"""

    /** Respuesta del `GET tipos-turno` (catálogo global de tipos de turno): `(estado, cuerpo)`. */
    @Volatile
    var respuestaDeCatalogoDeTipos: Pair<Int, String> = 404 to """{"ok":false,"mensaje":"la ruta de los tipos de turno no está en el servidor falso"}"""

    /** Respuesta del `POST tipos-turno` (alta de un tipo): `(estado, cuerpo)`. */
    @Volatile
    var respuestaDeAltaDeTipo: Pair<Int, String> = 404 to """{"ok":false,"mensaje":"la ruta de los tipos de turno no está en el servidor falso"}"""

    /** Respuesta del `GET medicos/{n}/tipos-turno` (tipos habilitados de un médico): `(estado, cuerpo)`. */
    @Volatile
    var respuestaDeTiposDelMedico: Pair<Int, String> = 404 to """{"ok":false,"mensaje":"la ruta de los tipos de turno del médico no está en el servidor falso"}"""

    /** Respuesta del `PUT medicos/{n}/tipos-turno` (lista exacta de tipos habilitados): `(estado, cuerpo)`. */
    @Volatile
    var respuestaDeGuardarTiposDelMedico: Pair<Int, String> = 404 to """{"ok":false,"mensaje":"la ruta de los tipos de turno del médico no está en el servidor falso"}"""

    /** Respuesta del `POST pacientes` (alta de un paciente): `(estado, cuerpo)`. */
    @Volatile
    var respuestaDeAltaDePaciente: Pair<Int, String> = 404 to """{"ok":false,"mensaje":"la ruta de pacientes no está en el servidor falso"}"""

    /** Respuesta del `PUT pacientes/{n}` (edición de un paciente): `(estado, cuerpo)`. */
    @Volatile
    var respuestaDeEdicionDePaciente: Pair<Int, String> = 404 to """{"ok":false,"mensaje":"la ruta de pacientes no está en el servidor falso"}"""

    private val rutasLibres = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()

    /**
     * Contesta `metodo ruta` (la ruta sin el prefijo `/api/salud360/`, por ejemplo `agenda/dia`) con `(estado, cuerpo)`.
     * Sirve para las rutas de la agenda y de los turnos; lo que no se configura sigue dando 404.
     */
    fun responderA(metodo: String, ruta: String, estado: Int, cuerpo: String) {
        rutasLibres["$metodo $ruta"] = estado to cuerpo
    }

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
                consulta = x.requestURI.rawQuery.orEmpty(),
            )
        }
        val ruta = x.requestURI.path.removePrefix(PREFIJO)
        when {
            x.requestMethod == "GET" && ruta == "catalogos" -> responder(x, 200, catalogos())
            x.requestMethod == "GET" && ruta == "usuarios" -> responder(x, 200, usuarios())
            x.requestMethod == "GET" && ruta == "secretarias" -> responder(x, 200, """{"secretarias":[]}""")
            x.requestMethod == "GET" && ruta == "tipos-turno" -> responder(x, respuestaDeCatalogoDeTipos.first, respuestaDeCatalogoDeTipos.second)
            x.requestMethod == "POST" && ruta == "tipos-turno" -> responder(x, respuestaDeAltaDeTipo.first, respuestaDeAltaDeTipo.second)
            x.requestMethod == "GET" && ruta.startsWith("medicos/") && ruta.endsWith("/tipos-turno") ->
                responder(x, respuestaDeTiposDelMedico.first, respuestaDeTiposDelMedico.second)
            x.requestMethod == "PUT" && ruta.startsWith("medicos/") && ruta.endsWith("/tipos-turno") ->
                responder(x, respuestaDeGuardarTiposDelMedico.first, respuestaDeGuardarTiposDelMedico.second)
            x.requestMethod == "PUT" && ruta.startsWith("medicos/") && ruta.endsWith("/agenda") ->
                responder(x, respuestaDeAgenda.first, respuestaDeAgenda.second)
            x.requestMethod == "PUT" && ruta.startsWith("medicos/") && ruta.endsWith("/historias-clinicas") ->
                responder(x, respuestaDeHistoriasClinicas.first, respuestaDeHistoriasClinicas.second)
            x.requestMethod == "PUT" && ruta.startsWith("medicos/") && ruta.endsWith("/activo") ->
                responder(x, respuestaDeActivo.first, respuestaDeActivo.second)
            x.requestMethod == "POST" && ruta == "pacientes" -> responder(x, respuestaDeAltaDePaciente.first, respuestaDeAltaDePaciente.second)
            x.requestMethod == "PUT" && ruta.startsWith("pacientes/") ->
                responder(x, respuestaDeEdicionDePaciente.first, respuestaDeEdicionDePaciente.second)
            rutasLibres.containsKey("${x.requestMethod} $ruta") -> rutasLibres.getValue("${x.requestMethod} $ruta").let { responder(x, it.first, it.second) }
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
        // Como el servidor real: `GET catalogos` no trae a los médicos con `activo = 0` (`GET usuarios` sí los informa).
        val lista = medicos.filter { it.activo == 1 }.joinToString(",") { medicoJson(it) }
        val consultorio = """{"id":5,"nombre":"Consultorio de prueba"}"""
        return """{"medicos":[$lista],"consultorios":[$consultorio],"especialidades":[],"obras_sociales":[]}"""
    }

    private fun usuarios(): String =
        """{"usuarios":[${medicos.joinToString(",") { usuarioJson(it) }}]}"""

    private fun usuarioJson(m: MedicoFalso): String {
        val mail = "medico${m.id}@ejemplo.test"
        return """{"id":${1000 + m.id},"nombre":"${m.nombre} ${m.apellido}","email":"$mail","rol":"medico","medico_id":${m.id},"activo":${m.activo}}"""
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
        m.historiasClinicas?.let { campos += "\"historias_clinicas\":[${it.joinToString(",") { c -> "\"$c\"" }}]" }
        return "{" + campos.joinToString(",") + "}"
    }

    override fun close() = server.stop(0)

    private companion object {
        const val PREFIJO = "/api/salud360/"
    }
}
