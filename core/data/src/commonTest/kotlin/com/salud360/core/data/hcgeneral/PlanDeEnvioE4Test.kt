package com.salud360.core.data.hcgeneral

import com.salud360.core.data.hcgeneral.SubentregaDeEscritura.E3
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura.E4
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Spec 005 RF-44, RF-46, RF-56, RF-60, RF-68 (T104): el plan de envío de los **documentos** (PDF, JPG, JPEG y PNG) de una consulta
 * propia, con `habilitadas = seccionesDe(E4)`. Puro. El paso `Adjuntos` va después de los estudios y las cargas y antes del cierre, con
 * un alta por cada documento **sin** `remoto_id` y una baja por cada documento dado de baja que **la web ya tiene**. Un adjunto que
 * nunca se subió y se borró no viaja; lo que `adjuntoViaja` (T103) deja afuera —sin consulta, de otra consulta o de otra HC, la foto
 * de un examen (T105) y lo que no es PDF, JPG, JPEG ni PNG— no viaja, se limpia si nunca va a viajar o espera en reserva si es de una
 * foto o el candado de la versión todavía no enciende `documentos`. Las fotos de exámenes quedan **fuera de alcance** (T105).
 */
class PlanDeEnvioE4Test {
    private val id = "4d1e2f3a-5b6c-4d7e-8f90-1a2b3c4d5e6f"
    private val fecha = LocalDate(2026, 11, 2)
    private val e4 = seccionesDe(E4)

    private fun consulta(estado: EstadoConsulta = EstadoConsulta.ABIERTA, remotoId: String = "7011", fecha: LocalDate = this.fecha) =
        Consulta(id, "p1", "m1", "clinica", "control", fecha, estado, "46 años", Instant.fromEpochMilliseconds(1_000), remotoId, true)

    private fun valor(seccion: String, campo: String, valor: String, dirty: Boolean = true, en: Long = 2_000) =
        FilaSucia(SeccionValor(id, seccion, campo, valor), dirty, en)

    private fun antecedente(clave: String = "hta", en: Long = 2_500) =
        FilaSucia(Antecedente("a-familiares-$clave", "p1", "clinica", "familiares", clave, true, "padre", id, true), true, en)

    private fun estudio(rid: String = "r-eco") =
        FilaSucia(RegistroClinico(rid, "p1", id, "clinica", "eco_abdominal", fecha, mapOf("detalle" to "normal")), true, 3_100)

    private fun carga(lid: String = "l1", tipo: String = TIPO_LABORATORIO_GENERAL, valores: Map<String, String> = mapOf("hb" to "13.5"), en: Long = 4_100) =
        FilaSucia(Laboratorio(lid, "p1", id, "clinica", tipo, LocalDate(2026, 11, 6), valores), true, en)

    /** Un documento del paciente como lo deja el repositorio: solo los sucios llegan al plan. */
    private fun archivo(
        aid: String = "a1",
        consultaId: Id? = id,
        paciente: Id = "p1",
        seccion: String = SECCION_DOCUMENTOS,
        nombre: String = "$aid.pdf",
        mime: String = "application/pdf",
        registroId: Id? = null,
        activo: Boolean = true,
        remotoId: String = "",
        dirty: Boolean = true,
        en: Long = 5_100,
    ) = FilaSucia(
        Archivo(
            id = aid, pacienteId = paciente, consultaId = consultaId, registroId = registroId, seccion = seccion, nombre = nombre, mime = mime,
            activo = activo, remotoId = remotoId,
        ),
        dirty,
        en,
    )

    private fun plan(
        consulta: Consulta = consulta(),
        sucia: Boolean = false,
        valores: List<FilaSucia<SeccionValor>> = emptyList(),
        examen: FilaSucia<ExamenFisico>? = null,
        antecedentes: List<FilaSucia<Antecedente>> = emptyList(),
        registros: List<FilaSucia<RegistroClinico>> = emptyList(),
        cargas: List<FilaSucia<Laboratorio>> = emptyList(),
        archivos: List<FilaSucia<Archivo>> = emptyList(),
        loEnviado: LoEnviado = LoEnviado(),
        estados: Map<String, EstadoEnvio> = emptyMap(),
        habilitadas: Set<String> = e4,
        reintentoManual: Boolean = false,
    ) = planDeEnvio(
        EntradaDelPlan(
            consulta = consulta, consultaSucia = sucia, valores = valores, examen = examen, antecedentes = antecedentes,
            registros = registros, cargas = cargas, archivos = archivos, loEnviado = loEnviado, estados = estados,
            habilitadas = habilitadas, reintentoManual = reintentoManual, armadoEn = 6_000,
        ),
    )

