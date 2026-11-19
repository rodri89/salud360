package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.RespuestaLimitesDeAdjuntosDto
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T103 (RF-44, RF-46, RF-60): qué documento de la HC general viaja a la web con su consulta y el aviso del tope
 * **antes** de subir. Puro: sin Compose, sin red, sin base y sin reloj. La sección `documentos` acepta PDF, JPG, JPEG y PNG
 * (decisión del usuario del 2026-11-13); las fotos de exámenes quedan fuera de alcance (T100 y T105).
 */
class AdjuntosClinicaTest {
    private val paciente = "p-99000100"
    private val fecha = LocalDate.parse("2026-11-13")

    private fun consulta(
        id: String = "u1",
        especialidad: String = "clinica",
        propia: Boolean = true,
        estado: EstadoConsulta = EstadoConsulta.ABIERTA,
        remotoId: String = "",
        deEstePaciente: String = paciente,
    ) = Consulta(id, deEstePaciente, "m-1", especialidad, "control", fecha, estado, "45 años", remotoId = remotoId, propia = propia)

    private fun archivo(
        consultaId: String? = "u1",
        seccion: String = "documentos",
        nombre: String = "informe.pdf",
        mime: String = "application/pdf",
        activo: Boolean = true,
        registroId: String? = null,
        deEstePaciente: String = paciente,
    ) = Archivo("a-1", deEstePaciente, consultaId, registroId, seccion, nombre, mime, activo = activo)

    // ---------- Qué adjunto viaja (RF-44, RF-46) ----------

    @Test
    fun los_cuatro_tipos_de_documento_de_esa_consulta_viajan() {
        listOf(
            "informe.pdf" to "application/pdf",
            "foto.jpg" to "image/jpeg",
            "foto.jpeg" to "image/jpeg",
            "placa.png" to "image/png",
        ).forEach { (nombre, mime) ->
            assertTrue(adjuntoViaja(archivo(nombre = nombre, mime = mime), consulta()), "$nombre tiene que viajar")
        }
    }

    @Test
    fun el_tipo_sale_del_nombre_y_del_content_type_con_los_mismos_ids_que_la_lista_de_la_web() {
        assertEquals(TipoDeAdjunto.PDF, tipoDeAdjunto("Informe.PDF", "application/pdf"), "la extensión no distingue mayúsculas")
        assertEquals(TipoDeAdjunto.JPEG, tipoDeAdjunto("foto.jpg", "image/jpeg"))
        assertEquals(TipoDeAdjunto.JPEG, tipoDeAdjunto("foto.jpeg", "image/jpeg"), ".jpg y .jpeg son el mismo tipo")
        assertEquals(TipoDeAdjunto.PNG, tipoDeAdjunto("placa.png", "image/png"))
        assertEquals(listOf("pdf", "jpeg", "png"), TipoDeAdjunto.entries.map { it.id }, "los `tipo` que devuelve la lista de T102")
        assertEquals(listOf("application/pdf", "image/jpeg", "image/png"), TipoDeAdjunto.entries.map { it.mime })
    }

    @Test
    fun sin_extension_manda_el_content_type_y_si_no_es_de_los_cuatro_no_hay_tipo() {
        assertEquals(TipoDeAdjunto.PDF, tipoDeAdjunto("informe", "application/pdf"))
        assertEquals(TipoDeAdjunto.PNG, tipoDeAdjunto("captura", "image/png; charset=binary"))
        assertNull(tipoDeAdjunto("informe", "text/plain"))
        assertNull(tipoDeAdjunto("informe", "application/octet-stream"))
    }

    @Test
    fun una_extension_que_no_es_de_los_cuatro_no_se_tapa_con_el_content_type() {
        assertNull(tipoDeAdjunto("dibujo.gif", "image/png"), "la extensión declarada manda")
        assertNull(tipoDeAdjunto("informe.doc", "application/pdf"))
        assertFalse(adjuntoViaja(archivo(nombre = "dibujo.gif", mime = "image/png"), consulta()))
    }

    @Test
    fun un_adjunto_de_otro_tipo_no_viaja() {
        listOf(
            "informe.doc" to "application/msword",
            "informe.txt" to "text/plain",
            "audio.mp3" to "audio/mpeg",
            "video.mp4" to "video/mp4",
            "dibujo.gif" to "image/gif",
        ).forEach { (nombre, mime) ->
            assertFalse(adjuntoViaja(archivo(nombre = nombre, mime = mime), consulta()), "$nombre no tiene que viajar")
        }
    }

