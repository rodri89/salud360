package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La consulta de la web en la pantalla (spec 002, E7.3: RF-39, RF-43 a RF-45, RF-62, RF-63): qué se puede hacer con ella
 * (nada: se lee), qué avisos lleva, qué filas de la web no se tocan desde una consulta local y cómo se cuenta una lectura
 * que falla. Todo puro: la pantalla muestra lo que esto decide. Datos inventados.
 */
class SoloLecturaDeLaWebTest {
    private val yo = "m-sesion"
    private val paciente = "p1"

    private fun consulta(
        id: String, estado: EstadoConsulta = EstadoConsulta.CERRADA, medico: String = yo, especialidad: String = "clinica", remotoId: String = "",
    ) = Consulta(id, paciente, medico, especialidad, "control", LocalDate.parse("2026-10-01"), estado, remotoId = remotoId)

    private val web = consulta(idConsultaWeb(7003), EstadoConsulta.CERRADA, remotoId = "7003")
    private val webAbierta = consulta(idConsultaWeb(7004), EstadoConsulta.ABIERTA, remotoId = "7004")
    private val local = consulta("3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b", EstadoConsulta.ABIERTA)

    // ---------- Qué es «de la web» ----------

    @Test
    fun el_id_de_la_web_se_reconoce_por_el_prefijo() {
        listOf(
            idConsultaWeb(7003), idMedicoWeb(14), idCargaWeb(TIPO_LABORATORIO_GENERAL, 703), idFilaWeb("veda", 1797),
            idPendienteWeb(1744), idAntecedenteWeb(5001, "hta"),
        ).forEach { assertTrue(esIdDeLaWeb(it), it) }
        listOf("3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b", "hcp-c12", "hc-clinica-p5001", "tobb-9", "", "hcc").forEach { assertFalse(esIdDeLaWeb(it), it) }
    }

    @Test
    fun una_consulta_con_id_de_la_web_es_siempre_solo_lectura_aunque_el_backend_no_lo_diga() {
        assertTrue(web.esSoloLecturaWeb(backendSoloLectura = true))
        assertTrue(web.esSoloLecturaWeb(backendSoloLectura = false), "el prefijo hcc- solo lo usa esta lectura: nunca se edita")
        assertTrue(webAbierta.esSoloLecturaWeb(false), "abierta en la web también se lee")
    }

    @Test
    fun una_con_remoto_id_solo_es_de_la_web_si_su_backend_solo_lee() {
        val conRemoto = consulta("3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b", remotoId = "88")
        assertTrue(conRemoto.esSoloLecturaWeb(backendSoloLectura = true))
        // Pediatría: una consulta creada en el dispositivo también tiene remoto_id una vez enviada, y se sigue editando.
        val pediatria = consulta("hcp-c88", EstadoConsulta.ABIERTA, especialidad = "pediatria", remotoId = "88")
        assertFalse(pediatria.esSoloLecturaWeb(backendSoloLectura = false))
    }

    @Test
    fun una_consulta_del_dispositivo_nunca_es_de_la_web() {
        assertFalse(local.esSoloLecturaWeb(true))
        assertFalse(local.esSoloLecturaWeb(false))
    }

    // ---------- Qué se puede hacer con la consulta (RF-45) ----------

    @Test
    fun la_consulta_de_la_web_no_se_edita_no_se_cierra_y_no_se_reabre_abierta_o_cerrada() {
        for (estado in EstadoConsulta.entries) for (puedeReabrir in listOf(true, false)) for (porTipo in listOf(true, false)) {
            val e = edicionDeConsulta(estado, soloLecturaPorTipo = porTipo, puedeReabrirPorTipo = puedeReabrir, soloLecturaWeb = true)
            assertTrue(e.soloLectura, "$estado $porTipo $puedeReabrir")
            assertFalse(e.puedeCerrar, "sin «Guardar y cerrar»")
            assertFalse(e.puedeReabrir, "sin «Reabrir para editar»")
        }
    }

    @Test
    fun las_consultas_del_dispositivo_siguen_exactamente_como_antes() {
        for (estado in EstadoConsulta.entries) for (puedeReabrir in listOf(true, false)) for (porTipo in listOf(true, false)) {
            // La regla de siempre: se lee si no está abierta o es de solo lectura por su tipo; se cierra si se edita; se reabre
            // lo que se lee si el tipo lo permite.
            val soloLectura = estado != EstadoConsulta.ABIERTA || porTipo
            val e = edicionDeConsulta(estado, soloLecturaPorTipo = porTipo, puedeReabrirPorTipo = puedeReabrir, soloLecturaWeb = false)
            assertEquals(soloLectura, e.soloLectura, "$estado $porTipo")
            assertEquals(!soloLectura, e.puedeCerrar, "$estado $porTipo")
            assertEquals(soloLectura && puedeReabrir, e.puedeReabrir, "$estado $porTipo $puedeReabrir")
        }
    }