    private val PlanDeEnvio.partes get() = pasos.map { it.parte }
    private inline fun <reified T : PasoDeEnvio> PlanDeEnvio.paso(): T = pasos.filterIsInstance<T>().single()
    private val rechazo = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "datos")

    // ---------- Altas y bajas ----------

    @Test
    fun un_documento_nuevo_viaja_en_el_paso_de_adjuntos_con_su_fila_cubierta() {
        val nuevo = archivo("a1", en = 5_100)
        val p = plan(archivos = listOf(nuevo))

        assertEquals(listOf(PARTE_ADJUNTOS), p.partes)
        val paso = p.paso<PasoDeEnvio.Adjuntos>()
        assertEquals(listOf(nuevo.fila), paso.adjuntos, "un alta por documento sin id de la web")
        assertEquals(listOf(FilaCubierta(TablaDeLaFila.ARCHIVO, "a1", 5_100)), paso.cubre, "una fila por adjunto con su updated_at (RF-55)")
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty())
        assertTrue(p.hayAlgoPorEnviar)
    }

    @Test
    fun la_baja_de_un_documento_que_la_web_tiene_viaja() {
        val borrado = archivo("a1", activo = false, remotoId = "731", en = 5_100)
        val p = plan(archivos = listOf(borrado))

        assertEquals(listOf(PARTE_ADJUNTOS), p.partes)
        val paso = p.paso<PasoDeEnvio.Adjuntos>()
        assertEquals(listOf(borrado.fila), paso.adjuntos)
        assertFalse(paso.adjuntos.single().activo, "la baja va con activo = false y su id de la web")
        assertEquals("731", paso.adjuntos.single().remotoId)
        assertEquals(listOf(FilaCubierta(TablaDeLaFila.ARCHIVO, "a1", 5_100)), paso.cubre)
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty())
    }

    @Test
    fun las_altas_y_las_bajas_del_mismo_paso_salen_ordenadas_por_su_id() {
        val p = plan(
            archivos = listOf(
                archivo("a3", activo = false, remotoId = "733", en = 5_300),
                archivo("a1", en = 5_100),
                archivo("a2", nombre = "a2.png", mime = "image/png", en = 5_200),
            ),
        )

        val paso = p.paso<PasoDeEnvio.Adjuntos>()
        assertEquals(listOf("a1", "a2", "a3"), paso.adjuntos.map { it.id })
        assertEquals(
            listOf(
                FilaCubierta(TablaDeLaFila.ARCHIVO, "a1", 5_100),
                FilaCubierta(TablaDeLaFila.ARCHIVO, "a2", 5_200),
                FilaCubierta(TablaDeLaFila.ARCHIVO, "a3", 5_300),
            ),
            paso.cubre,
        )
    }

    @Test
    fun un_documento_que_nunca_se_envio_y_se_borro_no_viaja() {
        val p = plan(archivos = listOf(archivo("a1", activo = false, remotoId = "")))

        assertTrue(p.pasos.isEmpty(), "en la web no hay nada que dar de baja")
        assertEquals(listOf(FilaSinViaje(TablaDeLaFila.ARCHIVO, "a1", MotivoSinViaje.SIN_CAMBIO_REAL, 5_100)), p.sinViaje)
        assertTrue(p.enReserva.isEmpty())
    }

    @Test
    fun un_documento_que_la_web_ya_tiene_y_sigue_activo_no_se_vuelve_a_subir() {
        val p = plan(archivos = listOf(archivo("a1", remotoId = "731")))

        assertTrue(p.pasos.isEmpty(), "ya está en la web: no hay nada que subir")
        assertEquals(listOf(FilaSinViaje(TablaDeLaFila.ARCHIVO, "a1", MotivoSinViaje.SIN_CAMBIO_REAL, 5_100)), p.sinViaje)
    }

    @Test
    fun un_documento_limpio_no_se_mira() {
        val p = plan(archivos = listOf(archivo("a1", dirty = false)))

        assertTrue(p.pasos.isEmpty() && p.sinViaje.isEmpty() && p.enReserva.isEmpty())
    }

    @Test
    fun el_plan_no_depende_del_orden_de_los_adjuntos_de_entrada() {
        val a = plan(archivos = listOf(archivo("a1", en = 5_100), archivo("a2", nombre = "a2.jpg", mime = "image/jpeg", en = 5_200)))
        val b = plan(archivos = listOf(archivo("a2", nombre = "a2.jpg", mime = "image/jpeg", en = 5_200), archivo("a1", en = 5_100)))

        assertEquals(a, b)
    }

    // ---------- Los cuatro tipos y lo que no viaja ----------

    @Test
    fun los_cuatro_tipos_de_documento_viajan() {
        val p = plan(
            archivos = listOf(
                archivo("pdf", nombre = "informe.pdf", mime = "application/pdf"),
                archivo("jpg", nombre = "foto.jpg", mime = "image/jpeg"),
                archivo("jpeg", nombre = "foto.jpeg", mime = "image/jpeg"),
                archivo("png", nombre = "foto.png", mime = "image/png"),
            ),
        )

        assertEquals(listOf("jpeg", "jpg", "pdf", "png"), p.paso<PasoDeEnvio.Adjuntos>().adjuntos.map { it.id })
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty())
    }

    @Test
    fun un_archivo_que_no_es_pdf_jpg_jpeg_ni_png_no_viaja_y_se_limpia() {
        // La extensión declarada manda: un `.gif` que dice `image/png` no se cuela (T103).
        val p = plan(archivos = listOf(archivo("gif", nombre = "dibujo.gif", mime = "image/png"), archivo("doc", nombre = "informe.docx", mime = "application/msword")))

        assertTrue(p.pasos.isEmpty())
        assertEquals(
            listOf(
                FilaSinViaje(TablaDeLaFila.ARCHIVO, "doc", MotivoSinViaje.LA_WEB_NO_LA_GUARDA, 5_100),
                FilaSinViaje(TablaDeLaFila.ARCHIVO, "gif", MotivoSinViaje.LA_WEB_NO_LA_GUARDA, 5_100),
            ),
            p.sinViaje,
            "lo que la web no acepta nunca no sale y se limpia sin figurar enviado (RF-60)",
        )
    }

    @Test
    fun lo_que_no_es_de_la_seccion_documentos_no_viaja() {
        val p = plan(archivos = listOf(archivo("audio", seccion = "audio", nombre = "audio.webm", mime = "audio/webm")))

        assertTrue(p.pasos.isEmpty())
        assertEquals(listOf(FilaSinViaje(TablaDeLaFila.ARCHIVO, "audio", MotivoSinViaje.LA_WEB_NO_LA_GUARDA, 5_100)), p.sinViaje)
    }

    @Test
    fun la_foto_de_un_examen_no_viaja_y_espera() {
        // Las fotos de exámenes complementarios quedaron fuera de alcance (T105): el dato no se pierde.
        val p = plan(archivos = listOf(archivo("foto", registroId = "r-eco", seccion = "examen_complementario", nombre = "foto.jpg", mime = "image/jpeg")))

        assertTrue(p.pasos.isEmpty())
        assertEquals(listOf(FilaEnReserva(TablaDeLaFila.ARCHIVO, "foto", MotivoEnReserva.NO_HAY_ENVIO_PARA_ESTE_DATO, 5_100)), p.enReserva)
        assertTrue(p.sinViaje.isEmpty())
    }

    @Test
    fun solo_viajan_los_adjuntos_de_esta_consulta() {
        val p = plan(
            archivos = listOf(
                archivo("a1"), archivo("a2", consultaId = "otra-propia"), archivo("a3", consultaId = null, seccion = "familigrama"),
            ),
        )

        assertEquals(listOf("a1"), p.paso<PasoDeEnvio.Adjuntos>().adjuntos.map { it.id })
        assertTrue(p.sinViaje.isEmpty() && p.enReserva.isEmpty(), "los de otra consulta ni se envían ni se limpian ni se cuentan (RF-68)")
    }

    // ---------- El candado de la versión (RF-4) ----------

    @Test
    fun con_e3_los_adjuntos_siguen_en_reserva() {
        val p = plan(
            archivos = listOf(archivo("a1"), archivo("a2", activo = false, remotoId = "731", en = 5_200)),
            habilitadas = seccionesDe(E3),
        )

        assertTrue(p.pasos.isEmpty())
        assertEquals(
            listOf(
                FilaEnReserva(TablaDeLaFila.ARCHIVO, "a1", MotivoEnReserva.TODAVIA_NO_SE_ENVIA, 5_100),
                FilaEnReserva(TablaDeLaFila.ARCHIVO, "a2", MotivoEnReserva.TODAVIA_NO_SE_ENVIA, 5_200),
            ),
            p.enReserva,
        )
        assertTrue(p.sinViaje.isEmpty(), "no se limpian: salen cuando la versión encienda `documentos`")
    }

    // ---------- Una consulta anulada ----------

    @Test
    fun los_adjuntos_de_una_consulta_anulada_quedan_en_reserva_y_solo_sale_su_baja() {
        val nunca = plan(consulta(estado = EstadoConsulta.ANULADA, remotoId = ""), sucia = true, archivos = listOf(archivo("a1")))
        assertTrue(nunca.pasos.isEmpty(), "anulada antes de crearse: queda solo en el dispositivo")
        assertEquals(listOf(FilaEnReserva(TablaDeLaFila.ARCHIVO, "a1", MotivoEnReserva.CONSULTA_ANULADA, 5_100)), nunca.enReserva)

        val creada = plan(consulta(estado = EstadoConsulta.ANULADA), sucia = true, archivos = listOf(archivo("a1")))
        assertEquals(listOf(PARTE_BAJA), creada.partes, "ya creada: solo sale la baja")
        assertEquals(listOf(MotivoEnReserva.CONSULTA_ANULADA), creada.enReserva.map { it.motivo })
    }

    // ---------- Orden y partes rechazadas ----------

    private fun todoJunto(estados: Map<String, EstadoEnvio> = emptyMap(), reintentoManual: Boolean = false, remotoId: String = "") = plan(
        consulta(estado = EstadoConsulta.CERRADA, remotoId = remotoId), sucia = true,
        valores = listOf(valor("nota", "texto", "x"), valor("serologias", "hiv", "NO")),
        examen = FilaSucia(ExamenFisico(id, peso = "70"), true, 3_000),
        antecedentes = listOf(antecedente()), registros = listOf(estudio()),
        cargas = listOf(carga(), carga("p1", tipo = TIPO_PESO, valores = mapOf("peso" to "72.5"), en = 4_200)),
        archivos = listOf(archivo("a1", en = 5_100), archivo("a2", nombre = "a2.png", mime = "image/png", en = 5_200)),
        estados = estados, reintentoManual = reintentoManual,
    )

    @Test
    fun el_orden_es_crear_contenido_cargas_adjuntos_y_cierre() {
        assertEquals(
            listOf(
                PARTE_CREAR, PARTE_SECCIONES, PARTE_EXAMEN, PARTE_FORMULARIOS, PARTE_ANTECEDENTES, PARTE_REGISTROS, PARTE_CARGAS,
                PARTE_ADJUNTOS, PARTE_CIERRE,
            ),
            todoJunto().partes,
        )
    }

    @Test
    fun el_cierre_solo_sale_con_los_adjuntos_y_todo_lo_anterior_confirmado() {
        val p = todoJunto(remotoId = "7011")
        var confirmadas = setOf<String>()

        for (esperada in p.partes) {
            assertEquals(esperada, p.siguiente(confirmadas)?.parte)
            if (esperada == PARTE_CIERRE) assertEquals(p.partes.dropLast(1).toSet(), confirmadas)
            confirmadas = confirmadas + esperada
        }
        assertNull(p.siguiente(confirmadas))
        assertNull(p.siguiente(p.partes.dropLast(2).toSet(), frenadas = setOf(PARTE_ADJUNTOS)), "se corta ante los adjuntos que fallan")
    }

    @Test
    fun una_parte_de_adjuntos_rechazada_frena_el_cierre_y_las_demas_siguen() {
        val p = todoJunto(estados = mapOf(PARTE_ADJUNTOS to rechazo), remotoId = "7011")

        assertEquals(
            listOf(PARTE_SECCIONES, PARTE_EXAMEN, PARTE_FORMULARIOS, PARTE_ANTECEDENTES, PARTE_REGISTROS, PARTE_CARGAS),
            p.partes,
        )
        assertEquals(
            listOf(Retencion(PARTE_ADJUNTOS, MotivoRetencion.RECHAZADA), Retencion(PARTE_CIERRE, MotivoRetencion.FRENADO_POR_UNA_PARTE_RECHAZADA)),
            p.retenciones,
        )
    }

    @Test
    fun con_reintento_a_mano_los_adjuntos_rechazados_vuelven_a_incluirse() {
        val p = todoJunto(estados = mapOf(PARTE_ADJUNTOS to rechazo), reintentoManual = true, remotoId = "7011")

        assertTrue(PARTE_ADJUNTOS in p.partes)
        assertEquals(PARTE_CIERRE, p.partes.last(), "el médico pidió reintentar: el cierre vuelve a salir detrás")
        assertTrue(p.retenciones.isEmpty())
    }

    @Test
    fun confirmar_los_adjuntos_no_toca_lo_confirmado_de_las_otras_partes() {
        val base = LoEnviado(
            valores = mapOf("nota/texto" to "a"), cargas = mapOf("731" to mapOf("hb" to "13.5")),
            registros = mapOf("7015" to mapOf("fecha" to "2026-11-02", "detalle" to "d")),
        )
        val p = plan(archivos = listOf(archivo("a1")))

        val despues = p.pasos.fold(base) { lo, paso -> lo.conLoConfirmado(paso, remotos = mapOf("a1" to "731")) }

        assertEquals(base, despues, "el id de un adjunto vive en su fila (remoto_id): no hay nada más que anotar")
    }
}
