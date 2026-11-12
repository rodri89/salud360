package com.salud360.core.data.hcgeneral

import com.salud360.core.data.hcgeneral.SubentregaDeEscritura.E2
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura.E3
import com.salud360.core.data.network.hc.PayloadsClinica
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
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
 * Spec 005 RF-41, RF-42, RF-56, RF-60, RF-68 y RF-69 (T84-a): el plan de envío de las **cargas de laboratorio general y de peso** de una consulta
 * propia, con `habilitadas = seccionesDe(E3)`. Puro. Una carga viaja con la fecha que resolvió [fechaAEnviar] (la suya o la de la consulta; nunca un
 * marcador de «sin fecha» ni la de hoy), al alta con todos los valores con contenido y al actualizar solo lo tocado contra lo último que la web confirmó
 * (`LoEnviado.cargas`); una que la web ya tenía se da de baja (`DELETE`, sin fecha ni valores) y una que nunca se envió y se borró no viaja. Las de otra
 * consulta, del histórico o sin consulta no viajan (RF-68), y las de una anulada quedan en reserva (Q-E3-2). El laboratorio reducido y los pendientes
 * quedaron fuera de alcance (T73, T82-b) y esperan en reserva.
 */
class PlanDeEnvioE3Test {
    private val id = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
    private val fecha = LocalDate(2026, 11, 2)
    private val e3 = seccionesDe(E3)

    private fun consulta(estado: EstadoConsulta = EstadoConsulta.ABIERTA, remotoId: String = "7011", fecha: LocalDate = this.fecha) =
        Consulta(id, "p1", "m1", "clinica", "control", fecha, estado, "46 años", Instant.fromEpochMilliseconds(1_000), remotoId, true)

    private fun valor(seccion: String, campo: String, valor: String, dirty: Boolean = true, en: Long = 2_000) =
        FilaSucia(SeccionValor(id, seccion, campo, valor), dirty, en)

    private fun antecedente(
        clave: String, flag: Boolean = true, detalle: String = "x", categoria: String = "familiares", activo: Boolean = true,
        consultaId: String? = id, dirty: Boolean = true, en: Long = 2_500,
    ) = FilaSucia(Antecedente("a-$categoria-$clave", "p1", "clinica", categoria, clave, flag, detalle, consultaId, activo), dirty, en)

    private fun estudio(rid: String, tipo: String = "eco_abdominal", detalle: String = "hígado de aspecto normal", consultaId: String? = id) =
        FilaSucia(RegistroClinico(rid, "p1", consultaId, "clinica", tipo, fecha, mapOf("detalle" to detalle)), true, 3_100)

    /** Una carga de laboratorio general (o de peso, con [tipo]) tal como la deja el repositorio: solo las sucias llegan al plan. */
    private fun carga(
        id: String = "l1",
        tipo: String = TIPO_LABORATORIO_GENERAL,
        valores: Map<String, String> = mapOf("hb" to "13.5"),
        fecha: LocalDate = LocalDate(2026, 11, 6),
        remotoId: String = "",
        activo: Boolean = true,
        consultaId: String? = this.id,
        dirty: Boolean = true,
        en: Long = 4_100,
    ) = FilaSucia(Laboratorio(id, "p1", consultaId, "clinica", tipo, fecha, valores, activo, remotoId), dirty, en)

    private fun plan(
        consulta: Consulta = consulta(),
        sucia: Boolean = false,
        valores: List<FilaSucia<SeccionValor>> = emptyList(),
        examen: FilaSucia<ExamenFisico>? = null,
        antecedentes: List<FilaSucia<Antecedente>> = emptyList(),
        registros: List<FilaSucia<RegistroClinico>> = emptyList(),
        cargas: List<FilaSucia<Laboratorio>> = emptyList(),
        loEnviado: LoEnviado = LoEnviado(),
        estados: Map<String, EstadoEnvio> = emptyMap(),
        habilitadas: Set<String> = e3,
        reintentoManual: Boolean = false,
    ) = planDeEnvio(
        EntradaDelPlan(
            consulta = consulta, consultaSucia = sucia, valores = valores, examen = examen, antecedentes = antecedentes,
            registros = registros, cargas = cargas, loEnviado = loEnviado, estados = estados, habilitadas = habilitadas,
            reintentoManual = reintentoManual, armadoEn = 5_000,
        ),
    )

