package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005 RF-8, RF-62, RF-64 y RF-65 (T16): de dónde es una consulta de la HC general —propia de la app, del histórico del
 * dispositivo o de la web— y en qué grupo de la historia cae. El origen sale de lo que la consulta guarda (`propia`, el prefijo
 * `hcc-` y el `remoto_id`), no de la sesión ni del reloj, y no cambia al editarla, cerrarla ni enviarla.
 */
class OrigenDeConsultaTest {
    private val yo = "m-sesion"
    private val otro = "hcc-m5"
    private val l1 = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
    private val l2 = "8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f"
    private val l3 = "a1b2c3d4-0000-4000-8000-000000000003"

    private fun consulta(
        id: String, estado: EstadoConsulta = EstadoConsulta.ABIERTA, propia: Boolean = false, remotoId: String = "",
        medico: String = yo, fecha: String = "2026-10-20",
    ) = Consulta(
        id = id, pacienteId = "p1", medicoId = medico, especialidad = "clinica", tipo = "consulta",
        fecha = LocalDate.parse(fecha), estado = estado, edadMostrar = "40 años", remotoId = remotoId, propia = propia,
    )

    private fun ids(l: List<ConsultaDeLaLista>) = l.map { it.consulta.id }

    // ---------- El origen ----------

    @Test
    fun una_consulta_con_la_marca_es_propia_aunque_ya_tenga_su_id_de_la_web() {
        assertEquals(OrigenDeConsulta.PROPIA, consulta(l1, propia = true).origenDeConsulta())
        assertEquals(OrigenDeConsulta.PROPIA, consulta(l1, propia = true, remotoId = "7003").origenDeConsulta(), "RF-62: enviada sigue siendo propia")
    }

    @Test
    fun una_consulta_sin_la_marca_y_sin_id_de_la_web_es_del_historico() {
        assertEquals(OrigenDeConsulta.HISTORICO, consulta(l1).origenDeConsulta())
        assertEquals(OrigenDeConsulta.HISTORICO, consulta(l1, estado = EstadoConsulta.CERRADA).origenDeConsulta())
        assertEquals(OrigenDeConsulta.HISTORICO, consulta(l1, remotoId = "   ").origenDeConsulta(), "un remoto_id en blanco no es un id")
    }

    @Test
    fun una_consulta_traida_de_la_web_es_de_la_web_por_el_prefijo_o_por_el_remoto_id() {
        assertEquals(OrigenDeConsulta.WEB, consulta(idConsultaWeb(7003), remotoId = "7003").origenDeConsulta())
        assertEquals(OrigenDeConsulta.WEB, consulta(idConsultaWeb(7003)).origenDeConsulta(), "por el prefijo")
        assertEquals(OrigenDeConsulta.WEB, consulta(l1, remotoId = "55").origenDeConsulta(), "por el remoto_id, como hasta ahora")
    }

    @Test
    fun la_propia_que_creo_otro_dispositivo_llega_como_de_la_web_y_es_solo_lectura_RF_64() {
        // La lectura la guarda con id `hcc-c<id>` y sin la marca (`consultaDeLaWeb` nunca la pone): para este dispositivo es de la web.
        val deOtroDispositivo = consulta(idConsultaWeb(7010), remotoId = "7010", medico = yo)
        assertEquals(OrigenDeConsulta.WEB, deOtroDispositivo.origenDeConsulta())
        val g = agruparConsultas(listOf(deOtroDispositivo), yo)
        assertEquals(listOf(idConsultaWeb(7010)), ids(g.abiertasEnLaWeb), "RF-65: «Abierta en la web»")
        assertTrue(g.enCurso.isEmpty())
        assertTrue(g.abiertasEnLaWeb.single().soloLectura)
    }

    @Test
    fun una_fila_con_el_prefijo_de_la_web_es_siempre_de_la_web_aunque_traiga_la_marca() {
        assertEquals(OrigenDeConsulta.WEB, consulta(idConsultaWeb(7003), propia = true, remotoId = "7003").origenDeConsulta())
    }

