package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.MedicoPreferencia
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La lista de consultas de la HC general (RF-38, RF-44 a RF-46, RF-63): grupos, origen, de quién es y la marca «Ya no figura
 * en la web». Es lo que la pantalla muestra sin decidir nada (constitución, punto 4).
 */
class ConsultaWebTest {
    private val yo = "m-sesion"
    private val paciente = "p1"

    private fun web(
        idWeb: Long, fecha: String, estado: EstadoConsulta = EstadoConsulta.CERRADA, medico: String = yo, tipo: String = "control",
    ) = Consulta(
        id = idConsultaWeb(idWeb), pacienteId = paciente, medicoId = medico, especialidad = "clinica", tipo = tipo,
        fecha = LocalDate.parse(fecha), estado = estado, edadMostrar = "40 años", remotoId = idWeb.toString(),
    )

    private fun local(id: String, fecha: String, estado: EstadoConsulta = EstadoConsulta.CERRADA, medico: String = yo) = Consulta(
        id = id, pacienteId = paciente, medicoId = medico, especialidad = "clinica", tipo = "control",
        fecha = LocalDate.parse(fecha), estado = estado,
    )

    private val l1 = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
    private val l2 = "8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f"

    private fun ids(l: List<ConsultaDeLaLista>) = l.map { it.consulta.id }

    // ---------- Grupos ----------

    @Test
    fun separa_abiertas_en_la_web_consulta_en_curso_anteriores_y_solo_en_este_dispositivo() {
        val g = agruparConsultas(
            listOf(
                web(7010, "2026-10-10", EstadoConsulta.ABIERTA),
                local(l1, "2026-10-09", EstadoConsulta.ABIERTA),
                web(7003, "2026-10-01"),
                local(l2, "2026-09-20"),
                web(7001, "2026-08-15"),
            ),
            yo,
        )
        assertEquals(listOf(idConsultaWeb(7010)), ids(g.abiertasEnLaWeb))
        assertEquals(listOf(l1), ids(g.enCurso), "la consulta en curso es solo la del dispositivo")
        assertEquals(listOf(idConsultaWeb(7003), l2, idConsultaWeb(7001)), ids(g.anteriores), "cerradas, de las dos orígenes, de la más nueva a la más vieja")
        assertEquals(listOf(l2), ids(g.soloEnEsteDispositivo), "las cerradas del dispositivo, vistas aparte: salen de las anteriores")
    }

    @Test
    fun cada_consulta_lleva_su_origen_y_si_es_de_solo_lectura() {
        val g = agruparConsultas(
            listOf(web(7010, "2026-10-10", EstadoConsulta.ABIERTA), local(l1, "2026-10-09", EstadoConsulta.ABIERTA), web(7003, "2026-10-01"), local(l2, "2026-09-20")),
            yo,
        )
        val abierta = g.abiertasEnLaWeb.single()
        assertEquals(OrigenConsulta.WEB, abierta.origen)
        assertTrue(abierta.soloLectura)
        assertTrue(abierta.abiertaEnLaWeb, "RF-44")
        val enCurso = g.enCurso.single()
        assertEquals(OrigenConsulta.SOLO_EN_ESTE_DISPOSITIVO, enCurso.origen)
        assertFalse(enCurso.soloLectura, "la del dispositivo se sigue continuando y anulando")
        assertFalse(enCurso.abiertaEnLaWeb)
        val cerradaWeb = g.anteriores.first { it.consulta.id == idConsultaWeb(7003) }
        assertEquals(OrigenConsulta.WEB, cerradaWeb.origen)
        assertTrue(cerradaWeb.soloLectura)
        assertFalse(cerradaWeb.abiertaEnLaWeb, "cerrada: no es «abierta en la web»")
        val cerradaLocal = g.anteriores.first { it.consulta.id == l2 }
        assertEquals(OrigenConsulta.SOLO_EN_ESTE_DISPOSITIVO, cerradaLocal.origen)
        assertFalse(cerradaLocal.soloLectura)
    }