    @Test
    fun una_propia_con_el_cierre_enviado_o_viajando_no_se_puede_reabrir_pero_se_lee() {
        for (estado in EstadoConsulta.entries) {
            val e = edicionDeConsulta(estado, soloLecturaPorTipo = false, puedeReabrirPorTipo = true, soloLecturaWeb = false, cierreEnviadoOViajando = true)
            assertEquals(estado != EstadoConsulta.ABIERTA, e.soloLectura, "$estado: lo cerrado se lee, lo abierto sigue editable")
            assertFalse(e.puedeReabrir, "$estado: sin «Reabrir para editar» una vez enviado el cierre (RF-25, D13)")
        }
        // Sin esa marca, todo como antes.
        assertTrue(edicionDeConsulta(EstadoConsulta.CERRADA, false, true, false, cierreEnviadoOViajando = false).puedeReabrir)
    }
    @Test
    fun la_etiqueta_de_estado_de_una_consulta_de_la_web_abierta_no_dice_cerrada() {
        assertEquals("Solo lectura", etiquetaDeEstadoEnLectura(EstadoConsulta.ABIERTA, soloLecturaPorTipo = false, soloLecturaWeb = true))
        assertEquals("Cerrada", etiquetaDeEstadoEnLectura(EstadoConsulta.CERRADA, soloLecturaPorTipo = false, soloLecturaWeb = true))
        // Como hoy para las del dispositivo.
        assertEquals("Cerrada", etiquetaDeEstadoEnLectura(EstadoConsulta.CERRADA, soloLecturaPorTipo = false, soloLecturaWeb = false))
        assertEquals("Solo lectura", etiquetaDeEstadoEnLectura(EstadoConsulta.ABIERTA, soloLecturaPorTipo = true, soloLecturaWeb = false))
    }

    // ---------- Filas de la web dentro de una consulta del dispositivo (RF-62) ----------

    @Test
    fun un_registro_de_la_web_no_se_edita_ni_se_quita_pero_uno_local_si_mientras_la_consulta_se_edite() {
        val deLaWeb = RegistroClinico(idFilaWeb("veda", 1797), paciente, null, "clinica", "veda")
        val propio = RegistroClinico("8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f", paciente, "c1", "clinica", "interconsulta")
        assertTrue(deLaWeb.esDeLaWeb())
        assertFalse(puedeEditarRegistro(deLaWeb, editable = true))
        assertFalse(puedeEditarRegistro(deLaWeb, editable = false))
        assertTrue(puedeEditarRegistro(propio, editable = true))
        assertFalse(puedeEditarRegistro(propio, editable = false), "una consulta que se lee no edita nada")
    }

    @Test
    fun un_antecedente_de_la_web_no_se_edita_desde_una_consulta_local() {
        val deLaWeb = Antecedente(idAntecedenteWeb(5001, "hta"), paciente, "clinica", "familiares", "hta", flag = true, detalle = "Padre")
        val propio = Antecedente("8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f", paciente, "clinica", "familiares", "dm", flag = true)
        assertTrue(deLaWeb.esDeLaWeb())
        assertFalse(puedeEditarAntecedente(deLaWeb, editable = true))
        assertTrue(puedeEditarAntecedente(propio, editable = true))
        assertTrue(puedeEditarAntecedente(null, editable = true), "una casilla sin fila todavía se puede cargar")
        assertFalse(puedeEditarAntecedente(propio, editable = false))
        assertFalse(puedeEditarAntecedente(null, editable = false))
    }

    @Test
    fun el_pendiente_de_la_web_se_lista_aparte_y_sin_los_resueltos() {
        val deLaWeb = Pendiente(idPendienteWeb(1744), paciente, yo, "Pedir ecografía", null)
        val resueltoDeLaWeb = Pendiente(idPendienteWeb(1700), paciente, yo, "Viejo", null, resuelto = true)
        val propio = Pendiente("8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f", paciente, yo, "Traer estudios", "c9")
        assertEquals(listOf(deLaWeb), pendientesDeLaWeb(listOf(propio, deLaWeb, resueltoDeLaWeb)))
        assertEquals(emptyList(), pendientesDeLaWeb(listOf(propio)))
    }

    // ---------- Avisos de la pantalla de la consulta (RF-43 a RF-45, RF-63) ----------

    private fun lectura(vararg marcas: Pair<String, String>, noLeidas: Map<Long, List<String>> = emptyMap()) =
        ExtrasDeLectura(null, null, noLeidas, AusenciasEnLaWeb(mapOf(*marcas)))

