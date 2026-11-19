package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005 RF-44 a RF-46 (T102): la API de adjuntos de la HC general en la app, contra un servidor falso en `127.0.0.1`
 * (puerto libre, sin dependencias de prueba nuevas). **Ningún pedido sale a un servidor real**: el contrato es el de
 * `md/API_SALUD360_CLINICA.md` de hclinica, «Escritura (E4)». Se prueba el método, la ruta y el cuerpo de cada pedido
 * (la subida, además, con sus bytes exactos y el multipart completo), que el token va como en la lectura, que cada error
 * llega con su `status` y su `codigo`, que un error es **un solo pedido** (el cliente no reintenta: eso es del envío
 * diferido), que el `413` del servidor web se lee como `archivo_grande` y que una respuesta cortada o incompleta no se
 * toma por buena. Las fotos de exámenes (`tipo: "foto_examen"`) quedaron fuera de alcance: el servidor contesta
 * `422 tipo_invalido` y acá solo se comprueba que el error llega tal cual.
 */
class ClinicaAdjuntosApiTest {
    private class Pedido(
        val metodo: String,
        val ruta: String,
        val query: String?,
        val cuerpo: ByteArray,
        val contentType: String?,
        val authorization: String?,
        val tokenAparte: String?,
    ) {
        fun texto(): String = cuerpo.toString(Charsets.UTF_8)
    }

    /** Una parte de un cuerpo multiparte: los campos de texto y el archivo. */
    private class Parte(val nombre: String, val nombreDeArchivo: String?, val tipoDeContenido: String?, val bytes: ByteArray)

    private class Respuesta(val estado: Int, val cuerpo: ByteArray, val contentType: String)

    private class Contexto(val api: ClinicaAdjuntosApi, val pedidos: List<Pedido>)