    @Test
    fun un_adjunto_sin_consulta_no_viaja_aunque_sea_del_mismo_paciente() {
        assertFalse(adjuntoViaja(archivo(consultaId = null, seccion = "familigrama"), consulta()))
        assertFalse(adjuntoViaja(archivo(consultaId = null), consulta()), "un documento sin consulta tampoco")
    }

    @Test
    fun un_adjunto_de_otra_consulta_del_mismo_paciente_no_viaja() {
        assertFalse(adjuntoViaja(archivo(consultaId = "u2"), consulta(id = "u1")), "RF-46: nunca el de otra HC del mismo paciente")
        assertFalse(adjuntoViaja(archivo(), consulta(id = "u2")), "ni al revés")
    }

    @Test
    fun un_adjunto_del_historico_o_de_la_web_no_viaja() {
        assertFalse(adjuntoViaja(archivo(), consulta(propia = false)), "el histórico no se envía solo")
        val deLaWeb = consulta(id = "hcc-c7003", propia = false, remotoId = "7003")
        assertFalse(adjuntoViaja(archivo(consultaId = "hcc-c7003"), deLaWeb))
    }

    @Test
    fun un_adjunto_de_una_consulta_de_otra_historia_clinica_no_viaja() {
        assertFalse(adjuntoViaja(archivo(), consulta(especialidad = "pediatria", propia = true)))
        assertFalse(adjuntoViaja(archivo(), consulta(especialidad = "hepatologia")), "la HC general es `clinica`")
    }

    @Test
    fun un_adjunto_desactivado_no_viaja() {
        assertFalse(adjuntoViaja(archivo(activo = false), consulta()))
    }

    @Test
    fun una_foto_de_examen_o_un_adjunto_de_otra_seccion_queda_afuera() {
        listOf("examenes_complementarios", "fotos", "familigrama", "audio", "evolucion").forEach { seccion ->
            assertFalse(adjuntoViaja(archivo(seccion = seccion), consulta()), seccion)
        }
        assertFalse(adjuntoViaja(archivo(registroId = "r-1"), consulta()), "la foto colgada de un examen no es un documento (T105)")
    }

    @Test
    fun un_adjunto_de_otro_paciente_no_viaja_aunque_cuelgue_de_esta_consulta() {
        assertFalse(adjuntoViaja(archivo(deEstePaciente = "p-otro"), consulta()))
    }

    // ---------- El tope antes de subir (RF-44, RF-60) ----------

    private val quinceMb = 15L * 1024 * 1024

    private fun limites(maximo: Long = quinceMb, web: Long = quinceMb) =
        RespuestaLimitesDeAdjuntosDto(maximoBytes = maximo, maximoWebBytes = web, uploadMaxFilesize = "64M", postMaxSize = "64M")

    @Test
    fun un_archivo_que_entra_en_el_tope_no_avisa_nada() {
        assertNull(limiteExcedido(0, limites()))
        assertNull(limiteExcedido(1_000_000, limites()))
        assertNull(limiteExcedido(quinceMb, limites()), "justo el máximo entra")
    }

    @Test
    fun un_archivo_mas_grande_que_el_tope_avisa_con_su_peso_y_el_maximo_antes_de_subir() {
        assertEquals("El archivo pesa 16 MB y el máximo es 15 MB", limiteExcedido(16L * 1024 * 1024, limites()))
        assertEquals("El archivo pesa 15,1 MB y el máximo es 15 MB", limiteExcedido(quinceMb + 1, limites()), "un byte de más ya avisa")
        assertEquals("El archivo pesa 16,5 MB y el máximo es 15 MB", limiteExcedido(16L * 1024 * 1024 + 512 * 1024, limites()))
    }

    @Test
    fun el_tope_que_manda_es_el_efectivo_y_no_el_de_la_web() {
        // `maximo_bytes` es el efectivo (el menor entre la web y el hosting, D18): es el que decide.
        assertNull(limiteExcedido(10L * 1024 * 1024, limites(maximo = quinceMb, web = 5L * 1024 * 1024)))
        assertEquals(
            "El archivo pesa 10 MB y el máximo es 5 MB",
            limiteExcedido(10L * 1024 * 1024, limites(maximo = 5L * 1024 * 1024, web = quinceMb)),
        )
    }

