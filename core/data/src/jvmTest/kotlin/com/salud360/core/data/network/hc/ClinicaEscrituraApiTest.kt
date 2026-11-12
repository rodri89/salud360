package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005 RF-12 a RF-15, RF-20 a RF-22, RF-26 (T19) y RF-35 a RF-40, RF-70 (T61, E2): la API de escritura de la HC general en la app, contra un servidor falso
 * en `127.0.0.1` (puerto libre, sin dependencias de prueba nuevas). **Ningún pedido sale a un servidor real**: el contrato es
 * el de `md/API_SALUD360_CLINICA.md` de hclinica, «Escritura (E1)». Se prueba el método, la ruta y el cuerpo de cada pedido, que
 * el token va como en la lectura, que cada error llega con su `status` y su `codigo`, que un error es **un solo pedido** (el
 * cliente no reintenta: eso es del envío diferido) y que una respuesta cortada o incompleta no se toma por buena.
 */
class ClinicaEscrituraApiTest {
    private class Pedido(
        val metodo: String,
        val ruta: String,
        val query: String?,
        val cuerpo: String,
        val contentType: String?,
        val authorization: String?,
        val tokenAparte: String?,
    )

    private class Contexto(val api: ClinicaEscrituraApi, val pedidos: List<Pedido>)

