package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.AntecedentesFamiliaresDto
import com.salud360.core.data.network.hc.CasillaAntecedenteDto
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.EjemplosConsultasClinica
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.data.network.hc.RespuestaListaConsultasDto
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Q15 = nunca borrar (RF-55, RF-63): `marcarAusentes` decide qué lleva la marca «Ya no figura en la web» y qué se
 * desmarca. Solo marca lo que la respuesta cubre y solo si es completa; el resultado no tiene forma de expresar un
 * borrado ni un vaciado (la comprobación por reflexión está en `LecturaClinicaSinBorradoTest`, de `jvmTest`).
 */
class LecturaClinicaAusentesTest {
    private val ejemplos = RespuestasClinicaDeEjemplo
    private val paciente = "hc-clinica-p5001"
    private val ninguna = MarcasAusentes(emptySet(), emptySet())
    private val consultasTraidas = setOf("hcc-c7003", "hcc-c7004", "hcc-c7005")

    private fun cabecera(id: Long): ConsultaCabeceraDto = ejemplos.consulta().consulta.copy(id = id)

    private fun pagina(ids: List<Long>, total: Int, desde: Int = 0, hayMas: Int = 0) =
        RespuestaListaConsultasDto(ids.map { cabecera(it) }, total, desde, hayMas)

    private fun lista(vararg paginas: RespuestaListaConsultasDto, paciente: Long = 5001) = LecturaWeb.Lista(paciente, paginas.toList())

    private fun marcar(vararg claves: String) = MarcasAusentes(claves.toSet(), emptySet())

    private fun desmarcar(vararg claves: String) = MarcasAusentes(emptySet(), claves.toSet())

    // --- Las marcas en paciente_extra ---

    @Test
    fun la_marca_es_una_clave_hcc_ausente_con_la_fecha_en_que_se_noto() {
        assertEquals("hcc_ausente_", PREFIJO_MARCA_AUSENTE)
        assertEquals(
            PacienteExtra(paciente, "clinica", "hcc_ausente_hcc-c7004", "2026-10-30"),
            marcaAusente(paciente, "hcc-c7004", LocalDate(2026, 10, 30)),
        )
        assertEquals(
            PacienteExtra(paciente, "clinica", "hcc_ausente_hcc-c7003:habitos:texto", "2026-10-30"),
            marcaAusente(paciente, "hcc-c7003:habitos:texto", LocalDate(2026, 10, 30)),
        )
    }

    @Test
    fun la_clave_de_la_marca_se_lee_de_vuelta_y_no_se_confunde_con_las_otras_claves_del_paciente() {
        assertEquals("hcc-c7004", claveDeLaMarca("hcc_ausente_hcc-c7004"))
        assertEquals("hcc-c7003:habitos:texto", claveDeLaMarca("hcc_ausente_hcc-c7003:habitos:texto"))
        listOf("hcc_omitidas", "hcc_no_leidas_7003", "hc_paciente_id", "tobb_id", "hcc_ausente_", "hcc_ausente", "").forEach {
            assertNull(claveDeLaMarca(it), it)
        }
    }

    // --- Lista de consultas ---

    @Test
    fun una_consulta_que_desaparece_de_una_lista_completa_se_marca() {
        val l = lista(pagina(listOf(7005, 7003), total = 2))
        assertEquals(marcar("hcc-c7004"), marcarAusentes(consultasTraidas, emptySet(), l))
    }

    @Test
    fun la_que_sigue_viniendo_no_se_marca() {
        val l = lista(pagina(listOf(7005, 7004, 7003), total = 3))
        assertEquals(ninguna, marcarAusentes(consultasTraidas, emptySet(), l))
    }

    @Test
    fun la_que_reaparece_pierde_la_marca() {
        val l = lista(pagina(listOf(7005, 7004, 7003), total = 3))
        assertEquals(desmarcar("hcc-c7004"), marcarAusentes(consultasTraidas, setOf("hcc-c7004"), l))
    }

