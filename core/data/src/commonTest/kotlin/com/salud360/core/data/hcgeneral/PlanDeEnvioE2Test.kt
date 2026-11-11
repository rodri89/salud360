package com.salud360.core.data.hcgeneral

import com.salud360.core.data.hcgeneral.SubentregaDeEscritura.E1
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura.E2
import com.salud360.core.data.network.hc.PayloadsClinica
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Spec 005 RF-9, RF-34, RF-56, RF-60, RF-68, RF-69 (T62-a): el plan de envío de los **formularios** (alimentación, serologías, Child-Pugh) y de los
 * **antecedentes familiares** de una consulta propia, con `habilitadas = seccionesDe(E2)`. Puro. Cada campo o casilla viaja solo si es un **cambio
 * real** contra lo último que la web confirmó (`LoEnviado`); vaciar a propósito viaja vacío (`""`; la casilla `flag 0`, `detalle ""`); un vacío que el
 * médico no tocó no viaja nunca. Los registros (estudios, exámenes, interconsultas) son de T62-b y no se prueban acá. Quedan afuera, a la espera del
 * usuario: la nota del perfil 4 (Q-E2-1).
 */
class PlanDeEnvioE2Test {
    private val id = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
    private val fecha = LocalDate(2026, 11, 2)
    private val e2 = seccionesDe(E2)

    private fun consulta(estado: EstadoConsulta = EstadoConsulta.ABIERTA, remotoId: String = "7011") =
        Consulta(id, "p1", "m1", "clinica", "control", fecha, estado, "46 años", Instant.fromEpochMilliseconds(1_000), remotoId, true)

    private fun valor(seccion: String, campo: String, valor: String, dirty: Boolean = true, en: Long = 2_000) =
        FilaSucia(SeccionValor(id, seccion, campo, valor), dirty, en)

    private fun antecedente(
        clave: String, flag: Boolean = true, detalle: String = "x", categoria: String = "familiares", activo: Boolean = true,
        consultaId: String? = id, dirty: Boolean = true, en: Long = 2_500,
    ) = FilaSucia(Antecedente("a-$categoria-$clave", "p1", "clinica", categoria, clave, flag, detalle, consultaId, activo), dirty, en)

    private fun plan(
        consulta: Consulta = consulta(),
        sucia: Boolean = false,
        valores: List<FilaSucia<SeccionValor>> = emptyList(),
        examen: FilaSucia<ExamenFisico>? = null,
        antecedentes: List<FilaSucia<Antecedente>> = emptyList(),
        loEnviado: LoEnviado = LoEnviado(),
        estados: Map<String, EstadoEnvio> = emptyMap(),
        habilitadas: Set<String> = e2,
        reintentoManual: Boolean = false,
    ) = planDeEnvio(EntradaDelPlan(consulta, sucia, valores, examen, antecedentes, emptyList(), loEnviado, estados, habilitadas, reintentoManual, armadoEn = 5_000))

    private fun json(texto: String) = Json.parseToJsonElement(texto) as JsonObject

