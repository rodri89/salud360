package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005 RF-6, RF-19 y RF-30 (T20): de un error del envío a «En espera: <motivo>» (causa ajena al dato) o «No se pudo enviar:
 * <motivo>» (causa del dato), con el nombre de la HC en el motivo; la precedencia de RF-19; y cómo se guarda el estado de cada
 * consulta en `paciente_extra`. Puro: sin red ni base. Los errores son los del contrato de hclinica (`TobbException` con
 * `status`, `codigo` y, en un `422`, el `campo` y el `motivo` en `cuerpo`).
 */
class ClasificacionDeEnvioTest {
    private val hc = "Clínica"
    private fun json(texto: String): JsonObject = Json.parseToJsonElement(texto) as JsonObject

    private fun error(status: Int, codigo: String?, mensaje: String = "mensaje de la web", cuerpo: JsonObject? = null) =
        TobbException(status, codigo, mensaje, cuerpo = cuerpo)

    private fun clasificar(e: Throwable, yaEnviada: Boolean = false, nombre: String = hc) = clasificarErrorDeEnvio(e, nombre, yaEnviada)

    private fun enEspera(c: ClasificacionDeEnvio) = assertIs<EstadoEnvio.EnEspera>(c.estado)
    private fun noSePudo(c: ClasificacionDeEnvio) = assertIs<EstadoEnvio.NoSePudoEnviar>(c.estado)

    // ---------- Causa ajena al dato: «En espera» (RF-6) ----------

    private data class Caso(val status: Int, val codigo: String?, val causa: CausaEnEspera, val conEspera: Boolean)

    private val ajenas = listOf(
        Caso(401, "sin_token", CausaEnEspera.SESION_VENCIDA, false),
        Caso(401, "token", CausaEnEspera.SESION_VENCIDA, false),
        Caso(403, "hc_no_habilitada", CausaEnEspera.HC_NO_HABILITADA, false),
        Caso(403, "medico_no_vinculado", CausaEnEspera.MEDICO_NO_VINCULADO, false),
        Caso(403, "licencia_vencida", CausaEnEspera.LICENCIA_VENCIDA, false),
        Caso(403, "fuera_de_cartera", CausaEnEspera.FUERA_DE_CARTERA, false),
        Caso(403, "sin_permiso", CausaEnEspera.SIN_PERMISO, false),
        Caso(404, "paciente_no_encontrado", CausaEnEspera.PACIENTE_DE_BAJA, false),
        Caso(503, "tobb_caido", CausaEnEspera.SERVIDOR, true),
        Caso(500, "escritura", CausaEnEspera.SERVIDOR, true),
        Caso(502, null, CausaEnEspera.SERVIDOR, true),
        Caso(504, null, CausaEnEspera.SERVIDOR, true),
        Caso(429, null, CausaEnEspera.DEMASIADOS_PEDIDOS, true),
        Caso(0, "red", CausaEnEspera.SIN_CONEXION, true),
        Caso(0, "respuesta", CausaEnEspera.SERVIDOR, true),
        Caso(200, null, CausaEnEspera.SERVIDOR, true),
    )

    @Test
    fun cada_causa_ajena_queda_en_espera_con_su_causa_y_se_reintenta_sola() {
        for (caso in ajenas) {
            val c = clasificar(error(caso.status, caso.codigo))

            assertEquals(caso.causa, enEspera(c).causa, "${caso.status} ${caso.codigo}")
            assertTrue(c.reintentoAutomatico, "${caso.status} ${caso.codigo}: «En espera» se vuelve a intentar sola")
            assertEquals(caso.conEspera, c.conEspera, "${caso.status} ${caso.codigo}: con espera y sin ráfaga solo si es 429, 5xx, red o respuesta rara")
            assertFalse(c.marcaYaNoFigura, "${caso.status} ${caso.codigo}")
        }
    }

    @Test
    fun el_motivo_de_la_espera_nombra_la_hc_y_el_texto_dice_en_espera() {
        for (caso in ajenas) {
            val c = clasificar(error(caso.status, caso.codigo))
            val e = enEspera(c)

            assertTrue(e.motivo.startsWith("Historia clínica de Clínica: "), "${caso.status} ${caso.codigo}: ${e.motivo}")
            assertEquals("En espera: ${e.motivo}", textoDelEstado(e), "${caso.status} ${caso.codigo}")
        }
    }