    @Test
    fun debajo_de_un_mb_el_aviso_igual_habla_de_mb() {
        assertEquals("El archivo pesa 0,6 MB y el máximo es 0,5 MB", limiteExcedido(600_000, limites(maximo = 524_288)))
    }

    // ---------- La pantalla de la sección «Documentos» (T107) ----------

    @Test
    fun el_selector_ofrece_pdf_jpg_jpeg_y_png_y_nada_mas() {
        assertEquals(
            listOf("application/pdf", "image/jpeg", "image/png"),
            TIPOS_MIME_DE_ADJUNTO,
            "los tres MIME que cubren PDF, JPG, JPEG y PNG (`.jpg` y `.jpeg` son `image/jpeg`)",
        )
        assertEquals(TipoDeAdjunto.entries.map { it.mime }, TIPOS_MIME_DE_ADJUNTO, "salen del mismo lugar que lo que viaja")
    }

    @Test
    fun un_archivo_que_no_es_de_los_cuatro_se_rechaza_antes_de_guardarlo() {
        listOf(
            "informe.pdf" to "application/pdf",
            "foto.jpg" to "image/jpeg",
            "foto.jpeg" to "image/jpeg",
            "Informe.PDF" to "",
            "placa.png" to "image/png",
        ).forEach { (nombre, mime) ->
            assertNull(avisoDelTipoDeAdjunto(nombre, mime), "$nombre tiene que aceptarse")
        }
        listOf("informe.doc", "dibujo.gif", "audio.mp3", "informe.txt", "vacío").forEach { nombre ->
            assertEquals(AVISO_TIPO_DE_ADJUNTO, avisoDelTipoDeAdjunto(nombre, "application/octet-stream"), nombre)
        }
        assertEquals(AVISO_TIPO_DE_ADJUNTO, avisoDelTipoDeAdjunto("dibujo.gif", "image/png"), "la extensión declarada manda")
        assertEquals("Solo se aceptan PDF, JPG, JPEG o PNG", AVISO_TIPO_DE_ADJUNTO, "el mismo texto para el médico")
    }

    // ---------- El contenido antes de guardar (corrección del reviewer, 2026-11-14; RF-44) ----------

