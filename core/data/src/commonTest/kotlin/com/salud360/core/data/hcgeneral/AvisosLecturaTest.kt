package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.OmitidasConsultasDto
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Textos y estado de los avisos de la lectura de la HC general (RF-43 a RF-46, RF-49, RF-63): lo que la web tiene y la
 * app no muestra, la consulta abierta en la web, la de otro médico y «Ya no figura en la web». Los textos de la spec y
 * del plan van aquí TAL CUAL, a propósito: si alguien los cambia, este test falla.
 */
class AvisosLecturaTest {
    private val yo = "m-sesion"
    private val paciente = "p1"
    private val otro = idMedicoWeb(14)

    private fun web(idWeb: Long, estado: EstadoConsulta = EstadoConsulta.CERRADA, medico: String = yo) = Consulta(
        id = idConsultaWeb(idWeb), pacienteId = paciente, medicoId = medico, especialidad = "clinica", tipo = "control",
        fecha = LocalDate.parse("2026-10-01"), estado = estado, remotoId = idWeb.toString(),
    )

    private val local = Consulta(
        id = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b", pacienteId = paciente, medicoId = yo, especialidad = "clinica", tipo = "control",
        fecha = LocalDate.parse("2026-10-01"), estado = EstadoConsulta.ABIERTA,
    )

    private fun extra(clave: String, valor: String, especialidad: String = "clinica") = PacienteExtra(paciente, especialidad, clave, valor)

    // ---------- Los textos, tal cual ----------

    @Test
    fun los_textos_fijos_son_los_de_la_spec_y_del_plan() {
        // RF-63 y plan («Lo que ya no figura en la web»).
        assertEquals("Ya no figura en la web", YA_NO_FIGURA_EN_LA_WEB)
        assertEquals("Esta consulta ya no figura en la web: lo que ves es lo último que se trajo", CONSULTA_YA_NO_FIGURA_EN_LA_WEB)
        // RF-49.
        assertEquals("Este paciente todavía no está vinculado con la historia clínica de la web", PACIENTE_SIN_VINCULO)
        // Plan («Pantalla»), RF-44 y RF-45.
        assertEquals("Abiertas en la web (solo lectura)", TITULO_ABIERTAS_EN_LA_WEB)
        assertEquals("Consulta de la web — solo lectura", BANNER_CONSULTA_DE_LA_WEB)
        assertEquals("Abierta en la web: lo pendiente de confirmar no se ve hasta que se cierre", CONSULTA_ABIERTA_EN_LA_WEB)
        // RF-46 y Q14.
        assertEquals("Web", ETIQUETA_WEB)
        assertEquals("Solo en este dispositivo", ETIQUETA_SOLO_EN_ESTE_DISPOSITIVO)
        assertEquals("Solo en este dispositivo (no se envía a la web)", MARCA_SOLO_EN_ESTE_DISPOSITIVO)
        assertEquals("Cargada por Prueba Salud360 Médica Uno", etiquetaCargadaPor("Prueba Salud360 Médica Uno"))
        assertEquals("Cargada por otro médico", etiquetaCargadaPor(null))
    }

    // ---------- Consultas que la app no muestra (RF-43, Q2) ----------

    @Test
    fun el_aviso_de_consultas_omitidas_dice_cuantas_y_solo_si_hay() {
        assertEquals("5 consultas de la web de un tipo que esta versión no muestra", avisoConsultasOmitidas(5))
        assertEquals("2 consultas de la web de un tipo que esta versión no muestra", avisoConsultasOmitidas(2))
        assertEquals("1 consulta de la web de un tipo que esta versión no muestra", avisoConsultasOmitidas(1))
        assertNull(avisoConsultasOmitidas(0))
        assertNull(avisoConsultasOmitidas(-3))
    }

    @Test
    fun el_aviso_de_cargas_sin_fecha_dice_cuantas_y_solo_si_hay() {
        assertEquals("3 cargas de laboratorio/peso sin fecha no se muestran", avisoCargasSinFecha(3))
        assertEquals("1 carga de laboratorio/peso sin fecha no se muestra", avisoCargasSinFecha(1))
        assertNull(avisoCargasSinFecha(0))
    }

    @Test
    fun la_historia_suma_foto_otro_y_sin_fecha_de_las_consultas_y_no_las_cargas() {
        val lectura = ExtrasDeLectura(OmitidasConsultasDto(foto = 2, otro = 1, sinFecha = 4), cargasSinFecha = 3, noLeidas = emptyMap(), ausencias = AusenciasEnLaWeb.NINGUNA)
        val a = avisosDeLaHistoria(vinculado = true, lectura = lectura)
        assertEquals("7 consultas de la web de un tipo que esta versión no muestra", a.consultasOmitidas)
        assertEquals("3 cargas de laboratorio/peso sin fecha no se muestran", a.cargasSinFecha, "las cargas son otra cuenta: no se mezclan con las consultas")
        assertNull(a.sinVinculo)
        assertEquals(listOf(a.consultasOmitidas, a.cargasSinFecha), a.todos)
    }