    /** Servidor que contesta [estado] y [respuesta] a todo y anota lo que le llega. */
    private fun conServidor(estado: Int = 200, respuesta: String = "{}", bloque: suspend Contexto.() -> Unit) {
        val pedidos = CopyOnWriteArrayList<Pedido>()
        val servidor = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        servidor.createContext("/") { x ->
            pedidos.add(
                Pedido(
                    x.requestMethod, x.requestURI.path, x.requestURI.rawQuery, x.requestBody.readBytes().toString(Charsets.UTF_8),
                    x.requestHeaders.getFirst("Content-Type"), x.requestHeaders.getFirst("Authorization"), x.requestHeaders.getFirst("X-Salud360-Token"),
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
            runBlocking { Contexto(ClinicaEscrituraApiRemota(cliente), pedidos).bloque() }
        } finally {
            servidor.stop(0)
        }
    }

    private val fecha = LocalDate(2026, 10, 1)
    private val dispositivo = "disp-prueba-1"
    private fun json(texto: String): JsonObject = Json.parseToJsonElement(texto) as JsonObject
    private fun Pedido.cuerpoJson() = json(cuerpo)

    private fun Pedido.assertEnviadoConElToken() {
        assertEquals("Bearer token-de-prueba-1", authorization)
        assertEquals("token-de-prueba-1", tokenAparte, "el mismo encabezado de respaldo que la lectura")
        assertNotNull(contentType)
        assertTrue(contentType!!.startsWith("application/json"), "Content-Type: $contentType")
    }

    private val cabecera = """{"id":7011,"paciente_id":5001,"medico_id":9,"medico_nombre":"Médico Prueba Salud360","tipo":"control","tipo_numero":1,
        "fecha":"2026-10-01","created_at":"2026-10-01 00:00:00","estado":"ABIERTA","edad_mostrar":"45 años","propia":true,"updated_at":"2026-11-06 10:00:00"}"""
    private val cabeceraCerrada = cabecera.replace("\"ABIERTA\"", "\"CERRADA\"")

    // ---------- Crear ----------

    @Test
    fun crear_es_un_post_a_consultas_con_la_clave_y_lo_que_el_medico_cargo() = conServidor(
        201, """{"ok":true,"consulta":$cabecera,"creada":true,"otras_abiertas":[{"id":6990,"medico_nombre":"Otra Médica"},{"id":6980,"medico_nombre":null}]}""",
    ) {
        val r = api.crear("c:uuid-1", dispositivo, 5001, "control", fecha, "45 años")

        val p = pedidos.single()
        assertEquals("POST", p.metodo)
        assertEquals("/api/salud360/consultas", p.ruta)
        assertNull(p.query)
        assertEquals(json("""{"clave":"c:uuid-1","dispositivo":"$dispositivo","paciente_id":"5001","tipo":"control","fecha":"2026-10-01","edad_mostrar":"45 años"}"""), p.cuerpoJson())
        p.assertEnviadoConElToken()
        assertEquals(7011L, r.consulta.id)
        assertEquals("ABIERTA", r.consulta.estado)
        assertTrue(r.consulta.propia)
        assertEquals("2026-10-01", r.consulta.fecha)
        assertTrue(r.creada)
        assertEquals(listOf(6990L to "Otra Médica", 6980L to null), r.otrasAbiertas.map { it.id to it.medicoNombre }, "el aviso de RF-59")
    }

    @Test
    fun repetir_el_envio_de_crear_contesta_200_con_creada_falso_y_lo_mismo() = conServidor(
        200, """{"ok":true,"consulta":$cabecera,"creada":false,"otras_abiertas":[]}""",
    ) {
        val r = api.crear("c:uuid-1", dispositivo, 5001, "telemedicina", fecha, null)

        assertEquals(false, r.creada)
        assertEquals(7011L, r.consulta.id)
        assertEquals(emptyList(), r.otrasAbiertas)
        assertEquals(setOf("clave", "dispositivo", "paciente_id", "tipo", "fecha"), pedidos.single().cuerpoJson().keys, "sin edad no manda edad_mostrar")
    }

    // ---------- Secciones y examen ----------

    @Test
    fun secciones_es_un_put_con_solo_lo_tocado_y_contesta_guardadas_y_desconocidas() = conServidor(
        200, """{"ok":true,"guardadas":["nota","conductas"],"desconocidas":["tabaquismo"]}""",
    ) {
        val r = api.secciones(7011, mapOf("nota" to "Control", "conductas" to ""))

        val p = pedidos.single()
        assertEquals("PUT", p.metodo)
        assertEquals("/api/salud360/consultas/7011/secciones", p.ruta)
        assertEquals(json("""{"secciones":{"nota":{"texto":"Control"},"conductas":{"texto":""}}}"""), p.cuerpoJson())
        p.assertEnviadoConElToken()
        assertEquals(listOf("nota", "conductas"), r.guardadas)
        assertEquals(listOf("tabaquismo"), r.desconocidas)
    }

    @Test
    fun el_examen_es_un_put_con_solo_las_columnas_tocadas() = conServidor(200, """{"ok":true,"guardadas":["peso","imc"],"desconocidas":[]}""") {
        val r = api.examen(7011, mapOf("peso" to "70.5", "imc" to "0.00"))

        val p = pedidos.single()
        assertEquals("PUT", p.metodo)
        assertEquals("/api/salud360/consultas/7011/examen", p.ruta)
        assertEquals(json("""{"peso":"70.5","imc":""}"""), p.cuerpoJson())
        p.assertEnviadoConElToken()
        assertEquals(listOf("peso", "imc"), r.guardadas)
        assertEquals(emptyList(), r.desconocidas)
    }

    @Test
    fun sin_nada_que_mandar_no_sale_ningun_pedido() = conServidor {
        val a = api.secciones(7011, emptyMap())
        val b = api.secciones(7011, mapOf("tabaquismo" to "x"))
        val c = api.examen(7011, emptyMap())
        val d = api.examen(7011, mapOf("frecuencia_cardiaca" to "72"))

        assertEquals(0, pedidos.size, "un cuerpo vacío no se manda")
        for (r in listOf(a, b, c, d)) {
            assertEquals(emptyList(), r.guardadas)
            assertEquals(emptyList(), r.desconocidas)
        }
    }

    // ---------- Cerrar y baja ----------

    @Test
    fun cerrar_es_un_post_con_la_clave_de_cierre_la_fecha_clinica_y_la_edad() = conServidor(
        200, """{"ok":true,"consulta":$cabeceraCerrada,"cerrada":true}""",
    ) {
        val r = api.cerrar(7011, "c:uuid-1-cierre", dispositivo, LocalDate(2026, 11, 2), "46 años")

        val p = pedidos.single()
        assertEquals("POST", p.metodo)
        assertEquals("/api/salud360/consultas/7011/cerrar", p.ruta)
        assertEquals(json("""{"clave":"c:uuid-1-cierre","dispositivo":"$dispositivo","fecha":"2026-11-02","edad_mostrar":"46 años"}"""), p.cuerpoJson())
        p.assertEnviadoConElToken()
        assertTrue(r.cerrada)
        assertEquals("CERRADA", r.consulta.estado)
    }

    @Test
    fun la_baja_es_un_delete_con_cuerpo_json_con_la_clave_y_el_dispositivo() = conServidor(200, """{"ok":true,"baja":true,"consulta_id":7011}""") {
        val r = api.baja(7011, "c:uuid-1-baja", dispositivo)

        val p = pedidos.single()
        assertEquals("DELETE", p.metodo)
        assertEquals("/api/salud360/consultas/7011", p.ruta)
        assertEquals(json("""{"clave":"c:uuid-1-baja","dispositivo":"$dispositivo"}"""), p.cuerpoJson(), "el DELETE lleva cuerpo, igual que un POST")
        p.assertEnviadoConElToken()
        assertTrue(r.baja)
        assertEquals(7011L, r.consultaId)
    }

    // ---------- E2: formularios, antecedentes y registros (T61) ----------

    private fun ant(clave: String, flag: Boolean = false, detalle: String = "", activo: Boolean = true, categoria: String = "familiares") = Antecedente(
        id = "a-$clave", pacienteId = "p1", especialidad = "clinica", categoria = categoria, clave = clave, flag = flag, detalle = detalle,
        consultaId = "c1", activo = activo,
    )

    private fun reg(
        id: String,
        tipo: String,
        campos: Map<String, String> = emptyMap(),
        fecha: LocalDate? = null,
        activo: Boolean = true,
        remotoId: String = "",
    ) = RegistroClinico(
        id = id, pacienteId = "p1", consultaId = "c1", especialidad = "clinica", tipo = tipo, fecha = fecha, campos = campos, activo = activo, remotoId = remotoId,
    )

    @Test
    fun formularios_es_un_put_con_solo_lo_tocado_y_contesta_guardadas_y_desconocidas() = conServidor(
        200, """{"ok":true,"guardadas":["alimentacion.hiposodica","serologias.hiv","child_pugh.ascitis"],"desconocidas":["serologias.xx"]}""",
    ) {
        val r = api.formularios(
            7011,
            mapOf(
                "alimentacion" to mapOf("hiposodica" to "1|sin sal", "otra" to ""),
                "serologias" to mapOf("hiv" to "NO"),
                "child_pugh" to mapOf("ascitis" to "2"),
            ),
        )

        val p = pedidos.single()
        assertEquals("PUT", p.metodo)
        assertEquals("/api/salud360/consultas/7011/formularios", p.ruta)
        assertNull(p.query)
        assertEquals(
            json("""{"formularios":{"alimentacion":{"hiposodica":"1|sin sal","otra":""},"serologias":{"hiv":"NO"},"child_pugh":{"ascitis":"2"}}}"""),
            p.cuerpoJson(),
        )
        p.assertEnviadoConElToken()
        assertEquals(listOf("alimentacion.hiposodica", "serologias.hiv", "child_pugh.ascitis"), r.guardadas)
        assertEquals(listOf("serologias.xx"), r.desconocidas)
    }

    @Test
    fun antecedentes_es_un_put_a_la_ficha_con_la_consulta_las_casillas_y_la_nota() = conServidor(
        200, """{"ok":true,"id":321,"guardadas":["hta","nota"],"desconocidas":[]}""",
    ) {
        val r = api.antecedentes(5001, 7011, listOf(ant("hta", true, "padre"), ant("nota", true, "Madre diabética"), ant("tabaquismo", true, "no viaja")))

        val p = pedidos.single()
        assertEquals("PUT", p.metodo)
        assertEquals("/api/salud360/pacientes/5001/antecedentes-familiares", p.ruta)
        assertNull(p.query)
        assertEquals(json("""{"consulta_id":"7011","casillas":{"hta":{"flag":"1","detalle":"padre"}},"nota":"Madre diabética"}"""), p.cuerpoJson())
        p.assertEnviadoConElToken()
        assertEquals(321L, r.id)
        assertEquals(listOf("hta", "nota"), r.guardadas)
        assertEquals(emptyList(), r.desconocidas)
    }

    @Test
    fun antecedentes_manda_solo_lo_que_cambio_contra_lo_enviado() = conServidor(200, """{"ok":true,"id":321,"guardadas":["acv"],"desconocidas":[]}""") {
        api.antecedentes(5001, 7011, listOf(ant("hta", true, "padre"), ant("acv", true, "madre")), mapOf("hta" to ant("hta", true, "padre")))

        assertEquals(json("""{"consulta_id":"7011","casillas":{"acv":{"flag":"1","detalle":"madre"}}}"""), pedidos.single().cuerpoJson())
    }

    @Test
    fun registros_es_un_put_con_los_items_y_lee_los_ids_por_ref() = conServidor(
        200, """{"ok":true,"ids":{"r:e1":501,"r:x1":"40"},"guardadas":["r:x1"],"desconocidas":["r:z9"]}""",
    ) {
        val r = api.registros(
            7011,
            listOf(
                reg("e1", "eco_abdominal", mapOf("detalle" to "Normal"), LocalDate(2026, 10, 1)),
                reg("x1", "examen_complementario", mapOf("solicito" to "Eco"), activo = false, remotoId = "40"),
            ),
        )

        val p = pedidos.single()
        assertEquals("PUT", p.metodo)
        assertEquals("/api/salud360/consultas/7011/registros", p.ruta)
        assertNull(p.query)
        assertEquals(
            json(
                """{"items":[{"ref":"r:e1","tipo":"eco_abdominal","fecha":"2026-10-01","campos":{"detalle":"Normal"}},
                    {"ref":"r:x1","tipo":"examen_complementario","id":"40","campos":{},"borrado":true}]}""",
            ),
            p.cuerpoJson(),
        )
        p.assertEnviadoConElToken()
        assertEquals(mapOf("r:e1" to 501L, "r:x1" to 40L), r.idsPorRef(), "el id como número o como texto")
        assertEquals(listOf("r:x1"), r.guardadas)
        assertEquals(listOf("r:z9"), r.desconocidas)
    }

    @Test
    fun registros_tolera_un_ids_vacio_codificado_como_lista_por_php() = conServidor(200, """{"ok":true,"ids":[],"guardadas":[],"desconocidas":["r:z9"]}""") {
        val r = api.registros(7011, listOf(reg("e1", "eco_abdominal", mapOf("detalle" to "x"), LocalDate(2026, 10, 1))))

        assertEquals(emptyMap(), r.idsPorRef())
        assertEquals(listOf("r:z9"), r.desconocidas)
    }

    @Test
    fun el_422_de_los_formularios_y_de_los_registros_trae_el_campo_y_el_motivo() {
        conServidor(
            422, """{"ok":false,"mensaje":"El dato no es válido.","codigo":"datos_invalidos","campo":"formularios.alimentacion.hiposodica","motivo":"flag_invalido"}""",
        ) {
            val e = assertFailsWith<TobbException> { api.formularios(7011, mapOf("alimentacion" to mapOf("hiposodica" to "1|x"))) }

            assertEquals(422, e.status)
            assertEquals("datos_invalidos", e.codigo)
            assertEquals("formularios.alimentacion.hiposodica", e.cuerpo?.get("campo")?.jsonPrimitive?.content)
            assertEquals("flag_invalido", e.cuerpo?.get("motivo")?.jsonPrimitive?.content)
            assertEquals(1, pedidos.size)
        }
        conServidor(
            422, """{"ok":false,"mensaje":"El dato no es válido.","codigo":"datos_invalidos","campo":"items.0.fecha","motivo":"fecha_invalida"}""",
        ) {
            val e = assertFailsWith<TobbException> { api.registros(7011, listOf(reg("e1", "veda", mapOf("detalle" to "x"), LocalDate(2026, 10, 1)))) }

            assertEquals("items.0.fecha", e.cuerpo?.get("campo")?.jsonPrimitive?.content)
            assertEquals("fecha_invalida", e.cuerpo?.get("motivo")?.jsonPrimitive?.content)
        }
        conServidor(
            422, """{"ok":false,"mensaje":"Es demasiado largo.","codigo":"datos_invalidos","campo":"items.0.campos.detalle","motivo":"texto_demasiado_largo"}""",
        ) {
            val e = assertFailsWith<TobbException> { api.registros(7011, listOf(reg("e1", "estudios_cardiologicos", mapOf("detalle" to "x"), LocalDate(2026, 10, 1)))) }

            assertEquals("texto_demasiado_largo", e.cuerpo?.get("motivo")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun una_respuesta_200_sin_guardadas_o_sin_ids_no_es_un_exito() {
        // formularios y antecedentes: sin `guardadas` no se sabe qué se escribió.
        conServidor(200, """{"ok":true,"desconocidas":[]}""") {
            val e = assertFailsWith<TobbException> { api.formularios(7011, mapOf("serologias" to mapOf("hiv" to "NO"))) }
            assertEquals("respuesta", e.codigo)
        }
        conServidor(200, """{"ok":true,"id":321}""") {
            val e = assertFailsWith<TobbException> { api.antecedentes(5001, 7011, listOf(ant("hta", true, "x"))) }
            assertEquals("respuesta", e.codigo)
        }
        // registros: sin `ids` no se puede anotar el `remoto_id`; sin `guardadas` no se sabe qué se escribió.
        conServidor(200, """{"ok":true,"guardadas":["r:e1"],"desconocidas":[]}""") {
            val e = assertFailsWith<TobbException> { api.registros(7011, listOf(reg("e1", "veda", mapOf("detalle" to "x"), LocalDate(2026, 10, 1)))) }
            assertEquals("respuesta", e.codigo)
        }
        conServidor(200, """{"ok":true,"ids":{"r:e1":501},"desconocidas":[]}""") {
            val e = assertFailsWith<TobbException> { api.registros(7011, listOf(reg("e1", "veda", mapOf("detalle" to "x"), LocalDate(2026, 10, 1)))) }
            assertEquals("respuesta", e.codigo)
        }
    }

    @Test
    fun sin_nada_que_mandar_las_tres_operaciones_de_E2_no_hacen_ningun_pedido() = conServidor {
        val a = api.formularios(7011, emptyMap())
        val b = api.formularios(7011, mapOf("serologias" to mapOf("hiv" to "quizás"), "tabaquismo" to mapOf("x" to "1")))
        val c = api.antecedentes(5001, 7011, listOf(ant("tabaquismo", true, "x"), ant("hta", true, "x", categoria = "personales"), ant("acv", true, "x", activo = false)))
        val d = api.antecedentes(5001, 7011, listOf(ant("hta", true, "padre")), mapOf("hta" to ant("hta", true, "padre")))
        val e = api.registros(7011, emptyList())
        val f = api.registros(7011, listOf(reg("n1", "documentos", mapOf("detalle" to "x"), LocalDate(2026, 10, 1)), reg("e2", "veda", mapOf("detalle" to ""), LocalDate(2026, 10, 1))))

        assertEquals(0, pedidos.size, "un cuerpo vacío no se manda")
        for (r in listOf(a, b)) {
            assertEquals(emptyList(), r.guardadas)
            assertEquals(emptyList(), r.desconocidas)
        }
        for (r in listOf(c, d)) {
            assertNull(r.id)
            assertEquals(emptyList(), r.guardadas)
        }
        for (r in listOf(e, f)) {
            assertEquals(emptyMap(), r.idsPorRef())
            assertEquals(emptyList(), r.guardadas)
            assertEquals(emptyList(), r.desconocidas)
        }
    }

    // ---------- E3: laboratorio general y peso (T81) ----------

    private val fechaDeCarga = LocalDate(2026, 11, 6)
    private val idCarga = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"

    private fun carga(
        id: String = idCarga,
        valores: Map<String, String> = mapOf("hb" to "13.5", "otro" to "control en 3 meses"),
        tipo: String = "laboratorio_general",
        fecha: LocalDate = fechaDeCarga,
    ) = Laboratorio(id = id, pacienteId = "p1", consultaId = "c1", especialidad = "clinica", tipo = tipo, fecha = fecha, valores = valores)

    @Test
    fun laboratorio_es_un_put_a_la_ficha_con_la_clave_la_consulta_la_fecha_y_los_valores() = conServidor(
        200, """{"ok":true,"id":"731","guardadas":["hb","otro"],"desconocidas":["inventado"]}""",
    ) {
        val r = api.laboratorio(5001, 7011, dispositivo, fechaDeCarga, carga())

        val p = pedidos.single()
        assertEquals("PUT", p.metodo)
        assertEquals("/api/salud360/pacientes/5001/laboratorios", p.ruta)
        assertNull(p.query)
        assertEquals(
            json(
                """{"clave":"l:$idCarga","dispositivo":"$dispositivo","consulta_id":"7011","tipo":"laboratorio_general",
                    "fecha":"2026-11-06","valores":{"hb":"13.5","otro":"control en 3 meses"}}""",
            ),
            p.cuerpoJson(),
        )
        p.assertEnviadoConElToken()
        assertEquals("731", r.id, "el id de la fila de la web viene como texto")
        assertEquals(listOf("hb", "otro"), r.guardadas)
        assertEquals(listOf("inventado"), r.desconocidas)
    }

    @Test
    fun el_peso_es_un_put_a_pesos_con_la_clave_peso_y_sin_tipo() = conServidor(200, """{"ok":true,"id":"890","guardadas":["peso"],"desconocidas":[]}""") {
        val r = api.peso(5001, 7011, dispositivo, fechaDeCarga, carga(valores = mapOf("peso" to "72.5"), tipo = "peso"))

        val p = pedidos.single()
        assertEquals("PUT", p.metodo)
        assertEquals("/api/salud360/pacientes/5001/pesos", p.ruta)
        assertEquals(
            json("""{"clave":"l:$idCarga","dispositivo":"$dispositivo","consulta_id":"7011","fecha":"2026-11-06","valores":{"peso":"72.5"}}"""),
            p.cuerpoJson(),
            "el peso viaja como `peso`, no como `valor`",
        )
        p.assertEnviadoConElToken()
        assertEquals("890", r.id)
        assertEquals(listOf("peso"), r.guardadas)
    }

    @Test
    fun la_actualizacion_de_una_carga_lleva_el_id_y_solo_lo_tocado() = conServidor(200, """{"ok":true,"id":"731","guardadas":["hb"],"desconocidas":[]}""") {
        val antes = carga(valores = mapOf("hb" to "13.5", "otro" to "control en 3 meses"))

        api.laboratorio(5001, 7011, dispositivo, LocalDate(2026, 11, 7), antes.copy(fecha = LocalDate(2026, 11, 7), valores = mapOf("hb" to "14", "otro" to "control en 3 meses")), idWeb = 731, confirmada = antes)

        assertEquals(
            json("""{"clave":"l:$idCarga","dispositivo":"$dispositivo","consulta_id":"7011","tipo":"laboratorio_general","id":"731","fecha":"2026-11-07","valores":{"hb":"14"}}"""),
            pedidos.single().cuerpoJson(),
        )
    }

    @Test
    fun la_baja_de_una_carga_es_un_delete_con_cuerpo_json_a_la_ruta_del_tipo() = conServidor(200, """{"ok":true,"baja":true,"id":"731"}""") {
        val r = api.bajaDeCarga(5001, 731, 7011, "l:$idCarga-baja", dispositivo)

        val p = pedidos.single()
        assertEquals("DELETE", p.metodo)
        assertEquals("/api/salud360/pacientes/5001/laboratorios/731", p.ruta)
        assertNull(p.query)
        assertEquals(json("""{"clave":"l:$idCarga-baja","dispositivo":"$dispositivo","consulta_id":"7011"}"""), p.cuerpoJson(), "el DELETE lleva cuerpo JSON")
        p.assertEnviadoConElToken()
        assertTrue(r.baja)
        assertEquals("731", r.id)
    }

    @Test
    fun la_baja_de_un_peso_va_a_pesos_y_el_422_de_la_carga_trae_el_campo_y_el_motivo() {
        conServidor(200, """{"ok":true,"baja":true,"id":"890"}""") {
            api.bajaDeCarga(5001, 890, 7011, "l:$idCarga-baja", dispositivo, tipo = "peso")

            assertEquals("/api/salud360/pacientes/5001/pesos/890", pedidos.single().ruta)
        }
        conServidor(
            422, """{"ok":false,"mensaje":"El dato «fecha» tiene que ser una fecha AAAA-MM-DD válida.","codigo":"datos_invalidos","campo":"fecha","motivo":"fecha_invalida"}""",
        ) {
            val e = assertFailsWith<TobbException> { api.peso(5001, 7011, dispositivo, fechaDeCarga, carga(valores = mapOf("peso" to "72"), tipo = "peso")) }

            assertEquals(422, e.status)
            assertEquals("datos_invalidos", e.codigo)
            assertEquals("fecha", e.cuerpo?.get("campo")?.jsonPrimitive?.content)
            assertEquals("fecha_invalida", e.cuerpo?.get("motivo")?.jsonPrimitive?.content)
            assertEquals(1, pedidos.size)
        }
        conServidor(
            422, """{"ok":false,"mensaje":"El dato «valores» no trae ningún valor con contenido.","codigo":"datos_invalidos","campo":"valores","motivo":"carga_vacia"}""",
        ) {
            val e = assertFailsWith<TobbException> { api.laboratorio(5001, 7011, dispositivo, fechaDeCarga, carga()) }

            assertEquals("valores", e.cuerpo?.get("campo")?.jsonPrimitive?.content)
            assertEquals("carga_vacia", e.cuerpo?.get("motivo")?.jsonPrimitive?.content)
        }
        conServidor(
            422, """{"ok":false,"mensaje":"El dato «valores.hb» tiene que tener menos caracteres.","codigo":"datos_invalidos","campo":"valores.hb","motivo":"texto_demasiado_largo"}""",
        ) {
            val e = assertFailsWith<TobbException> { api.laboratorio(5001, 7011, dispositivo, fechaDeCarga, carga()) }

            assertEquals("valores.hb", e.cuerpo?.get("campo")?.jsonPrimitive?.content)
            assertEquals("texto_demasiado_largo", e.cuerpo?.get("motivo")?.jsonPrimitive?.content)
        }
        conServidor(
            422, """{"ok":false,"mensaje":"El dato «clave» ya se usó para otra operación u otra consulta.","codigo":"datos_invalidos","campo":"clave","motivo":"clave_en_uso"}""",
        ) {
            val e = assertFailsWith<TobbException> { api.laboratorio(5001, 7011, dispositivo, fechaDeCarga, carga()) }

            assertEquals("clave_en_uso", e.cuerpo?.get("motivo")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun los_errores_de_una_carga_ajena_llegan_con_su_status_y_su_codigo() {
        conServidor(403, """{"ok":false,"mensaje":"Esa carga no es tuya.","codigo":"carga_no_propia"}""") {
            val e = assertFailsWith<TobbException> { api.laboratorio(5001, 7011, dispositivo, fechaDeCarga, carga()) }

            assertEquals(403, e.status)
            assertEquals("carga_no_propia", e.codigo)
            assertEquals(1, pedidos.size, "el cliente no reintenta")
        }
        conServidor(404, """{"ok":false,"mensaje":"La carga no existe para ese paciente.","codigo":"carga_no_encontrada"}""") {
            val e = assertFailsWith<TobbException> { api.bajaDeCarga(5001, 731, 7011, "l:$idCarga-baja", dispositivo) }

            assertEquals(404, e.status)
            assertEquals("carga_no_encontrada", e.codigo)
            assertEquals(1, pedidos.size)
        }
    }

    @Test
    fun sin_nada_que_mandar_las_cargas_no_hacen_ningun_pedido() = conServidor {
        val a = api.laboratorio(5001, 7011, dispositivo, fechaDeCarga, carga(valores = mapOf("hb" to "", "otro" to "   ")))
        val b = api.peso(5001, 7011, dispositivo, fechaDeCarga, carga(valores = emptyMap(), tipo = "peso"))
        val c = api.laboratorio(5001, 7011, dispositivo, fechaDeCarga, carga(), idWeb = 731, confirmada = carga())

        assertEquals(0, pedidos.size, "un cuerpo vacío no se manda")
        for (r in listOf(a, b, c)) {
            assertEquals("", r.id, "no hubo pedido: no hay id de la web")
            assertEquals(emptyList(), r.guardadas)
            assertEquals(emptyList(), r.desconocidas)
        }
    }

    @Test
    fun una_respuesta_200_de_una_carga_sin_id_o_sin_guardadas_no_es_un_exito() {
        conServidor(200, """{"ok":true,"guardadas":[],"desconocidas":[]}""") {
            val e = assertFailsWith<TobbException> { api.laboratorio(5001, 7011, dispositivo, fechaDeCarga, carga()) }

            assertEquals("respuesta", e.codigo, "sin `id` no se sabe qué fila de la web quedó")
        }
        conServidor(200, """{"ok":true,"id":"731","desconocidas":[]}""") {
            val e = assertFailsWith<TobbException> { api.laboratorio(5001, 7011, dispositivo, fechaDeCarga, carga()) }

            assertEquals("respuesta", e.codigo, "sin `guardadas` no se sabe qué se escribió")
        }
        conServidor(200, """{"ok":true,"id":"731"}""") {
            val e = assertFailsWith<TobbException> { api.bajaDeCarga(5001, 731, 7011, "l:$idCarga-baja", dispositivo) }

            assertEquals("respuesta", e.codigo, "la baja sin `baja` no es un éxito")
        }
    }

    // ---------- Errores ----------

    private class Operacion(val nombre: String, val llamar: suspend (ClinicaEscrituraApi) -> Unit)

    private val fecha2 = LocalDate(2026, 11, 2)
    private val operaciones = listOf(
        Operacion("crear") { it.crear("c:uuid-1", dispositivo, 5001, "control", fecha, "45 años") },
        Operacion("secciones") { it.secciones(7011, mapOf("nota" to "x")) },
        Operacion("examen") { it.examen(7011, mapOf("peso" to "70")) },
        Operacion("cerrar") { it.cerrar(7011, "c:uuid-1-cierre", dispositivo, fecha2, null) },
        Operacion("baja") { it.baja(7011, "c:uuid-1-baja", dispositivo) },
        Operacion("formularios") { it.formularios(7011, mapOf("serologias" to mapOf("hiv" to "NO"))) },
        Operacion("antecedentes") { it.antecedentes(5001, 7011, listOf(ant("hta", true, "padre"))) },
        Operacion("registros") { it.registros(7011, listOf(reg("e1", "eco_abdominal", mapOf("detalle" to "Normal"), LocalDate(2026, 10, 1)))) },
        Operacion("laboratorio") { it.laboratorio(5001, 7011, dispositivo, fechaDeCarga, carga()) },
        Operacion("peso") { it.peso(5001, 7011, dispositivo, fechaDeCarga, carga(valores = mapOf("peso" to "72.5"), tipo = "peso")) },
        Operacion("bajaDeCarga") { it.bajaDeCarga(5001, 731, 7011, "l:$idCarga-baja", dispositivo) },
    )

    private val errores = listOf(
        Triple(401, "sin_token", "Falta el token."),
        Triple(401, "token", "Sesión vencida. Volvé a iniciar sesión."),
        Triple(403, "sin_permiso", "Solo los médicos pueden cargar consultas."),
        Triple(403, "fuera_de_cartera", "Ese paciente no está en tu cartera."),
        Triple(403, "consulta_no_propia", "Esa consulta no es tuya."),
        Triple(403, "hc_no_habilitada", "No tenés habilitada esta historia clínica."),
        Triple(403, "medico_no_vinculado", "Falta vincular al médico."),
        Triple(403, "licencia_vencida", "Tu licencia está vencida."),
        Triple(404, "paciente_no_encontrado", "Paciente no encontrado."),
        Triple(404, "consulta_no_encontrada", "Consulta no encontrada."),
        Triple(409, "consulta_cerrada", "La consulta está cerrada."),
        Triple(422, "datos_invalidos", "El dato «fecha» no es válido."),
        Triple(500, "escritura", "No se pudo guardar la consulta."),
        Triple(503, "tobb_caido", "No se pudo validar la sesión."),
    )

    @Test
    fun cada_error_llega_con_su_status_y_su_codigo_en_cada_operacion_y_es_un_solo_pedido() {
        for (op in operaciones) {
            for ((status, codigo, mensaje) in errores) {
                conServidor(status, """{"ok":false,"mensaje":"$mensaje","codigo":"$codigo"}""") {
                    val e = assertFailsWith<TobbException>("${op.nombre} $status $codigo") { op.llamar(api) }

                    assertEquals(status, e.status, "${op.nombre} $codigo")
                    assertEquals(codigo, e.codigo, "${op.nombre} $status")
                    assertEquals(mensaje, e.message)
                    assertEquals(1, pedidos.size, "${op.nombre} $status $codigo: el cliente no reintenta")
                }
            }
        }
    }

    @Test
    fun el_429_de_laravel_llega_sin_codigo_y_no_se_reintenta() {
        for (op in operaciones) {
            conServidor(429, """{"message":"Too Many Attempts."}""") {
                val e = assertFailsWith<TobbException>(op.nombre) { op.llamar(api) }

                assertEquals(429, e.status)
                assertNull(e.codigo)
                assertEquals(1, pedidos.size, op.nombre)
            }
        }
    }

    @Test
    fun el_422_trae_el_campo_y_el_motivo_para_quien_decide_que_hacer() = conServidor(
        422, """{"ok":false,"mensaje":"El dato «clave» ya se usó para otra operación u otra consulta.","codigo":"datos_invalidos","campo":"clave","motivo":"clave_en_uso"}""",
    ) {
        val e = assertFailsWith<TobbException> { api.cerrar(7011, "c:uuid-1", dispositivo, fecha2, null) }

        // `clave_en_uso` no es un `codigo` del servidor: es el `motivo` de un `422 datos_invalidos`.
        assertEquals(422, e.status)
        assertEquals("datos_invalidos", e.codigo)
        assertEquals("clave", e.cuerpo?.get("campo")?.jsonPrimitive?.content)
        assertEquals("clave_en_uso", e.cuerpo?.get("motivo")?.jsonPrimitive?.content)
    }

    @Test
    fun el_422_de_un_texto_demasiado_largo_trae_su_motivo() = conServidor(
        422, """{"ok":false,"mensaje":"El dato «nota» es demasiado largo.","codigo":"datos_invalidos","campo":"nota","motivo":"texto_demasiado_largo"}""",
    ) {
        val e = assertFailsWith<TobbException> { api.secciones(7011, mapOf("nota" to "x")) }

        assertEquals("texto_demasiado_largo", e.cuerpo?.get("motivo")?.jsonPrimitive?.content)
    }

    @Test
    fun el_403_de_licencia_conserva_el_cuerpo() = conServidor(
        403, """{"ok":false,"mensaje":"Tu licencia está vencida.","codigo":"licencia_vencida","licencia":{"vence":"2031-06-15","vencida":true}}""",
    ) {
        val e = assertFailsWith<TobbException> { api.crear("c:uuid-1", dispositivo, 5001, "control", fecha, null) }

        assertEquals("licencia_vencida", e.codigo)
        assertNotNull(e.cuerpo?.get("licencia"))
    }

    // ---------- Respuestas que no sirven ----------

    @Test
    fun un_json_cortado_no_se_toma_por_bueno_en_ninguna_operacion() {
        val cortado = """{"ok":true,"consulta":{"id":7011,"paciente_id":5001,"fecha":"2026-10-01","est"""
        for (op in operaciones) {
            conServidor(200, cortado) {
                val e = assertFailsWith<TobbException>(op.nombre) { op.llamar(api) }

                assertEquals(200, e.status, op.nombre)
                assertNull(e.codigo, op.nombre)
                assertEquals(1, pedidos.size, "${op.nombre}: no se reintenta solo")
            }
        }
    }

    @Test
    fun una_respuesta_sin_lo_esperado_es_un_error_de_respuesta_y_no_un_exito() {
        for (op in operaciones) {
            conServidor(200, """{"ok":true}""") {
                val e = assertFailsWith<TobbException>(op.nombre) { op.llamar(api) }

                assertEquals(0, e.status, op.nombre)
                assertEquals("respuesta", e.codigo, op.nombre)
            }
        }
    }

    @Test
    fun una_respuesta_con_ok_falso_es_un_error_aunque_el_status_sea_200() = conServidor(200, """{"ok":false,"mensaje":"no se pudo","codigo":"escritura"}""") {
        val e = assertFailsWith<TobbException> { api.baja(7011, "c:uuid-1-baja", dispositivo) }

        assertEquals(200, e.status)
        assertEquals("escritura", e.codigo)
    }

    @Test
    fun sin_conexion_es_un_error_de_red_con_un_solo_intento() {
        val puerto = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val api = ClinicaEscrituraApiRemota(HcApiClient("http://127.0.0.1:$puerto", "clinica").apply { token = "token-de-prueba-1" })

        for (op in operaciones) {
            val e = runBlocking { assertFailsWith<TobbException>(op.nombre) { op.llamar(api) } }

            assertEquals(0, e.status, op.nombre)
            assertEquals("red", e.codigo, op.nombre)
        }
    }

    // ---------- Por construcción ----------

    @Test
    fun la_remota_recibe_el_cliente_completo_y_la_interfaz_tiene_las_once_operaciones() {
        assertEquals(listOf(HcApiClient::class.java), ClinicaEscrituraApiRemota::class.java.constructors.single().parameterTypes.toList())
        val operacionesDeLaInterfaz = ClinicaEscrituraApi::class.java.declaredMethods.map { it.name }.filter { '$' !in it }.toSet()
        // T61 sumó las tres de E2 y T81 las tres de E3 (laboratorio general y peso; nada de pendientes ni adjuntos: E4).
        assertEquals(
            setOf("crear", "secciones", "examen", "cerrar", "baja", "formularios", "antecedentes", "registros", "laboratorio", "peso", "bajaDeCarga"),
            operacionesDeLaInterfaz,
        )
    }
}