    @Test
    fun editar_cerrar_reabrir_o_enviar_no_cambia_el_origen_RF_8() {
        val historica = consulta(l1)
        val propia = consulta(l2, propia = true)
        listOf(
            { c: Consulta -> c.copy(estado = EstadoConsulta.CERRADA) },
            { c: Consulta -> c.copy(edadMostrar = "41 años", fecha = LocalDate.parse("2026-11-01")) },
            { c: Consulta -> c.copy(estado = EstadoConsulta.ANULADA) },
        ).forEach { cambio ->
            assertEquals(OrigenDeConsulta.HISTORICO, cambio(historica).origenDeConsulta(), "una del histórico que se edita sigue en el histórico")
            assertEquals(OrigenDeConsulta.PROPIA, cambio(propia).origenDeConsulta())
        }
        assertEquals(OrigenDeConsulta.PROPIA, propia.copy(remotoId = "7011").origenDeConsulta(), "al enviarse recibe su remoto_id y sigue propia")
    }

    @Test
    fun solo_la_marca_decide_entre_propia_e_historica_no_la_fecha_ni_el_medico() {
        val vieja = consulta(l1, fecha = "2015-01-01", propia = true)
        val nueva = consulta(l2, fecha = "2026-11-04", propia = false)
        assertEquals(OrigenDeConsulta.PROPIA, vieja.origenDeConsulta())
        assertEquals(OrigenDeConsulta.HISTORICO, nueva.origenDeConsulta())
        assertEquals(OrigenDeConsulta.HISTORICO, consulta(l3, medico = otro).origenDeConsulta(), "el médico no cambia el origen")
    }

    @Test
    fun las_reglas_de_consulta_de_la_web_tienen_en_cuenta_la_marca() {
        val propiaEnviada = consulta(l1, propia = true, remotoId = "7003")
        assertFalse(propiaEnviada.esDeLaWeb(), "una propia enviada tiene remoto_id y no es «de la web»")
        assertFalse(propiaEnviada.esSoloLecturaWeb(backendSoloLectura = true))
        assertFalse(propiaEnviada.esSoloLecturaWeb(backendSoloLectura = false))
        // Sin la marca, todo igual que antes.
        val historicaConRemoto = consulta(l2, remotoId = "9")
        assertTrue(historicaConRemoto.esDeLaWeb())
        assertTrue(historicaConRemoto.esSoloLecturaWeb(backendSoloLectura = true))
        assertFalse(historicaConRemoto.esSoloLecturaWeb(backendSoloLectura = false))
        // El prefijo manda sobre la marca.
        assertTrue(consulta(idConsultaWeb(1), propia = true).esDeLaWeb())
        assertTrue(consulta(idConsultaWeb(1), propia = true).esSoloLecturaWeb(backendSoloLectura = false))
    }

    // ---------- La lista ----------

    @Test
    fun una_propia_abierta_ya_enviada_es_consulta_en_curso_y_no_abierta_en_la_web_RF_62() {
        val g = agruparConsultas(listOf(consulta(l1, propia = true, remotoId = "7011")), yo)

        assertEquals(listOf(l1), ids(g.enCurso))
        assertTrue(g.abiertasEnLaWeb.isEmpty(), "no es «Abierta en la web»")
        val item = g.enCurso.single()
        assertEquals(OrigenDeConsulta.PROPIA, item.origenDeConsulta)
        assertFalse(item.abiertaEnLaWeb)
        assertFalse(item.soloLectura, "se sigue editando, cerrando y anulando")
        assertEquals(OrigenConsulta.SOLO_EN_ESTE_DISPOSITIVO, item.origen, "la vista de dos valores: no es de la web")
    }