    @Test
    fun una_marca_que_ya_esta_puesta_no_se_repite_ni_se_quita_si_sigue_ausente() {
        val l = lista(pagina(listOf(7005, 7003), total = 2))
        assertEquals(ninguna, marcarAusentes(consultasTraidas, setOf("hcc-c7004"), l))
    }

    @Test
    fun una_pagina_parcial_no_marca_nada_aunque_falten_consultas() {
        val parcial = lista(ejemplos.lista()) // total 4, 3 consultas, hay_mas = 1
        val traido = consultasTraidas + "hcc-c7001" + "hcc-c7099"
        assertEquals(ninguna, marcarAusentes(traido, emptySet(), parcial))
        // Solo la primera página de dos.
        assertEquals(ninguna, marcarAusentes(traido, emptySet(), lista(pagina(listOf(7005, 7004), total = 3, hayMas = 1))))
    }

    @Test
    fun una_pagina_parcial_si_quita_la_marca_de_lo_que_trae() {
        val parcial = lista(ejemplos.lista())
        assertEquals(desmarcar("hcc-c7004"), marcarAusentes(consultasTraidas, setOf("hcc-c7004", "hcc-c7001"), parcial))
    }

    @Test
    fun todas_las_paginas_en_un_mismo_actualizar_cubren_la_lista() {
        val p1 = pagina(listOf(7005, 7004), total = 3, desde = 0, hayMas = 1)
        val p2 = pagina(listOf(7003), total = 3, desde = 2, hayMas = 0)
        assertEquals(marcar("hcc-c7001"), marcarAusentes(consultasTraidas + "hcc-c7001", emptySet(), lista(p1, p2)))
    }

    @Test
    fun paginas_que_no_encajan_no_marcan_nada() {
        val traido = consultasTraidas + "hcc-c7001"
        val p1 = pagina(listOf(7005, 7004), total = 3, desde = 0, hayMas = 1)
        val casos = mapOf(
            "el total cambió entre páginas" to lista(p1, pagina(listOf(7003), total = 4, desde = 2)),
            "se saltea una página" to lista(p1, pagina(listOf(7003), total = 3, desde = 3)),
            "no es la que sigue" to lista(p1, pagina(listOf(7003), total = 3, desde = 1)),
            "la suma no da el total" to lista(pagina(listOf(7005, 7004), total = 5, hayMas = 1), pagina(listOf(7003), total = 5, desde = 2)),
            "la primera dice que no hay más y hay otra" to lista(pagina(listOf(7005, 7004), total = 3, hayMas = 0), pagina(listOf(7003), total = 3, desde = 2)),
            "la última dice que hay más" to lista(p1, pagina(listOf(7003), total = 3, desde = 2, hayMas = 1)),
            "una consulta repetida" to lista(p1, pagina(listOf(7004), total = 3, desde = 2)),
            "no empieza en el principio" to lista(pagina(listOf(7003), total = 3, desde = 2)),
            "sin páginas" to lista(),
        )
        casos.forEach { (motivo, l) -> assertEquals(ninguna, marcarAusentes(traido, emptySet(), l), motivo) }
    }

    @Test
    fun una_lista_de_otro_paciente_no_marca_ni_desmarca() {
        val ajena = lista(pagina(listOf(7005, 7003), total = 2), paciente = 9999)
        assertEquals(ninguna, marcarAusentes(consultasTraidas, setOf("hcc-c7005"), ajena))
    }

    @Test
    fun una_lista_vacia_completa_y_correcta_marca_todas_pero_solo_las_consultas() {
        val vacia = lista(pagina(emptyList(), total = 0))
        val traido = consultasTraidas + "hcc-llaboratorio_general-703" + "hcc-c7003:habitos:texto" + "hcc-p1744"
        assertEquals(marcar("hcc-c7003", "hcc-c7004", "hcc-c7005"), marcarAusentes(traido, emptySet(), vacia))
    }