    @Test
    fun la_consulta_de_la_web_lleva_el_banner_y_nada_mas_si_esta_cerrada_y_es_propia() {
        val a = avisosDeLaPantallaDeConsulta(web, backendSoloLectura = true, medicoSesionId = yo, nombresMedicos = emptyMap(), lectura = ExtrasDeLectura.VACIO)
        assertEquals(listOf("Consulta de la web — solo lectura"), a.todos)
    }

    @Test
    fun abierta_en_la_web_avisa_que_lo_pendiente_de_confirmar_no_se_ve() {
        val a = avisosDeLaPantallaDeConsulta(webAbierta, true, yo, emptyMap(), ExtrasDeLectura.VACIO)
        assertEquals(
            listOf("Consulta de la web — solo lectura", "Abierta en la web: lo pendiente de confirmar no se ve hasta que se cierre"),
            a.todos,
        )
    }

    @Test
    fun la_de_otro_medico_dice_quien_la_cargo_con_su_nombre_o_sin_el() {
        val ajena = consulta(idConsultaWeb(7005), medico = idMedicoWeb(14), remotoId = "7005")
        val conNombre = avisosDeLaPantallaDeConsulta(ajena, true, yo, mapOf(idMedicoWeb(14) to "Prueba Salud360 Médica Uno"), ExtrasDeLectura.VACIO)
        assertEquals("Cargada por Prueba Salud360 Médica Uno", conNombre.cargadaPor)
        val sinNombre = avisosDeLaPantallaDeConsulta(ajena, true, yo, emptyMap(), ExtrasDeLectura.VACIO)
        assertEquals("Cargada por otro médico", sinNombre.cargadaPor)
    }

    @Test
    fun una_consulta_marcada_dice_que_ya_no_figura_en_la_web_con_su_texto_largo() {
        val a = avisosDeLaPantallaDeConsulta(web, true, yo, emptyMap(), lectura(idConsultaWeb(7003) to "2026-10-20"))
        assertEquals("Esta consulta ya no figura en la web: lo que ves es lo último que se trajo", a.ausente)
        assertTrue(a.soloLecturaWeb)
        // La de al lado, sin marca, no lo dice.
        assertNull(avisosDeLaPantallaDeConsulta(webAbierta, true, yo, emptyMap(), lectura(idConsultaWeb(7003) to "2026-10-20")).ausente)
    }

    @Test
    fun no_leidas_nombra_que_tiene_la_web_y_esta_version_no_muestra() {
        val a = avisosDeLaPantallaDeConsulta(web, true, yo, emptyMap(), lectura(noLeidas = mapOf(7003L to listOf("documentos", "audio"))))
        assertEquals("La web tiene adjuntos PDF y audio que esta versión no muestra", a.noLeidas)
    }

    @Test
    fun los_avisos_van_en_orden_banner_autor_ausente_abierta_no_leidas() {
        val ajenaAbierta = consulta(idConsultaWeb(7006), EstadoConsulta.ABIERTA, medico = idMedicoWeb(14), remotoId = "7006")
        val a = avisosDeLaPantallaDeConsulta(
            ajenaAbierta, true, yo, mapOf(idMedicoWeb(14) to "Prueba Salud360 Médica Uno"),
            lectura(idConsultaWeb(7006) to "2026-10-20", noLeidas = mapOf(7006L to listOf("fotos"))),
        )
        assertEquals(
            listOf(
                "Consulta de la web — solo lectura",
                "Cargada por Prueba Salud360 Médica Uno",
                "Esta consulta ya no figura en la web: lo que ves es lo último que se trajo",
                "Abierta en la web: lo pendiente de confirmar no se ve hasta que se cierre",
                "La web tiene fotos que esta versión no muestra",
            ),
            a.todos,
        )
    }

    @Test
    fun una_consulta_del_dispositivo_o_de_pediatria_no_lleva_ningun_aviso_de_la_web() {
        val marcas = lectura(idConsultaWeb(7003) to "2026-10-20", noLeidas = mapOf(7003L to listOf("fotos")))
        val pediatria = consulta("hcp-c88", EstadoConsulta.ABIERTA, especialidad = "pediatria", remotoId = "88")
        for (c in listOf(local, pediatria)) {
            val a = avisosDeLaPantallaDeConsulta(c, backendSoloLectura = false, medicoSesionId = yo, nombresMedicos = emptyMap(), lectura = marcas)
            assertEquals(emptyList(), a.todos, c.id)
            assertFalse(a.soloLecturaWeb)
        }
        assertEquals(emptyList(), avisosDeLaPantallaDeConsulta(local, true, yo, emptyMap(), marcas).todos)
    }

    // ---------- «Ya no figura en la web» en una sección (RF-63) ----------