    @Test
    fun cada_aviso_sale_solo_si_hay_algo_que_avisar() {
        val solo = avisosDeLaHistoria(true, ExtrasDeLectura(OmitidasConsultasDto(0, 0, 0), 0, emptyMap(), AusenciasEnLaWeb.NINGUNA))
        assertNull(solo.consultasOmitidas)
        assertNull(solo.cargasSinFecha)
        assertEquals(emptyList(), solo.todos)
        val unaSola = avisosDeLaHistoria(true, ExtrasDeLectura(OmitidasConsultasDto(0, 0, 1), null, emptyMap(), AusenciasEnLaWeb.NINGUNA))
        assertEquals("1 consulta de la web de un tipo que esta versión no muestra", unaSola.consultasOmitidas)
        assertNull(unaSola.cargasSinFecha)
        val sinNada = avisosDeLaHistoria(true, ExtrasDeLectura.VACIO)
        assertEquals(emptyList(), sinNada.todos, "sin nada leído todavía no hay ningún aviso")
    }

    @Test
    fun el_paciente_sin_vinculo_lo_dice_y_el_vinculado_no() {
        val sin = avisosDeLaHistoria(vinculado = false, lectura = ExtrasDeLectura.VACIO)
        assertEquals("Este paciente todavía no está vinculado con la historia clínica de la web", sin.sinVinculo)
        assertEquals(listOf(PACIENTE_SIN_VINCULO), sin.todos)
        assertNull(avisosDeLaHistoria(vinculado = true, lectura = ExtrasDeLectura.VACIO).sinVinculo)
    }

    // ---------- Lo que dejó la lectura en paciente_extra ----------

    @Test
    fun lee_omitidas_cargas_no_leidas_y_marcas_de_paciente_extra() {
        val lectura = extrasDeLectura(
            listOf(
                extra(CLAVE_OMITIDAS, "1|2|3"),
                extra(CLAVE_OMITIDAS_LABORATORIO, "4"),
                extra(claveNoLeidas(7003), "documentos,audio"),
                extra(claveNoLeidas(7004), ""),
                extra(PREFIJO_MARCA_AUSENTE + idConsultaWeb(7001), "2026-10-20"),
                extra(PREFIJO_MARCA_AUSENTE + claveDeCampo(idConsultaWeb(7003), "habitos", "texto"), "2026-10-21"),
                extra("otra_cosa", "x"),
            ),
        )
        assertEquals(OmitidasConsultasDto(1, 2, 3), lectura.omitidasConsultas)
        assertEquals(4, lectura.cargasSinFecha)
        assertEquals(mapOf(7003L to listOf("documentos", "audio"), 7004L to emptyList()), lectura.noLeidas)
        assertTrue(lectura.ausencias.consulta(idConsultaWeb(7001)))
        assertTrue(lectura.ausencias.contenido(idConsultaWeb(7003), "habitos", "texto"))
        assertEquals(2, lectura.ausencias.items().size)
    }

    @Test
    fun lo_que_no_se_entiende_o_es_de_otra_historia_no_se_inventa() {
        val lectura = extrasDeLectura(
            listOf(
                extra(CLAVE_OMITIDAS, "1|2"),
                extra(CLAVE_OMITIDAS_LABORATORIO, "muchas"),
                extra(PREFIJO_CLAVE_NO_LEIDAS + "abc", "audio"),
                extra(PREFIJO_CLAVE_NO_LEIDAS + "0", "audio"),
                extra(claveNoLeidas(7003), "fotos", especialidad = "pediatria"),
                extra(PREFIJO_MARCA_AUSENTE + idConsultaWeb(7001), "2026-10-20", especialidad = "pediatria"),
                extra(PREFIJO_MARCA_AUSENTE, "2026-10-20"),
            ),
        )
        assertNull(lectura.omitidasConsultas)
        assertNull(lectura.cargasSinFecha)
        assertEquals(emptyMap(), lectura.noLeidas)
        assertEquals(emptyList(), lectura.ausencias.items())
        assertEquals(ExtrasDeLectura.VACIO, extrasDeLectura(emptyList()))
    }

    // ---------- Lo que la web tiene en una consulta y la app no muestra (RF-43) ----------

