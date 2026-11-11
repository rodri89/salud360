package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec 005, T24 (RF-31, RF-60, RF-67): cuántas consultas de la HC general tienen cambios sin enviar. Cuenta **consultas** (no filas),
 * de **todo tipo de dato** que esta versión envía, solo de las **propias** (no del histórico ni de lo que trajo la web), no cuenta lo
 * que nunca va a viajar, y separa «Falta enviar», «En espera» y «No se pudo enviar». Puro: el plan de cada consulta lo arma
 * `planDeEnvio` y acá solo se cuenta.
 */
class ContadorDeEnvioTest {
    private val fecha = LocalDate.parse("2026-11-06")

    private fun propia(id: Id, estado: EstadoConsulta = EstadoConsulta.ABIERTA, remotoId: String = "") =
        Consulta(id, "p-$id", "m1", "clinica", "consulta", fecha, estado, remotoId = remotoId, propia = true)

    private fun sucia(valor: SeccionValor) = FilaSucia(valor, dirty = true, actualizadaEn = 10)
    private fun texto(consulta: Id, seccion: String = SeccionesComunes.NOTA) = sucia(SeccionValor(consulta, seccion, "texto", "texto inventado"))

    /** La entrada del contador de [consulta] con lo que tenga de sucio, para la versión que envía hasta [habilitadas]. */
    private fun entrada(
        consulta: Consulta,
        consultaSucia: Boolean = false,
        valores: List<FilaSucia<SeccionValor>> = emptyList(),
        examen: FilaSucia<ExamenFisico>? = null,
        antecedentes: List<FilaSucia<Antecedente>> = emptyList(),
        registros: List<FilaSucia<RegistroClinico>> = emptyList(),
        laboratorios: List<Laboratorio> = emptyList(),
        pendientes: List<Pendiente> = emptyList(),
        estados: Map<String, EstadoEnvio> = emptyMap(),
        habilitadas: Set<String> = seccionesDe(SubentregaDeEscritura.E1),
    ) = EntradaDelContador(
        pacienteId = consulta.pacienteId,
        plan = planDeEnvio(
            EntradaDelPlan(consulta, consultaSucia, valores, examen, antecedentes, registros, estados = estados, habilitadas = habilitadas, armadoEn = 1_000),
        ),
        estados = estados,
        laboratorios = laboratorios,
        pendientes = pendientes,
        habilitadas = habilitadas,
    )

