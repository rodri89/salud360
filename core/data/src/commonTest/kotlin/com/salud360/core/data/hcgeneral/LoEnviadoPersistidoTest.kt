package com.salud360.core.data.hcgeneral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec 005, T25: cómo se guarda `LoEnviado` (lo último que la web confirmó de una consulta) en `paciente_extra`, sin migración, para que
 * sobreviva a cerrar la app. Puro: de `LoEnviado` a texto y de texto a `LoEnviado`. Un texto que no se entiende no inventa nada.
 */
class LoEnviadoPersistidoTest {

    @Test
    fun la_clave_lleva_el_id_de_la_consulta_y_no_se_confunde_con_la_del_estado() {
        assertEquals("hce_enviado_u1", claveDeLoEnviado("u1"))
        assertEquals(false, claveDeLoEnviado("u1").startsWith(PREFIJO_ESTADO_ENVIO))
        assertEquals(false, claveDeEstadoDeEnvio("u1", PARTE_SECCIONES).startsWith(PREFIJO_LO_ENVIADO))
    }

    @Test
    fun ida_y_vuelta_conserva_cada_texto_tal_cual() {
        val lo = LoEnviado(
            valores = mapOf(
                "nota/texto" to "Línea 1\nLínea 2\t\"entre comillas\" y \\ barra",
                "habitos/texto" to "Ñandú, café y 日本語 😀",
                "somnia/texto" to "",
            ),
            examen = mapOf("peso" to "70.5", "tension_arterial" to "120/80", "nota" to ""),
        )

        assertEquals(lo, loEnviadoDeTexto(loEnviadoComoTexto(lo)))
    }

    @Test
    fun el_vaciado_se_conserva_como_vacio_y_no_como_si_no_hubiera_nada() {
        val lo = LoEnviado(valores = mapOf("nota/texto" to ""))

        assertEquals(mapOf("nota/texto" to ""), loEnviadoDeTexto(loEnviadoComoTexto(lo)).valores)
    }

    @Test
    fun sin_nada_confirmado_no_se_guarda_ninguna_fila() {
        assertNull(loEnviadoComoTexto(LoEnviado()))
    }

    @Test
    fun un_texto_ausente_o_ilegible_es_lo_que_no_se_sabe_que_tenga_la_web() {
        assertEquals(LoEnviado(), loEnviadoDeTexto(null))
        assertEquals(LoEnviado(), loEnviadoDeTexto(""))
        assertEquals(LoEnviado(), loEnviadoDeTexto("no es json"))
        assertEquals(LoEnviado(), loEnviadoDeTexto("[1,2,3]"))
        assertEquals(LoEnviado(), loEnviadoDeTexto("""{"v": "no es un objeto"}"""))
    }

    @Test
    fun lo_que_no_es_un_texto_se_ignora_sin_romper_el_resto() {
        val leido = loEnviadoDeTexto("""{"v": {"nota/texto": "ok", "raro": 5, "otro": null}, "e": {"peso": "70"}}""")

        assertEquals(LoEnviado(valores = mapOf("nota/texto" to "ok"), examen = mapOf("peso" to "70")), leido)
    }

    @Test
    fun el_mismo_contenido_da_siempre_el_mismo_texto() {
        val a = LoEnviado(valores = mapOf("b/texto" to "2", "a/texto" to "1"))
        val b = LoEnviado(valores = mapOf("a/texto" to "1", "b/texto" to "2"))

        assertEquals(loEnviadoComoTexto(a), loEnviadoComoTexto(b))
    }

    @Test
    fun confirmar_un_paso_y_guardarlo_suma_a_lo_que_ya_estaba() {
        val antes = LoEnviado(valores = mapOf("nota/texto" to "vieja"), examen = mapOf("peso" to "70"))
        val despues = antes
            .conLoConfirmado(PasoDeEnvio.Secciones(mapOf("nota" to "nueva", "habitos" to "h"), emptyList()))
            .conLoConfirmado(PasoDeEnvio.Examen(mapOf("talla" to "1.70"), emptyList()))

        val releido = loEnviadoDeTexto(loEnviadoComoTexto(despues))

        assertEquals(mapOf("nota/texto" to "nueva", "habitos/texto" to "h"), releido.valores)
        assertEquals(mapOf("peso" to "70", "talla" to "1.70"), releido.examen)
    }
}