    @Test
    fun el_aviso_de_no_leidas_nombra_que_es() {
        assertEquals("La web tiene adjuntos PDF que esta versión no muestra", avisoNoLeidas(listOf("documentos")))
        assertEquals("La web tiene audio que esta versión no muestra", avisoNoLeidas(listOf("audio")))
        assertEquals("La web tiene fotos que esta versión no muestra", avisoNoLeidas(listOf("fotos")))
        assertEquals("La web tiene diagnósticos que esta versión no muestra", avisoNoLeidas(listOf("diagnosticos")))
        assertEquals("La web tiene antecedentes personales que esta versión no muestra", avisoNoLeidas(listOf("antecedentes_personales")))
    }

    @Test
    fun el_aviso_de_no_leidas_junta_varios_con_comas_y_una_y() {
        assertEquals("La web tiene adjuntos PDF y audio que esta versión no muestra", avisoNoLeidas(listOf("documentos", "audio")))
        assertEquals(
            "La web tiene adjuntos PDF, audio y fotos que esta versión no muestra",
            avisoNoLeidas(listOf("documentos", "audio", "fotos")),
        )
        assertEquals(
            "La web tiene adjuntos PDF, audio, fotos, diagnósticos y antecedentes personales que esta versión no muestra",
            avisoNoLeidas(listOf("documentos", "audio", "fotos", "diagnosticos", "antecedentes_personales")),
        )
    }

    @Test
    fun el_aviso_de_no_leidas_no_repite_ni_inventa_y_nombra_lo_que_no_conoce_como_vino() {
        assertEquals("La web tiene audio que esta versión no muestra", avisoNoLeidas(listOf("audio", "", "  ", "audio")))
        assertEquals("La web tiene audio y videos_raros que esta versión no muestra", avisoNoLeidas(listOf("audio", "videos_raros")), "un id nuevo del servidor se nombra tal cual")
        assertNull(avisoNoLeidas(null), "sin fila no se sabe nada: ningún aviso")
        assertNull(avisoNoLeidas(emptyList()), "ya no tiene nada")
        assertNull(avisoNoLeidas(listOf("", " ")))
    }

    // ---------- Avisos de una consulta ----------

    @Test
    fun la_consulta_cerrada_de_la_web_propia_solo_dice_que_es_de_solo_lectura() {
        val a = avisosDeConsulta(web(7003), yo, emptyMap(), ExtrasDeLectura.VACIO)
        assertTrue(a.soloLecturaWeb)
        assertEquals("Consulta de la web — solo lectura", a.banner)
        assertNull(a.abiertaEnLaWeb)
        assertNull(a.cargadaPor)
        assertNull(a.noLeidas)
        assertNull(a.ausente)
        assertEquals(listOf(BANNER_CONSULTA_DE_LA_WEB), a.todos)
    }

    @Test
    fun la_abierta_en_la_web_avisa_que_lo_pendiente_no_se_ve_hasta_que_se_cierre() {
        val a = avisosDeConsulta(web(7010, EstadoConsulta.ABIERTA), yo, emptyMap(), ExtrasDeLectura.VACIO)
        assertEquals("Abierta en la web: lo pendiente de confirmar no se ve hasta que se cierre", a.abiertaEnLaWeb)
        assertTrue(a.soloLecturaWeb, "abierta o cerrada, solo para leer (RF-45)")
        assertEquals(listOf(BANNER_CONSULTA_DE_LA_WEB, CONSULTA_ABIERTA_EN_LA_WEB), a.todos)
    }

    @Test
    fun la_de_otro_medico_dice_quien_la_cargo() {
        val con = avisosDeConsulta(web(7003, medico = otro), yo, mapOf(otro to "Prueba Salud360 Médica Uno"), ExtrasDeLectura.VACIO)
        assertEquals("Cargada por Prueba Salud360 Médica Uno", con.cargadaPor)
        val sin = avisosDeConsulta(web(7003, medico = otro), yo, emptyMap(), ExtrasDeLectura.VACIO)
        assertEquals("Cargada por otro médico", sin.cargadaPor)
        assertTrue(sin.soloLecturaWeb)
    }

    @Test
    fun la_consulta_con_datos_que_la_app_no_muestra_lo_avisa_por_su_id_de_la_web() {
        val lectura = extrasDeLectura(listOf(extra(claveNoLeidas(7003), "documentos,audio"), extra(claveNoLeidas(7004), "fotos")))
        assertEquals("La web tiene adjuntos PDF y audio que esta versión no muestra", avisosDeConsulta(web(7003), yo, emptyMap(), lectura).noLeidas)
        assertEquals("La web tiene fotos que esta versión no muestra", avisosDeConsulta(web(7004), yo, emptyMap(), lectura).noLeidas)
        assertNull(avisosDeConsulta(web(7005), yo, emptyMap(), lectura).noLeidas, "otra consulta: nada")
    }