    private fun respuestaJson(estado: Int = 200, cuerpo: String = "{}") =
        Respuesta(estado, cuerpo.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    /** Servidor que contesta lo que diga [manejar] y anota lo que le llega. */
    private fun conServidor(manejar: (Pedido) -> Respuesta, bloque: suspend Contexto.() -> Unit) {
        val pedidos = CopyOnWriteArrayList<Pedido>()
        val servidor = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        servidor.createContext("/") { x ->
            val pedido = Pedido(
                x.requestMethod, x.requestURI.path, x.requestURI.rawQuery, x.requestBody.readBytes(),
                x.requestHeaders.getFirst("Content-Type"), x.requestHeaders.getFirst("Authorization"), x.requestHeaders.getFirst("X-Salud360-Token"),
            )
            pedidos.add(pedido)
            val r = manejar(pedido)
            x.responseHeaders.add("Content-Type", r.contentType)
            x.sendResponseHeaders(r.estado, r.cuerpo.size.toLong())
            x.responseBody.use { it.write(r.cuerpo) }
        }
        servidor.start()
        try {
            val cliente = HcApiClient("http://127.0.0.1:${servidor.address.port}", "clinica").apply { token = "token-de-prueba-1" }
            runBlocking { Contexto(ClinicaAdjuntosApiRemota(cliente), pedidos).bloque() }
        } finally {
            servidor.stop(0)
        }
    }

    /** Servidor JSON: contesta [estado] y [respuesta] a todo. */
    private fun conServidor(estado: Int = 200, respuesta: String = "{}", bloque: suspend Contexto.() -> Unit) =
        conServidor({ respuestaJson(estado, respuesta) }, bloque)

    private fun Pedido.assertEnviadoConElToken() {
        assertEquals("Bearer token-de-prueba-1", authorization)
        assertEquals("token-de-prueba-1", tokenAparte, "el mismo encabezado de respaldo que la lectura")
    }

    /** Un pedido con cuerpo (la subida y la baja) lleva su `Content-Type`; un `GET` o un `DELETE` sin cuerpo, no. */
    private fun Pedido.assertJson() {
        assertNotNull(contentType)
        assertTrue(contentType.startsWith("application/json"), "Content-Type: $contentType")
        assertEnviadoConElToken()
    }

    /**
     * Las partes de un cuerpo multiparte, por `name`. Se parte el cuerpo como texto ISO-8859-1 (byte por byte, sin
     * perder nada) y se vuelve a bytes con el mismo juego, así el archivo se compara **exacto**.
     */
    private fun Pedido.partes(): Map<String, Parte> {
        val tipo = assertNotNull(contentType, "la subida no trae Content-Type")
        assertTrue(tipo.startsWith("multipart/form-data"), "Content-Type: $tipo")
        val boundary = tipo.substringAfter("boundary=", "").trim('"')
        assertTrue(boundary.isNotEmpty(), "el multipart no trae boundary: $tipo")
        val partes = linkedMapOf<String, Parte>()
        for (trozo in cuerpo.toString(Charsets.ISO_8859_1).split("--$boundary").drop(1)) {
            if (trozo.startsWith("--")) continue // el cierre del multipart
            val limpio = trozo.removePrefix("\r\n")
            val cabecera = limpio.substringBefore("\r\n\r\n")
            val contenido = limpio.substringAfter("\r\n\r\n").removeSuffix("\r\n")
            var nombre: String? = null
            var nombreDeArchivo: String? = null
            var tipoDeLaParte: String? = null
            for (linea in cabecera.split("\r\n")) {
                when {
                    linea.startsWith("Content-Disposition", ignoreCase = true) -> {
                        nombre = valorDeParametro(linea, "name")
                        nombreDeArchivo = valorDeParametro(linea, "filename")
                    }
                    linea.startsWith("Content-Type", ignoreCase = true) -> tipoDeLaParte = linea.substringAfter(":").trim()
                }
            }
            val clave = assertNotNull(nombre, "una parte del multipart no tiene nombre: $cabecera")
            partes[clave] = Parte(clave, nombreDeArchivo, tipoDeLaParte, contenido.toByteArray(Charsets.ISO_8859_1))
        }
        return partes
    }

    /** El valor de `parametro="valor"` (o `parametro=valor`) de una cabecera, sin comillas. */
    private fun valorDeParametro(cabecera: String, parametro: String): String? =
        Regex("""$parametro=(?:"([^"]*)"|([^;\s]+))""").find(cabecera)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

    private val dispositivo = "disp-prueba-1"
    private val idAdjunto = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"

    // Bytes con la firma de cada tipo y algo que no es ASCII: si el multipart cambiara un byte, se vería.
    private val pdf = "%PDF-1.4\r\n%prueba T102\r\n".toByteArray(Charsets.ISO_8859_1) + ByteArray(512) { (it % 256).toByte() }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(128) { (it * 3 % 256).toByte() }
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(96) { (it * 5 % 256).toByte() }

    private val alta = """{"ok":true,"adjunto":{"id":"731","nombre_original":"informe.pdf","tamanio":537,"created_at":"2026-11-13 10:00:00"}}"""

    private val listaDeDos = """{"ok":true,"adjuntos":[
        {"id":"731","nombre_original":"Informe final.pdf","tamanio":3210,"tipo":"pdf","created_at":"2026-11-13 10:00:00","consulta_id":"2040"},
        {"id":"732","nombre_original":"Placa.png","tamanio":0,"tipo":"png","created_at":"2026-11-12 09:30:00","consulta_id":"2039"}]}"""

    // ---------- Subir ----------

    @Test
    fun subir_un_pdf_es_un_post_multipart_a_la_consulta_con_los_campos_y_los_bytes_exactos() = conServidor(201, alta) {
        val r = api.subir(7011, "a:$idAdjunto", dispositivo, "informe.pdf", "application/pdf", pdf)

        val p = pedidos.single()
        assertEquals("POST", p.metodo)
        assertEquals("/api/salud360/consultas/7011/adjuntos", p.ruta)
        assertNull(p.query)
        p.assertEnviadoConElToken()
        val partes = p.partes()
        assertEquals(setOf("clave", "dispositivo", "tipo", "archivo"), partes.keys, "el multipart lleva los tres campos y el archivo")
        assertEquals("a:$idAdjunto", partes.getValue("clave").bytes.toString(Charsets.UTF_8))
        assertEquals(dispositivo, partes.getValue("dispositivo").bytes.toString(Charsets.UTF_8))
        assertEquals("documento", partes.getValue("tipo").bytes.toString(Charsets.UTF_8), "las fotos de exámenes quedaron fuera de alcance")
        val archivo = partes.getValue("archivo")
        assertEquals("informe.pdf", archivo.nombreDeArchivo)
        assertEquals("application/pdf", archivo.tipoDeContenido)
        assertContentEquals(pdf, archivo.bytes, "los bytes viajan exactos, sin recortar ni convertir")
        assertEquals("731", r.adjunto.id, "el id de la fila de la web viene como texto")
        assertEquals("informe.pdf", r.adjunto.nombreOriginal)
        assertEquals(537L, r.adjunto.tamanio)
        assertEquals("2026-11-13 10:00:00", r.adjunto.creadoEn)
    }

    @Test
    fun subir_un_jpg_y_un_png_llegan_con_su_tipo_y_sus_bytes() {
        val casos = listOf(Triple("foto.jpg", "image/jpeg", jpeg), Triple("placa.png", "image/png", png))
        for ((nombre, mime, bytes) in casos) {
            conServidor(201, alta) {
                api.subir(7011, "a:$idAdjunto", dispositivo, nombre, mime, bytes)

                val archivo = pedidos.single().partes().getValue("archivo")
                assertEquals(nombre, archivo.nombreDeArchivo)
                assertEquals(mime, archivo.tipoDeContenido)
                assertContentEquals(bytes, archivo.bytes, nombre)
            }
        }
    }

    @Test
    fun subir_contra_una_consulta_cerrada_llega_con_su_status_y_su_codigo() = conServidor(
        409, """{"ok":false,"mensaje":"La consulta está cerrada.","codigo":"consulta_cerrada"}""",
    ) {
        val e = assertFailsWith<TobbException> { api.subir(7011, "a:$idAdjunto", dispositivo, "informe.pdf", "application/pdf", pdf) }

        assertEquals(409, e.status)
        assertEquals("consulta_cerrada", e.codigo)
        assertEquals(1, pedidos.size, "el cliente no reintenta: eso es del envío diferido")
    }

    // ---------- Listar ----------

    @Test
    fun listar_de_un_paciente_es_un_get_a_la_ficha_con_los_documentos_de_todas_sus_consultas() = conServidor(200, listaDeDos) {
        val r = api.listarDePaciente(5001)

        val p = pedidos.single()
        assertEquals("GET", p.metodo)
        assertEquals("/api/salud360/pacientes/5001/adjuntos", p.ruta)
        assertNull(p.query)
        assertEquals("", p.texto(), "el GET no lleva cuerpo")
        assertNull(p.contentType, "sin cuerpo no hay Content-Type")
        p.assertEnviadoConElToken()
        assertEquals(2, r.adjuntos.size)
        val primero = r.adjuntos.first()
        assertEquals("731", primero.id)
        assertEquals("Informe final.pdf", primero.nombreOriginal)
        assertEquals(3210L, primero.tamanio)
        assertEquals("pdf", primero.tipo)
        assertEquals("2026-11-13 10:00:00", primero.creadoEn)
        assertEquals("2040", primero.consultaId, "cada adjunto trae la consulta de la que cuelga (consulta nueva: todos)")
        assertEquals("2039", r.adjuntos[1].consultaId)
        assertEquals(0L, r.adjuntos[1].tamanio, "0 si falta el archivo en el disco: la fila se lista igual")
    }

    @Test
    fun listar_de_una_consulta_es_un_get_a_la_consulta_con_solo_los_suyos() = conServidor(200, listaDeDos) {
        api.listarDeConsulta(7011)

        val p = pedidos.single()
        assertEquals("GET", p.metodo)
        assertEquals("/api/salud360/consultas/7011/adjuntos", p.ruta)
        assertNull(p.query)
    }

    @Test
    fun una_lista_sin_adjuntos_es_un_error_de_respuesta_y_no_una_lista_vacia() {
        conServidor(200, """{"ok":true}""") {
            val e = assertFailsWith<TobbException> { api.listarDePaciente(5001) }

            assertEquals(0, e.status)
            assertEquals("respuesta", e.codigo, "sin `adjuntos` no se sabe si el paciente no tiene o la respuesta vino mal")
        }
    }

    // ---------- Bajar ----------

    @Test
    fun bajar_es_un_get_a_la_ruta_del_archivo_y_devuelve_los_bytes_con_su_content_type() {
        conServidor({ Respuesta(200, pdf, "application/pdf") }) {
            val bytes = api.bajar("731")

            val p = pedidos.single()
            assertEquals("GET", p.metodo)
            assertEquals("/api/salud360/adjuntos/731/archivo", p.ruta)
            assertNull(p.query)
            p.assertEnviadoConElToken()
            assertContentEquals(pdf, bytes, "los bytes, tal cual los sirve el servidor")
        }
        conServidor({ Respuesta(200, png, "image/png") }) {
            assertContentEquals(png, api.bajar("732"), "una imagen se baja igual que un PDF")
        }
    }

    @Test
    fun si_en_lugar_del_archivo_llega_un_json_es_un_error() {
        // El archivo dado de baja o que falta en el disco: el servidor contesta un JSON, no bytes.
        conServidor(404, """{"ok":false,"mensaje":"Ese adjunto no existe.","codigo":"adjunto_no_encontrado"}""") {
            val e = assertFailsWith<TobbException> { api.bajar("731") }

            assertEquals(404, e.status)
            assertEquals("adjunto_no_encontrado", e.codigo)
            assertEquals(1, pedidos.size)
        }
        // Un `200` con JSON no es el archivo: no se toma por bueno.
        conServidor(200, """{"ok":true,"adjuntos":[]}""") {
            val e = assertFailsWith<TobbException> { api.bajar("731") }

            assertEquals(200, e.status)
            assertEquals("respuesta", e.codigo)
        }
    }

    // ---------- Borrar ----------

    @Test
    fun borrar_es_un_delete_a_la_ruta_del_adjunto_con_cuerpo_json() = conServidor(200, """{"ok":true,"baja":true,"id":"731"}""") {
        val r = api.borrar("731", 2040, "a:$idAdjunto-baja", dispositivo)

        val p = pedidos.single()
        assertEquals("DELETE", p.metodo)
        assertEquals("/api/salud360/adjuntos/731", p.ruta)
        assertNull(p.query)
        p.assertJson()
        assertEquals(
            """{"clave":"a:$idAdjunto-baja","dispositivo":"$dispositivo","consulta_id":"2040"}""",
            p.texto(),
            "el DELETE lleva cuerpo JSON con la clave de la baja, el dispositivo y la consulta",
        )
        assertTrue(r.baja)
        assertEquals("731", r.id, "el id viene como texto")
    }

    @Test
    fun la_baja_de_un_adjunto_que_subio_la_web_llega_como_ajeno() = conServidor(
        403, """{"ok":false,"mensaje":"Ese documento lo subió la web.","codigo":"adjunto_no_propio"}""",
    ) {
        val e = assertFailsWith<TobbException> { api.borrar("731", 2040, "a:$idAdjunto-baja", dispositivo) }

        assertEquals(403, e.status)
        assertEquals("adjunto_no_propio", e.codigo)
        assertEquals(1, pedidos.size)
    }

    // ---------- Límites ----------

    @Test
    fun los_limites_son_un_get_sin_cuerpo_con_el_maximo_el_de_la_web_y_los_dos_crudos() {
        conServidor(
            200, """{"ok":true,"maximo_bytes":15728640,"maximo_web_bytes":15728640,"upload_max_filesize":"64M","post_max_size":"64M"}""",
        ) {
            val r = api.limites()

            val p = pedidos.single()
            assertEquals("GET", p.metodo)
            assertEquals("/api/salud360/adjuntos/limites", p.ruta)
            assertNull(p.query)
            assertEquals("", p.texto())
            p.assertEnviadoConElToken()
            assertEquals(15_728_640L, r.maximoBytes)
            assertEquals(15_728_640L, r.maximoWebBytes)
            assertEquals("64M", r.uploadMaxFilesize)
            assertEquals("64M", r.postMaxSize)
        }
        // Una instalación que no informa los dos crudos: `null`, no una cadena inventada.
        conServidor(200, """{"ok":true,"maximo_bytes":15728640,"maximo_web_bytes":15728640}""") {
            val r = api.limites()

            assertEquals(15_728_640L, r.maximoBytes)
            assertNull(r.uploadMaxFilesize)
            assertNull(r.postMaxSize)
        }
    }

    @Test
    fun unos_limites_sin_el_maximo_efectivo_no_sirven() = conServidor(200, """{"ok":true,"maximo_web_bytes":15728640}""") {
        val e = assertFailsWith<TobbException> { api.limites() }

        assertEquals(0, e.status)
        assertEquals("respuesta", e.codigo, "la app necesita un número para avisar antes de subir")
    }

    // ---------- Errores ----------

    private class Operacion(val nombre: String, val llamar: suspend (ClinicaAdjuntosApi) -> Unit)

    private val operaciones = listOf(
        Operacion("subir") { it.subir(7011, "a:$idAdjunto", dispositivo, "informe.pdf", "application/pdf", pdf) },
        Operacion("listarDePaciente") { it.listarDePaciente(5001) },
        Operacion("listarDeConsulta") { it.listarDeConsulta(7011) },
        Operacion("bajar") { it.bajar("731") },
        Operacion("borrar") { it.borrar("731", 2040, "a:$idAdjunto-baja", dispositivo) },
        Operacion("limites") { it.limites() },
    )

    private val errores = listOf(
        Triple(401, "sin_token", "Falta el token."),
        Triple(401, "token", "Sesión vencida. Volvé a iniciar sesión."),
        Triple(403, "sin_permiso", "Solo los médicos pueden cargar consultas."),
        Triple(403, "fuera_de_cartera", "Ese paciente no está en tu cartera."),
        Triple(403, "consulta_no_propia", "Esa consulta no es tuya."),
        Triple(403, "adjunto_no_propio", "Ese documento lo subió la web."),
        Triple(404, "paciente_no_encontrado", "Paciente no encontrado."),
        Triple(404, "consulta_no_encontrada", "Consulta no encontrada."),
        Triple(404, "adjunto_no_encontrado", "Ese adjunto no existe."),
        Triple(409, "consulta_cerrada", "La consulta está cerrada."),
        Triple(422, "archivo_invalido", "El archivo no es un PDF, JPG ni PNG."),
        Triple(422, "archivo_grande", "El archivo pasa el límite."),
        Triple(422, "tipo_invalido", "Ese tipo de adjunto no está habilitado."),
        Triple(500, "carpeta_no_escribible", "No se puede escribir en la carpeta."),
        Triple(500, "escritura", "No se pudo guardar el adjunto."),
        Triple(500, "consultas", "No se pudieron listar los adjuntos."),
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
    fun el_413_del_servidor_web_al_subir_se_lee_como_archivo_grande() {
        // Apache/PHP corta el cuerpo antes de que llegue a Laravel: HTML, sin JSON y sin `codigo`.
        val html = """<html><head><title>413 Request Entity Too Large</title></head><body><h1>413</h1></body></html>"""
        conServidor({ Respuesta(413, html.toByteArray(Charsets.UTF_8), "text/html; charset=utf-8") }) {
            val e = assertFailsWith<TobbException> { api.subir(7011, "a:$idAdjunto", dispositivo, "informe.pdf", "application/pdf", pdf) }

            assertEquals(413, e.status)
            assertEquals("archivo_grande", e.codigo, "para la app es un archivo grande: el servidor web ni siquiera lo miró")
            assertEquals(1, pedidos.size)
        }
        // En las demás operaciones un 413 no se reinterpreta: llega como llegó.
        for (op in operaciones.filter { it.nombre != "subir" }) {
            conServidor(413, """{"ok":false,"mensaje":"El cuerpo es demasiado grande."}""") {
                val e = assertFailsWith<TobbException>(op.nombre) { op.llamar(api) }

                assertEquals(413, e.status, op.nombre)
                assertNull(e.codigo, op.nombre)
            }
        }
    }

    @Test
    fun el_422_de_la_subida_trae_el_campo_y_el_motivo() = conServidor(
        422, """{"ok":false,"mensaje":"El archivo no es válido.","codigo":"datos_invalidos","campo":"archivo","motivo":"archivo_invalido"}""",
    ) {
        val e = assertFailsWith<TobbException> { api.subir(7011, "a:$idAdjunto", dispositivo, "informe.pdf", "application/pdf", pdf) }

        assertEquals(422, e.status)
        assertEquals("datos_invalidos", e.codigo)
        assertEquals("archivo", e.cuerpo?.get("campo")?.jsonPrimitive?.content)
        assertEquals("archivo_invalido", e.cuerpo?.get("motivo")?.jsonPrimitive?.content)
    }

    // ---------- Respuestas que no sirven ----------

    @Test
    fun un_json_cortado_no_se_toma_por_bueno_en_ninguna_operacion() {
        val cortado = """{"ok":true,"adjuntos":[{"id":"731","nombre_original":"info"""
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
        for (op in operaciones.filter { it.nombre != "bajar" }) {
            conServidor(200, """{"ok":true}""") {
                val e = assertFailsWith<TobbException>(op.nombre) { op.llamar(api) }

                assertEquals(0, e.status, op.nombre)
                assertEquals("respuesta", e.codigo, op.nombre)
            }
        }
        // La descarga es distinta: el `200` con JSON no se toma por archivo (lo arma `HcApiClient.descargar`).
        conServidor(200, """{"ok":true}""") {
            val e = assertFailsWith<TobbException> { api.bajar("731") }

            assertEquals(200, e.status)
            assertEquals("respuesta", e.codigo)
        }
    }

    @Test
    fun sin_conexion_es_un_error_de_red_con_un_solo_intento() {
        val puerto = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val api = ClinicaAdjuntosApiRemota(HcApiClient("http://127.0.0.1:$puerto", "clinica").apply { token = "token-de-prueba-1" })

        for (op in operaciones) {
            val e = runBlocking { assertFailsWith<TobbException>(op.nombre) { op.llamar(api) } }

            assertEquals(0, e.status, op.nombre)
            assertEquals("red", e.codigo, op.nombre)
        }
    }

    // ---------- Las claves de repetibilidad ----------

    @Test
    fun las_claves_del_adjunto_son_a_id_local_y_a_id_local_baja() {
        assertEquals("a:$idAdjunto", PayloadsClinica.claveDeAdjunto(idAdjunto))
        assertEquals(PayloadsClinica.claveDeAdjunto(idAdjunto), PayloadsClinica.claveDeAdjunto(idAdjunto), "estable entre reintentos")
        assertEquals("a:$idAdjunto-baja", PayloadsClinica.claveDeBajaDeAdjunto(idAdjunto))
        assertEquals("a:abc", PayloadsClinica.claveDeAdjunto("abc"))
        assertEquals("a:abc-baja", PayloadsClinica.claveDeBajaDeAdjunto("abc"))
        assertTrue(Regex("^a:[A-Za-z0-9._-]+\$").matches(PayloadsClinica.claveDeBajaDeAdjunto(idAdjunto)))
        assertEquals(191, PayloadsClinica.claveDeAdjunto("x".repeat(189)).length, "a: + 189 = 191: el tope del servidor")
        assertEquals(191, PayloadsClinica.claveDeBajaDeAdjunto("x".repeat(184)).length, "a: + 184 + -baja = 191")
        for (mala in listOf("", "con espacio", "con/slash", "ñ", "x".repeat(190))) {
            assertFailsWith<IllegalArgumentException>("id='$mala'") { PayloadsClinica.claveDeAdjunto(mala) }
        }
        assertFailsWith<IllegalArgumentException> { PayloadsClinica.claveDeBajaDeAdjunto("x".repeat(185)) }
    }

    // ---------- Por construcción ----------

    @Test
    fun la_remota_recibe_el_cliente_completo_y_la_interfaz_tiene_las_seis_operaciones() {
        assertEquals(listOf(HcApiClient::class.java), ClinicaAdjuntosApiRemota::class.java.constructors.single().parameterTypes.toList())
        val operacionesDeLaInterfaz = ClinicaAdjuntosApi::class.java.declaredMethods.map { it.name }.filter { '$' !in it }.toSet()
        assertEquals(
            setOf("subir", "listarDePaciente", "listarDeConsulta", "bajar", "borrar", "limites"),
            operacionesDeLaInterfaz,
        )
    }
}