    @Test
    fun el_nombre_de_la_hc_sale_del_mecanismo_de_nombres_y_no_esta_escrito_a_mano() {
        for (nombre in listOf("Pediatría", "HC general", "Clínica médica")) {
            for (caso in ajenas) {
                val e = enEspera(clasificar(error(caso.status, caso.codigo), nombre = nombre))
                assertTrue(e.motivo.startsWith("Historia clínica de $nombre: "), "$nombre ${caso.status} ${caso.codigo}: ${e.motivo}")
            }
            val rechazo = noSePudo(clasificar(error(422, "datos_invalidos"), nombre = nombre))
            assertTrue(rechazo.motivo.startsWith("Historia clínica de $nombre: "), rechazo.motivo)
        }
    }

    @Test
    fun los_motivos_que_ya_existian_conservan_su_texto() {
        // Se reutiliza `motivoHc`: el mismo texto que la cartera, las enfermedades y la lectura (RF-6: «con el motivo de la cartera», 002 RF-9).
        for ((status, codigo) in listOf(401 to "token", 401 to "sin_token", 403 to "hc_no_habilitada", 403 to "medico_no_vinculado", 403 to "licencia_vencida", 0 to "red", 503 to "tobb_caido")) {
            val e = error(status, codigo)
            assertEquals(motivoHc(hc, e).texto, enEspera(clasificar(e)).motivo, "$status $codigo")
        }
        assertEquals(motivoDeLaLectura(hc, error(429, null)).texto, enEspera(clasificar(error(429, null))).motivo)
    }

    @Test
    fun los_motivos_nuevos_dicen_que_pasa_en_palabras_del_usuario() {
        assertEquals("Historia clínica de Clínica: este paciente no está en tu cartera.", enEspera(clasificar(error(403, "fuera_de_cartera"))).motivo)
        assertTrue(enEspera(clasificar(error(404, "paciente_no_encontrado"))).motivo.contains("ficha de este paciente"))
        assertTrue(enEspera(clasificar(error(403, "sin_permiso"))).motivo.contains("administrador"))
        assertTrue(enEspera(clasificar(error(500, "escritura"))).motivo.contains("Se vuelve a intentar sola"))
        assertTrue(enEspera(clasificar(error(0, "respuesta"))).motivo.contains("inesperado"))
    }

    @Test
    fun cualquier_cosa_que_no_es_un_error_de_la_api_tambien_queda_en_espera_sin_perder_nada() {
        val c = clasificar(IllegalStateException("algo raro"))

        assertEquals(CausaEnEspera.SERVIDOR, enEspera(c).causa)
        assertTrue(c.reintentoAutomatico)
    }

    // ---------- Causa del dato: «No se pudo enviar» (RF-30) ----------

    private data class Rechazo(val status: Int, val codigo: String?, val causa: CausaDeRechazo)

    private val rechazos = listOf(
        Rechazo(422, "datos_invalidos", CausaDeRechazo.DATOS_INVALIDOS),
        Rechazo(404, "consulta_no_encontrada", CausaDeRechazo.CONSULTA_DE_BAJA),
        Rechazo(409, "consulta_cerrada", CausaDeRechazo.CONSULTA_CERRADA),
        Rechazo(403, "consulta_no_propia", CausaDeRechazo.CONSULTA_NO_PROPIA),
        Rechazo(400, null, CausaDeRechazo.OTRO),
        Rechazo(404, null, CausaDeRechazo.OTRO),
        Rechazo(409, "otro_codigo", CausaDeRechazo.CONSULTA_CERRADA),
    )