    @Test
    fun la_consulta_marcada_dice_que_ya_no_figura_y_no_se_oculta() {
        val lectura = extrasDeLectura(listOf(extra(PREFIJO_MARCA_AUSENTE + idConsultaWeb(7003), "2026-10-20")))
        val a = avisosDeConsulta(web(7003), yo, emptyMap(), lectura)
        assertEquals("Esta consulta ya no figura en la web: lo que ves es lo último que se trajo", a.ausente)
        assertTrue(a.todos.contains(CONSULTA_YA_NO_FIGURA_EN_LA_WEB))
        assertNull(avisosDeConsulta(web(7004), yo, emptyMap(), lectura).ausente)
    }

    @Test
    fun una_marca_de_una_seccion_o_fila_no_es_la_marca_de_la_consulta() {
        val lectura = extrasDeLectura(listOf(extra(PREFIJO_MARCA_AUSENTE + claveDeCampo(idConsultaWeb(7003), "habitos", "texto"), "2026-10-21")))
        assertNull(avisosDeConsulta(web(7003), yo, emptyMap(), lectura).ausente)
    }

    @Test
    fun una_consulta_solo_del_dispositivo_no_recibe_ningun_aviso_de_la_web() {
        // Aunque en paciente_extra hubiera filas con su id o con el remoto_id de otra: la consulta local no es de la web.
        val lectura = extrasDeLectura(
            listOf(
                extra(claveNoLeidas(7003), "documentos"),
                extra(PREFIJO_MARCA_AUSENTE + local.id, "2026-10-20"),
            ),
        )
        val a = avisosDeConsulta(local, yo, emptyMap(), lectura)
        assertFalse(a.soloLecturaWeb)
        assertNull(a.banner)
        assertNull(a.abiertaEnLaWeb)
        assertNull(a.cargadaPor)
        assertNull(a.noLeidas)
        assertNull(a.ausente)
        assertEquals(emptyList(), a.todos)
    }

    @Test
    fun el_orden_de_los_avisos_de_una_consulta() {
        val lectura = extrasDeLectura(
            listOf(extra(claveNoLeidas(7010), "audio"), extra(PREFIJO_MARCA_AUSENTE + idConsultaWeb(7010), "2026-10-20")),
        )
        val a = avisosDeConsulta(web(7010, EstadoConsulta.ABIERTA, medico = otro), yo, mapOf(otro to "Prueba Salud360 Médica Uno"), lectura)
        assertEquals(
            listOf(
                "Consulta de la web — solo lectura",
                "Cargada por Prueba Salud360 Médica Uno",
                "Esta consulta ya no figura en la web: lo que ves es lo último que se trajo",
                "Abierta en la web: lo pendiente de confirmar no se ve hasta que se cierre",
                "La web tiene audio que esta versión no muestra",
            ),
            a.todos,
        )
    }

    // ---------- Nota del perfil 4 (D2) ----------

    private fun familiar(clave: String, detalle: String, flag: Boolean = true, activo: Boolean = true) =
        Antecedente(idAntecedenteWeb(5001, clave), paciente, "clinica", CATEGORIA_FAMILIARES, clave, flag, detalle, null, activo)

    @Test
    fun la_nota_del_perfil_4_va_en_antecedentes_familiares_como_nota_aparte() {
        assertEquals("antecedentes_familiares", SECCION_DE_LA_NOTA_PERFIL4)
        val nota = notaPerfil4(listOf(familiar("hta", "Padre"), familiar(CLAVE_NOTA, "Una nota"), familiar(CLAVE_NOTA_PERFIL4, "Prueba Salud360 nota del perfil 4")))
        assertEquals("Prueba Salud360 nota del perfil 4", nota)
    }

    @Test
    fun la_nota_del_perfil_4_solo_si_hay() {
        assertNull(notaPerfil4(emptyList()))
        assertNull(notaPerfil4(listOf(familiar("hta", "Padre"), familiar(CLAVE_NOTA, "Una nota"))), "la nota común no es la del perfil 4")
        assertNull(notaPerfil4(listOf(familiar(CLAVE_NOTA_PERFIL4, ""))))
        assertNull(notaPerfil4(listOf(familiar(CLAVE_NOTA_PERFIL4, "   "))))
        assertNull(notaPerfil4(listOf(familiar(CLAVE_NOTA_PERFIL4, "x", activo = false))))
        assertNull(notaPerfil4(listOf(familiar(CLAVE_NOTA_PERFIL4, "x").copy(categoria = "personales"))))
    }

    @Test
    fun la_nota_del_perfil_4_se_muestra_tal_cual_vino() {
        val texto = "  Línea uno\nLínea dos ñandú 😀  "
        assertEquals(texto, notaPerfil4(listOf(familiar(CLAVE_NOTA_PERFIL4, texto))))
    }
}