    @Test
    fun una_consulta_con_remoto_id_o_id_hcc_es_de_la_web_y_ninguna_otra() {
        assertEquals(OrigenConsulta.WEB, web(1, "2026-10-01").origen())
        assertEquals(OrigenConsulta.WEB, local("hcc-c9", "2026-10-01").origen(), "por el prefijo del id")
        assertEquals(OrigenConsulta.WEB, local(l1, "2026-10-01").copy(remotoId = "55").origen(), "por el remoto_id")
        assertEquals(OrigenConsulta.SOLO_EN_ESTE_DISPOSITIVO, local(l1, "2026-10-01").origen())
    }

    @Test
    fun una_abierta_del_dispositivo_nunca_pasa_a_las_abiertas_en_la_web_ni_al_reves() {
        val g = agruparConsultas(listOf(web(7010, "2026-10-10", EstadoConsulta.ABIERTA), local(l1, "2026-10-09", EstadoConsulta.ABIERTA)), yo)
        assertTrue(g.enCurso.none { it.origen == OrigenConsulta.WEB }, "RF-44: la de la web no se ofrece como consulta en curso")
        assertTrue(g.abiertasEnLaWeb.all { it.origen == OrigenConsulta.WEB })
    }

    @Test
    fun las_anuladas_no_se_listan_y_sin_consultas_todo_queda_vacio() {
        val g = agruparConsultas(listOf(local(l1, "2026-10-09", EstadoConsulta.ANULADA), web(7003, "2026-10-01")), yo)
        assertEquals(listOf(idConsultaWeb(7003)), ids(g.anteriores))
        val vacio = agruparConsultas(emptyList(), yo)
        assertEquals(emptyList(), vacio.abiertasEnLaWeb)
        assertEquals(emptyList(), vacio.enCurso)
        assertEquals(emptyList(), vacio.anteriores)
        assertEquals(emptyList(), vacio.soloEnEsteDispositivo)
    }

    @Test
    fun con_dos_abiertas_del_dispositivo_ninguna_se_oculta() {
        // Una base migrada de hepatología puede tener las dos (8.sqm no las pisa): nunca se esconde una consulta.
        val g = agruparConsultas(listOf(local(l1, "2026-10-09", EstadoConsulta.ABIERTA), local(l2, "2026-10-02", EstadoConsulta.ABIERTA)), yo)
        assertEquals(listOf(l1, l2), ids(g.enCurso))
    }

    // ---------- Orden ----------

    @Test
    fun ordena_por_fecha_de_la_mas_nueva_a_la_mas_vieja_en_cada_grupo() {
        val g = agruparConsultas(
            listOf(web(7001, "2026-08-15"), web(7003, "2026-10-01"), web(7002, "2026-09-01"), web(7011, "2026-10-10", EstadoConsulta.ABIERTA), web(7010, "2026-10-12", EstadoConsulta.ABIERTA)),
            yo,
        )
        assertEquals(listOf(idConsultaWeb(7003), idConsultaWeb(7002), idConsultaWeb(7001)), ids(g.anteriores))
        assertEquals(listOf(idConsultaWeb(7010), idConsultaWeb(7011)), ids(g.abiertasEnLaWeb))
    }

    @Test
    fun el_mismo_dia_respeta_el_orden_en_que_vienen_que_es_el_de_la_base_y_no_compara_ids_como_texto() {
        // La base no guarda `created_at`: las consultas se escriben de la más vieja a la más nueva y la lista llega por
        // (fecha, updated_at) descendente. Dentro de un mismo día manda ese orden; comparar «hcc-c9» con «hcc-c10» como
        // texto pondría la 9 antes que la 10.
        val g = agruparConsultas(listOf(web(10, "2026-10-01"), web(9, "2026-10-01"), local(l1, "2026-10-01"), web(8, "2026-10-01")), yo)
        assertEquals(listOf(idConsultaWeb(10), idConsultaWeb(9), l1, idConsultaWeb(8)), ids(g.anteriores))
    }

    @Test
    fun aunque_llegue_desordenada_la_fecha_manda() {
        val g = agruparConsultas(listOf(web(1, "2026-01-01"), web(2, "2026-12-01"), web(3, "2026-06-01")), yo)
        assertEquals(listOf(idConsultaWeb(2), idConsultaWeb(3), idConsultaWeb(1)), ids(g.anteriores))
    }