    private val enEspera = EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "Sin conexión con la historia clínica general")
    private val rechazado = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "La web no acepta ese dato")

    // ---- Qué cuenta ----

    @Test
    fun una_propia_con_un_texto_sin_enviar_cuenta_una_y_falta_enviar() {
        val r = resumenDeEnvio(listOf(entrada(propia("c1"), valores = listOf(texto("c1")))))

        assertEquals(1, r.total)
        assertEquals(listOf(ConsultaPorEnviar("c1", "p-c1", SituacionDeEnvio.FALTA_ENVIAR)), r.consultas)
        assertEquals(1, r.faltaEnviar)
        assertEquals(0, r.enEspera)
        assertEquals(0, r.noSePudoEnviar)
    }

    @Test
    fun una_propia_sin_nada_sucio_no_cuenta() {
        val r = resumenDeEnvio(listOf(entrada(propia("c1", remotoId = "7001"))))

        assertEquals(0, r.total)
        assertEquals(emptyList(), r.consultas)
    }

    @Test
    fun una_propia_sin_crear_cuenta_aunque_no_tenga_contenido() {
        // Falta crearla en la web: su fila sigue sucia.
        assertEquals(1, resumenDeEnvio(listOf(entrada(propia("c1"), consultaSucia = true))).total)
    }

    @Test
    fun una_cerrada_con_el_cierre_sin_enviar_cuenta_y_una_cerrada_con_el_cierre_enviado_no() {
        val sinCierre = entrada(propia("c1", EstadoConsulta.CERRADA, remotoId = "7001"), consultaSucia = true)
        val conCierre = entrada(propia("c2", EstadoConsulta.CERRADA, remotoId = "7002"), consultaSucia = false)

        assertEquals(listOf("c1"), resumenDeEnvio(listOf(sinCierre, conCierre)).consultas.map { it.consultaId })
    }

    @Test
    fun una_consulta_anulada_ya_creada_cuenta_por_la_baja_y_una_anulada_sin_crear_no() {
        val conBaja = entrada(propia("c1", EstadoConsulta.ANULADA, remotoId = "7001"), consultaSucia = true, valores = listOf(texto("c1")))
        val sinCrear = entrada(propia("c2", EstadoConsulta.ANULADA), consultaSucia = true, valores = listOf(texto("c2")))

        assertEquals(listOf("c1"), resumenDeEnvio(listOf(conBaja, sinCrear)).consultas.map { it.consultaId })
    }

    @Test
    fun cuenta_consultas_y_no_filas_aunque_tenga_de_todo() {
        val c = propia("c1")
        val r = resumenDeEnvio(
            listOf(
                entrada(
                    c,
                    consultaSucia = true,
                    valores = listOf(texto("c1"), texto("c1", SeccionesComunes.MOTIVO_CONSULTA), texto("c1", "habitos")),
                    examen = FilaSucia(ExamenFisico("c1", peso = "70", talla = "170"), dirty = true, actualizadaEn = 10),
                ),
            ),
        )

        assertEquals(1, r.total)
    }

    // ---- Todo tipo de dato (RF-31) ----

    @Test
    fun cuenta_todo_tipo_de_dato_que_esta_version_envia() {
        val e = seccionesDe(SubentregaDeEscritura.E3)
        val entradas = listOf(
            entrada(propia("texto"), valores = listOf(texto("texto")), habilitadas = e),
            entrada(
                propia("examen"),
                examen = FilaSucia(ExamenFisico("examen", peso = "70"), dirty = true, actualizadaEn = 10),
                habilitadas = e,
            ),
            entrada(propia("formulario"), valores = listOf(sucia(SeccionValor("formulario", "alimentacion", "hiposodica", "1|mate"))), habilitadas = e),
            entrada(
                propia("antecedente"),
                antecedentes = listOf(FilaSucia(Antecedente("a1", "p-antecedente", "clinica", "familiares", "hta", true, "padre", "antecedente"), true, 10)),
                habilitadas = e,
            ),
            entrada(
                propia("registro", remotoId = "7001"),
                registros = listOf(
                    // T62-b: un estudio de los cinco con fecha y detalle. La consulta ya está creada, así que solo cuenta si el estudio viaja.
                    FilaSucia(RegistroClinico("r1", "p-registro", "registro", "clinica", "eco_abdominal", fecha, mapOf("detalle" to "estudio")), true, 10),
                ),
                habilitadas = e,
            ),
            entrada(
                propia("laboratorio"),
                laboratorios = listOf(Laboratorio("l1", "p-laboratorio", "laboratorio", "clinica", "laboratorio_general", fecha, mapOf("hb" to "13"))),
                habilitadas = e,
            ),
            entrada(propia("pendiente"), pendientes = listOf(Pendiente("pe1", "p-pendiente", "m1", "pedir estudio", "pendiente")), habilitadas = e),
        )

        val r = resumenDeEnvio(entradas)

        assertEquals(7, r.total, "una consulta por cada tipo de dato: ${r.consultas}")
        assertEquals(7, r.faltaEnviar)
    }

    @Test
    fun laboratorio_y_pendientes_cuentan_solo_cuando_esta_version_los_envia() {
        val lab = Laboratorio("l1", "p-c1", "c1", "clinica", "laboratorio_general", fecha, mapOf("hb" to "13"))
        val pend = Pendiente("pe1", "p-c2", "m1", "pedir estudio", "c2")

        fun cuenta(habilitadas: Set<String>) = resumenDeEnvio(
            listOf(
                entrada(propia("c1", remotoId = "7001"), laboratorios = listOf(lab), habilitadas = habilitadas),
                entrada(propia("c2", remotoId = "7002"), pendientes = listOf(pend), habilitadas = habilitadas),
            ),
        ).total

        // RF-4, RF-60: hasta que la versión los envíe no se cuentan (no van a viajar) ni frenan nada.
        assertEquals(0, cuenta(seccionesDe(SubentregaDeEscritura.E1)))
        assertEquals(0, cuenta(seccionesDe(SubentregaDeEscritura.E2)))
        assertEquals(2, cuenta(seccionesDe(SubentregaDeEscritura.E3)))
    }

    // ---- Lo que no cuenta ----

    @Test
    fun el_historico_no_cuenta_aunque_tenga_de_todo_sucio() {
        val historica = Consulta("h1", "p-h1", "m1", "clinica", "consulta", fecha, EstadoConsulta.CERRADA)
        val r = resumenDeEnvio(
            listOf(
                entrada(
                    historica,
                    consultaSucia = true,
                    valores = listOf(texto("h1")),
                    examen = FilaSucia(ExamenFisico("h1", peso = "70"), dirty = true, actualizadaEn = 10),
                    laboratorios = listOf(Laboratorio("l1", "p-h1", "h1", "clinica", "laboratorio_general", fecha, mapOf("hb" to "13"))),
                    habilitadas = seccionesDe(SubentregaDeEscritura.E3),
                ),
            ),
        )

        assertEquals(0, r.total, "el histórico es «Solo en este dispositivo»: no es un pendiente de envío (RF-67)")
    }

    @Test
    fun lo_que_trajo_la_web_no_cuenta() {
        val deLaWeb = Consulta("hcc-c7003", "p-1", "m1", "clinica", "consulta", fecha, EstadoConsulta.CERRADA, remotoId = "7003")

        assertEquals(0, resumenDeEnvio(listOf(entrada(deLaWeb, consultaSucia = true, valores = listOf(texto("hcc-c7003"))))).total)
    }

    @Test
    fun lo_que_la_web_no_guarda_y_lo_desactivado_no_cuenta() {
        val c = propia("c1", remotoId = "7001")
        val desactivado = Antecedente("a1", "p-c1", "clinica", "familiares", "hta", true, "x", "c1", activo = false)
        val tabaquismo = Antecedente("a2", "p-c1", "clinica", "familiares", "tabaquismo", true, "", "c1")
        val personales = Antecedente("a3", "p-c1", "clinica", "personales", "asma", true, "", "c1")
        val diagnostico = sucia(SeccionValor("c1", SeccionesComunes.DIAGNOSTICO, "texto", "x"))

        val r = resumenDeEnvio(
            listOf(
                entrada(
                    c,
                    valores = listOf(diagnostico),
                    antecedentes = listOf(desactivado, tabaquismo, personales).map { FilaSucia(it, true, 10) },
                    habilitadas = seccionesDe(SubentregaDeEscritura.E3),
                ),
            ),
        )

        assertEquals(0, r.total, "RF-60: lo que nunca va a viajar no se deja como pendiente ni se cuenta")
    }

    @Test
    fun una_seccion_que_esta_version_todavia_no_envia_no_cuenta_hasta_que_la_habilita() {
        val formulario = listOf(sucia(SeccionValor("c1", "alimentacion", "hiposodica", "1|mate")))

        val creada = propia("c1", remotoId = "7001")
        assertEquals(0, resumenDeEnvio(listOf(entrada(creada, valores = formulario, habilitadas = seccionesDe(SubentregaDeEscritura.E1)))).total)
        assertEquals(1, resumenDeEnvio(listOf(entrada(creada, valores = formulario, habilitadas = seccionesDe(SubentregaDeEscritura.E2)))).total)
    }

    @Test
    fun un_texto_que_ya_esta_igual_en_la_web_no_cuenta() {
        val c = propia("c1", remotoId = "7001")
        val plan = planDeEnvio(
            EntradaDelPlan(
                c, consultaSucia = false, valores = listOf(texto("c1")),
                loEnviado = LoEnviado(valores = mapOf("nota/texto" to "texto inventado")), armadoEn = 1_000,
            ),
        )

        assertEquals(0, resumenDeEnvio(listOf(EntradaDelContador("p-c1", plan))).total)
    }

    // ---- «En espera» y «No se pudo enviar» (RF-31) ----

    @Test
    fun separa_falta_enviar_en_espera_y_no_se_pudo_enviar() {
        val r = resumenDeEnvio(
            listOf(
                entrada(propia("falta"), valores = listOf(texto("falta"))),
                entrada(propia("espera"), valores = listOf(texto("espera")), estados = mapOf(PARTE_CREAR to enEspera)),
                entrada(propia("rechazo"), valores = listOf(texto("rechazo")), estados = mapOf(PARTE_SECCIONES to rechazado)),
            ),
        )

        assertEquals(3, r.total)
        assertEquals(1, r.faltaEnviar)
        assertEquals(1, r.enEspera)
        assertEquals(1, r.noSePudoEnviar)
        assertEquals(
            listOf(
                ConsultaPorEnviar("falta", "p-falta", SituacionDeEnvio.FALTA_ENVIAR),
                ConsultaPorEnviar("espera", "p-espera", SituacionDeEnvio.EN_ESPERA),
                ConsultaPorEnviar("rechazo", "p-rechazo", SituacionDeEnvio.NO_SE_PUDO_ENVIAR),
            ),
            r.consultas,
        )
        assertEquals(r.total, r.faltaEnviar + r.enEspera + r.noSePudoEnviar, "cada consulta está en una sola situación")
    }

    @Test
    fun una_parte_rechazada_sigue_contando_aunque_ya_no_viaje() {
        // El plan saca de los pasos la parte rechazada (no se reintenta sola), pero sigue sin enviarse: la consulta cuenta.
        val r = resumenDeEnvio(listOf(entrada(propia("c1"), valores = listOf(texto("c1")), estados = mapOf(PARTE_SECCIONES to rechazado))))

        assertEquals(1, r.total)
        assertEquals(1, r.noSePudoEnviar)
    }

    @Test
    fun una_cerrada_con_el_cierre_frenado_por_una_parte_rechazada_cuenta_como_no_se_pudo_enviar() {
        val c = propia("c1", EstadoConsulta.CERRADA, remotoId = "7001")
        val r = resumenDeEnvio(listOf(entrada(c, consultaSucia = true, valores = listOf(texto("c1")), estados = mapOf(PARTE_SECCIONES to rechazado))))

        assertEquals(listOf(SituacionDeEnvio.NO_SE_PUDO_ENVIAR), r.consultas.map { it.situacion })
    }

    @Test
    fun si_hay_una_parte_en_espera_y_otra_rechazada_gana_la_rechazada() {
        // Es la que necesita al médico: reintentar o descartar. La que espera se arregla sola.
        val estados = mapOf(PARTE_CREAR to enEspera, PARTE_SECCIONES to rechazado)

        val r = resumenDeEnvio(listOf(entrada(propia("c1"), valores = listOf(texto("c1")), estados = estados)))

        assertEquals(SituacionDeEnvio.NO_SE_PUDO_ENVIAR, r.consultas.single().situacion)
    }

    @Test
    fun el_estado_en_espera_de_una_parte_que_no_tiene_nada_pendiente_no_cambia_la_situacion() {
        // «Examen» quedó en espera en otro momento, pero hoy lo que falta es solo el texto.
        val r = resumenDeEnvio(listOf(entrada(propia("c1"), valores = listOf(texto("c1")), estados = mapOf(PARTE_EXAMEN to enEspera))))

        assertEquals(SituacionDeEnvio.FALTA_ENVIAR, r.consultas.single().situacion)
    }

    @Test
    fun un_estado_en_espera_o_descartado_sin_nada_pendiente_no_hace_contar_la_consulta() {
        val c = propia("c1", remotoId = "7001")
        val r = resumenDeEnvio(listOf(entrada(c, estados = mapOf(PARTE_CIERRE to enEspera, PARTE_EXAMEN to EstadoEnvio.Descartado))))

        assertEquals(0, r.total)
    }

    @Test
    fun una_parte_rechazada_sigue_pendiente_hasta_que_el_medico_la_resuelva_aunque_no_quede_nada_sucio() {
        // Un rechazo no se reintenta solo ni se olvida: la consulta no se cierra en la web hasta que el médico reintente o descarte (RF-56).
        val c = propia("c1", remotoId = "7001")
        val r = resumenDeEnvio(listOf(entrada(c, estados = mapOf(PARTE_SECCIONES to rechazado))))

        assertEquals(listOf(SituacionDeEnvio.NO_SE_PUDO_ENVIAR), r.consultas.map { it.situacion })
    }

    @Test
    fun el_estado_descartado_no_cambia_lo_que_falta_enviar() {
        val r = resumenDeEnvio(listOf(entrada(propia("c1"), valores = listOf(texto("c1")), estados = mapOf(PARTE_SECCIONES to EstadoEnvio.Descartado))))

        assertEquals(SituacionDeEnvio.FALTA_ENVIAR, r.consultas.single().situacion)
    }
    // ---- El estado de una consulta (la marca de su tarjeta, T28) ----

    @Test
    fun el_estado_pendiente_de_una_consulta_es_el_mas_grave_de_sus_partes() {
        val texto = listOf(texto("c1"))

        assertNull(estadoPendienteDeLaConsulta(entrada(propia("c1", remotoId = "7001"))), "nada pendiente: sin estado")
        assertEquals(EstadoEnvio.Pendiente, estadoPendienteDeLaConsulta(entrada(propia("c1"), valores = texto)))
        assertEquals(enEspera, estadoPendienteDeLaConsulta(entrada(propia("c1"), valores = texto, estados = mapOf(PARTE_CREAR to enEspera))))
        assertEquals(rechazado, estadoPendienteDeLaConsulta(entrada(propia("c1"), valores = texto, estados = mapOf(PARTE_SECCIONES to rechazado))))
        assertEquals(
            rechazado,
            estadoPendienteDeLaConsulta(entrada(propia("c1"), valores = texto, estados = mapOf(PARTE_CREAR to enEspera, PARTE_SECCIONES to rechazado))),
            "un rechazo manda sobre una espera",
        )
    }

    @Test
    fun el_estado_pendiente_de_lo_que_no_se_cuenta_es_nulo() {
        val historica = Consulta("h1", "p-h1", "m1", "clinica", "consulta", fecha, EstadoConsulta.CERRADA)

        assertNull(estadoPendienteDeLaConsulta(entrada(historica, consultaSucia = true, valores = listOf(texto("h1")))))
    }

    @Test
    fun la_situacion_de_la_cuenta_sale_del_mismo_estado() {
        val r = resumenDeEnvio(listOf(entrada(propia("c1"), valores = listOf(texto("c1")), estados = mapOf(PARTE_CREAR to enEspera))))

        assertEquals(SituacionDeEnvio.EN_ESPERA, r.consultas.single().situacion)
    }

    // ---- Forma ----

    @Test
    fun sin_entradas_no_hay_nada() {
        val r = resumenDeEnvio(emptyList())

        assertEquals(0, r.total)
        assertEquals(ResumenDeEnvio(emptyList()), r)
    }

    @Test
    fun conserva_el_orden_de_las_entradas() {
        val r = resumenDeEnvio(listOf("b", "a", "c").map { entrada(propia(it), valores = listOf(texto(it))) })

        assertEquals(listOf("b", "a", "c"), r.consultas.map { it.consultaId })
    }
}