    private val PlanDeEnvio.partes get() = pasos.map { it.parte }
    private inline fun <reified T : PasoDeEnvio> PlanDeEnvio.paso(): T = pasos.filterIsInstance<T>().single()
    private fun PasoDeEnvio.Formularios.comoMapa() = valores.associate { "${it.seccion}/${it.campo}" to it.valor }
    private val rechazo = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "datos")
    private val espera = EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "sin conexión")

    // ---------- Formularios: cada campo, solo lo tocado y solo un cambio real ----------

    @Test
    fun cada_campo_de_formulario_tocado_viaja_en_un_solo_paso_con_su_fila_cubierta() {
        val p = plan(
            valores = listOf(
                valor("serologias", "hiv", "NO", en = 2_100), valor("child_pugh", "ascitis", "2", en = 2_200),
                valor("alimentacion", "hiposodica", "1|poca sal", en = 2_300),
            ),
        )

        assertEquals(listOf(PARTE_FORMULARIOS), p.partes)
        val paso = p.paso<PasoDeEnvio.Formularios>()
        assertEquals(mapOf("alimentacion/hiposodica" to "1|poca sal", "child_pugh/ascitis" to "2", "serologias/hiv" to "NO"), paso.comoMapa())
        assertEquals(
            listOf(
                FilaCubierta(TablaDeLaFila.SECCION_VALOR, "alimentacion/hiposodica", 2_300),
                FilaCubierta(TablaDeLaFila.SECCION_VALOR, "child_pugh/ascitis", 2_200),
                FilaCubierta(TablaDeLaFila.SECCION_VALOR, "serologias/hiv", 2_100),
            ),
            paso.cubre,
            "una fila por campo y con su updated_at (RF-55); ordenadas por clave",
        )
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty())
        assertTrue(p.hayAlgoPorEnviar)
    }

    @Test
    fun lo_que_no_esta_sucio_no_se_manda_ni_se_limpia() {
        val p = plan(valores = listOf(valor("serologias", "hiv", "NO", dirty = false), valor("child_pugh", "tp", "3", dirty = false)))

        assertTrue(p.pasos.isEmpty() && p.sinViaje.isEmpty() && p.enReserva.isEmpty())
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun un_valor_igual_a_lo_que_la_web_ya_tiene_no_viaja_y_se_limpia_sin_figurar_enviado() {
        val p = plan(
            valores = listOf(valor("serologias", "hiv", "NO"), valor("child_pugh", "ascitis", "2"), valor("alimentacion", "otra", "1|dieta")),
            loEnviado = LoEnviado(formularios = mapOf("serologias/hiv" to "NO", "child_pugh/ascitis" to "2", "alimentacion/otra" to "1|dieta")),
        )

        assertTrue(p.pasos.isEmpty())
        assertEquals(3, p.sinViaje.size)
        assertTrue(p.sinViaje.all { it.tabla == TablaDeLaFila.SECCION_VALOR && it.motivo == MotivoSinViaje.SIN_CAMBIO_REAL })
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun un_valor_distinto_de_lo_que_la_web_tiene_viaja_y_el_igual_no() {
        val p = plan(
            valores = listOf(valor("serologias", "hiv", "SI"), valor("serologias", "fan", "NO")),
            loEnviado = LoEnviado(formularios = mapOf("serologias/hiv" to "NO", "serologias/fan" to "NO")),
        )

        assertEquals(mapOf("serologias/hiv" to "SI"), p.paso<PasoDeEnvio.Formularios>().comoMapa())
        assertEquals(listOf("serologias/fan"), p.sinViaje.map { it.clave })
    }

    @Test
    fun un_vacio_que_el_medico_no_toco_no_viaja_nunca() {
        val p = plan(
            valores = listOf(
                valor("alimentacion", "diabetes", "0|"), valor("alimentacion", "otra", ""), valor("alimentacion", "celiaco", "   "),
                valor("serologias", "ama", ""), valor("child_pugh", "tp", ""),
            ),
        )

        assertTrue(p.pasos.isEmpty(), "alimentación «0|» sobre nada, y un «sin cargar» sobre nada, no son un cambio")
        assertEquals(5, p.sinViaje.size)
        assertTrue(p.sinViaje.all { it.motivo == MotivoSinViaje.SIN_CAMBIO_REAL })
    }

    @Test
    fun vaciar_a_proposito_algo_que_la_web_tiene_viaja_como_vacio() {
        val p = plan(
            valores = listOf(
                valor("alimentacion", "hiposodica", ""), valor("alimentacion", "diabetes", "0|"), valor("alimentacion", "otra", "0|   "),
                valor("serologias", "hiv", ""), valor("child_pugh", "tp", "  "),
            ),
            loEnviado = LoEnviado(
                formularios = mapOf(
                    "alimentacion/hiposodica" to "1|sin sal", "alimentacion/diabetes" to "1|", "alimentacion/otra" to "0|algo",
                    "serologias/hiv" to "NO", "child_pugh/tp" to "3",
                ),
            ),
        )

        assertEquals(
            mapOf(
                "alimentacion/hiposodica" to "", "alimentacion/diabetes" to "", "alimentacion/otra" to "", "serologias/hiv" to "", "child_pugh/tp" to "",
            ),
            p.paso<PasoDeEnvio.Formularios>().comoMapa(),
            "«0|» y en blanco sobre algo enviado son un vaciado: sale vacío (RF-69)",
        )
    }

    @Test
    fun una_serologia_que_vuelve_a_sin_cargar_viaja_vacia_y_nunca_como_NO() {
        val p = plan(valores = listOf(valor("serologias", "hiv", "")), loEnviado = LoEnviado(formularios = mapOf("serologias/hiv" to "NO")))

        assertEquals(mapOf("serologias/hiv" to ""), p.paso<PasoDeEnvio.Formularios>().comoMapa())
    }

    @Test
    fun una_casilla_de_alimentacion_con_detalle_y_sin_marcar_es_un_dato_y_viaja() {
        val p = plan(valores = listOf(valor("alimentacion", "otra", "0|sin lactosa")))

        assertEquals(mapOf("alimentacion/otra" to "0|sin lactosa"), p.paso<PasoDeEnvio.Formularios>().comoMapa())
    }

    @Test
    fun un_flag_sin_barra_es_lo_mismo_que_el_flag_con_la_barra_vacia() {
        val nuevo = plan(valores = listOf(valor("alimentacion", "celiaco", "1")))
        assertEquals(mapOf("alimentacion/celiaco" to "1|"), nuevo.paso<PasoDeEnvio.Formularios>().comoMapa(), "sale con la barra: el servidor la exige")

        val igual = plan(valores = listOf(valor("alimentacion", "celiaco", "1")), loEnviado = LoEnviado(formularios = mapOf("alimentacion/celiaco" to "1|")))
        assertTrue(igual.pasos.isEmpty(), "«1» y «1|» son lo mismo")
        assertEquals(listOf(MotivoSinViaje.SIN_CAMBIO_REAL), igual.sinViaje.map { it.motivo })
    }

    @Test
    fun lo_que_la_web_no_entiende_no_viaja_ni_se_limpia_y_queda_en_reserva() {
        val p = plan(
            valores = listOf(
                valor("child_pugh", "tp", "4"), valor("child_pugh", "total_puntos", "9"), valor("serologias", "hiv", "si"),
                valor("alimentacion", "desayuno", "1|mate"), valor("alimentacion", "hiposodica", "2|x"), valor("serologias", "inventada", "SI"),
            ),
        )

        assertTrue(p.pasos.isEmpty())
        assertEquals(6, p.enReserva.size)
        assertTrue(p.enReserva.all { it.motivo == MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO && it.tabla == TablaDeLaFila.SECCION_VALOR })
        assertTrue(p.sinViaje.isEmpty(), "siguen sucios: no se pierde lo que el médico cargó")
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun otra_seccion_que_no_es_un_formulario_ni_un_texto_no_se_manda_como_formulario() {
        val p = plan(valores = listOf(valor("eco_abdominal", "detalle", "x"), valor("documentos", "detalle", "x")), habilitadas = seccionesDe(SubentregaDeEscritura.E4))

        assertTrue(p.pasos.isEmpty())
        assertEquals(2, p.enReserva.size)
        assertTrue(p.enReserva.all { it.motivo == MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO })
    }

    @Test
    fun con_e1_los_formularios_siguen_en_reserva_como_antes() {
        val p = plan(
            valores = listOf(valor("serologias", "hiv", "NO"), valor("alimentacion", "hiposodica", "1|x"), valor("child_pugh", "ascitis", "2")),
            habilitadas = seccionesDe(E1),
        )

        assertTrue(p.pasos.isEmpty())
        assertEquals(3, p.enReserva.size)
        assertTrue(p.enReserva.all { it.motivo == MotivoEnReserva.TODAVIA_NO_SE_ENVIA })
        assertTrue(p.sinViaje.isEmpty(), "no se limpian: salen cuando la versión los habilite (RF-4)")
    }

    @Test
    fun con_un_conjunto_propio_solo_viajan_los_formularios_habilitados() {
        val p = plan(
            valores = listOf(valor("serologias", "hiv", "NO"), valor("alimentacion", "hiposodica", "1|x"), valor("child_pugh", "ascitis", "2")),
            habilitadas = seccionesDe(E1) + setOf("serologias", "child_pugh"),
        )

        assertEquals(mapOf("child_pugh/ascitis" to "2", "serologias/hiv" to "NO"), p.paso<PasoDeEnvio.Formularios>().comoMapa())
        assertEquals(listOf("alimentacion/hiposodica"), p.enReserva.map { it.clave }, "alimentación sigue apagada aunque sea de la misma subentrega")
    }

    @Test
    fun los_formularios_de_una_consulta_anulada_se_descartan_como_siempre() {
        val nunca = plan(consulta(estado = EstadoConsulta.ANULADA, remotoId = ""), sucia = true, valores = listOf(valor("serologias", "hiv", "NO")))
        assertTrue(nunca.pasos.isEmpty(), "anulada antes de crearse: queda solo en el dispositivo")
        assertEquals(listOf(MotivoSinViaje.CONSULTA_ANULADA), nunca.sinViaje.map { it.motivo })

        val creada = plan(consulta(estado = EstadoConsulta.ANULADA), sucia = true, valores = listOf(valor("serologias", "hiv", "NO")))
        assertEquals(listOf(PARTE_BAJA), creada.partes, "ya creada: solo sale la baja")
        assertEquals(listOf(MotivoSinViaje.DESCARTADO_POR_ANULAR), creada.sinViaje.map { it.motivo })
    }

    // ---------- Antecedentes: solo activos, solo de esta consulta, solo un cambio real ----------

    @Test
    fun una_casilla_nueva_viaja_con_su_fila_cubierta() {
        val p = plan(antecedentes = listOf(antecedente("hta", true, "padre", en = 2_700), antecedente("acv", true, "", en = 2_800)))

        assertEquals(listOf(PARTE_ANTECEDENTES), p.partes)
        val paso = p.paso<PasoDeEnvio.Antecedentes>()
        assertEquals(listOf("acv", "hta"), paso.items.map { it.clave }.sorted())
        assertEquals(setOf(FilaCubierta(TablaDeLaFila.ANTECEDENTE, "a-familiares-hta", 2_700), FilaCubierta(TablaDeLaFila.ANTECEDENTE, "a-familiares-acv", 2_800)), paso.cubre.toSet())
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty())
    }

    @Test
    fun solo_viajan_los_activos_y_los_de_esta_consulta() {
        val p = plan(
            antecedentes = listOf(
                antecedente("hta"), antecedente("acv", activo = false), antecedente("dsl", consultaId = "historica-1"),
                antecedente("fa", consultaId = null), antecedente("mp", consultaId = "otra-propia"),
            ),
        )

        assertEquals(listOf("hta"), p.paso<PasoDeEnvio.Antecedentes>().items.map { it.clave })
        assertEquals(listOf(MotivoSinViaje.DESACTIVADO), p.sinViaje.map { it.motivo }, "el desactivado no viaja ni queda pendiente (RF-9)")
        assertTrue(p.enReserva.isEmpty(), "los de otra consulta ni se envían ni se limpian ni se cuentan (RF-68)")
    }

    @Test
    fun una_casilla_igual_a_la_ultima_confirmada_no_viaja_y_una_distinta_si() {
        val p = plan(
            antecedentes = listOf(antecedente("hta", true, "padre"), antecedente("acv", true, "madre"), antecedente("dsl", false, "")),
            loEnviado = LoEnviado(antecedentes = mapOf("hta" to "1|padre", "acv" to "1|abuela")),
        )

        assertEquals(listOf("acv"), p.paso<PasoDeEnvio.Antecedentes>().items.map { it.clave })
        assertEquals(setOf("a-familiares-hta", "a-familiares-dsl"), p.sinViaje.map { it.clave }.toSet(), "igual a la web, y vacía sobre nada")
        assertTrue(p.sinViaje.all { it.motivo == MotivoSinViaje.SIN_CAMBIO_REAL })
    }

    @Test
    fun una_casilla_vaciada_a_proposito_viaja_y_el_cuerpo_sale_flag_0_detalle_vacio() {
        val enviado = antecedente("hta", true, "padre").fila
        val p = plan(
            antecedentes = listOf(antecedente("hta", false, ""), antecedente("dsl", false, "   ")),
            loEnviado = LoEnviado(antecedentes = mapOf("hta" to "1|padre", "dsl" to "0|algo")),
        )

        assertEquals(listOf("dsl", "hta"), p.paso<PasoDeEnvio.Antecedentes>().items.map { it.clave }.sorted())
        // Con lo mismo que decidió el plan, el cuerpo de la API (T61) sale con flag 0 y detalle vacío.
        val cuerpo = PayloadsClinica.antecedentes(
            7011, p.paso<PasoDeEnvio.Antecedentes>().items,
            mapOf("hta" to enviado, "dsl" to enviado.copy(clave = "dsl", flag = false, detalle = "algo")),
        )
        assertEquals(json("""{"consulta_id":"7011","casillas":{"hta":{"flag":"0","detalle":""},"dsl":{"flag":"0","detalle":""}}}"""), cuerpo)
    }

    @Test
    fun una_casilla_vacia_sobre_nada_no_viaja() {
        val p = plan(antecedentes = listOf(antecedente("hta", false, ""), antecedente("acv", false, "  ")))

        assertTrue(p.pasos.isEmpty())
        assertEquals(2, p.sinViaje.size)
        assertTrue(p.sinViaje.all { it.motivo == MotivoSinViaje.SIN_CAMBIO_REAL })
    }

    @Test
    fun el_tabaquismo_y_lo_personal_no_viajan_y_se_limpian_sin_figurar_enviados() {
        val p = plan(
            antecedentes = listOf(
                antecedente("tabaquismo", true, "10 al día"), antecedente("alergias", true, "polen", categoria = "personales"),
                antecedente("hta", true, "x", categoria = "personales"),
            ),
        )

        assertTrue(p.pasos.isEmpty())
        assertEquals(3, p.sinViaje.size)
        assertTrue(p.sinViaje.all { it.motivo == MotivoSinViaje.LA_WEB_NO_LA_GUARDA })
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun dependiente_terceros_viaja_como_una_casilla_mas() {
        val p = plan(antecedentes = listOf(antecedente("dependiente_terceros", true, "hijo")))

        assertEquals(listOf("dependiente_terceros"), p.paso<PasoDeEnvio.Antecedentes>().items.map { it.clave })
    }

    @Test
    fun la_nota_viaja_si_cambio_y_no_si_es_igual_y_vaciarla_viaja() {
        val nueva = plan(antecedentes = listOf(antecedente("nota", true, "Madre diabética")))
        assertEquals(listOf("nota"), nueva.paso<PasoDeEnvio.Antecedentes>().items.map { it.clave })

        val igual = plan(antecedentes = listOf(antecedente("nota", true, "antes")), loEnviado = LoEnviado(antecedentes = mapOf("nota" to "1|antes")))
        assertTrue(igual.pasos.isEmpty())
        assertEquals(listOf(MotivoSinViaje.SIN_CAMBIO_REAL), igual.sinViaje.map { it.motivo })

        val cambiada = plan(antecedentes = listOf(antecedente("nota", true, "ahora")), loEnviado = LoEnviado(antecedentes = mapOf("nota" to "1|antes")))
        assertEquals(listOf("nota"), cambiada.paso<PasoDeEnvio.Antecedentes>().items.map { it.clave })

        val vaciada = plan(antecedentes = listOf(antecedente("nota", true, "")), loEnviado = LoEnviado(antecedentes = mapOf("nota" to "1|antes")))
        assertEquals(listOf("nota"), vaciada.paso<PasoDeEnvio.Antecedentes>().items.map { it.clave })
        assertEquals(
            json("""{"consulta_id":"7011","casillas":{},"nota":""}"""),
            PayloadsClinica.antecedentes(7011, vaciada.paso<PasoDeEnvio.Antecedentes>().items, mapOf("nota" to antecedente("nota", true, "antes").fila)),
        )

        val sobreNada = plan(antecedentes = listOf(antecedente("nota", true, "  ")))
        assertTrue(sobreNada.pasos.isEmpty(), "una nota en blanco sobre nada no es un cambio")
    }

    @Test
    fun la_nota_del_perfil_4_y_lo_que_el_contrato_no_conoce_quedan_en_reserva_hasta_que_el_usuario_decida() {
        val p = plan(antecedentes = listOf(antecedente("nota_perfil4", true, "nota del perfil 4"), antecedente("inventada", true, "x"), antecedente("hta")))

        assertEquals(listOf("hta"), p.paso<PasoDeEnvio.Antecedentes>().items.map { it.clave })
        assertEquals(setOf("a-familiares-nota_perfil4", "a-familiares-inventada"), p.enReserva.map { it.clave }.toSet())
        assertTrue(p.enReserva.all { it.motivo == MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO && it.tabla == TablaDeLaFila.ANTECEDENTE })
        assertTrue(p.sinViaje.isEmpty(), "Q-E2-1: no se tira lo que el médico cargó")
    }

    @Test
    fun con_e1_los_antecedentes_siguen_en_reserva_y_con_una_anulada_tambien() {
        val e1 = plan(antecedentes = listOf(antecedente("hta")), habilitadas = seccionesDe(E1))
        assertTrue(e1.pasos.isEmpty())
        assertEquals(listOf(MotivoEnReserva.TODAVIA_NO_SE_ENVIA), e1.enReserva.map { it.motivo })

        val anulada = plan(consulta(estado = EstadoConsulta.ANULADA, remotoId = ""), sucia = true, antecedentes = listOf(antecedente("hta")))
        assertTrue(anulada.pasos.isEmpty())
        assertEquals(listOf(MotivoEnReserva.CONSULTA_ANULADA), anulada.enReserva.map { it.motivo }, "los datos por paciente de una anulada no se tiran")
    }

    @Test
    fun solo_la_seccion_de_antecedentes_habilitada_los_deja_viajar() {
        val sinAntecedentes = plan(antecedentes = listOf(antecedente("hta")), habilitadas = seccionesDe(E1) + setOf("serologias"))
        assertTrue(sinAntecedentes.pasos.isEmpty())
        assertEquals(listOf(MotivoEnReserva.TODAVIA_NO_SE_ENVIA), sinAntecedentes.enReserva.map { it.motivo })

        val con = plan(antecedentes = listOf(antecedente("hta")), habilitadas = seccionesDe(E1) + setOf("antecedentes_familiares"))
        assertEquals(listOf(PARTE_ANTECEDENTES), con.partes)
    }

    // ---------- Orden y partes rechazadas ----------

    private fun todoJunto(estados: Map<String, EstadoEnvio> = emptyMap(), reintentoManual: Boolean = false, remotoId: String = "") = plan(
        consulta(estado = EstadoConsulta.CERRADA, remotoId = remotoId), sucia = true,
        valores = listOf(
            valor("nota", "texto", "x"), valor("serologias", "hiv", "NO"), valor("alimentacion", "hiposodica", "1|x"),
        ),
        examen = FilaSucia(ExamenFisico(id, peso = "70"), true, 3_000),
        antecedentes = listOf(antecedente("hta")), estados = estados, reintentoManual = reintentoManual,
    )

    @Test
    fun el_orden_es_crear_secciones_examen_formularios_antecedentes_y_cierre() {
        assertEquals(
            listOf(PARTE_CREAR, PARTE_SECCIONES, PARTE_EXAMEN, PARTE_FORMULARIOS, PARTE_ANTECEDENTES, PARTE_CIERRE),
            todoJunto().partes,
        )
        assertTrue(PARTE_REGISTROS !in todoJunto().partes, "los registros son de T62-b")
    }

    @Test
    fun el_cierre_solo_sale_con_los_formularios_y_los_antecedentes_confirmados() {
        val p = todoJunto(remotoId = "7011")
        var confirmadas = setOf<String>()

        for (esperada in p.partes) {
            assertEquals(esperada, p.siguiente(confirmadas)?.parte)
            if (esperada == PARTE_CIERRE) assertEquals(p.partes.dropLast(1).toSet(), confirmadas)
            confirmadas = confirmadas + esperada
        }
        assertNull(p.siguiente(confirmadas))
        assertNull(p.siguiente(setOf(PARTE_SECCIONES, PARTE_EXAMEN), frenadas = setOf(PARTE_FORMULARIOS)), "se corta ante los formularios que fallan")
    }

    @Test
    fun una_parte_rechazada_de_formularios_o_de_antecedentes_frena_el_cierre_y_las_demas_siguen() {
        for (parte in listOf(PARTE_FORMULARIOS, PARTE_ANTECEDENTES)) {
            val p = todoJunto(estados = mapOf(parte to rechazo), remotoId = "7011")

            assertEquals(listOf(PARTE_SECCIONES, PARTE_EXAMEN, PARTE_FORMULARIOS, PARTE_ANTECEDENTES).filter { it != parte }, p.partes, parte)
            assertEquals(
                listOf(Retencion(parte, MotivoRetencion.RECHAZADA), Retencion(PARTE_CIERRE, MotivoRetencion.FRENADO_POR_UNA_PARTE_RECHAZADA)),
                p.retenciones,
                parte,
            )
        }
    }

    @Test
    fun una_parte_en_espera_se_reintenta_y_el_reintento_a_mano_vuelve_a_incluir_lo_rechazado() {
        val enEspera = todoJunto(estados = mapOf(PARTE_FORMULARIOS to espera), remotoId = "7011")
        assertTrue(PARTE_FORMULARIOS in enEspera.partes && enEspera.partes.last() == PARTE_CIERRE)
        assertTrue(enEspera.retenciones.isEmpty())

        val manual = todoJunto(estados = mapOf(PARTE_ANTECEDENTES to rechazo, PARTE_CIERRE to rechazo), reintentoManual = true, remotoId = "7011")
        assertTrue(PARTE_ANTECEDENTES in manual.partes && manual.partes.last() == PARTE_CIERRE)
        assertTrue(manual.retenciones.isEmpty())
    }

    @Test
    fun el_plan_no_depende_del_orden_de_las_filas_de_entrada() {
        val a = plan(
            valores = listOf(valor("serologias", "hiv", "NO"), valor("child_pugh", "tp", "2"), valor("alimentacion", "otra", "1|x")),
            antecedentes = listOf(antecedente("hta"), antecedente("acv"), antecedente("nota", true, "n")),
        )
        val b = plan(
            valores = listOf(valor("alimentacion", "otra", "1|x"), valor("child_pugh", "tp", "2"), valor("serologias", "hiv", "NO")),
            antecedentes = listOf(antecedente("nota", true, "n"), antecedente("acv"), antecedente("hta")),
        )

        assertEquals(a, b)
    }

    // ---------- Lo confirmado ----------

    @Test
    fun lo_confirmado_de_los_formularios_se_anota_por_campo_y_el_vaciado_queda_vacio() {
        val p = plan(valores = listOf(valor("serologias", "hiv", "NO"), valor("alimentacion", "hiposodica", "1"), valor("child_pugh", "tp", "3")))
        val enviado = p.pasos.fold(LoEnviado(valores = mapOf("nota/texto" to "n"))) { lo, paso -> lo.conLoConfirmado(paso) }

        assertEquals(mapOf("serologias/hiv" to "NO", "alimentacion/hiposodica" to "1|", "child_pugh/tp" to "3"), enviado.formularios)
        assertEquals(mapOf("nota/texto" to "n"), enviado.valores, "lo anterior se conserva")

        val vaciar = plan(valores = listOf(valor("serologias", "hiv", "")), loEnviado = enviado)
        val despues = vaciar.pasos.fold(enviado) { lo, paso -> lo.conLoConfirmado(paso) }
        assertEquals("", despues.formularios["serologias/hiv"])
        assertEquals("3", despues.formularios["child_pugh/tp"])
        // Ya vaciado en la web, volver a guardar lo mismo no es un cambio.
        assertTrue(plan(valores = listOf(valor("serologias", "hiv", "")), loEnviado = despues).pasos.isEmpty())
    }

    @Test
    fun lo_confirmado_de_los_antecedentes_se_anota_por_clave_y_decide_el_cambio_siguiente() {
        val p = plan(antecedentes = listOf(antecedente("hta", true, "padre"), antecedente("dsl", false, "solo detalle"), antecedente("nota", true, "Texto")))
        val enviado = p.pasos.fold(LoEnviado()) { lo, paso -> lo.conLoConfirmado(paso) }

        assertEquals(mapOf("hta" to "1|padre", "dsl" to "0|solo detalle", "nota" to "1|Texto"), enviado.antecedentes)
        assertTrue(plan(antecedentes = listOf(antecedente("hta", true, "padre"), antecedente("nota", true, "Texto")), loEnviado = enviado).pasos.isEmpty(), "lo mismo: nada que mandar")

        val vaciada = plan(antecedentes = listOf(antecedente("hta", false, ""), antecedente("nota", true, "")), loEnviado = enviado)
        val despues = vaciada.pasos.fold(enviado) { lo, paso -> lo.conLoConfirmado(paso) }
        assertEquals("0|", despues.antecedentes["hta"])
        assertEquals("0|", despues.antecedentes["nota"])
        assertTrue(plan(antecedentes = listOf(antecedente("hta", false, ""), antecedente("nota", true, "")), loEnviado = despues).pasos.isEmpty(), "ya vaciadas: no se repite")
    }

    @Test
    fun confirmar_formularios_y_antecedentes_no_toca_lo_de_los_textos_ni_del_examen() {
        val base = LoEnviado(valores = mapOf("nota/texto" to "a"), examen = mapOf("peso" to "70"))
        val p = plan(valores = listOf(valor("serologias", "hiv", "NO")), antecedentes = listOf(antecedente("hta")))
        val despues = p.pasos.fold(base) { lo, paso -> lo.conLoConfirmado(paso) }

        assertEquals(base.valores, despues.valores)
        assertEquals(base.examen, despues.examen)
    }
}