    // ---------- De quién es (Q4, RF-45) ----------

    @Test
    fun la_propia_no_lleva_el_nombre_de_nadie() {
        val c = agruparConsultas(listOf(web(7003, "2026-10-01", medico = yo)), yo).anteriores.single()
        assertTrue(c.propia)
        assertNull(c.autor)
        assertNull(c.cargadaPor)
        assertEquals(listOf(ETIQUETA_WEB), c.etiquetas)
    }

    @Test
    fun la_de_otro_medico_dice_quien_la_cargo_si_se_sabe() {
        val otro = idMedicoWeb(14)
        val c = agruparConsultas(listOf(web(7003, "2026-10-01", medico = otro)), yo, mapOf(otro to "Prueba Salud360 Médica Uno")).anteriores.single()
        assertFalse(c.propia)
        assertEquals("Prueba Salud360 Médica Uno", c.autor)
        assertEquals("Cargada por Prueba Salud360 Médica Uno", c.cargadaPor)
        assertEquals(listOf(ETIQUETA_WEB, "Cargada por Prueba Salud360 Médica Uno"), c.etiquetas)
        assertTrue(c.soloLectura)
    }

    @Test
    fun la_de_otro_medico_sin_nombre_dice_otro_medico() {
        val otro = idMedicoWeb(14)
        listOf(emptyMap(), mapOf(otro to ""), mapOf(otro to "   "), mapOf(idMedicoWeb(99) to "Otra")).forEach { nombres ->
            val c = agruparConsultas(listOf(web(7003, "2026-10-01", medico = otro)), yo, nombres).anteriores.single()
            assertNull(c.autor, "$nombres")
            assertEquals("Cargada por otro médico", c.cargadaPor, "$nombres")
        }
        val sinId = agruparConsultas(listOf(web(7003, "2026-10-01", medico = MEDICO_WEB_DESCONOCIDO)), yo).anteriores.single()
        assertEquals("Cargada por otro médico", sinId.cargadaPor, "la web no informó quién fue")
    }

    @Test
    fun el_nombre_se_recorta_pero_no_se_cambia() {
        val otro = idMedicoWeb(14)
        val c = agruparConsultas(listOf(web(7003, "2026-10-01", medico = otro)), yo, mapOf(otro to "  Ñandú Müller  ")).anteriores.single()
        assertEquals("Ñandú Müller", c.autor)
    }

    @Test
    fun la_consulta_del_dispositivo_de_otro_medico_no_lleva_etiqueta_de_autor() {
        // El nombre de un médico solo se guarda para los de la web (`hcc-m<id>`): lo del dispositivo se muestra como hoy.
        val c = agruparConsultas(listOf(local(l1, "2026-10-01", medico = "m-otro")), yo).anteriores.single()
        assertNull(c.cargadaPor)
        assertEquals(listOf(ETIQUETA_SOLO_EN_ESTE_DISPOSITIVO), c.etiquetas)
    }

    @Test
    fun los_nombres_de_los_medicos_de_la_web_salen_de_medico_preferencia() {
        val nombres = nombresDeMedicosWeb(
            listOf(
                MedicoPreferencia(idMedicoWeb(14), CLAVE_NOMBRE_MEDICO_WEB, "Prueba Salud360 Médica Uno"),
                MedicoPreferencia(idMedicoWeb(15), CLAVE_NOMBRE_MEDICO_WEB, "  "),
                MedicoPreferencia(idMedicoWeb(16), "otra_clave", "No es un nombre"),
                MedicoPreferencia("m-sesion", CLAVE_NOMBRE_MEDICO_WEB, "No es de la web"),
            ),
        )
        assertEquals(mapOf(idMedicoWeb(14) to "Prueba Salud360 Médica Uno"), nombres)
    }

    // ---------- Origen y etiquetas ----------

    @Test
    fun las_etiquetas_dicen_el_origen() {
        val g = agruparConsultas(listOf(web(7003, "2026-10-01"), local(l2, "2026-09-20")), yo)
        assertEquals(listOf(ETIQUETA_WEB), g.anteriores.first { it.origen == OrigenConsulta.WEB }.etiquetas)
        assertEquals(listOf(ETIQUETA_SOLO_EN_ESTE_DISPOSITIVO), g.anteriores.first { it.origen != OrigenConsulta.WEB }.etiquetas)
        assertEquals("Web", ETIQUETA_WEB)
        assertEquals("Solo en este dispositivo", ETIQUETA_SOLO_EN_ESTE_DISPOSITIVO)
    }