    @Test
    fun una_respuesta_con_error_no_marca_ni_desmarca_nada() {
        val errores = listOf("500", "JSON inválido", "sin conexión", "429", "403 fuera_de_cartera", "respuesta cortada")
        errores.forEach { motivo ->
            assertEquals(ninguna, marcarAusentes(consultasTraidas + "hcc-p1744", setOf("hcc-c7004"), LecturaWeb.ConError(motivo)), motivo)
        }
    }

    // --- Una consulta ---

    @Test
    fun las_claves_de_lo_que_trae_una_consulta() {
        val claves = clavesDeLaConsulta(ejemplos.consulta())
        assertEquals(
            setOf(
                "hcc-c7003:motivo_consulta:texto", "hcc-c7003:datos_subjetivos:texto", "hcc-c7003:datos_objetivos:texto",
                "hcc-c7003:conductas:texto", "hcc-c7003:nota:texto", "hcc-c7003:habitos:texto", "hcc-c7003:diuresis_catarsis:texto",
                "hcc-c7003:somnia:texto",
                "hcc-c7003:alimentacion:hiposodica", "hcc-c7003:alimentacion:diabetes", "hcc-c7003:alimentacion:celiaco",
                "hcc-c7003:serologias:igm_hav", "hcc-c7003:serologias:ags_hbv", "hcc-c7003:serologias:hiv",
                "hcc-c7003:child_pugh:ascitis", "hcc-c7003:child_pugh:bilirrubina", "hcc-c7003:child_pugh:albumina",
                "hcc-c7003:child_pugh:tp", "hcc-c7003:child_pugh:encefalopatia",
                "hcc-c7003:examen_fisico:peso", "hcc-c7003:examen_fisico:talla", "hcc-c7003:examen_fisico:tension_arterial",
                "hcc-c7003:examen_fisico:imc", "hcc-c7003:examen_fisico:nota",
            ),
            claves,
        )
        assertEquals("hcc-c7003:habitos:texto", claveDeCampo("hcc-c7003", "habitos", "texto"))
    }

    private val traidoDeLaConsulta get() = clavesDeLaConsulta(ejemplos.consulta()) + "hcc-c7003"

    @Test
    fun una_consulta_igual_no_marca_nada() {
        assertEquals(ninguna, marcarAusentes(traidoDeLaConsulta, emptySet(), LecturaWeb.UnaConsulta(ejemplos.consulta())))
    }

    @Test
    fun un_texto_una_casilla_o_un_campo_que_la_web_vacio_se_marcan() {
        val c = ejemplos.consulta()
        val vaciada = c.copy(
            secciones = c.secciones.copy(habitos = null, alimentacion = c.secciones.alimentacion - "celiaco", childPugh = emptyMap()),
            examenFisico = c.examenFisico.copy(peso = null, nota = ""),
        )
        assertEquals(
            marcar(
                "hcc-c7003:habitos:texto", "hcc-c7003:alimentacion:celiaco", "hcc-c7003:examen_fisico:peso", "hcc-c7003:examen_fisico:nota",
                "hcc-c7003:child_pugh:ascitis", "hcc-c7003:child_pugh:bilirrubina", "hcc-c7003:child_pugh:albumina",
                "hcc-c7003:child_pugh:tp", "hcc-c7003:child_pugh:encefalopatia",
            ),
            marcarAusentes(traidoDeLaConsulta, emptySet(), LecturaWeb.UnaConsulta(vaciada)),
        )
    }

    @Test
    fun una_consulta_sin_examen_ni_secciones_marca_todo_su_contenido_pero_no_la_consulta() {
        val c = ejemplos.consultaConSecciones("{}")
        val esperado = clavesDeLaConsulta(ejemplos.consulta())
        assertEquals(MarcasAusentes(esperado, emptySet()), marcarAusentes(traidoDeLaConsulta, emptySet(), LecturaWeb.UnaConsulta(c)))
    }