    @Test
    fun cada_causa_del_dato_es_no_se_pudo_enviar_sin_reintento_automatico() {
        for (r in rechazos) {
            val c = clasificar(error(r.status, r.codigo, "El dato «fecha» no es válido."))
            val e = noSePudo(c)

            assertEquals(r.causa, e.causa, "${r.status} ${r.codigo}")
            assertFalse(c.reintentoAutomatico, "${r.status} ${r.codigo}: no se arregla reintentando")
            assertFalse(c.conEspera, "${r.status} ${r.codigo}")
            assertTrue(e.motivo.startsWith("Historia clínica de Clínica: "), e.motivo)
            assertEquals("No se pudo enviar: ${e.motivo}", textoDelEstado(e))
        }
    }

    @Test
    fun el_422_muestra_lo_que_dijo_la_web_y_el_resto_dice_que_paso() {
        val e422 = noSePudo(clasificar(error(422, "datos_invalidos", "El dato «fecha» no es válido.")))
        assertTrue(e422.motivo.contains("El dato «fecha» no es válido."), e422.motivo)

        assertTrue(noSePudo(clasificar(error(404, "consulta_no_encontrada"))).motivo.contains("ya no figura en la web"))
        assertTrue(noSePudo(clasificar(error(409, "consulta_cerrada"))).motivo.contains("cerrada o dada de baja"))
        assertTrue(noSePudo(clasificar(error(403, "consulta_no_propia"))).motivo.contains("no se puede modificar"))
        assertTrue(noSePudo(clasificar(error(400, null, "Bad request"))).motivo.contains("Bad request"))
    }

    @Test
    fun el_422_se_distingue_por_el_motivo_del_cuerpo_y_no_solo_por_el_codigo() {
        val claveEnUso = error(
            422, "datos_invalidos", "El dato «clave» ya se usó para otra operación u otra consulta.",
            json("""{"ok":false,"codigo":"datos_invalidos","campo":"clave","motivo":"clave_en_uso"}"""),
        )
        val textoLargo = error(
            422, "datos_invalidos", "El dato «nota» es demasiado largo.",
            json("""{"ok":false,"codigo":"datos_invalidos","campo":"nota","motivo":"texto_demasiado_largo"}"""),
        )
        val otro = error(422, "datos_invalidos", "El dato «fecha» no es válido.", json("""{"ok":false,"codigo":"datos_invalidos","campo":"fecha","motivo":"fecha_invalida"}"""))

        val a = noSePudo(clasificar(claveEnUso))
        val b = noSePudo(clasificar(textoLargo))
        val c = noSePudo(clasificar(otro))

        assertEquals(CausaDeRechazo.CLAVE_EN_USO, a.causa, "un identificador repetido no lo arregla el médico")
        assertEquals(CausaDeRechazo.DATOS_INVALIDOS, b.causa)
        assertEquals(CausaDeRechazo.DATOS_INVALIDOS, c.causa)
        assertTrue(a.motivo.contains("identificador"), a.motivo)
        assertTrue(b.motivo.contains("demasiado largo"), b.motivo)
        assertNotEquals(a.motivo, c.motivo)
        // Sin cuerpo (un servidor viejo) sigue siendo un rechazo del dato.
        assertEquals(CausaDeRechazo.DATOS_INVALIDOS, noSePudo(clasificar(error(422, "datos_invalidos", "x", null))).causa)
    }

    // ---------- Consulta dada de baja y «Ya no figura en la web» (RF-19, RF-33) ----------

    @Test
    fun la_consulta_dada_de_baja_marca_ya_no_figura_en_la_web_solo_si_ya_estaba_enviada() {
        val enviada = clasificar(error(404, "consulta_no_encontrada"), yaEnviada = true)
        val sinEnviar = clasificar(error(404, "consulta_no_encontrada"), yaEnviada = false)

        assertTrue(enviada.marcaYaNoFigura)
        assertFalse(sinEnviar.marcaYaNoFigura, "si nunca llegó a la web no hay nada que marcar")
        assertEquals(CausaDeRechazo.CONSULTA_DE_BAJA, noSePudo(enviada).causa, "lo pendiente pasa a «No se pudo enviar»")
        assertFalse(clasificar(error(409, "consulta_cerrada"), yaEnviada = true).marcaYaNoFigura)
        assertFalse(clasificar(error(422, "datos_invalidos"), yaEnviada = true).marcaYaNoFigura)
    }

    // ---------- Precedencia de RF-19 ----------