    // ---------- Ya no figura en la web (RF-63) ----------

    @Test
    fun la_consulta_marcada_sigue_en_su_grupo_y_lleva_la_etiqueta() {
        val ausencias = AusenciasEnLaWeb(
            mapOf(idConsultaWeb(7003) to "2026-10-20", idConsultaWeb(7010) to "2026-10-21"),
        )
        val g = agruparConsultas(
            listOf(web(7010, "2026-10-10", EstadoConsulta.ABIERTA), web(7003, "2026-10-01"), web(7001, "2026-08-15"), local(l2, "2026-09-20")),
            yo, ausencias = ausencias,
        )
        // Cada una en el grupo en que estaría sin la marca, en el mismo orden.
        assertEquals(listOf(idConsultaWeb(7010)), ids(g.abiertasEnLaWeb))
        assertEquals(listOf(idConsultaWeb(7003), l2, idConsultaWeb(7001)), ids(g.anteriores))
        val marcada = g.anteriores.first { it.consulta.id == idConsultaWeb(7003) }
        assertTrue(marcada.ausente)
        assertEquals("2026-10-20", marcada.ausenteDesde)
        assertEquals(listOf(ETIQUETA_WEB, YA_NO_FIGURA_EN_LA_WEB), marcada.etiquetas, "«Ya no figura en la web» al lado de «Web»")
        val abierta = g.abiertasEnLaWeb.single()
        assertTrue(abierta.ausente, "una abierta que desapareció sigue en las abiertas")
        assertTrue(abierta.abiertaEnLaWeb)
        // Las demás, sin marca.
        assertFalse(g.anteriores.first { it.consulta.id == idConsultaWeb(7001) }.ausente)
        assertFalse(g.anteriores.first { it.consulta.id == l2 }.ausente)
    }

    @Test
    fun marcar_una_consulta_no_cambia_nada_mas_de_la_lista() {
        val consultas = listOf(web(7010, "2026-10-10", EstadoConsulta.ABIERTA), web(7003, "2026-10-01"), local(l1, "2026-09-30", EstadoConsulta.ABIERTA), local(l2, "2026-09-20"))
        val sin = agruparConsultas(consultas, yo)
        val con = agruparConsultas(consultas, yo, ausencias = AusenciasEnLaWeb(consultas.associate { it.id to "2026-10-20" }))
        assertEquals(ids(sin.abiertasEnLaWeb), ids(con.abiertasEnLaWeb))
        assertEquals(ids(sin.enCurso), ids(con.enCurso))
        assertEquals(ids(sin.anteriores), ids(con.anteriores))
        assertEquals(sin.anteriores.map { it.consulta }, con.anteriores.map { it.consulta }, "la consulta misma no se toca")
    }

    @Test
    fun una_consulta_del_dispositivo_nunca_lleva_la_marca_de_la_web() {
        val c = agruparConsultas(listOf(local(l2, "2026-09-20")), yo, ausencias = AusenciasEnLaWeb(mapOf(l2 to "2026-10-20"))).anteriores.single()
        assertFalse(c.ausente)
        assertNull(c.ausenteDesde)
        assertEquals(listOf(ETIQUETA_SOLO_EN_ESTE_DISPOSITIVO), c.etiquetas)
    }

    @Test
    fun las_etiquetas_van_en_orden_origen_ausente_autor() {
        val otro = idMedicoWeb(14)
        val c = agruparConsultas(
            listOf(web(7003, "2026-10-01", medico = otro)), yo, mapOf(otro to "Prueba Salud360 Médica Uno"),
            AusenciasEnLaWeb(mapOf(idConsultaWeb(7003) to "2026-10-20")),
        ).anteriores.single()
        assertEquals(listOf("Web", "Ya no figura en la web", "Cargada por Prueba Salud360 Médica Uno"), c.etiquetas)
    }
}