    @Test
    fun una_consulta_solo_marca_lo_suyo() {
        val otra = setOf("hcc-c7004:nota:texto", "hcc-c7005:examen_fisico:peso", "hcc-c70030:nota:texto", "hcc-llaboratorio_general-703", "hcc-p1744")
        val vacia = ejemplos.consultaConSecciones("{}")
        val r = marcarAusentes(traidoDeLaConsulta + otra, emptySet(), LecturaWeb.UnaConsulta(vacia))
        assertTrue(r.marcar.none { it in otra }, r.marcar.toString())
        assertTrue(r.marcar.all { it.startsWith("hcc-c7003:") })
    }

    @Test
    fun el_dato_que_vuelve_pierde_la_marca() {
        val marcadas = setOf("hcc-c7003:habitos:texto", "hcc-c7003:examen_fisico:peso", "hcc-c7003:nota:texto")
        val c = ejemplos.consulta()
        val sinNota = c.copy(secciones = c.secciones.copy(nota = null))
        assertEquals(
            desmarcar("hcc-c7003:habitos:texto", "hcc-c7003:examen_fisico:peso"),
            marcarAusentes(traidoDeLaConsulta, marcadas, LecturaWeb.UnaConsulta(sinNota)),
        )
    }

    @Test
    fun abrir_una_consulta_marcada_que_ahora_esta_la_desmarca() {
        assertEquals(desmarcar("hcc-c7003"), marcarAusentes(traidoDeLaConsulta, setOf("hcc-c7003"), LecturaWeb.UnaConsulta(ejemplos.consulta())))
    }

    @Test
    fun un_404_consulta_no_encontrada_marca_la_consulta_y_deja_su_contenido() {
        val r = marcarAusentes(traidoDeLaConsulta, emptySet(), LecturaWeb.ConsultaNoEncontrada(7003))
        assertEquals(marcar("hcc-c7003"), r)
        // No traída (nunca estuvo en el dispositivo) o ya marcada: nada que hacer.
        assertEquals(ninguna, marcarAusentes(setOf("hcc-c7004"), emptySet(), LecturaWeb.ConsultaNoEncontrada(7003)))
        assertEquals(ninguna, marcarAusentes(traidoDeLaConsulta, setOf("hcc-c7003"), LecturaWeb.ConsultaNoEncontrada(7003)))
        assertEquals(ninguna, marcarAusentes(traidoDeLaConsulta, emptySet(), LecturaWeb.ConsultaNoEncontrada(0)))
    }

    @Test
    fun una_consulta_con_error_no_marca_nada() {
        assertEquals(ninguna, marcarAusentes(traidoDeLaConsulta, setOf("hcc-c7003:nota:texto"), LecturaWeb.ConError("500")))
    }

    // --- Historia del paciente ---

    private val historia: RespuestaHistoriaDto get() = ejemplos.historia()

    private fun historiaVacia() = ejemplos.historia(EjemplosConsultasClinica.HISTORIA_VACIA).copy(pacienteId = 5001)

    private fun lectura(h: RespuestaHistoriaDto = historia, paciente: Long = 5001) = LecturaWeb.Historia(paciente, h)

    @Test
    fun las_claves_de_lo_que_trae_la_historia() {
        assertEquals(
            setOf(
                "hcc-a5001-hta", "hcc-a5001-dbt_ir", "hcc-a5001-otro", "hcc-a5001-nota", "hcc-a5001-nota_perfil4",
                "hcc-llaboratorio_general-703", "hcc-llaboratorio_reducido-19", "hcc-lpeso-25",
                "hcc-reco_abdominal-1801", "hcc-reco_abdominal-1802", "hcc-rveda-1797", "hcc-rbiopsia_hepatica-1797", "hcc-rtc_rmn-1797",
                "hcc-rexamen_complementario-22", "hcc-rexamen_complementario-23", "hcc-rinterconsulta-14",
                "hcc-p1744",
                "hcc-c7005:motivo_consulta:texto", "hcc-c7003:motivo_consulta:texto",
            ),
            clavesDeLaHistoria(historia),
        )
    }

