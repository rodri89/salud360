package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.PayloadsClinica
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Spec 005 RF-9, RF-34, RF-55, RF-56, RF-60, RF-68, RF-69 (T21): qué viaja de una consulta propia de la HC general, en qué orden
 * y qué se queda. Puro: las filas, lo último que la web confirmó y el momento del armado entran como parámetros; no hay red, base
 * ni reloj. El plan solo ordena: no ejecuta ningún pedido (RF-54 es de quien envía).
 */
class PlanDeEnvioTest {
    private val id = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
    private val fecha = LocalDate(2026, 11, 2)
    private val armadoEn = 5_000L

    private fun consulta(
        estado: EstadoConsulta = EstadoConsulta.ABIERTA, propia: Boolean = true, remotoId: String = "", consultaId: String = id,
        edad: String = "46 años", tipo: String = "control",
    ) = Consulta(consultaId, "p1", "m1", "clinica", tipo, fecha, estado, edad, Instant.fromEpochMilliseconds(1_000), remotoId, propia)

    private fun valor(seccion: String, campo: String = "texto", valor: String, dirty: Boolean = true, en: Long = 2_000) =
        FilaSucia(SeccionValor(id, seccion, campo, valor), dirty, en)

    private fun examen(dirty: Boolean = true, en: Long = 3_000, peso: String = "", talla: String = "", ta: String = "", imc: String = "", nota: String = "", fc: String = "", ca: String = "") =
        FilaSucia(ExamenFisico(id, peso = peso, talla = talla, tensionArterial = ta, imc = imc, nota = nota, frecuenciaCardiaca = fc, circunferenciaAbdominal = ca), dirty, en)

    private fun antecedente(
        clave: String, categoria: String = "familiares", flag: Boolean = true, detalle: String = "x", activo: Boolean = true,
        consultaId: String? = id, dirty: Boolean = true, en: Long = 2_500,
    ) = FilaSucia(Antecedente("a-$categoria-$clave", "p1", "clinica", categoria, clave, flag, detalle, consultaId, activo), dirty, en)

    private fun registro(rid: String, tipo: String = "examen_complementario", consultaId: String? = id, activo: Boolean = true, dirty: Boolean = true, en: Long = 2_600) =
        FilaSucia(RegistroClinico(rid, "p1", consultaId, "clinica", tipo, fecha, mapOf("evaluacion" to "pedido $rid"), activo), dirty, en)

    private fun plan(
        consulta: Consulta = consulta(),
        sucia: Boolean = false,
        valores: List<FilaSucia<SeccionValor>> = emptyList(),
        examen: FilaSucia<ExamenFisico>? = null,
        antecedentes: List<FilaSucia<Antecedente>> = emptyList(),
        registros: List<FilaSucia<RegistroClinico>> = emptyList(),
        loEnviado: LoEnviado = LoEnviado(),
        estados: Map<String, EstadoEnvio> = emptyMap(),
        habilitada: SubentregaDeEscritura = SubentregaDeEscritura.E1,
        reintentoManual: Boolean = false,
        armadoEn: Long = this.armadoEn,
    ) = planDeEnvio(EntradaDelPlan(consulta, sucia, valores, examen, antecedentes, registros, loEnviado, estados, habilitada, reintentoManual, armadoEn))