    @Test
    fun una_seccion_con_un_campo_marcado_lleva_la_marca_y_nombra_el_campo() {
        val id = idConsultaWeb(7003)
        val ausencias = AusenciasEnLaWeb(
            mapOf(claveDeCampo(id, "serologias", "hbsag") to "2026-10-20", claveDeCampo(id, "habitos", CAMPO_TEXTO) to "2026-10-20"),
        )
        val marca = assertNotNull(marcaDeSeccion(ausencias, id, "serologias", mapOf("hbsag" to "HBsAg", "hcv" to "HCV")))
        assertEquals("Ya no figura en la web", marca.texto)
        assertEquals(listOf("HBsAg"), marca.campos)
        // Una sección de texto: la marca es de toda la sección, sin nombrar el campo.
        val texto = assertNotNull(marcaDeSeccion(ausencias, id, "habitos"))
        assertEquals(emptyList(), texto.campos)
    }

    @Test
    fun una_seccion_sin_marca_o_de_otra_consulta_no_lleva_nada() {
        val id = idConsultaWeb(7003)
        val ausencias = AusenciasEnLaWeb(mapOf(claveDeCampo(id, "habitos", CAMPO_TEXTO) to "2026-10-20"))
        assertNull(marcaDeSeccion(ausencias, id, "diuresis"))
        assertNull(marcaDeSeccion(ausencias, idConsultaWeb(7004), "habitos"))
        assertNull(marcaDeSeccion(AusenciasEnLaWeb.NINGUNA, id, "habitos"))
    }

    @Test
    fun el_campo_sin_etiqueta_conocida_no_se_inventa() {
        val id = idConsultaWeb(7003)
        val ausencias = AusenciasEnLaWeb(mapOf(claveDeCampo(id, "child_pugh", "ascitis") to "2026-10-20"))
        val marca = assertNotNull(marcaDeSeccion(ausencias, id, "child_pugh"))
        assertEquals(emptyList(), marca.campos, "sin etiqueta conocida se marca la sección, no se adivina un nombre")
    }

    @Test
    fun el_antecedente_marcado_se_encuentra_por_su_clave_aunque_la_fila_local_tenga_otro_id() {
        val ausencias = AusenciasEnLaWeb(mapOf(idAntecedenteWeb(5001, "hta") to "2026-10-20", idPendienteWeb(1744) to "2026-10-20"))
        assertTrue(ausencias.antecedenteDeClave("hta"))
        assertFalse(ausencias.antecedenteDeClave("dm"))
        assertFalse(ausencias.antecedenteDeClave("1744"), "un pendiente no es un antecedente")
        assertFalse(AusenciasEnLaWeb.NINGUNA.antecedenteDeClave("hta"))
    }

    // ---------- La lectura de la consulta que falla (RF-56, RF-58) ----------

    @Test
    fun un_fallo_de_la_lectura_dice_el_motivo_nombrando_la_historia_clinica() {
        assertEquals(
            "Historia clínica de Clínica: sin conexión. Intentá de nuevo en un momento.",
            mensajeDeLaLecturaDeConsulta("Clínica", TobbException(0, "red", "Sin conexión con la historia clínica de clinica")),
        )
        assertEquals(
            "Historia clínica de Clínica: sesión vencida. Volvé a iniciar sesión.",
            mensajeDeLaLecturaDeConsulta("Clínica", TobbException(401, "token", "Sesión vencida")),
        )
        assertEquals(
            "Historia clínica de Clínica: demasiados pedidos seguidos. Intentá de nuevo en un momento.",
            mensajeDeLaLecturaDeConsulta("Clínica", TobbException(429, null, "Too Many Attempts.")),
        )
        assertEquals(
            "Historia clínica de Clínica: No se pudo leer la historia clínica.",
            mensajeDeLaLecturaDeConsulta("Clínica", TobbException(500, "consultas", "No se pudo leer la historia clínica.")),
        )
    }

    @Test
    fun el_404_consulta_no_encontrada_no_es_un_error_que_mostrar_la_marca_lo_dice() {
        assertNull(mensajeDeLaLecturaDeConsulta("Clínica", TobbException(404, "consulta_no_encontrada", "Consulta no encontrada.")))
        // Otro 404 sí es un error.
        assertNotNull(mensajeDeLaLecturaDeConsulta("Clínica", TobbException(404, "paciente_no_encontrado", "Paciente no encontrado.")))
    }

    @Test
    fun la_lectura_arranca_sin_leer_ni_mensaje() {
        assertEquals(LecturaDeLaConsulta(leyendo = false, error = null), LecturaDeLaConsulta())
        assertTrue(LecturaDeLaConsulta(leyendo = true).leyendo)
    }
}