    @Test
    fun una_historia_igual_no_marca_nada() {
        assertEquals(ninguna, marcarAusentes(clavesDeLaHistoria(historia), emptySet(), lectura()))
    }

    @Test
    fun cada_cosa_que_la_web_deja_de_mandar_se_marca_con_su_clave() {
        val h = historia
        val casos: List<Pair<RespuestaHistoriaDto, String>> = listOf(
            h.copy(laboratorios = h.laboratorios.copy(laboratorioGeneral = emptyList())) to "hcc-llaboratorio_general-703",
            h.copy(laboratorios = h.laboratorios.copy(laboratorioReducido = emptyList())) to "hcc-llaboratorio_reducido-19",
            h.copy(laboratorios = h.laboratorios.copy(peso = emptyList())) to "hcc-lpeso-25",
            h.copy(estudios = h.estudios.copy(veda = emptyList())) to "hcc-rveda-1797",
            h.copy(estudios = h.estudios.copy(biopsiaHepatica = emptyList())) to "hcc-rbiopsia_hepatica-1797",
            h.copy(estudios = h.estudios.copy(tcRmn = emptyList())) to "hcc-rtc_rmn-1797",
            h.copy(estudios = h.estudios.copy(ecoAbdominal = h.estudios.ecoAbdominal.take(1))) to "hcc-reco_abdominal-1802",
            h.copy(examenesComplementarios = h.examenesComplementarios.take(1)) to "hcc-rexamen_complementario-23",
            h.copy(interconsultas = emptyList()) to "hcc-rinterconsulta-14",
            h.copy(pendientes = null) to "hcc-p1744",
            h.copy(motivos = h.motivos.drop(1)) to "hcc-c7005:motivo_consulta:texto",
            h.copy(antecedentesFamiliares = h.antecedentesFamiliares!!.let { AntecedentesFamiliaresDto(it.casillas - "hta", it.nota) }) to "hcc-a5001-hta",
            h.copy(antecedentesFamiliares = h.antecedentesFamiliares!!.let { AntecedentesFamiliaresDto(it.casillas, null) }) to "hcc-a5001-nota",
        )
        casos.forEachIndexed { i, (respuesta, clave) ->
            assertEquals(marcar(clave), marcarAusentes(clavesDeLaHistoria(h), emptySet(), lectura(respuesta)), "caso $i: $clave")
        }
    }

    @Test
    fun una_casilla_que_la_web_dejo_en_cero_ya_no_figura() {
        val h = historia
        val casillas = h.antecedentesFamiliares!!.casillas + ("dbt_ir" to CasillaAntecedenteDto(0))
        val sinMarcar = h.copy(antecedentesFamiliares = AntecedentesFamiliaresDto(casillas, h.antecedentesFamiliares!!.nota))
        assertEquals(marcar("hcc-a5001-dbt_ir"), marcarAusentes(clavesDeLaHistoria(h), emptySet(), lectura(sinMarcar)))
    }

    @Test
    fun una_carga_que_perdio_la_fecha_o_los_valores_tambien_ya_no_figura() {
        val h = historia
        val sinFecha = h.copy(laboratorios = h.laboratorios.copy(laboratorioGeneral = listOf(h.laboratorios.laboratorioGeneral.single().copy(fecha = null))))
        assertEquals(marcar("hcc-llaboratorio_general-703"), marcarAusentes(clavesDeLaHistoria(h), emptySet(), lectura(sinFecha)))
        val sinValores = h.copy(laboratorios = h.laboratorios.copy(peso = listOf(h.laboratorios.peso.single().copy(valores = mapOf("peso" to "")))))
        assertEquals(marcar("hcc-lpeso-25"), marcarAusentes(clavesDeLaHistoria(h), emptySet(), lectura(sinValores)))
    }

