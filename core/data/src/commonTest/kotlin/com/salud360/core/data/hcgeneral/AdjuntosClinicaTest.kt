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
}
