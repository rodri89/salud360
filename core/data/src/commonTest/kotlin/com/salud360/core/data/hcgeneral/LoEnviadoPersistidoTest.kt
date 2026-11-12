package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    // ---------- T62-a: formularios y antecedentes ----------

    @Test
    fun ida_y_vuelta_conserva_los_formularios_y_los_antecedentes_confirmados() {
        val lo = LoEnviado(
            valores = mapOf("nota/texto" to "n"),
            examen = mapOf("peso" to "70"),
            formularios = mapOf("alimentacion/hiposodica" to "1|sin sal | y más", "serologias/hiv" to "NO", "child_pugh/tp" to "", "serologias/ama" to "SI"),
            antecedentes = mapOf("hta" to "1|padre", "dsl" to "0|", "nota" to "1|Línea 1\nLínea 2 \"comillas\""),
        )

        assertEquals(lo, loEnviadoDeTexto(loEnviadoComoTexto(lo)))
    }

    @Test
    fun solo_formularios_o_solo_antecedentes_tambien_se_guardan() {
        assertNotNull(loEnviadoComoTexto(LoEnviado(formularios = mapOf("serologias/hiv" to ""))), "un vaciado confirmado es un dato: no se pierde")
        assertNotNull(loEnviadoComoTexto(LoEnviado(antecedentes = mapOf("hta" to "0|"))))
        assertNull(loEnviadoComoTexto(LoEnviado(formularios = emptyMap(), antecedentes = emptyMap())))
    }

    @Test
    fun lo_guardado_antes_de_t62_se_sigue_leyendo_sin_formularios_ni_antecedentes() {
        val viejo = """{"v":{"nota/texto":"ok"},"e":{"peso":"70"}}"""

        assertEquals(LoEnviado(valores = mapOf("nota/texto" to "ok"), examen = mapOf("peso" to "70")), loEnviadoDeTexto(viejo))
        assertEquals(emptyMap(), loEnviadoDeTexto(viejo).formularios)
        assertEquals(emptyMap(), loEnviadoDeTexto(viejo).antecedentes)
    }

    @Test
    fun sin_formularios_ni_antecedentes_el_texto_es_el_de_siempre_para_que_una_version_vieja_lo_lea() {
        val lo = LoEnviado(valores = mapOf("nota/texto" to "ok"), examen = mapOf("peso" to "70"))

        assertEquals("""{"v":{"nota/texto":"ok"},"e":{"peso":"70"}}""", loEnviadoComoTexto(lo))
    }

    @Test
    fun lo_que_no_es_un_texto_dentro_de_formularios_y_antecedentes_se_ignora_sin_romper_el_resto() {
        val leido = loEnviadoDeTexto("""{"f": {"serologias/hiv": "NO", "raro": 5, "otro": null}, "a": {"hta": "1|x", "malo": true}, "v": {"nota/texto": "ok"}}""")

        assertEquals(
            LoEnviado(valores = mapOf("nota/texto" to "ok"), formularios = mapOf("serologias/hiv" to "NO"), antecedentes = mapOf("hta" to "1|x")),
            leido,
        )
        assertEquals(LoEnviado(), loEnviadoDeTexto("""{"f": "no es un objeto", "a": [1]}"""))
    }

    @Test
    fun el_mismo_contenido_de_formularios_y_antecedentes_da_siempre_el_mismo_texto() {
        val a = LoEnviado(formularios = mapOf("b/x" to "2", "a/y" to "1"), antecedentes = mapOf("hta" to "1|", "acv" to "0|"))
        val b = LoEnviado(formularios = mapOf("a/y" to "1", "b/x" to "2"), antecedentes = mapOf("acv" to "0|", "hta" to "1|"))

        assertEquals(loEnviadoComoTexto(a), loEnviadoComoTexto(b))
    }

    @Test
    fun confirmar_formularios_y_antecedentes_y_guardarlo_suma_a_lo_que_ya_estaba() {
        val antes = LoEnviado(valores = mapOf("nota/texto" to "vieja"), formularios = mapOf("serologias/hiv" to "NO"), antecedentes = mapOf("hta" to "1|padre"))
        val despues = antes
            .conLoConfirmado(PasoDeEnvio.Formularios(listOf(SeccionValor("c", "serologias", "hiv", ""), SeccionValor("c", "child_pugh", "tp", "3")), emptyList()))
            .conLoConfirmado(PasoDeEnvio.Antecedentes(listOf(Antecedente("a1", "p", "clinica", "familiares", "acv", true, "madre"), Antecedente("a2", "p", "clinica", "familiares", "hta", false, "")), emptyList()))

        val releido = loEnviadoDeTexto(loEnviadoComoTexto(despues))

        assertEquals(mapOf("serologias/hiv" to "", "child_pugh/tp" to "3"), releido.formularios, "el vaciado confirmado queda como vacío")
        assertEquals(mapOf("hta" to "0|", "acv" to "1|madre"), releido.antecedentes)
        assertEquals(mapOf("nota/texto" to "vieja"), releido.valores)
    }

    // ---------- T62-b: los cinco estudios ----------

    @Test
    fun ida_y_vuelta_conserva_los_estudios_confirmados_por_estudio_remoto() {
        val lo = LoEnviado(
            valores = mapOf("nota/texto" to "n"),
            registros = mapOf(
                "7015" to mapOf("fecha" to "2026-11-02", "detalle" to "hígado graso, «sin nódulos»\nsegunda línea"),
                "7020" to mapOf("fecha" to "2026-11-03", "detalle" to ""),
            ),
        )

        assertEquals(lo, loEnviadoDeTexto(loEnviadoComoTexto(lo)))
    }

    @Test
    fun solo_estudios_tambien_se_guardan() {
        val solo = LoEnviado(registros = mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "")))

        assertNotNull(loEnviadoComoTexto(solo), "un detalle vaciado confirmado es un dato: no se pierde")
        assertEquals(solo, loEnviadoDeTexto(loEnviadoComoTexto(solo)))
        assertNull(loEnviadoComoTexto(LoEnviado(registros = emptyMap())))
    }

    @Test
    fun lo_guardado_antes_de_t62b_se_sigue_leyendo_sin_estudios() {
        val viejo = """{"v":{"nota/texto":"ok"},"e":{"peso":"70"},"f":{"serologias/hiv":"NO"},"a":{"hta":"1|padre"}}"""

        val leido = loEnviadoDeTexto(viejo)

        assertEquals(emptyMap(), leido.registros)
        assertEquals(
            LoEnviado(
                valores = mapOf("nota/texto" to "ok"), examen = mapOf("peso" to "70"),
                formularios = mapOf("serologias/hiv" to "NO"), antecedentes = mapOf("hta" to "1|padre"),
            ),
            leido,
        )
    }

    @Test
    fun sin_estudios_el_texto_es_el_de_antes_para_que_una_version_vieja_lo_lea() {
        val lo = LoEnviado(valores = mapOf("nota/texto" to "ok"), formularios = mapOf("serologias/hiv" to "NO"), antecedentes = mapOf("hta" to "1|padre"))

        assertEquals("""{"v":{"nota/texto":"ok"},"e":{},"f":{"serologias/hiv":"NO"},"a":{"hta":"1|padre"}}""", loEnviadoComoTexto(lo))
    }

    @Test
    fun lo_que_no_es_un_texto_dentro_de_los_estudios_se_ignora_sin_romper_el_resto() {
        val leido = loEnviadoDeTexto(
            """{"r": {"7015": {"fecha": "2026-11-02", "detalle": "ok", "raro": 5}, "malo": "no es un objeto", "otro": null},
                "v": {"nota/texto": "n"}}""",
        )

        assertEquals(LoEnviado(valores = mapOf("nota/texto" to "n"), registros = mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "ok"))), leido)
        assertEquals(LoEnviado(), loEnviadoDeTexto("""{"r": "no es un objeto"}"""))
    }

    @Test
    fun el_mismo_contenido_de_estudios_da_siempre_el_mismo_texto() {
        val a = LoEnviado(registros = mapOf("7020" to mapOf("fecha" to "2026-11-03", "detalle" to "b"), "7015" to mapOf("detalle" to "a", "fecha" to "2026-11-02")))
        val b = LoEnviado(registros = mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "a"), "7020" to mapOf("detalle" to "b", "fecha" to "2026-11-03")))

        assertEquals(loEnviadoComoTexto(a), loEnviadoComoTexto(b))
    }

    @Test
    fun confirmar_estudios_y_guardarlo_suma_a_lo_que_ya_estaba() {
        val antes = LoEnviado(valores = mapOf("nota/texto" to "vieja"), registros = mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "viejo")))
        val despues = antes.conLoConfirmado(
            PasoDeEnvio.Registros(
                listOf(RegistroClinico("r-eco", "p", "c", "clinica", "eco_abdominal", LocalDate(2026, 11, 2), mapOf("detalle" to ""), remotoId = "7015")),
                emptyList(),
            ),
        )

        val releido = loEnviadoDeTexto(loEnviadoComoTexto(despues))

        assertEquals(mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "")), releido.registros, "el vaciado confirmado queda como vacío")
        assertEquals(mapOf("nota/texto" to "vieja"), releido.valores)
    }

    // ---------- T84-a: las cargas de laboratorio general y peso ----------

    @Test
    fun ida_y_vuelta_conserva_las_cargas_confirmadas_por_carga_remota() {
        val lo = LoEnviado(
            valores = mapOf("nota/texto" to "n"),
            registros = mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "d")),
            cargas = mapOf(
                "731" to mapOf("hb" to "13.5", "glucemia" to "", "otro" to "control «en 3 meses»\nsegunda línea"),
                "740" to mapOf("peso" to "72.5"),
            ),
        )

        assertEquals(lo, loEnviadoDeTexto(loEnviadoComoTexto(lo)))
    }

    @Test
    fun solo_cargas_tambien_se_guardan() {
        val solo = LoEnviado(cargas = mapOf("731" to mapOf("glucemia" to "")))

        assertNotNull(loEnviadoComoTexto(solo), "un vaciado confirmado es un dato: no se pierde")
        assertEquals(solo, loEnviadoDeTexto(loEnviadoComoTexto(solo)))
        assertNull(loEnviadoComoTexto(LoEnviado(cargas = emptyMap())))
    }

    @Test
    fun lo_guardado_antes_de_t84_se_sigue_leyendo_sin_cargas() {
        val viejo = """{"v":{"nota/texto":"ok"},"e":{"peso":"70"},"f":{"serologias/hiv":"NO"},"a":{"hta":"1|padre"},"r":{"7015":{"fecha":"2026-11-02","detalle":"d"}}}"""

        val leido = loEnviadoDeTexto(viejo)

        assertEquals(emptyMap(), leido.cargas)
        assertEquals(
            LoEnviado(
                valores = mapOf("nota/texto" to "ok"), examen = mapOf("peso" to "70"), formularios = mapOf("serologias/hiv" to "NO"),
                antecedentes = mapOf("hta" to "1|padre"), registros = mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "d")),
            ),
            leido,
        )
    }

    @Test
    fun sin_cargas_el_texto_es_el_de_antes_para_que_una_version_vieja_lo_lea() {
        val lo = LoEnviado(valores = mapOf("nota/texto" to "ok"), cargas = emptyMap())

        assertEquals("""{"v":{"nota/texto":"ok"},"e":{}}""", loEnviadoComoTexto(lo))
    }

    @Test
    fun lo_que_no_es_un_texto_dentro_de_las_cargas_se_ignora_sin_romper_el_resto() {
        val leido = loEnviadoDeTexto(
            """{"c": {"731": {"hb": "13.5", "glucemia": "", "raro": 5}, "malo": "no es un objeto", "otro": null},
                "v": {"nota/texto": "n"}}""",
        )

        assertEquals(LoEnviado(valores = mapOf("nota/texto" to "n"), cargas = mapOf("731" to mapOf("hb" to "13.5", "glucemia" to ""))), leido)
        assertEquals(LoEnviado(), loEnviadoDeTexto("""{"c": "no es un objeto"}"""))
    }

    @Test
    fun el_mismo_contenido_de_cargas_da_siempre_el_mismo_texto() {
        val a = LoEnviado(cargas = mapOf("740" to mapOf("peso" to "72.5"), "731" to mapOf("glucemia" to "", "hb" to "13.5")))
        val b = LoEnviado(cargas = mapOf("731" to mapOf("hb" to "13.5", "glucemia" to ""), "740" to mapOf("peso" to "72.5")))

        assertEquals(loEnviadoComoTexto(a), loEnviadoComoTexto(b))
    }

    @Test
    fun confirmar_cargas_y_guardarlo_suma_a_lo_que_ya_estaba() {
        val antes = LoEnviado(
            valores = mapOf("nota/texto" to "vieja"), registros = mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "d")),
            cargas = mapOf("731" to mapOf("hb" to "13.5", "glucemia" to "0.95")),
        )
        val carga = Laboratorio("l1", "p", "c", "clinica", "laboratorio_general", LocalDate(2026, 11, 6), mapOf("hb" to "14", "glucemia" to ""), remotoId = "731")
        val despues = antes.conLoConfirmado(PasoDeEnvio.Cargas(listOf(CargaQueViaja.Guardar(carga, LocalDate(2026, 11, 6))), emptyList()))

        val releido = loEnviadoDeTexto(loEnviadoComoTexto(despues))

        assertEquals(
            mapOf("731" to mapOf("hb" to "14", "glucemia" to "")),
            releido.cargas,
            "lo que queda en la web después de la actualización: lo mandado se suma a lo que ya había",
        )
        assertEquals(mapOf("nota/texto" to "vieja"), releido.valores)
        assertEquals(mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "d")), releido.registros)
    }
}