    private val PlanDeEnvio.partes get() = pasos.map { it.parte }
    private inline fun <reified T : PasoDeEnvio> PlanDeEnvio.paso(): T = pasos.filterIsInstance<T>().single()
    private val rechazo = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "datos")
    private val espera = EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "sin conexión")

    // ---------- Solo las consultas propias ----------

    @Test
    fun una_consulta_del_historico_no_entra_en_el_plan_ni_se_toca_nada_suyo() {
        val p = plan(
            consulta(propia = false), sucia = true,
            valores = listOf(valor("nota", valor = "x")), examen = examen(peso = "70"), antecedentes = listOf(antecedente("hta")),
        )

        assertEquals(MotivoPlanVacio.DEL_HISTORICO, p.motivoVacio)
        assertTrue(p.pasos.isEmpty() && p.sinViaje.isEmpty() && p.enReserva.isEmpty(), "ni se envía ni se limpia ni se cuenta (RF-7)")
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun una_consulta_de_la_web_tampoco() {
        for (c in listOf(consulta(consultaId = "hcc-c7003", remotoId = "7003", propia = false), consulta(remotoId = "9", propia = false), consulta(consultaId = "hcc-c1", propia = true))) {
            val p = plan(c, sucia = true, valores = listOf(valor("nota", valor = "x")))

            assertEquals(MotivoPlanVacio.DE_LA_WEB, p.motivoVacio, c.id)
            assertTrue(p.pasos.isEmpty() && p.sinViaje.isEmpty() && p.enReserva.isEmpty(), c.id)
        }
    }

    // ---------- Crear ----------

    @Test
    fun una_propia_sin_nada_tocado_solo_se_crea() {
        val p = plan(sucia = true)

        assertEquals(listOf(PARTE_CREAR), p.partes)
        val crear = p.paso<PasoDeEnvio.Crear>()
        assertEquals("c:$id", crear.clave)
        assertEquals("control", crear.tipo)
        assertEquals(fecha, crear.fecha)
        assertEquals("46 años", crear.edadMostrar, "la fecha y la edad que cargó el médico")
        assertTrue(p.hayAlgoPorEnviar)
        assertNull(p.motivoVacio)
    }

    @Test
    fun una_propia_ya_creada_no_vuelve_a_crearse_y_sin_cambios_no_hay_nada_que_enviar() {
        val p = plan(consulta(remotoId = "7011"), sucia = false)

        assertEquals(emptyList(), p.pasos)
        assertFalse(p.hayAlgoPorEnviar)
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty() && p.retenciones.isEmpty())
    }

    @Test
    fun una_propia_ya_creada_no_repite_crear_aunque_tenga_contenido() {
        val p = plan(consulta(remotoId = "7011"), valores = listOf(valor("nota", valor = "texto")))

        assertEquals(listOf(PARTE_SECCIONES), p.partes)
    }

    @Test
    fun crear_va_aunque_la_consulta_sea_de_telemedicina() {
        assertEquals("telemedicina", plan(consulta(tipo = "telemedicina"), sucia = true).paso<PasoDeEnvio.Crear>().tipo)
    }

    // ---------- Solo lo tocado ----------

    @Test
    fun de_los_textos_solo_viajan_los_tocados() {
        val p = plan(
            consulta(remotoId = "7011"),
            valores = listOf(
                valor("nota", valor = "Control sin novedades"), valor("conductas", valor = "ya enviado", dirty = false),
                valor("motivo_consulta", valor = "Control"),
            ),
        )

        val s = p.paso<PasoDeEnvio.Secciones>()
        assertEquals(mapOf("nota" to "Control sin novedades", "motivo_consulta" to "Control"), s.textos)
        assertEquals(listOf("motivo_consulta/texto", "nota/texto"), s.cubre.map { it.clave }, "ordenadas, sin la que no se tocó")
        assertTrue(s.cubre.all { it.tabla == TablaDeLaFila.SECCION_VALOR })
    }

    @Test
    fun lo_que_no_esta_tocado_no_aparece_en_ninguna_lista() {
        val p = plan(
            consulta(remotoId = "7011"),
            valores = listOf(valor("nota", valor = "x", dirty = false)), examen = examen(dirty = false, peso = "70"),
            antecedentes = listOf(antecedente("hta", dirty = false)), registros = listOf(registro("r1", dirty = false)),
        )

        assertTrue(p.pasos.isEmpty() && p.sinViaje.isEmpty() && p.enReserva.isEmpty())
    }

    // ---------- Vaciar a propósito y vacío no tocado (RF-69) ----------

    @Test
    fun el_vaciado_a_proposito_de_algo_que_la_web_tiene_se_envia_vacio() {
        val p = plan(
            consulta(remotoId = "7011"), valores = listOf(valor("nota", valor = "")),
            loEnviado = LoEnviado(valores = mapOf("nota/texto" to "texto que se había enviado")),
        )

        assertEquals(mapOf("nota" to ""), p.paso<PasoDeEnvio.Secciones>().textos)
    }

    @Test
    fun un_vacio_que_nunca_se_envio_no_viaja_ni_queda_pendiente() {
        val p = plan(consulta(remotoId = "7011"), valores = listOf(valor("nota", valor = ""), valor("conductas", valor = "   ")))

        assertTrue(p.pasos.isEmpty(), "nunca un vacío no tocado")
        assertEquals(listOf(MotivoSinViaje.SIN_CAMBIO_REAL, MotivoSinViaje.SIN_CAMBIO_REAL), p.sinViaje.map { it.motivo })
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun lo_que_ya_esta_igual_en_la_web_no_se_reenvia() {
        val p = plan(
            consulta(remotoId = "7011"), valores = listOf(valor("nota", valor = "igual")),
            loEnviado = LoEnviado(valores = mapOf("nota/texto" to "igual")),
        )

        assertTrue(p.pasos.isEmpty())
        assertEquals(MotivoSinViaje.SIN_CAMBIO_REAL, p.sinViaje.single().motivo)
    }

    @Test
    fun un_texto_cambiado_se_envia_y_en_una_consulta_sin_crear_el_vacio_no_se_envia() {
        val cambiado = plan(
            consulta(remotoId = "7011"), valores = listOf(valor("nota", valor = "nuevo")),
            loEnviado = LoEnviado(valores = mapOf("nota/texto" to "viejo")),
        )
        val sinCrear = plan(sucia = true, valores = listOf(valor("nota", valor = ""), valor("conductas", valor = "algo")))

        assertEquals(mapOf("nota" to "nuevo"), cambiado.paso<PasoDeEnvio.Secciones>().textos)
        assertEquals(listOf(PARTE_CREAR, PARTE_SECCIONES), sinCrear.partes)
        assertEquals(mapOf("conductas" to "algo"), sinCrear.paso<PasoDeEnvio.Secciones>().textos, "no hay nada que vaciar en una consulta que todavía no existe")
    }

    // ---------- Examen físico ----------

    @Test
    fun del_examen_solo_viajan_los_campos_que_cambiaron() {
        val p = plan(
            consulta(remotoId = "7011"), examen = examen(peso = "71", talla = "170", ta = "120/80"),
            loEnviado = LoEnviado(examen = mapOf("peso" to "70", "talla" to "170", "tension_arterial" to "120/80")),
        )

        assertEquals(mapOf("peso" to "71"), p.paso<PasoDeEnvio.Examen>().campos, "la talla y la tensión no se tocaron: no se mandan (RF-34)")
        assertEquals(listOf(TablaDeLaFila.EXAMEN_FISICO), p.paso<PasoDeEnvio.Examen>().cubre.map { it.tabla })
    }

    @Test
    fun el_examen_vaciado_a_proposito_manda_solo_el_campo_vaciado() {
        val p = plan(
            consulta(remotoId = "7011"), examen = examen(peso = "", talla = "170"),
            loEnviado = LoEnviado(examen = mapOf("peso" to "70", "talla" to "170")),
        )

        assertEquals(mapOf("peso" to ""), p.paso<PasoDeEnvio.Examen>().campos, "los otros campos vacíos no se tocaron")
    }

    @Test
    fun cero_coma_cero_cero_es_vacio_en_las_medidas_y_cero_y_cero_coma_cero_son_datos() {
        val sobreNada = plan(consulta(remotoId = "7011"), examen = examen(peso = "0.00", talla = "0.00", ta = "0.00", imc = "0.00"))
        val sobreUnPeso = plan(consulta(remotoId = "7011"), examen = examen(peso = "0.00"), loEnviado = LoEnviado(examen = mapOf("peso" to "70")))
        val datos = plan(consulta(remotoId = "7011"), examen = examen(peso = "0", talla = "0.0"))

        assertTrue(sobreNada.pasos.isEmpty(), "0.00 sobre nada no es un cambio")
        assertEquals(mapOf("peso" to "0.00"), sobreUnPeso.paso<PasoDeEnvio.Examen>().campos, "sobre un peso es vaciarlo (el cuerpo lo manda como texto vacío)")
        assertEquals(mapOf("peso" to "0", "talla" to "0.0"), datos.paso<PasoDeEnvio.Examen>().campos)
    }

    @Test
    fun la_frecuencia_cardiaca_y_la_circunferencia_nunca_viajan() {
        val soloEllas = plan(consulta(remotoId = "7011"), examen = examen(fc = "72", ca = "95"))
        val junto = plan(consulta(remotoId = "7011"), examen = examen(peso = "70", fc = "72", ca = "95"))

        assertTrue(soloEllas.pasos.isEmpty())
        assertEquals(MotivoSinViaje.SIN_CAMBIO_REAL, soloEllas.sinViaje.single().motivo, "la fila se limpia sin enviar y sin figurar como enviada")
        assertEquals(mapOf("peso" to "70"), junto.paso<PasoDeEnvio.Examen>().campos)
    }

    @Test
    fun un_examen_vacio_sobre_nada_no_viaja_ni_queda_pendiente() {
        val p = plan(consulta(remotoId = "7011"), examen = examen())

        assertTrue(p.pasos.isEmpty())
        assertEquals(listOf(TablaDeLaFila.EXAMEN_FISICO), p.sinViaje.map { it.tabla })
    }

    // ---------- Lo que no viaja y NO queda pendiente ni figura enviado (RF-9, RF-60) ----------

    @Test
    fun un_antecedente_desactivado_no_viaja_no_queda_pendiente_y_no_figura_enviado() {
        val p = plan(consulta(remotoId = "7011"), antecedentes = listOf(antecedente("hta", activo = false)), habilitada = SubentregaDeEscritura.E4)

        assertTrue(p.pasos.isEmpty(), "ni con todas las subentregas habilitadas")
        assertEquals(listOf(MotivoSinViaje.DESACTIVADO), p.sinViaje.map { it.motivo })
        assertTrue(p.enReserva.isEmpty())
        assertFalse(p.hayAlgoPorEnviar, "no cuenta como pendiente")
    }

    @Test
    fun lo_que_la_web_no_guarda_no_viaja_y_se_limpia_sin_figurar_enviado() {
        val p = plan(
            consulta(remotoId = "7011"),
            antecedentes = listOf(antecedente("tabaquismo"), antecedente("alergias", categoria = "personales")),
            valores = listOf(valor("audio", valor = "x"), valor("diagnostico", valor = "x"), valor("antecedentes_personales", valor = "x")),
            habilitada = SubentregaDeEscritura.E4,
        )

        assertTrue(p.pasos.isEmpty())
        assertEquals(5, p.sinViaje.size)
        assertTrue(p.sinViaje.all { it.motivo == MotivoSinViaje.LA_WEB_NO_LA_GUARDA })
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun una_seccion_que_todavia_no_se_envia_no_viaja_no_cuenta_y_conserva_lo_cargado_para_cuando_se_habilite() {
        val p = plan(
            consulta(remotoId = "7011"),
            valores = listOf(valor("alimentacion", "hiposodica", valor = "1|poca sal"), valor("nota", valor = "texto")),
            antecedentes = listOf(antecedente("hta")), registros = listOf(registro("r1")),
        )

        assertEquals(listOf(PARTE_SECCIONES), p.partes, "solo el texto")
        assertEquals(3, p.enReserva.size)
        assertTrue(p.enReserva.all { it.motivo == MotivoEnReserva.TODAVIA_NO_SE_ENVIA })
        assertTrue(p.sinViaje.isEmpty(), "no se limpian: salen cuando la versión las habilite (RF-4)")
        assertEquals(1, p.pasos.sumOf { it.cubre.size }, "lo que espera no cuenta como pendiente")
    }

    @Test
    fun una_seccion_que_la_version_habilita_pasa_de_reserva_a_viajar() {
        val entrada = plan(
            consulta(remotoId = "7011"),
            valores = listOf(valor("alimentacion", "hiposodica", valor = "1|poca sal")), antecedentes = listOf(antecedente("hta")),
            registros = listOf(registro("r1")),
        )
        val e2 = plan(
            consulta(remotoId = "7011"),
            valores = listOf(valor("alimentacion", "hiposodica", valor = "1|poca sal")), antecedentes = listOf(antecedente("hta")),
            registros = listOf(registro("r1")), habilitada = SubentregaDeEscritura.E2,
        )

        assertTrue(entrada.pasos.isEmpty())
        assertEquals(listOf(PARTE_FORMULARIOS, PARTE_ANTECEDENTES, PARTE_REGISTROS), e2.partes)
        assertTrue(e2.enReserva.isEmpty())
    }

    @Test
    fun el_laboratorio_y_lo_desconocido_esperan_hasta_que_exista_su_envio() {
        val p = plan(consulta(remotoId = "7011"), valores = listOf(valor("seccion_inventada", valor = "x"), valor("nota", "campo_raro", valor = "x")))

        assertTrue(p.pasos.isEmpty())
        assertEquals(2, p.enReserva.size)
    }

    // ---------- Datos por paciente (RF-68) ----------

    @Test
    fun solo_viajan_los_datos_por_paciente_asociados_a_esta_consulta() {
        val p = plan(
            consulta(remotoId = "7011"),
            antecedentes = listOf(
                antecedente("hta"), antecedente("dbt_ir", consultaId = "historica-1"), antecedente("acv", consultaId = null), antecedente("dsl", consultaId = "otra-propia"),
            ),
            registros = listOf(registro("r-mia"), registro("r-hist", consultaId = "historica-1"), registro("r-ninguna", consultaId = null)),
            habilitada = SubentregaDeEscritura.E2,
        )

        assertEquals(listOf("hta"), p.paso<PasoDeEnvio.Antecedentes>().items.map { it.clave })
        assertEquals(listOf("r-mia"), p.paso<PasoDeEnvio.Registros>().items.map { it.id })
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty(), "los de otra consulta ni se envían ni se limpian ni se cuentan")
    }

    @Test
    fun el_vaciado_a_proposito_de_un_antecedente_y_la_baja_de_un_registro_viajan() {
        val p = plan(
            consulta(remotoId = "7011"),
            antecedentes = listOf(antecedente("hta", flag = false, detalle = "")), registros = listOf(registro("r1", activo = false)),
            habilitada = SubentregaDeEscritura.E2,
        )

        assertEquals(listOf(false), p.paso<PasoDeEnvio.Antecedentes>().items.map { it.flag })
        assertEquals(listOf(false), p.paso<PasoDeEnvio.Registros>().items.map { it.activo })
    }

    // ---------- Orden (RF-56) ----------

    private fun todoJunto(estado: EstadoConsulta = EstadoConsulta.CERRADA, remotoId: String = "", estados: Map<String, EstadoEnvio> = emptyMap(), reintentoManual: Boolean = false) = plan(
        consulta(estado = estado, remotoId = remotoId), sucia = true,
        valores = listOf(valor("nota", valor = "x"), valor("alimentacion", "hiposodica", valor = "1|x")), examen = examen(peso = "70"),
        antecedentes = listOf(antecedente("hta")), registros = listOf(registro("r1")),
        habilitada = SubentregaDeEscritura.E2, estados = estados, reintentoManual = reintentoManual,
    )

    @Test
    fun el_orden_es_creacion_contenido_y_cierre_al_final() {
        assertEquals(
            listOf(PARTE_CREAR, PARTE_SECCIONES, PARTE_EXAMEN, PARTE_FORMULARIOS, PARTE_ANTECEDENTES, PARTE_REGISTROS, PARTE_CIERRE),
            todoJunto().partes,
        )
    }

    @Test
    fun el_orden_no_depende_del_orden_de_las_filas_de_entrada() {
        val directo = plan(
            consulta(remotoId = "7011"), valores = listOf(valor("nota", valor = "a"), valor("conductas", valor = "b"), valor("habitos", valor = "c")),
            antecedentes = listOf(antecedente("hta"), antecedente("acv")), habilitada = SubentregaDeEscritura.E2,
        )
        val alReves = plan(
            consulta(remotoId = "7011"), valores = listOf(valor("habitos", valor = "c"), valor("conductas", valor = "b"), valor("nota", valor = "a")),
            antecedentes = listOf(antecedente("acv"), antecedente("hta")), habilitada = SubentregaDeEscritura.E2,
        )

        assertEquals(directo, alReves)
    }

    @Test
    fun el_cierre_lleva_la_fecha_y_la_edad_de_la_consulta_y_solo_sale_si_esta_cerrada_y_sin_confirmar() {
        val cerrada = plan(consulta(estado = EstadoConsulta.CERRADA, remotoId = "7011", edad = "47 años"), sucia = true)
        val cerradaYa = plan(consulta(estado = EstadoConsulta.CERRADA, remotoId = "7011"), sucia = false)
        val abierta = plan(consulta(estado = EstadoConsulta.ABIERTA, remotoId = "7011"), sucia = true)

        val cierre = cerrada.paso<PasoDeEnvio.Cerrar>()
        assertEquals("c:$id-cierre", cierre.clave)
        assertEquals(fecha, cierre.fecha)
        assertEquals("47 años", cierre.edadMostrar)
        assertEquals(listOf(PARTE_CIERRE), cerrada.partes)
        assertTrue(cerradaYa.pasos.isEmpty(), "el cierre ya enviado no se repite")
        assertTrue(abierta.pasos.isEmpty(), "reabierta: no hay cierre")
    }

    @Test
    fun una_consulta_cerrada_sin_crear_se_crea_completa_y_cierra_al_final() {
        val p = plan(consulta(estado = EstadoConsulta.CERRADA), sucia = true, valores = listOf(valor("nota", valor = "x")))

        assertEquals(listOf(PARTE_CREAR, PARTE_SECCIONES, PARTE_CIERRE), p.partes)
    }

    @Test
    fun el_cierre_no_sale_mientras_falte_confirmar_algo_anterior() {
        val p = todoJunto()
        val partes = p.partes
        var confirmadas = emptySet<String>()

        for (esperada in partes) {
            val siguiente = p.siguiente(confirmadas)
            assertEquals(esperada, siguiente?.parte)
            assertTrue(siguiente?.parte != PARTE_CIERRE || confirmadas.size == partes.size - 1, "el cierre solo sale con todo lo anterior confirmado")
            confirmadas = confirmadas + esperada
        }
        assertNull(p.siguiente(confirmadas), "ya está todo")
    }

    @Test
    fun una_parte_frenada_corta_el_envio_y_el_cierre_no_sale() {
        val p = todoJunto()

        assertNull(p.siguiente(setOf(PARTE_CREAR), frenadas = setOf(PARTE_SECCIONES)), "se corta ante la primera parte que falla")
        assertEquals(PARTE_SECCIONES, p.siguiente(setOf(PARTE_CREAR))?.parte)
        assertTrue(p.partes.last() == PARTE_CIERRE && p.siguiente(p.partes.dropLast(1).toSet(), frenadas = setOf(PARTE_CIERRE)) == null)
    }

    // ---------- Partes en espera, rechazadas y descartadas ----------

    @Test
    fun una_parte_en_espera_se_reintenta_y_el_cierre_sigue_al_final() {
        val p = todoJunto(estados = mapOf(PARTE_EXAMEN to espera, PARTE_CIERRE to espera))

        assertEquals(PARTE_CIERRE, p.partes.last())
        assertTrue(PARTE_EXAMEN in p.partes)
        assertTrue(p.retenciones.isEmpty(), "«En espera» no es un rechazo: se reintenta sola (RF-6)")
    }

    @Test
    fun una_parte_rechazada_no_se_reintenta_sola_y_frena_el_cierre() {
        val p = todoJunto(estados = mapOf(PARTE_EXAMEN to rechazo))

        assertEquals(listOf(PARTE_CREAR, PARTE_SECCIONES, PARTE_FORMULARIOS, PARTE_ANTECEDENTES, PARTE_REGISTROS), p.partes, "las demás partes siguen; sin el examen ni el cierre")
        assertEquals(
            listOf(Retencion(PARTE_EXAMEN, MotivoRetencion.RECHAZADA), Retencion(PARTE_CIERRE, MotivoRetencion.FRENADO_POR_UNA_PARTE_RECHAZADA)),
            p.retenciones,
        )
    }

    @Test
    fun el_reintento_a_mano_vuelve_a_incluir_lo_rechazado_y_el_cierre() {
        val p = todoJunto(estados = mapOf(PARTE_EXAMEN to rechazo, PARTE_CIERRE to rechazo), reintentoManual = true)

        assertTrue(PARTE_EXAMEN in p.partes && p.partes.last() == PARTE_CIERRE)
        assertTrue(p.retenciones.isEmpty())
    }

    @Test
    fun una_parte_descartada_no_frena_el_cierre_ni_se_limpia_por_su_cuenta() {
        val p = todoJunto(estados = mapOf(PARTE_EXAMEN to EstadoEnvio.Descartado))

        assertEquals(PARTE_CIERRE, p.partes.last())
        assertTrue(p.retenciones.isEmpty(), "el cierre sigue")
        assertTrue(p.sinViaje.isEmpty(), "lo nuevo que se edite después del descarte sí viaja: el plan no descarta nada solo")
    }

    @Test
    fun si_la_creacion_fue_rechazada_no_sale_nada_de_esa_consulta() {
        val p = todoJunto(estados = mapOf(PARTE_CREAR to rechazo))

        assertTrue(p.pasos.isEmpty())
        assertEquals(Retencion(PARTE_CREAR, MotivoRetencion.RECHAZADA), p.retenciones.first())
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun un_cierre_rechazado_no_se_reintenta_solo_pero_lo_nuevo_del_contenido_sale() {
        val p = plan(
            consulta(estado = EstadoConsulta.CERRADA, remotoId = "7011"), sucia = true, valores = listOf(valor("nota", valor = "nuevo")),
            estados = mapOf(PARTE_CIERRE to rechazo),
        )

        assertEquals(listOf(PARTE_SECCIONES), p.partes)
        assertEquals(listOf(Retencion(PARTE_CIERRE, MotivoRetencion.RECHAZADA)), p.retenciones)
    }

    // ---------- Consulta anulada ----------

    @Test
    fun una_anulada_que_nunca_se_creo_no_envia_nada_y_descarta_su_contenido() {
        val p = plan(
            consulta(estado = EstadoConsulta.ANULADA), sucia = true,
            valores = listOf(valor("nota", valor = "x")), examen = examen(peso = "70"), antecedentes = listOf(antecedente("hta")),
        )

        assertTrue(p.pasos.isEmpty(), "queda anulada solo en el dispositivo (RF-26)")
        assertEquals(listOf(TablaDeLaFila.SECCION_VALOR, TablaDeLaFila.EXAMEN_FISICO), p.sinViaje.map { it.tabla })
        assertTrue(p.sinViaje.all { it.motivo == MotivoSinViaje.CONSULTA_ANULADA })
        assertEquals(listOf(MotivoEnReserva.CONSULTA_ANULADA), p.enReserva.map { it.motivo }, "los datos por paciente no se tiran")
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun una_anulada_ya_creada_y_abierta_solo_manda_la_baja_y_descarta_lo_pendiente() {
        val p = plan(
            consulta(estado = EstadoConsulta.ANULADA, remotoId = "7011"), sucia = true,
            valores = listOf(valor("nota", valor = "x")), examen = examen(peso = "70"),
        )

        assertEquals(listOf(PARTE_BAJA), p.partes)
        assertEquals("c:$id-baja", p.paso<PasoDeEnvio.Baja>().clave)
        assertEquals(2, p.sinViaje.size)
        assertTrue(p.sinViaje.all { it.motivo == MotivoSinViaje.DESCARTADO_POR_ANULAR })
    }

    @Test
    fun una_anulada_con_la_baja_ya_enviada_o_rechazada_no_manda_nada() {
        assertTrue(plan(consulta(estado = EstadoConsulta.ANULADA, remotoId = "7011"), sucia = false).pasos.isEmpty())
        val rechazada = plan(consulta(estado = EstadoConsulta.ANULADA, remotoId = "7011"), sucia = true, estados = mapOf(PARTE_BAJA to rechazo))
        assertTrue(rechazada.pasos.isEmpty())
        assertEquals(listOf(Retencion(PARTE_BAJA, MotivoRetencion.RECHAZADA)), rechazada.retenciones)
        assertEquals(listOf(PARTE_BAJA), plan(consulta(estado = EstadoConsulta.ANULADA, remotoId = "7011"), sucia = true, estados = mapOf(PARTE_BAJA to rechazo), reintentoManual = true).partes)
    }

    // ---------- Lo que viajó (RF-55) ----------

    @Test
    fun cada_paso_dice_que_filas_cubre_y_cuando_se_modificaron_para_no_dar_por_enviado_lo_que_cambio_en_vuelo() {
        val p = plan(
            consulta(remotoId = "7011"), valores = listOf(valor("nota", valor = "x", en = 2_222)), examen = examen(peso = "70", en = 3_333),
            armadoEn = 9_999,
        )

        assertEquals(9_999, p.armadoEn)
        assertEquals(listOf(FilaCubierta(TablaDeLaFila.SECCION_VALOR, "nota/texto", 2_222)), p.paso<PasoDeEnvio.Secciones>().cubre)
        assertEquals(listOf(FilaCubierta(TablaDeLaFila.EXAMEN_FISICO, id, 3_333)), p.paso<PasoDeEnvio.Examen>().cubre)
        assertEquals(listOf(FilaCubierta(TablaDeLaFila.CONSULTA, id, 1_000)), plan(sucia = true).paso<PasoDeEnvio.Crear>().cubre)
    }

    // ---------- Determinismo y reloj ----------

    @Test
    fun el_mismo_plan_con_la_misma_entrada_y_el_reloj_solo_por_parametro() {
        val a = todoJunto(remotoId = "7011")
        val b = todoJunto(remotoId = "7011")
        val otroMomento = plan(
            consulta(estado = EstadoConsulta.CERRADA, remotoId = "7011"), sucia = true,
            valores = listOf(valor("nota", valor = "x"), valor("alimentacion", "hiposodica", valor = "1|x")), examen = examen(peso = "70"),
            antecedentes = listOf(antecedente("hta")), registros = listOf(registro("r1")), habilitada = SubentregaDeEscritura.E2, armadoEn = 77,
        )

        assertEquals(a, b)
        assertEquals(a.pasos, otroMomento.pasos, "el momento del armado no cambia qué viaja")
        assertEquals(77, otroMomento.armadoEn)
    }

    // ---------- Con lo que ya existe ----------

    @Test
    fun el_vaciado_a_proposito_sale_como_texto_vacio_en_el_cuerpo_de_la_api() {
        val p = plan(
            consulta(remotoId = "7011"),
            valores = listOf(valor("nota", valor = ""), valor("conductas", valor = "nuevo")),
            examen = examen(peso = "", talla = "170"),
            loEnviado = LoEnviado(valores = mapOf("nota/texto" to "algo"), examen = mapOf("peso" to "70", "talla" to "170")),
        )

        val secciones = PayloadsClinica.secciones(p.paso<PasoDeEnvio.Secciones>().textos)!!
        val examen = PayloadsClinica.examen(p.paso<PasoDeEnvio.Examen>().campos)!!

        // Las secciones salen ordenadas: el mismo cuerpo, byte a byte, en cada reintento (RF-15).
        assertEquals("""{"secciones":{"conductas":{"texto":"nuevo"},"nota":{"texto":""}}}""", secciones.toString())
        assertEquals("""{"peso":""}""", examen.toString())
    }

    @Test
    fun lo_confirmado_actualiza_lo_ultimo_enviado_para_que_el_vaciado_siguiente_se_note() {
        val p = plan(
            consulta(remotoId = "7011"), valores = listOf(valor("nota", valor = "texto")), examen = examen(peso = "70", talla = "0.00"),
        )
        var enviado = LoEnviado()
        for (paso in p.pasos) enviado = enviado.conLoConfirmado(paso)

        assertEquals(mapOf("nota/texto" to "texto"), enviado.valores)
        assertEquals(mapOf("peso" to "70"), enviado.examen)
        // Con eso, vaciar la nota después ahora sí es un cambio real y se envía.
        val vaciar = plan(consulta(remotoId = "7011"), valores = listOf(valor("nota", valor = "")), loEnviado = enviado)
        assertEquals(mapOf("nota" to ""), vaciar.paso<PasoDeEnvio.Secciones>().textos)
        assertIs<PasoDeEnvio.Secciones>(vaciar.pasos.single())
    }
}