    private fun json(texto: String) = Json.parseToJsonElement(texto) as JsonObject

    private val PlanDeEnvio.partes get() = pasos.map { it.parte }
    private inline fun <reified T : PasoDeEnvio> PlanDeEnvio.paso(): T = pasos.filterIsInstance<T>().single()
    private val rechazo = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "datos")

    /** El cuerpo del pedido de una carga del paso, armado como lo hará la cola (T85): `PayloadsClinica.carga` con lo confirmado. */
    private fun cuerpo(paso: PasoDeEnvio.Cargas, i: Int = 0, confirmada: Laboratorio? = null): JsonObject? {
        val viaja = paso.cargas[i] as CargaQueViaja.Guardar
        return PayloadsClinica.carga(
            clave = PayloadsClinica.claveDeCarga(viaja.carga.id),
            dispositivo = "disp-1",
            consultaWebId = 7011,
            fecha = viaja.fecha,
            carga = viaja.carga,
            idWeb = viaja.carga.remotoId.takeIf { it.isNotBlank() }?.toLong(),
            confirmada = confirmada,
            tipo = viaja.carga.tipo,
        )
    }

    // ---------- Altas: con la fecha que resolvió el plan y los valores con contenido ----------

    @Test
    fun una_carga_nueva_viaja_con_su_fecha_y_su_fila_cubierta() {
        val nueva = carga("l1", valores = mapOf("hb" to "13.5", "glucemia" to "", "otro" to "control en 3 meses"), en = 4_100)
        val p = plan(cargas = listOf(nueva))

        assertEquals(listOf(PARTE_CARGAS), p.partes)
        val paso = p.paso<PasoDeEnvio.Cargas>()
        assertEquals(listOf(CargaQueViaja.Guardar(nueva.fila, LocalDate(2026, 11, 6))), paso.cargas)
        assertEquals(listOf(FilaCubierta(TablaDeLaFila.LABORATORIO, "l1", 4_100)), paso.cubre, "una fila por carga con su updated_at (RF-55)")
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty())
        assertTrue(p.hayAlgoPorEnviar)
        // El cuerpo de la API (T81) manda la fecha que decidió el plan y solo los valores con contenido.
        assertEquals(
            json(
                """{"clave":"l:l1","dispositivo":"disp-1","consulta_id":"7011","tipo":"laboratorio_general","fecha":"2026-11-06",
                    "valores":{"hb":"13.5","otro":"control en 3 meses"}}""",
            ),
            cuerpo(paso),
        )
    }

    @Test
    fun las_cargas_de_un_mismo_paso_salen_ordenadas_por_su_id_con_su_updated_at() {
        val p = plan(
            cargas = listOf(
                carga("l2", valores = mapOf("hb" to "14"), en = 4_200),
                carga("l1", valores = mapOf("hb" to "13.5"), en = 4_100),
                carga("p1", tipo = TIPO_PESO, valores = mapOf("peso" to "72.5"), en = 4_300),
            ),
        )

        val paso = p.paso<PasoDeEnvio.Cargas>()
        assertEquals(listOf("l1", "l2", "p1"), paso.cargas.map { it.carga.id })
        assertEquals(
            listOf(
                FilaCubierta(TablaDeLaFila.LABORATORIO, "l1", 4_100),
                FilaCubierta(TablaDeLaFila.LABORATORIO, "l2", 4_200),
                FilaCubierta(TablaDeLaFila.LABORATORIO, "p1", 4_300),
            ),
            paso.cubre,
        )
    }

    @Test
    fun una_carga_sin_fecha_propia_viaja_con_la_de_la_consulta_y_nunca_con_un_marcador_ni_la_de_hoy() {
        val deLaConsulta = LocalDate(2024, 2, 29)
        for (marcador in listOf(LocalDate(2000, 1, 1), LocalDate(1000, 1, 1))) {
            val p = plan(consulta(fecha = deLaConsulta), cargas = listOf(carga(fecha = marcador)))

            val viaja = p.paso<PasoDeEnvio.Cargas>().cargas.single() as CargaQueViaja.Guardar
            assertEquals(deLaConsulta, viaja.fecha, "la de la consulta: ni el marcador $marcador ni la fecha de hoy")
            assertFalse(viaja.fecha in PayloadsClinica.FECHAS_SIN_DATO)
            assertEquals("$deLaConsulta", cuerpo(p.paso<PasoDeEnvio.Cargas>())?.get("fecha")?.toString()?.trim('"'))
        }
    }

    @Test
    fun la_fecha_propia_de_una_carga_viaja_tal_cual() {
        val p = plan(consulta(fecha = LocalDate(2026, 11, 10)), cargas = listOf(carga(fecha = LocalDate(2024, 2, 29))))

        assertEquals(LocalDate(2024, 2, 29), (p.paso<PasoDeEnvio.Cargas>().cargas.single() as CargaQueViaja.Guardar).fecha)
    }

    @Test
    fun si_ni_la_carga_ni_la_consulta_tienen_una_fecha_util_la_carga_queda_en_reserva() {
        val p = plan(consulta(fecha = LocalDate(2000, 1, 1)), cargas = listOf(carga(fecha = LocalDate(2000, 1, 1))))

        assertTrue(p.pasos.isEmpty(), "el servidor exige una fecha (RF-41): no se inventa ninguna")
        assertEquals(listOf(FilaEnReserva(TablaDeLaFila.LABORATORIO, "l1", MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, 4_100)), p.enReserva)
        assertTrue(p.sinViaje.isEmpty(), "sigue sucio: no se pierde lo que el médico cargó")
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun el_peso_viaja_sin_tipo_y_con_la_clave_peso() {
        val peso = carga("p1", tipo = TIPO_PESO, valores = mapOf("peso" to "72.5"), fecha = LocalDate(2000, 1, 1))
        val p = plan(cargas = listOf(peso))

        val viaja = p.paso<PasoDeEnvio.Cargas>().cargas.single() as CargaQueViaja.Guardar
        assertEquals(LocalDate(2026, 11, 2), viaja.fecha, "el marcador del peso no deja la carga sin fecha: viaja la de la consulta")
        assertEquals(
            json("""{"clave":"l:p1","dispositivo":"disp-1","consulta_id":"7011","fecha":"2026-11-02","valores":{"peso":"72.5"}}"""),
            cuerpo(p.paso<PasoDeEnvio.Cargas>()),
        )
    }

    @Test
    fun una_carga_nueva_sin_ningun_valor_con_contenido_no_viaja() {
        val p = plan(cargas = listOf(carga("l1", valores = mapOf("hb" to "", "glucemia" to "  "))))

        assertTrue(p.pasos.isEmpty(), "el servidor contesta 422 carga_vacia: no hay nada que mandar")
        assertEquals(listOf(FilaSinViaje(TablaDeLaFila.LABORATORIO, "l1", MotivoSinViaje.SIN_CAMBIO_REAL, 4_100)), p.sinViaje)
        assertTrue(p.enReserva.isEmpty())
    }

    // ---------- Actualizaciones: solo lo tocado contra lo confirmado ----------

    @Test
    fun al_actualizar_viaja_solo_lo_que_cambio_contra_lo_confirmado() {
        val enviada = carga("l1", valores = mapOf("hb" to "13.5", "glucemia" to "0.98"), remotoId = "731")
        val lo = LoEnviado(cargas = mapOf("731" to mapOf("hb" to "13.5", "glucemia" to "0.95", "alb_mina" to "4.1")))
        val p = plan(cargas = listOf(enviada), loEnviado = lo)

        val paso = p.paso<PasoDeEnvio.Cargas>()
        assertEquals(listOf(CargaQueViaja.Guardar(enviada.fila, LocalDate(2026, 11, 6))), paso.cargas)
        assertEquals(
            mapOf("hb" to "13.5", "glucemia" to "0.95", "alb_mina" to "4.1"),
            lo.cargaEnviada(enviada.fila)?.valores,
            "lo que la web tiene antes de este pedido, para el cuerpo",
        )
        assertEquals(
            json(
                """{"clave":"l:l1","dispositivo":"disp-1","consulta_id":"7011","tipo":"laboratorio_general","id":"731",
                    "fecha":"2026-11-06","valores":{"glucemia":"0.98","alb_mina":""}}""",
            ),
            cuerpo(paso, confirmada = lo.cargaEnviada(enviada.fila)),
            "solo lo tocado; la columna que ya no está viaja vacía (RF-34, RF-69)",
        )
    }

    @Test
    fun una_carga_igual_a_lo_que_la_web_tiene_no_viaja_y_se_limpia_sin_figurar_enviada() {
        val enviada = carga("l1", valores = mapOf("hb" to "13.5"), remotoId = "731")
        val p = plan(cargas = listOf(enviada), loEnviado = LoEnviado(cargas = mapOf("731" to mapOf("hb" to "13.5"))))

        assertTrue(p.pasos.isEmpty())
        assertEquals(listOf(FilaSinViaje(TablaDeLaFila.LABORATORIO, "l1", MotivoSinViaje.SIN_CAMBIO_REAL, 4_100)), p.sinViaje)
        assertFalse(p.hayAlgoPorEnviar)
    }

    @Test
    fun una_carga_sin_id_de_la_web_se_manda_como_alta_aunque_lo_confirmado_tenga_los_mismos_valores() {
        // La misma fila sin remoto_id es un alta: lo que la web tenga de otra carga no decide nada (el id es la identidad).
        val nueva = carga("l1", valores = mapOf("hb" to "13.5"), remotoId = "")
        val p = plan(cargas = listOf(nueva), loEnviado = LoEnviado(cargas = mapOf("731" to mapOf("hb" to "13.5"))))

        assertEquals(listOf(PARTE_CARGAS), p.partes)
        assertNull(cuerpo(p.paso<PasoDeEnvio.Cargas>())?.get("id"), "sin id: es un alta")
    }

    // ---------- Bajas ----------

    @Test
    fun la_baja_de_una_carga_que_la_web_tiene_viaja_sin_fecha_ni_valores() {
        val borrada = carga("l1", valores = mapOf("hb" to "13.5"), remotoId = "731", activo = false, en = 4_100)
        val p = plan(cargas = listOf(borrada))

        assertEquals(listOf(PARTE_CARGAS), p.partes)
        val paso = p.paso<PasoDeEnvio.Cargas>()
        assertEquals(listOf(CargaQueViaja.Baja(borrada.fila)), paso.cargas)
        assertEquals(listOf(FilaCubierta(TablaDeLaFila.LABORATORIO, "l1", 4_100)), paso.cubre)
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty())
        assertEquals(
            json("""{"clave":"l:l1-baja","dispositivo":"disp-1","consulta_id":"7011"}"""),
            PayloadsClinica.bajaDeCarga(PayloadsClinica.claveDeBajaDeCarga("l1"), "disp-1", 7011),
        )
    }

    @Test
    fun una_carga_que_nunca_se_envio_y_se_borro_no_viaja() {
        // El repositorio la retira con `deleted = 1, dirty = 0` (T83-a): igual no viaja ni queda pendiente.
        val retirada = plan(cargas = listOf(carga("l1", remotoId = "", activo = false, dirty = false)))
        assertTrue(retirada.pasos.isEmpty() && retirada.sinViaje.isEmpty() && retirada.enReserva.isEmpty())

        val sucia = plan(cargas = listOf(carga("l1", remotoId = "", activo = false)))
        assertTrue(sucia.pasos.isEmpty())
        assertEquals(listOf(FilaSinViaje(TablaDeLaFila.LABORATORIO, "l1", MotivoSinViaje.SIN_CAMBIO_REAL, 4_100)), sucia.sinViaje, "en la web no hay nada que dar de baja")
    }

    // ---------- Las que no viajan ----------

    @Test
    fun solo_viajan_las_cargas_de_esta_consulta() {
        val p = plan(
            cargas = listOf(
                carga("l1"), carga("l2", consultaId = "otra-propia"), carga("l3", consultaId = "historica-1"), carga("l4", consultaId = null),
            ),
        )

        assertEquals(listOf("l1"), p.paso<PasoDeEnvio.Cargas>().cargas.map { it.carga.id })
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty(), "las de otra consulta ni se envían ni se limpian ni se cuentan (RF-68)")
    }

    @Test
    fun las_cargas_de_una_consulta_anulada_quedan_en_reserva_y_solo_sale_su_baja() {
        val nunca = plan(consulta(estado = EstadoConsulta.ANULADA, remotoId = ""), sucia = true, cargas = listOf(carga("l1")))
        assertTrue(nunca.pasos.isEmpty(), "anulada antes de crearse: queda solo en el dispositivo")
        assertEquals(listOf(FilaEnReserva(TablaDeLaFila.LABORATORIO, "l1", MotivoEnReserva.CONSULTA_ANULADA, 4_100)), nunca.enReserva, "Q-E3-2: no se tira")

        val creada = plan(consulta(estado = EstadoConsulta.ANULADA), sucia = true, cargas = listOf(carga("l1")))
        assertEquals(listOf(PARTE_BAJA), creada.partes, "ya creada: solo sale la baja")
        assertEquals(listOf(MotivoEnReserva.CONSULTA_ANULADA), creada.enReserva.map { it.motivo })
    }

    @Test
    fun con_e2_las_cargas_siguen_en_reserva() {
        val p = plan(cargas = listOf(carga("l1")), habilitadas = seccionesDe(E2))

        assertTrue(p.pasos.isEmpty())
        assertEquals(listOf(FilaEnReserva(TablaDeLaFila.LABORATORIO, "l1", MotivoEnReserva.TODAVIA_NO_SE_ENVIA, 4_100)), p.enReserva)
        assertTrue(p.sinViaje.isEmpty(), "no se limpian: salen cuando la versión las habilite (RF-4)")
    }

    @Test
    fun con_un_conjunto_propio_solo_viaja_lo_encendido() {
        val p = plan(
            cargas = listOf(carga("l1"), carga("p1", tipo = TIPO_PESO, valores = mapOf("peso" to "72.5"), en = 4_200)),
            habilitadas = seccionesDe(E2) + setOf("laboratorio_general"),
        )

        assertEquals(listOf("l1"), p.paso<PasoDeEnvio.Cargas>().cargas.map { it.carga.id }, "el peso sigue apagado")
        assertEquals(listOf(FilaEnReserva(TablaDeLaFila.LABORATORIO, "p1", MotivoEnReserva.TODAVIA_NO_SE_ENVIA, 4_200)), p.enReserva)
    }

    @Test
    fun el_laboratorio_reducido_no_viaja_y_queda_en_reserva() {
        val p = plan(cargas = listOf(carga("l1", tipo = "laboratorio_reducido")))

        assertTrue(p.pasos.isEmpty(), "el reducido quedó fuera de alcance (T73): no hay envío para él")
        assertEquals(listOf(FilaEnReserva(TablaDeLaFila.LABORATORIO, "l1", MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, 4_100)), p.enReserva)
        assertTrue(p.sinViaje.isEmpty(), "sigue sucio: no se pierde lo que el médico cargó")
    }

    @Test
    fun una_carga_de_la_web_no_viaja_ni_se_limpia() {
        val p = plan(cargas = listOf(carga("hcc-l731", remotoId = "")))

        assertTrue(p.pasos.isEmpty(), "la carga de la web es suya: la app no la manda")
        assertEquals(listOf(FilaEnReserva(TablaDeLaFila.LABORATORIO, "hcc-l731", MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, 4_100)), p.enReserva)
    }

    // ---------- Orden y partes rechazadas ----------

    private fun todoJunto(estados: Map<String, EstadoEnvio> = emptyMap(), reintentoManual: Boolean = false, remotoId: String = "") = plan(
        consulta(estado = EstadoConsulta.CERRADA, remotoId = remotoId), sucia = true,
        valores = listOf(valor("nota", "texto", "x"), valor("serologias", "hiv", "NO"), valor("alimentacion", "hiposodica", "1|x")),
        examen = FilaSucia(ExamenFisico(id, peso = "70"), true, 3_000),
        antecedentes = listOf(antecedente("hta")), registros = listOf(estudio("r-eco")),
        cargas = listOf(carga("l1"), carga("p1", tipo = TIPO_PESO, valores = mapOf("peso" to "72.5"), en = 4_200)),
        estados = estados, reintentoManual = reintentoManual,
    )

    @Test
    fun el_orden_es_crear_secciones_examen_formularios_antecedentes_registros_cargas_y_cierre() {
        assertEquals(
            listOf(
                PARTE_CREAR, PARTE_SECCIONES, PARTE_EXAMEN, PARTE_FORMULARIOS, PARTE_ANTECEDENTES, PARTE_REGISTROS, PARTE_CARGAS, PARTE_CIERRE,
            ),
            todoJunto().partes,
        )
    }

    @Test
    fun el_cierre_solo_sale_con_las_cargas_y_todo_lo_anterior_confirmado() {
        val p = todoJunto(remotoId = "7011")
        var confirmadas = setOf<String>()

        for (esperada in p.partes) {
            assertEquals(esperada, p.siguiente(confirmadas)?.parte)
            if (esperada == PARTE_CIERRE) assertEquals(p.partes.dropLast(1).toSet(), confirmadas)
            confirmadas = confirmadas + esperada
        }
        assertNull(p.siguiente(confirmadas))
        assertNull(p.siguiente(p.partes.dropLast(2).toSet(), frenadas = setOf(PARTE_CARGAS)), "se corta ante las cargas que fallan")
    }

    @Test
    fun una_parte_de_cargas_rechazada_frena_el_cierre_y_las_demas_siguen() {
        val p = todoJunto(estados = mapOf(PARTE_CARGAS to rechazo), remotoId = "7011")

        assertEquals(
            listOf(PARTE_SECCIONES, PARTE_EXAMEN, PARTE_FORMULARIOS, PARTE_ANTECEDENTES, PARTE_REGISTROS),
            p.partes,
        )
        assertEquals(
            listOf(Retencion(PARTE_CARGAS, MotivoRetencion.RECHAZADA), Retencion(PARTE_CIERRE, MotivoRetencion.FRENADO_POR_UNA_PARTE_RECHAZADA)),
            p.retenciones,
        )
    }

    @Test
    fun el_plan_no_depende_del_orden_de_las_cargas_de_entrada() {
        val a = plan(cargas = listOf(carga("l1", en = 4_100), carga("l2", valores = mapOf("hb" to "14"), en = 4_200)))
        val b = plan(cargas = listOf(carga("l2", valores = mapOf("hb" to "14"), en = 4_200), carga("l1", en = 4_100)))

        assertEquals(a, b)
    }

    // ---------- Lo confirmado ----------

    @Test
    fun lo_confirmado_de_las_cargas_se_anota_por_carga_remota_y_decide_el_cambio_siguiente() {
        val enviada = carga("l1", valores = mapOf("hb" to "13.5"), remotoId = "731")
        val nueva = carga("p1", tipo = TIPO_PESO, valores = mapOf("peso" to "72.5"), en = 4_200)
        val p = plan(cargas = listOf(enviada, nueva))

        val confirmado = p.pasos.fold(LoEnviado(valores = mapOf("nota/texto" to "n"))) { lo, paso -> lo.conLoConfirmado(paso) }
        assertEquals(
            mapOf("731" to mapOf("hb" to "13.5")),
            confirmado.cargas,
            "la carga nueva todavía no tiene id de la web: se anota cuando el envío lo recibe (T85)",
        )
        assertEquals(mapOf("nota/texto" to "n"), confirmado.valores, "lo anterior se conserva")

        // Con el id que asignó la web, la misma carga nueva queda anotada y no se vuelve a mandar.
        val conIds = confirmado.conLoConfirmado(p.paso<PasoDeEnvio.Cargas>(), remotos = mapOf("p1" to "740"))
        assertEquals(mapOf("731" to mapOf("hb" to "13.5"), "740" to mapOf("peso" to "72.5")), conIds.cargas)

        assertTrue(plan(cargas = listOf(enviada), loEnviado = conIds).pasos.isEmpty(), "lo mismo que la web: nada que mandar")
        assertTrue(
            plan(cargas = listOf(nueva.copy(fila = nueva.fila.copy(remotoId = "740"))), loEnviado = conIds).pasos.isEmpty(),
            "con el id de la web anotado en la fila (RF-61), la carga nueva ya no se vuelve a mandar",
        )
    }

    @Test
    fun el_vaciado_confirmado_de_una_carga_queda_vacio_y_no_se_repite() {
        val confirmada = LoEnviado(cargas = mapOf("731" to mapOf("hb" to "13.5", "glucemia" to "0.95")))
        val fila = carga("l1", valores = mapOf("hb" to "13.5"), remotoId = "731")

        val vaciar = plan(cargas = listOf(fila), loEnviado = confirmada)
        val despues = vaciar.pasos.fold(confirmada) { lo, paso -> lo.conLoConfirmado(paso) }

        assertEquals(mapOf("hb" to "13.5", "glucemia" to ""), despues.cargas.getValue("731"), "el vaciado confirmado queda como vacío")
        assertTrue(plan(cargas = listOf(fila), loEnviado = despues).pasos.isEmpty(), "ya vaciada en la web: nada que mandar")
    }

    @Test
    fun confirmar_la_baja_no_borra_lo_que_la_web_tenia_de_esa_carga() {
        val confirmada = LoEnviado(cargas = mapOf("731" to mapOf("hb" to "13.5")))
        val baja = plan(cargas = listOf(carga("l1", remotoId = "731", activo = false)))

        val despues = baja.pasos.fold(confirmada) { lo, paso -> lo.conLoConfirmado(paso) }

        assertEquals(confirmada, despues, "los valores los conserva la web (su cierre reactiva la fila)")
    }

    @Test
    fun confirmar_cargas_no_toca_lo_de_los_estudios_ni_de_los_textos() {
        val base = LoEnviado(
            valores = mapOf("nota/texto" to "a"), examen = mapOf("peso" to "70"),
            registros = mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "d")),
        )
        val p = plan(cargas = listOf(carga("l1")))

        val despues = p.pasos.fold(base) { lo, paso -> lo.conLoConfirmado(paso) }

        assertEquals(base.valores, despues.valores)
        assertEquals(base.examen, despues.examen)
        assertEquals(base.registros, despues.registros)
        assertTrue(despues.cargas.isEmpty(), "sin id de la web todavía no hay nada que anotar (T85)")
    }
}