    @Test
    fun de_los_tres_estudios_con_el_mismo_numero_solo_se_marca_el_que_falta() {
        val h = historia
        val r = marcarAusentes(clavesDeLaHistoria(h), emptySet(), lectura(h.copy(estudios = h.estudios.copy(veda = emptyList()))))
        assertEquals(marcar("hcc-rveda-1797"), r)
    }

    @Test
    fun una_historia_vacia_marca_todo_lo_traido_de_la_historia_menos_la_nota_del_perfil_4() {
        val traido = clavesDeLaHistoria(historia)
        val r = marcarAusentes(traido, emptySet(), lectura(historiaVacia()))
        assertEquals(MarcasAusentes(traido - "hcc-a5001-nota_perfil4", emptySet()), r)
    }

    @Test
    fun la_nota_del_perfil_4_nunca_se_marca_porque_su_ausencia_no_se_distingue_de_una_tabla_que_falta() {
        val h = historia
        val sinNota = h.copy(antecedentesFamiliaresPerfil4 = null)
        assertEquals(ninguna, marcarAusentes(clavesDeLaHistoria(h), emptySet(), lectura(sinNota)))
    }

    @Test
    fun la_historia_solo_marca_lo_del_paciente_de_la_historia() {
        val h = historia
        val traido = clavesDeLaHistoria(h) + "hcc-a5002-hta" + "hcc-c7003" + "hcc-c7003:habitos:texto" + "hcc-c7003:examen_fisico:peso"
        val r = marcarAusentes(traido, emptySet(), lectura(historiaVacia()))
        assertTrue("hcc-a5002-hta" !in r.marcar, "antecedente de otro paciente")
        assertTrue("hcc-c7003" !in r.marcar, "las consultas son de la lista")
        assertTrue("hcc-c7003:habitos:texto" !in r.marcar && "hcc-c7003:examen_fisico:peso" !in r.marcar, "el contenido es de la consulta")
    }

    @Test
    fun una_historia_de_otro_paciente_no_marca_ni_desmarca() {
        val h = historia
        assertEquals(ninguna, marcarAusentes(clavesDeLaHistoria(h), setOf("hcc-p1744"), lectura(historiaVacia().copy(pacienteId = 5002), paciente = 5001)))
        assertEquals(ninguna, marcarAusentes(clavesDeLaHistoria(h), emptySet(), lectura(historiaVacia(), paciente = 5002)))
    }

    @Test
    fun lo_que_vuelve_en_la_historia_pierde_la_marca() {
        val h = historia
        val marcadas = setOf("hcc-rveda-1797", "hcc-p1744", "hcc-a5001-hta", "hcc-c7003:motivo_consulta:texto")
        assertEquals(MarcasAusentes(emptySet(), marcadas), marcarAusentes(clavesDeLaHistoria(h), marcadas, lectura(h)))
        // Y la que sigue ausente conserva la marca (ni se repite ni se quita).
        val sinPendiente = h.copy(pendientes = null)
        assertEquals(desmarcar("hcc-rveda-1797"), marcarAusentes(clavesDeLaHistoria(h), setOf("hcc-rveda-1797", "hcc-p1744"), lectura(sinPendiente)))
    }

    @Test
    fun una_historia_con_error_no_marca_nada() {
        assertEquals(ninguna, marcarAusentes(clavesDeLaHistoria(historia), setOf("hcc-p1744"), LecturaWeb.ConError("500")))
    }

    // --- Nada se borra ---

    @Test
    fun el_resultado_solo_dice_que_marcar_y_que_desmarcar_nunca_que_borrar() {
        val r = marcarAusentes(clavesDeLaHistoria(historia), emptySet(), lectura(historiaVacia()))
        // Dos conjuntos de claves de ausencia; no hay nada más que el llamador pueda interpretar como un borrado.
        assertEquals(setOf(r.marcar, r.desmarcar).size, 2)
        assertTrue(r.desmarcar.isEmpty())
        assertEquals(emptyList(), r.marcar.filter { !it.startsWith(PREFIJO_ID_WEB) })
    }
}