    @Test
    fun paciente_de_baja_primero_despues_consulta_de_baja_despues_cualquier_otro_rechazo() {
        val paciente = clasificar(error(404, "paciente_no_encontrado"))
        val consulta = clasificar(error(404, "consulta_no_encontrada"), yaEnviada = true)
        val otro = clasificar(error(422, "datos_invalidos"))

        assertEquals(paciente, masPrioritaria(listOf(otro, consulta, paciente)))
        assertEquals(paciente, masPrioritaria(listOf(paciente, consulta, otro)))
        assertEquals(consulta, masPrioritaria(listOf(otro, consulta)))
        assertEquals(consulta, masPrioritaria(listOf(consulta, otro)))
        assertEquals(otro, masPrioritaria(listOf(otro)))
    }

    @Test
    fun el_paciente_de_baja_deja_en_espera_porque_una_ficha_se_puede_reactivar() {
        val c = masPrioritaria(listOf(clasificar(error(404, "consulta_no_encontrada")), clasificar(error(404, "paciente_no_encontrado"))))!!

        assertEquals(CausaEnEspera.PACIENTE_DE_BAJA, enEspera(c).causa)
        assertFalse(c.marcaYaNoFigura, "la consulta no se marca mientras el paciente es la causa")
    }

    @Test
    fun una_causa_ajena_va_antes_que_un_rechazo_porque_el_rechazo_vuelve_a_salir_al_reintentar() {
        val ajena = clasificar(error(503, "tobb_caido"))
        val rechazo = clasificar(error(422, "datos_invalidos"))

        assertEquals(ajena, masPrioritaria(listOf(rechazo, ajena)))
        assertEquals(ajena, masPrioritaria(listOf(ajena, rechazo)))
    }

    @Test
    fun entre_dos_iguales_gana_la_primera_y_sin_nada_no_hay_clasificacion() {
        val a = clasificar(error(422, "datos_invalidos", "uno"))
        val b = clasificar(error(422, "datos_invalidos", "dos"))

        assertEquals(a, masPrioritaria(listOf(a, b)))
        assertNull(masPrioritaria(emptyList()))
    }

    // ---------- Los textos de los estados ----------