    private fun pdf() = "%PDF-1.7\nresto del archivo".encodeToByteArray()
    private fun jpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46)
    private fun png() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)

    @Test
    fun el_tipo_por_contenido_son_las_mismas_firmas_que_valida_la_web() {
        assertEquals(TipoDeAdjunto.PDF, tipoPorContenido(pdf()), "la firma `%PDF-`")
        assertEquals(TipoDeAdjunto.JPEG, tipoPorContenido(jpeg()), "la firma `FF D8 FF`")
        assertEquals(TipoDeAdjunto.PNG, tipoPorContenido(png()), "la firma `89 50 4E 47 0D 0A 1A 0A`")
        assertNull(tipoPorContenido(ByteArray(0)), "un archivo vacío no es de ninguno de los cuatro")
        assertNull(tipoPorContenido("%PD".encodeToByteArray()), "una firma cortada no alcanza")
        assertNull(tipoPorContenido("<html>no es un documento</html>".encodeToByteArray()))
    }

    @Test
    fun un_archivo_con_extension_valida_y_contenido_invalido_se_rechaza_antes_de_guardarlo() {
        // Antes se guardaba y recién la web lo rechazaba por la firma (T96): no se crea la fila ni queda «No se pudo enviar».
        assertEquals(AVISO_TIPO_DE_ADJUNTO, avisoDelTipoDeAdjunto("informe.pdf", "application/pdf", png()), "un PNG con nombre .pdf")
        assertEquals(AVISO_TIPO_DE_ADJUNTO, avisoDelTipoDeAdjunto("informe.pdf", "application/pdf", ByteArray(0)), "un archivo vacío")
        assertEquals(AVISO_TIPO_DE_ADJUNTO, avisoDelTipoDeAdjunto("informe.pdf", "application/pdf", "<html>".encodeToByteArray()), "un HTML con nombre .pdf")
    }

    @Test
    fun un_archivo_con_extension_valida_y_contenido_valido_se_acepta() {
        assertNull(avisoDelTipoDeAdjunto("informe.pdf", "application/pdf", pdf()))
        assertNull(avisoDelTipoDeAdjunto("foto.jpg", "image/jpeg", jpeg()))
        assertNull(avisoDelTipoDeAdjunto("foto.jpeg", "image/jpeg", jpeg()), ".jpg y .jpeg son el mismo tipo")
        assertNull(avisoDelTipoDeAdjunto("placa.png", "image/png", png()))
        assertNull(avisoDelTipoDeAdjunto("informe", "application/pdf", pdf()), "sin extensión, el Content-Type y la firma coinciden")
    }

    // ---------- El contenido decide cuando el nombre no define el tipo (segunda vuelta del reviewer, 2026-11-14; RF-44) ----------

    @Test
    fun un_archivo_sin_extension_con_contenido_valido_se_acepta_por_su_contenido() {
        assertNull(avisoDelTipoDeAdjunto("informe", "application/octet-stream", pdf()), "el contenido es un PDF")
        assertNull(avisoDelTipoDeAdjunto("foto", "", jpeg()), "el contenido es un JPG")
        assertNull(avisoDelTipoDeAdjunto("captura", "text/plain", png()), "un MIME que no es de los cuatro tampoco decide")
    }

    @Test
    fun un_archivo_sin_extension_con_contenido_invalido_se_rechaza() {
        assertEquals(AVISO_TIPO_DE_ADJUNTO, avisoDelTipoDeAdjunto("informe", "application/octet-stream", ByteArray(0)), "un archivo vacío")
        assertEquals(AVISO_TIPO_DE_ADJUNTO, avisoDelTipoDeAdjunto("informe", "", "<html>no es un documento</html>".encodeToByteArray()), "un HTML")
    }

    @Test
    fun sin_bytes_el_nombre_sigue_decidiendo() {
        assertEquals(AVISO_TIPO_DE_ADJUNTO, avisoDelTipoDeAdjunto("informe", "application/octet-stream"), "sin bytes no hay contenido que mirar")
        assertEquals(AVISO_TIPO_DE_ADJUNTO, avisoDelTipoDeAdjunto("informe", ""), "sin extensión ni Content-Type no se cuela")
    }

    @Test
    fun el_tipo_que_se_guarda_sale_del_contenido_cuando_el_nombre_no_lo_define() {
        assertEquals(TipoDeAdjunto.PDF, tipoDelArchivo("informe", "application/octet-stream", pdf()), "sin extensión decide el contenido")
        assertEquals(TipoDeAdjunto.PNG, tipoDelArchivo("captura", "", png()), "sin extensión ni Content-Type, el contenido")
        assertEquals(TipoDeAdjunto.PDF, tipoDelArchivo("informe.pdf", "application/pdf", pdf()), "el nombre manda y el contenido coincide")
        assertEquals(TipoDeAdjunto.JPEG, tipoDelArchivo("foto", "image/jpeg", null), "sin bytes decide el nombre y el Content-Type")
        assertNull(tipoDelArchivo("informe", "application/octet-stream", ByteArray(0)), "vacío y sin extensión: no hay tipo para guardar")
        assertNull(tipoDelArchivo("informe", ""), "sin extensión, sin Content-Type y sin bytes no hay tipo")
    }

    @Test
    fun una_consulta_nueva_y_en_curso_lista_los_documentos_de_todo_el_paciente() {
        assertTrue(consulta().documentosDeTodoElPaciente(), "propia, abierta y de la general: la que se está cargando")
        assertTrue(consulta(remotoId = "7001").documentosDeTodoElPaciente(), "ya creada en la web y todavía abierta también")
        assertFalse(consulta(estado = EstadoConsulta.CERRADA).documentosDeTodoElPaciente(), "una previa lista solo los suyos")
        assertFalse(consulta(propia = false).documentosDeTodoElPaciente(), "el histórico no se envía")
        assertFalse(consulta(id = "hcc-c7003", propia = false, remotoId = "7003").documentosDeTodoElPaciente(), "la de la web es previa")
        assertFalse(consulta(especialidad = "pediatria").documentosDeTodoElPaciente(), "la regla es de la HC general")
    }
}