    @Test
    fun una_propia_cerrada_pasa_a_las_anteriores_sin_la_marca_de_solo_en_este_dispositivo() {
        val g = agruparConsultas(listOf(consulta(l1, EstadoConsulta.CERRADA, propia = true, remotoId = "7011")), yo)

        assertTrue(g.enCurso.isEmpty() && g.abiertasEnLaWeb.isEmpty())
        val item = g.anteriores.single()
        assertEquals(OrigenDeConsulta.PROPIA, item.origenDeConsulta)
        assertEquals(emptyList(), item.etiquetas, "«Solo en este dispositivo» es del histórico; el estado de envío llega en otras tareas")
        assertTrue(g.soloEnEsteDispositivo.isEmpty(), "las cerradas «Solo en este dispositivo» son las del histórico")
    }

    @Test
    fun el_historico_sigue_rotulado_solo_en_este_dispositivo() {
        val g = agruparConsultas(listOf(consulta(l1, EstadoConsulta.CERRADA), consulta(l2, EstadoConsulta.CERRADA, propia = true)), yo)

        val historica = g.anteriores.first { it.consulta.id == l1 }
        assertEquals(OrigenDeConsulta.HISTORICO, historica.origenDeConsulta)
        assertEquals(listOf(ETIQUETA_SOLO_EN_ESTE_DISPOSITIVO), historica.etiquetas)
        assertEquals(listOf(l1), ids(g.soloEnEsteDispositivo))
    }

    @Test
    fun cada_consulta_abierta_cae_en_su_grupo_cuando_hay_de_los_tres_origenes() {
        val g = agruparConsultas(
            listOf(
                consulta(idConsultaWeb(7010), remotoId = "7010", fecha = "2026-10-25", medico = otro),
                consulta(l1, propia = true, remotoId = "7011", fecha = "2026-10-24"),
                consulta(l2, propia = true, fecha = "2026-10-23"),
                consulta(l3, fecha = "2026-10-22"),
            ),
            yo,
        )

        assertEquals(listOf(idConsultaWeb(7010)), ids(g.abiertasEnLaWeb))
        assertEquals(listOf(l1, l2, l3), ids(g.enCurso), "las propias y la del histórico abierta (como hasta ahora), nunca la de la web")
        assertEquals(listOf(OrigenDeConsulta.PROPIA, OrigenDeConsulta.PROPIA, OrigenDeConsulta.HISTORICO), g.enCurso.map { it.origenDeConsulta })
    }

    @Test
    fun la_lista_de_dos_valores_de_siempre_no_cambia_para_lo_que_no_tiene_la_marca() {
        val g = agruparConsultas(
            listOf(
                consulta(idConsultaWeb(7010), remotoId = "7010", medico = otro),
                consulta(l1),
                consulta(idConsultaWeb(7003), EstadoConsulta.CERRADA, remotoId = "7003"),
            ),
            yo,
        )
        assertEquals(OrigenConsulta.WEB, g.abiertasEnLaWeb.single().origen)
        assertEquals(OrigenConsulta.SOLO_EN_ESTE_DISPOSITIVO, g.enCurso.single().origen)
        assertEquals(OrigenDeConsulta.WEB, g.anteriores.single().origenDeConsulta)
        assertEquals(listOf(ETIQUETA_WEB), g.anteriores.single().etiquetas)
    }

    // ---------- Lo que la lectura hace con una propia ----------

    @Test
    fun fusionar_con_lo_de_la_web_no_pierde_la_marca() {
        val web = consulta(idConsultaWeb(7011), EstadoConsulta.CERRADA, remotoId = "7011").copy(edadMostrar = "41 años")
        val localPropia = consulta(idConsultaWeb(7011), propia = true, remotoId = "7011")
        val localHistorica = consulta(idConsultaWeb(7011), remotoId = "7011")

        assertTrue(fusionarConsulta(web, localPropia)!!.propia, "lo que trae la web (propia = false) no pisa la marca de la fila")
        assertFalse(fusionarConsulta(web, localHistorica)!!.propia)
        assertFalse(fusionarConsulta(web, null)!!.propia, "una fila nueva de la web nunca es propia")
        assertNull(fusionarConsulta(localPropia, localPropia), "igual a lo que ya había: no se reescribe")
        assertNotEquals(fusionarConsulta(web, localPropia), fusionarConsulta(web, localHistorica))
    }
}
