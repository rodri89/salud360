package com.salud360.core.data.network.hc

import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005 RF-35 a RF-40, RF-68, RF-69, RF-70 (T61): los cuerpos de formularios, antecedentes familiares y registros de E2. Puros: sin red,
 * sin reloj, sin base. Contrato en `plan.md` («Contrato de E2», borrador de T48) y, para los formularios, en `md/API_SALUD360_CLINICA.md`
 * de hclinica. Llevan **solo lo que cambió** (RF-34), el vaciado a propósito como `""` (RF-69), todo escalar como texto, y **no arman ningún pedido
 * si no queda nada** que mandar. Quedan afuera, a la espera del usuario: la nota del perfil 4 (Q-E2-1), la baja de un estudio (Q-E2-2), el
 * tope de largo aplicado (Q-E2-3), completar un resultado de otra consulta (Q-E2-4) y `fechaSolicitud`/`numero` (Q-E2-5).
 */
class PayloadsClinicaE2Test {
    private fun json(texto: String): JsonObject = Json.parseToJsonElement(texto) as JsonObject

    private val fecha = LocalDate(2026, 10, 1)

    /** Todo escalar del cuerpo es un texto (el servidor lee el JSON crudo y no coacciona), salvo `borrado`, que es un booleano. */
    private fun assertTodoEsTexto(e: JsonElement, camino: String = "$") {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> if (k != "borrado") assertTodoEsTexto(v, "$camino.$k") else assertEquals(JsonPrimitive(true), v, "$camino.$k") }
            is JsonArray -> e.forEachIndexed { i, v -> assertTodoEsTexto(v, "$camino[$i]") }
            is JsonNull -> throw AssertionError("$camino es null")
            is JsonPrimitive -> assertTrue(e.isString, "$camino no es un texto: $e")
        }
    }

    // ---------- Formularios ----------

    @Test
    fun formularios_lleva_solo_lo_tocado_y_las_tres_secciones_en_su_lugar() {
        val cuerpo = PayloadsClinica.formularios(
            mapOf(
                "alimentacion" to mapOf("hiposodica" to "1|sin sal"),
                "serologias" to mapOf("hiv" to "NO", "fan" to "SI"),
                "child_pugh" to mapOf("ascitis" to "2"),
            ),
        )

        assertEquals(
            json("""{"formularios":{"alimentacion":{"hiposodica":"1|sin sal"},"serologias":{"hiv":"NO","fan":"SI"},"child_pugh":{"ascitis":"2"}}}"""),
            cuerpo,
        )
        assertTodoEsTexto(cuerpo!!)
    }

    @Test
    fun una_seccion_que_no_se_toco_no_sale() {
        val cuerpo = PayloadsClinica.formularios(mapOf("serologias" to mapOf("hiv" to "NO")))

        assertEquals(json("""{"formularios":{"serologias":{"hiv":"NO"}}}"""), cuerpo)
    }

    @Test
    fun alimentacion_es_flag_barra_detalle_y_el_vaciado_sale_como_vacio() {
        val cuerpo = PayloadsClinica.formularios(
            mapOf(
                "alimentacion" to mapOf(
                    "hiposodica" to "1|sin sal", "diabetes" to "0|", "celiaco" to "", "otra" to "   ",
                ),
            ),
        )

        assertEquals(
            json("""{"formularios":{"alimentacion":{"hiposodica":"1|sin sal","diabetes":"0|","celiaco":"","otra":""}}}"""),
            cuerpo,
        )
    }

    @Test
    fun el_detalle_de_alimentacion_va_tal_cual_con_mas_barras_espacios_saltos_y_emoji() {
        val detalle = " sin sal | y sin azúcar\ncon  espacios 🙂 "
        val cuerpo = PayloadsClinica.formularios(mapOf("alimentacion" to mapOf("otra" to "1|$detalle")))

        assertEquals(JsonPrimitive("1|$detalle"), ((cuerpo!!["formularios"] as JsonObject)["alimentacion"] as JsonObject)["otra"])
    }

    @Test
    fun alimentacion_usa_la_clave_celiaco_de_la_app_y_no_las_columnas_de_la_web() {
        val cuerpo = PayloadsClinica.formularios(
            mapOf("alimentacion" to mapOf("celiaco" to "1|", "seliaco" to "1|", "otro" to "1|", "hiposodica_detalle" to "x")),
        )

        assertEquals(json("""{"formularios":{"alimentacion":{"celiaco":"1|"}}}"""), cuerpo, "seliaco, otro y los *_detalle no son claves del contrato")
    }

    @Test
    fun un_flag_sin_barra_se_completa_y_uno_que_no_es_0_ni_1_no_sale() {
        val cuerpo = PayloadsClinica.formularios(
            mapOf("alimentacion" to mapOf("hiposodica" to "1", "diabetes" to "0", "celiaco" to "2|x", "otra" to "algo sin barra")),
        )

        assertEquals(json("""{"formularios":{"alimentacion":{"hiposodica":"1|","diabetes":"0|"}}}"""), cuerpo, "el servidor da 422 por un valor sin barra o un flag raro: no se manda")
    }

    @Test
    fun serologias_es_SI_NO_o_vacio_y_otro_valor_no_sale() {
        val cuerpo = PayloadsClinica.formularios(
            mapOf("serologias" to mapOf("hiv" to "NO", "fan" to "SI", "ama" to "", "asma" to "  ", "fibrotest" to "si", "hiv_x" to "SI", "ag_e" to "talvez", "anti_ag_e" to " SI")),
        )

        assertEquals(json("""{"formularios":{"serologias":{"hiv":"NO","fan":"SI","ama":"","asma":""}}}"""), cuerpo, "un vacío es «sin cargar» (nunca NO); minúsculas, espacios y otros valores no se mandan")
    }

    @Test
    fun las_dieciseis_serologias_son_claves_del_contrato() {
        val claves = listOf(
            "igm_hav", "ags_hbv", "ig_anti_hcv", "fan", "anti_lkm", "igg_hav", "ig_anti_core", "ig_anti_s_hbv", "ig_anti_hdv", "asma", "fibrotest", "hiv",
            "ag_e", "anti_ag_e", "ig_anti_hev", "ama",
        )
        val cuerpo = PayloadsClinica.formularios(mapOf("serologias" to claves.associateWith { "SI" }))

        val enviadas = ((cuerpo!!["formularios"] as JsonObject)["serologias"] as JsonObject).keys
        assertEquals(claves.toSet(), enviadas)
    }

    @Test
    fun child_pugh_es_1_a_3_o_vacio_y_el_total_lo_calcula_el_servidor() {
        val cuerpo = PayloadsClinica.formularios(
            mapOf(
                "child_pugh" to mapOf(
                    "ascitis" to "1", "bilirrubina" to "3", "albumina" to "", "tp" to "0", "encefalopatia" to "4", "total_puntos" to "9",
                ),
            ),
        )

        assertEquals(
            json("""{"formularios":{"child_pugh":{"ascitis":"1","bilirrubina":"3","albumina":""}}}"""),
            cuerpo,
            "«0» y «4» no son puntajes, y total_puntos no viaja nunca",
        )
    }

    @Test
    fun formularios_sin_nada_que_mandar_no_arma_cuerpo() {
        assertNull(PayloadsClinica.formularios(emptyMap()))
        assertNull(PayloadsClinica.formularios(mapOf("alimentacion" to emptyMap(), "serologias" to emptyMap())))
        assertNull(PayloadsClinica.formularios(mapOf("laboratorio_general" to mapOf("hemoglobina" to "13"), "tabaquismo" to mapOf("x" to "1"))), "otras secciones no son formularios de E2")
        assertNull(PayloadsClinica.formularios(mapOf("serologias" to mapOf("hiv" to "quizás"), "child_pugh" to mapOf("tp" to "7"))), "si todo se descarta, no queda cuerpo")
    }

    @Test
    fun formularios_no_recorta_ni_rechaza_un_detalle_largo_porque_el_tope_no_se_aplica_todavia() {
        val largo = "x".repeat(70_000)
        val cuerpo = PayloadsClinica.formularios(mapOf("alimentacion" to mapOf("otra" to "1|$largo")))

        assertEquals(JsonPrimitive("1|$largo"), ((cuerpo!!["formularios"] as JsonObject)["alimentacion"] as JsonObject)["otra"], "T64 aplica el tope (Q-E2-3); ahora sale tal cual")
    }

    // ---------- Antecedentes familiares ----------

    private fun ant(clave: String, flag: Boolean = false, detalle: String = "", activo: Boolean = true, categoria: String = "familiares") = Antecedente(
        id = "a-$clave", pacienteId = "p1", especialidad = "clinica", categoria = categoria, clave = clave, flag = flag, detalle = detalle,
        consultaId = "c1", activo = activo,
    )

    @Test
    fun antecedentes_lleva_la_consulta_y_las_casillas_con_flag_y_detalle_como_texto() {
        val cuerpo = PayloadsClinica.antecedentes(7011, listOf(ant("hta", true, "padre"), ant("acv", true, "")))

        assertEquals(json("""{"consulta_id":"7011","casillas":{"hta":{"flag":"1","detalle":"padre"},"acv":{"flag":"1","detalle":""}}}"""), cuerpo)
        assertTodoEsTexto(cuerpo!!)
    }

    @Test
    fun la_nota_va_aparte_y_no_como_casilla() {
        val cuerpo = PayloadsClinica.antecedentes(7011, listOf(ant("nota", true, "Madre con diabetes\ny dos tías.")))

        assertEquals(json("""{"consulta_id":"7011","casillas":{},"nota":"Madre con diabetes\ny dos tías."}"""), cuerpo)
    }

    @Test
    fun el_tabaquismo_lo_personal_los_desactivados_y_lo_que_el_contrato_no_conoce_no_salen() {
        val cuerpo = PayloadsClinica.antecedentes(
            7011,
            listOf(
                ant("tabaquismo", true, "10 al día"),
                ant("hta", true, "x", categoria = "personales"),
                ant("diabetes_tipo_2", true, "personal", categoria = "personales"),
                ant("acv", true, "desactivado", activo = false),
                ant("nota_perfil4", true, "nota del perfil 4"),
                ant("inventada", true, "no es del contrato"),
                ant("dsl", true, "sí"),
            ),
        )

        assertEquals(json("""{"consulta_id":"7011","casillas":{"dsl":{"flag":"1","detalle":"sí"}}}"""), cuerpo, "nota_perfil4 queda afuera hasta Q-E2-1")
    }

    @Test
    fun dependiente_terceros_viaja_con_la_clave_de_la_app_y_las_quince_casillas_son_las_del_contrato() {
        val claves = listOf(
            "hta", "dbt_nir", "dbt_ir", "acv", "dsl", "fa", "mp", "analfabeto", "postrado", "af_ca_mama", "ca_mama", "institucionalizado", "psicofarmacos",
            "dependiente_terceros", "otro",
        )
        val cuerpo = PayloadsClinica.antecedentes(7011, claves.map { ant(it, true, "d-$it") } + ant("dependientes_terceros", true, "con la clave de la columna"))

        val enviadas = (cuerpo!!["casillas"] as JsonObject).keys
        assertEquals(claves.toSet(), enviadas, "dependiente_terceros (clave de la app) sí; dependientes_terceros (columna) no")
    }

    @Test
    fun solo_viaja_lo_que_cambia_de_verdad_contra_lo_ya_enviado() {
        val enviados = mapOf(
            "hta" to ant("hta", true, "padre"),
            "acv" to ant("acv", true, ""),
            "nota" to ant("nota", true, "antes"),
        )
        val cuerpo = PayloadsClinica.antecedentes(
            7011,
            listOf(ant("hta", true, "padre"), ant("acv", true, "madre"), ant("nota", true, "antes"), ant("fa", false, "")),
            enviados,
        )

        assertEquals(json("""{"consulta_id":"7011","casillas":{"acv":{"flag":"1","detalle":"madre"}}}"""), cuerpo, "hta y nota son lo mismo; fa vacía sobre nada no es un cambio")
    }

    @Test
    fun vaciar_a_proposito_una_casilla_o_la_nota_sale_como_flag_0_detalle_vacio_y_nota_vacia() {
        val enviados = mapOf("hta" to ant("hta", true, "padre"), "dsl" to ant("dsl", false, "solo detalle"), "nota" to ant("nota", true, "algo"))
        val cuerpo = PayloadsClinica.antecedentes(
            7011,
            listOf(ant("hta", false, ""), ant("dsl", false, "  "), ant("nota", true, "")),
            enviados,
        )

        assertEquals(
            json("""{"consulta_id":"7011","casillas":{"hta":{"flag":"0","detalle":""},"dsl":{"flag":"0","detalle":""}},"nota":""}"""),
            cuerpo,
        )
    }

    @Test
    fun una_casilla_desmarcada_con_detalle_viaja_con_flag_0_y_su_detalle() {
        val cuerpo = PayloadsClinica.antecedentes(7011, listOf(ant("mp", false, "dudoso")))

        assertEquals(json("""{"consulta_id":"7011","casillas":{"mp":{"flag":"0","detalle":"dudoso"}}}"""), cuerpo)
    }

    @Test
    fun una_casilla_vacia_sobre_nada_no_arma_cuerpo_y_sin_cambios_tampoco() {
        assertNull(PayloadsClinica.antecedentes(7011, emptyList()))
        assertNull(PayloadsClinica.antecedentes(7011, listOf(ant("hta", false, ""), ant("nota", true, "   "))), "vacíos sobre nada")
        assertNull(PayloadsClinica.antecedentes(7011, listOf(ant("tabaquismo", true, "x"), ant("acv", true, "x", activo = false), ant("hta", true, "x", categoria = "personales"))))
        assertNull(PayloadsClinica.antecedentes(7011, listOf(ant("hta", true, "padre")), mapOf("hta" to ant("hta", true, "padre"))), "igual a lo enviado")
    }

    @Test
    fun antecedentes_no_recorta_un_detalle_largo_porque_el_tope_no_se_aplica_todavia() {
        val largo = "y".repeat(70_000)
        val cuerpo = PayloadsClinica.antecedentes(7011, listOf(ant("hta", true, largo)))

        assertEquals(JsonPrimitive(largo), ((cuerpo!!["casillas"] as JsonObject)["hta"] as JsonObject)["detalle"])
    }

    // ---------- Registros ----------

    private fun reg(
        id: String,
        tipo: String,
        campos: Map<String, String> = emptyMap(),
        fecha: LocalDate? = null,
        activo: Boolean = true,
        remotoId: String = "",
        consultaId: String? = "c1",
    ) = RegistroClinico(
        id = id, pacienteId = "p1", consultaId = consultaId, especialidad = "clinica", tipo = tipo, fecha = fecha, campos = campos, activo = activo,
        remotoId = remotoId,
    )

    private fun items(cuerpo: JsonObject?) = (cuerpo!!["items"] as JsonArray).map { it as JsonObject }

    @Test
    fun la_clave_de_un_registro_es_r_dos_puntos_y_el_id_local_y_sirve_para_el_servidor() {
        val id = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
        val clave = PayloadsClinica.claveDeRegistro(id)

        assertEquals("r:$id", clave)
        assertTrue(Regex("^r:[A-Za-z0-9._-]+\$").matches(clave) && clave.length <= 191)
        assertNotEquals(PayloadsClinica.claveDeCrear(id), clave)
        assertNotEquals(PayloadsClinica.claveDeCierre(id), clave)
        assertEquals(clave, PayloadsClinica.claveDeRegistro(id), "estable entre reintentos")
    }

    @Test
    fun una_clave_de_registro_que_el_servidor_rechazaria_no_se_arma() {
        for (mala in listOf("", "con espacio", "con:dos", "ñandú", "x".repeat(190))) {
            assertFailsWith<IllegalArgumentException>("id='$mala'") { PayloadsClinica.claveDeRegistro(mala) }
        }
        assertEquals(191, PayloadsClinica.claveDeRegistro("x".repeat(189)).length)
    }

    @Test
    fun un_estudio_nuevo_lleva_ref_tipo_fecha_y_detalle() {
        val cuerpo = PayloadsClinica.registros(listOf(reg("e1", "eco_abdominal", mapOf("detalle" to "Hígado de aspecto normal"), fecha)))

        assertEquals(
            json("""{"items":[{"ref":"r:e1","tipo":"eco_abdominal","fecha":"2026-10-01","campos":{"detalle":"Hígado de aspecto normal"}}]}"""),
            cuerpo,
        )
        assertTodoEsTexto(cuerpo!!)
    }

    @Test
    fun los_cinco_estudios_viajan_con_el_tipo_de_la_app() {
        val tipos = listOf("eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn")
        val cuerpo = PayloadsClinica.registros(tipos.mapIndexed { i, t -> reg("e$i", t, mapOf("detalle" to "texto $i"), fecha) })

        assertEquals(tipos, items(cuerpo).map { (it["tipo"] as JsonPrimitive).content }, "en el orden en que vienen")
        assertEquals(tipos.indices.map { "r:e$it" }, items(cuerpo).map { (it["ref"] as JsonPrimitive).content })
    }

    @Test
    fun un_estudio_sin_fecha_no_se_manda_porque_el_servidor_la_exige() {
        assertNull(PayloadsClinica.registros(listOf(reg("e1", "veda", mapOf("detalle" to "x"), fecha = null))))
    }

    @Test
    fun un_estudio_en_blanco_nunca_enviado_no_se_manda_y_uno_ya_enviado_se_vacia_con_detalle_vacio_sin_borrado() {
        assertNull(PayloadsClinica.registros(listOf(reg("e1", "biopsia_hepatica", mapOf("detalle" to "  "), fecha))), "vacío sobre nada")
        assertNull(PayloadsClinica.registros(listOf(reg("e2", "biopsia_hepatica", emptyMap(), fecha))))

        val enviado = reg("e3", "biopsia_hepatica", mapOf("detalle" to "Fibrosis F2"), fecha, remotoId = "88")
        val cuerpo = PayloadsClinica.registros(listOf(reg("e3", "biopsia_hepatica", mapOf("detalle" to ""), fecha, remotoId = "88")), mapOf("e3" to enviado))

        assertEquals(
            json("""{"items":[{"ref":"r:e3","tipo":"biopsia_hepatica","id":"88","fecha":"2026-10-01","campos":{"detalle":""}}]}"""),
            cuerpo,
            "Q-E2-2: vaciado a propósito (RF-69), no baja",
        )
        assertFalse(items(cuerpo).single().containsKey("borrado"))
    }

    @Test
    fun un_estudio_nunca_se_da_de_baja_ni_siquiera_desactivado() {
        val enviado = reg("e1", "tc_rmn", mapOf("detalle" to "Sin hallazgos"), fecha, remotoId = "12")
        val desactivado = enviado.copy(activo = false)

        assertNull(PayloadsClinica.registros(listOf(desactivado), mapOf("e1" to enviado)), "no hay camino de baja de estudio (Q-E2-2 abierta)")
        assertNull(PayloadsClinica.registros(listOf(desactivado.copy(remotoId = "")), emptyMap()))
    }

    @Test
    fun un_estudio_igual_a_lo_enviado_no_se_manda_y_un_cambio_de_fecha_o_de_texto_si() {
        val enviado = reg("e1", "veda", mapOf("detalle" to "Gastritis leve"), fecha, remotoId = "5")

        assertNull(PayloadsClinica.registros(listOf(enviado), mapOf("e1" to enviado)))

        val otraFecha = PayloadsClinica.registros(listOf(enviado.copy(fecha = LocalDate(2026, 10, 2))), mapOf("e1" to enviado))
        assertEquals(
            json("""{"items":[{"ref":"r:e1","tipo":"veda","id":"5","fecha":"2026-10-02","campos":{"detalle":"Gastritis leve"}}]}"""),
            otraFecha,
        )
        val otroTexto = PayloadsClinica.registros(listOf(enviado.copy(campos = mapOf("detalle" to "Gastritis moderada"))), mapOf("e1" to enviado))
        assertEquals(JsonPrimitive("Gastritis moderada"), ((items(otroTexto).single()["campos"]) as JsonObject)["detalle"])
    }

    @Test
    fun un_examen_nuevo_lleva_solo_sus_textos_sin_fecha_ni_numero_ni_consulta_de_respuesta() {
        val cuerpo = PayloadsClinica.registros(
            listOf(reg("x1", "examen_complementario", mapOf("solicito" to "Ecocardiograma", "respuesta" to "", "numero" to "3", "consulta_respuesta" to "7000"), fecha)),
        )

        assertEquals(json("""{"items":[{"ref":"r:x1","tipo":"examen_complementario","campos":{"solicito":"Ecocardiograma"}}]}"""), cuerpo, "Q-E2-4 y Q-E2-5 abiertas")
    }

    @Test
    fun una_interconsulta_nueva_lleva_especialista_solicito_y_respuesta_con_contenido() {
        val cuerpo = PayloadsClinica.registros(
            listOf(reg("i1", "interconsulta", mapOf("especialista" to "Cardiología", "solicito" to "Control", "respuesta" to "Pendiente de turno", "numero" to "1"))),
        )

        assertEquals(
            json("""{"items":[{"ref":"r:i1","tipo":"interconsulta","campos":{"especialista":"Cardiología","solicito":"Control","respuesta":"Pendiente de turno"}}]}"""),
            cuerpo,
        )
    }

    @Test
    fun la_edicion_de_un_examen_manda_solo_los_campos_que_cambiaron_y_el_vaciado_como_vacio() {
        val enviado = reg("x1", "examen_complementario", mapOf("solicito" to "Eco", "respuesta" to "Normal"), remotoId = "40")
        val editado = enviado.copy(campos = mapOf("solicito" to "Eco", "respuesta" to ""))
        val cuerpo = PayloadsClinica.registros(listOf(editado), mapOf("x1" to enviado))

        assertEquals(json("""{"items":[{"ref":"r:x1","tipo":"examen_complementario","id":"40","campos":{"respuesta":""}}]}"""), cuerpo)

        val completado = enviado.copy(campos = mapOf("solicito" to "Eco", "respuesta" to "Normal, sin cambios"))
        val completar = PayloadsClinica.registros(listOf(completado), mapOf("x1" to enviado))
        assertEquals(json("""{"items":[{"ref":"r:x1","tipo":"examen_complementario","id":"40","campos":{"respuesta":"Normal, sin cambios"}}]}"""), completar)
    }

    @Test
    fun un_examen_o_interconsulta_sin_cambios_o_sin_texto_no_se_manda() {
        val enviado = reg("x1", "examen_complementario", mapOf("solicito" to "Eco"), remotoId = "40")

        assertNull(PayloadsClinica.registros(listOf(enviado), mapOf("x1" to enviado)))
        assertNull(PayloadsClinica.registros(listOf(reg("x2", "examen_complementario", mapOf("solicito" to " ", "respuesta" to "")))), "una fila sin texto no se crea")
        assertNull(PayloadsClinica.registros(listOf(reg("i2", "interconsulta", mapOf("numero" to "2")))), "el numero no viaja")
    }

    @Test
    fun la_baja_solo_sale_para_un_examen_o_interconsulta_que_la_web_ya_tiene() {
        val baja = PayloadsClinica.registros(
            listOf(
                reg("x1", "examen_complementario", mapOf("solicito" to "Eco"), activo = false, remotoId = "40"),
                reg("i1", "interconsulta", mapOf("especialista" to "Nefro"), activo = false, remotoId = "9"),
            ),
        )

        assertEquals(
            json(
                """{"items":[{"ref":"r:x1","tipo":"examen_complementario","id":"40","campos":{},"borrado":true},
                    {"ref":"r:i1","tipo":"interconsulta","id":"9","campos":{},"borrado":true}]}""",
            ),
            baja,
        )
        assertEquals(JsonPrimitive(true), items(baja).first()["borrado"], "es un booleano, no un texto")

        val nuncaEnviado = reg("x2", "examen_complementario", mapOf("solicito" to "Eco"), activo = false, remotoId = "")
        assertNull(PayloadsClinica.registros(listOf(nuncaEnviado)), "RF-70: si todavía no se había enviado, no se envía nada")
        assertNull(PayloadsClinica.registros(listOf(nuncaEnviado, reg("e1", "eco_abdominal", mapOf("detalle" to "x"), fecha, activo = false))))
    }

    @Test
    fun una_baja_ya_enviada_no_mezcla_campos_y_un_mismo_pedido_puede_llevar_altas_y_bajas() {
        val cuerpo = PayloadsClinica.registros(
            listOf(
                reg("e1", "eco_abdominal", mapOf("detalle" to "Normal"), fecha),
                reg("x1", "examen_complementario", mapOf("solicito" to "Eco"), activo = false, remotoId = "40"),
                reg("i1", "interconsulta", mapOf("especialista" to "Cardio", "solicito" to "Control")),
            ),
        )

        assertEquals(listOf("r:e1", "r:x1", "r:i1"), items(cuerpo).map { (it["ref"] as JsonPrimitive).content })
        assertEquals(setOf("ref", "tipo", "id", "campos", "borrado"), items(cuerpo)[1].keys)
        assertTodoEsTexto(cuerpo!!)
    }

    @Test
    fun lo_que_no_es_de_E2_o_viene_de_la_web_se_descarta() {
        val cuerpo = PayloadsClinica.registros(
            listOf(
                reg("d1", "documentos", mapOf("detalle" to "x"), fecha),
                reg("n1", "internacion", mapOf("detalle" to "x"), fecha),
                reg("hcc-rveda-12", "veda", mapOf("detalle" to "de la web"), fecha, remotoId = "12"),
                reg("hcc-rexamen_complementario-4", "examen_complementario", mapOf("solicito" to "de la web"), remotoId = "4"),
                reg("e1", "tc_rmn", mapOf("detalle" to "propio"), fecha),
            ),
        )

        assertEquals(listOf("r:e1"), items(cuerpo).map { (it["ref"] as JsonPrimitive).content })
    }

    @Test
    fun registros_sin_nada_que_mandar_no_arma_cuerpo() {
        assertNull(PayloadsClinica.registros(emptyList()))
        assertNull(PayloadsClinica.registros(listOf(reg("d1", "documentos", mapOf("detalle" to "x"), fecha))))
    }

    @Test
    fun registros_no_recorta_el_detalle_de_un_estudio_cardiologico_porque_el_tope_no_se_aplica_todavia() {
        val largo = "z".repeat(500)
        val cuerpo = PayloadsClinica.registros(listOf(reg("c1", "estudios_cardiologicos", mapOf("detalle" to largo), fecha)))

        assertEquals(JsonPrimitive(largo), (items(cuerpo).single()["campos"] as JsonObject)["detalle"], "191 caracteres: lo aplica T64 (Q-E2-3)")
    }

    @Test
    fun dos_armados_con_lo_mismo_son_identicos() {
        val lista = listOf(reg("e1", "veda", mapOf("detalle" to "x"), fecha), reg("x1", "examen_complementario", mapOf("solicito" to "y")))

        assertEquals(PayloadsClinica.registros(lista), PayloadsClinica.registros(lista))
    }

    // ---------- Topes de largo (expuestos, no aplicados) ----------

    @Test
    fun los_topes_son_los_que_midio_T48() {
        assertEquals(TopeDeLargo(191, UnidadDeLargo.CARACTERES), TopesDeEscritura.delCampoDeRegistro("estudios_cardiologicos", "detalle"), "la única columna con tope corto")
        for (tipo in listOf("eco_abdominal", "veda", "biopsia_hepatica", "tc_rmn")) {
            assertEquals(TopeDeLargo(65_535, UnidadDeLargo.BYTES), TopesDeEscritura.delCampoDeRegistro(tipo, "detalle"), tipo)
        }
        assertEquals(TopesDeEscritura.TEXTO, TopesDeEscritura.delCampoDeRegistro("examen_complementario", "solicito"))
        assertEquals(TopesDeEscritura.TEXTO, TopesDeEscritura.delCampoDeRegistro("examen_complementario", "respuesta"))
        assertEquals(TopesDeEscritura.TEXTO, TopesDeEscritura.delCampoDeRegistro("interconsulta", "especialista"))
        assertEquals(TopesDeEscritura.TEXTO, TopesDeEscritura.delCampoDeRegistro("interconsulta", "respuesta"))
        assertNull(TopesDeEscritura.delCampoDeRegistro("examen_complementario", "numero"), "lo que no se escribe no tiene tope")
        assertNull(TopesDeEscritura.delCampoDeRegistro("documentos", "detalle"))
        assertEquals(TopesDeEscritura.TEXTO, TopesDeEscritura.delFormulario("alimentacion", "hiposodica"))
        assertNull(TopesDeEscritura.delFormulario("serologias", "hiv"), "valores cerrados")
        assertNull(TopesDeEscritura.delFormulario("child_pugh", "ascitis"))
        assertEquals(TopesDeEscritura.TEXTO, TopesDeEscritura.delAntecedente("hta"))
        assertEquals(TopesDeEscritura.TEXTO, TopesDeEscritura.delAntecedente("nota"))
        assertNull(TopesDeEscritura.delAntecedente("tabaquismo"))
    }

    @Test
    fun el_tope_de_cardiologia_cuenta_caracteres_unicode_y_el_de_texto_cuenta_bytes_de_utf8() {
        val cardiologia = TopesDeEscritura.ESTUDIO_CARDIOLOGICO
        assertFalse(cardiologia.excede("a".repeat(191)))
        assertTrue(cardiologia.excede("a".repeat(192)))
        assertFalse(cardiologia.excede("🙂".repeat(191)), "un emoji es un carácter, aunque ocupe dos unidades de UTF-16")
        assertTrue(cardiologia.excede("🙂".repeat(192)))
        assertEquals(3, cardiologia.largoDe("ñé🙂"))

        val texto = TopesDeEscritura.TEXTO
        assertFalse(texto.excede("a".repeat(65_535)))
        assertTrue(texto.excede("a".repeat(65_536)))
        assertFalse(texto.excede("é".repeat(32_767)), "dos bytes cada una: 65534")
        assertTrue(texto.excede("é".repeat(32_768)), "65536 bytes")
        assertEquals(8, texto.largoDe("ñé🙂"), "ñ=2, é=2, 🙂=4 bytes en UTF-8")
    }
}