    @Test
    fun los_textos_de_los_estados_son_los_de_la_spec() {
        assertEquals("Falta enviar", textoDelEstado(EstadoEnvio.Pendiente))
        assertEquals("Descartado: no se envió a la web", textoDelEstado(EstadoEnvio.Descartado))
        assertEquals("En espera: sin conexión", textoDelEstado(EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "sin conexión")))
        assertEquals("No se pudo enviar: datos", textoDelEstado(EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.OTRO, "datos")))
        // «En espera» no usa «No se pudo enviar» (RF-6).
        assertFalse(textoDelEstado(EstadoEnvio.EnEspera(CausaEnEspera.SERVIDOR, "x")).contains("No se pudo enviar"))
    }

    // ---------- Cómo se guarda en `paciente_extra` ----------

    private val consulta = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
    private val desde = LocalDate(2026, 11, 6)

    @Test
    fun la_clave_lleva_la_consulta_y_la_parte() {
        assertEquals("hce_estado_$consulta:crear", claveDeEstadoDeEnvio(consulta, "crear"))
        assertEquals("hce_estado_$consulta:cierre", claveDeEstadoDeEnvio(consulta, "cierre"))
        assertNotEquals(claveDeEstadoDeEnvio(consulta, "secciones"), claveDeEstadoDeEnvio(consulta, "examen"))
        assertTrue(claveDeEstadoDeEnvio(consulta, "crear").startsWith(PREFIJO_ESTADO_ENVIO))
        assertEquals("hce_estado_", PREFIJO_ESTADO_ENVIO)
    }

    @Test
    fun cada_estado_vuelve_igual_de_lo_que_se_guardo_incluido_un_motivo_con_separadores() {
        val estados = listOf(
            EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "Historia clínica de Clínica: sin conexión. Intentá de nuevo en un momento."),
            EstadoEnvio.EnEspera(CausaEnEspera.LICENCIA_VENCIDA, "motivo | con | barras: y dos puntos"),
            EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "El dato «fecha» no es válido."),
            EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.CLAVE_EN_USO, "ñandú 😀\ncon salto de línea"),
            EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.CONSULTA_DE_BAJA, ""),
            EstadoEnvio.Descartado,
        )
        for (estado in estados) {
            val texto = estadoDeEnvioComoTexto(estado, desde)!!

            assertEquals(EstadoGuardado(estado, desde), estadoDeEnvioDeTexto(texto), texto)
        }
    }

    @Test
    fun pendiente_no_se_guarda_porque_es_la_ausencia_de_un_estado() {
        assertNull(estadoDeEnvioComoTexto(EstadoEnvio.Pendiente, desde))
        assertNull(filaDeEstadoDeEnvio("p1", consulta, "crear", EstadoEnvio.Pendiente, desde))
    }

    @Test
    fun un_texto_que_no_se_entiende_no_inventa_un_estado() {
        for (basura in listOf(null, "", "espera", "espera|2026-11-06", "volando|2026-11-06|SIN_CONEXION|x", "espera|no-es-fecha|SIN_CONEXION|x", "espera|2026-11-06|CAUSA_INVENTADA|x", "rechazado|2026-11-06|SIN_CONEXION|x")) {
            assertNull(estadoDeEnvioDeTexto(basura), "«$basura»")
        }
    }

    @Test
    fun la_fila_es_de_la_hc_general_con_la_clave_del_estado() {
        val estado = EstadoEnvio.EnEspera(CausaEnEspera.SESION_VENCIDA, "sesión vencida")
        val fila = filaDeEstadoDeEnvio("p1", consulta, "cierre", estado, desde)!!

        assertEquals("p1", fila.pacienteId)
        assertEquals("clinica", fila.especialidad)
        assertEquals("hce_estado_$consulta:cierre", fila.clave)
        assertEquals(EstadoGuardado(estado, desde), estadoDeEnvioDeTexto(fila.valor))
    }

    @Test
    fun de_las_filas_del_paciente_se_leen_los_estados_por_consulta_y_parte() {
        val espera = EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "sin conexión")
        val rechazo = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "datos")
        val otra = "8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f"
        val filas = listOf(
            filaDeEstadoDeEnvio("p1", consulta, "crear", espera, desde)!!,
            filaDeEstadoDeEnvio("p1", consulta, "cierre", rechazo, desde)!!,
            filaDeEstadoDeEnvio("p1", otra, "examen", EstadoEnvio.Descartado, desde)!!,
            // Lo que no es un estado no cuenta: otras filas de la HC, otra historia clínica y claves o valores rotos.
            PacienteExtra("p1", "clinica", "hcc_omitidas", "0|0|0"),
            PacienteExtra("p1", "clinica", "hcc_ausente_hcc-c1", "2026-10-20"),
            PacienteExtra("p1", "pediatria", claveDeEstadoDeEnvio(consulta, "crear"), "espera|2026-11-06|SIN_CONEXION|x"),
            PacienteExtra("p1", "clinica", "hce_estado_sin-parte", "espera|2026-11-06|SIN_CONEXION|x"),
            PacienteExtra("p1", "clinica", claveDeEstadoDeEnvio(consulta, "roto"), "basura"),
        )

        val estados = estadosDeEnvio(filas)

        assertEquals(setOf(consulta, otra), estados.keys)
        assertEquals(mapOf("crear" to EstadoGuardado(espera, desde), "cierre" to EstadoGuardado(rechazo, desde)), estados.getValue(consulta))
        assertEquals(mapOf("examen" to EstadoGuardado(EstadoEnvio.Descartado, desde)), estados.getValue(otra))
    }

    @Test
    fun las_filas_de_estado_no_cambian_lo_que_lee_la_historia_de_la_web() {
        val base = listOf(PacienteExtra("p1", "clinica", "hcc_omitidas", "1|0|2"), PacienteExtra("p1", "clinica", "hcc_ausente_hcc-c7003", "2026-10-20"))
        val conEstado = base + filaDeEstadoDeEnvio("p1", consulta, "crear", EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "x"), desde)!!

        assertEquals(extrasDeLectura(base), extrasDeLectura(conEstado))
    }
}
