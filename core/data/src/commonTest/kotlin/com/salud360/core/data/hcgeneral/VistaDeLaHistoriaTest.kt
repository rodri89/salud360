package com.salud360.core.data.hcgeneral

import com.salud360.core.data.repos.PacientesHcRepository
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La historia del paciente de la HC general tal como la pantalla la muestra (RF-38, RF-44 a RF-46, RF-49, RF-63, Q9): los
 * grupos de E7.1, las 50 más recientes y «Ver más», y los avisos. La pantalla no decide nada de esto (constitución, punto 4).
 */
class VistaDeLaHistoriaTest {
    private val yo = "m-sesion"
    private val paciente = "p1"

    private fun web(idWeb: Long, fecha: String, estado: EstadoConsulta = EstadoConsulta.CERRADA, medico: String = yo) = Consulta(
        id = idConsultaWeb(idWeb), pacienteId = paciente, medicoId = medico, especialidad = "clinica", tipo = "control",
        fecha = LocalDate.parse(fecha), estado = estado, remotoId = idWeb.toString(),
    )

    private fun local(id: String, fecha: String, estado: EstadoConsulta = EstadoConsulta.CERRADA) = Consulta(
        id = id, pacienteId = paciente, medicoId = yo, especialidad = "clinica", tipo = "control",
        fecha = LocalDate.parse(fecha), estado = estado,
    )

    private val l1 = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
    private val l2 = "8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f"

    private fun extra(clave: String, valor: String, especialidad: String = "clinica") = PacienteExtra(paciente, especialidad, clave, valor)

    private val vinculado = extra(PacientesHcRepository.EXTRA_HC_PACIENTE_ID, "5001")

    /** [n] cerradas de la web, de a un día, la de número 1 es la más vieja. */
    private fun cerradas(n: Int): List<Consulta> =
        (1..n).map { i -> web(8000L + i, LocalDate.parse("2026-01-01").plus(i, DateTimeUnit.DAY).toString()) }

    private fun vista(consultas: List<Consulta>, extras: List<PacienteExtra> = listOf(vinculado), visibles: Int = CONSULTAS_POR_VEZ) =
        vistaDeLaHistoria(consultas, yo, emptyMap(), extras, visibles)

    private fun ids(l: List<ConsultaDeLaLista>) = l.map { it.consulta.id }

    // ---------- Q9: las 50 más recientes y «Ver más» ----------

    @Test
    fun se_ven_las_50_mas_recientes_y_dice_cuantas_quedan() {
        val v = vista(cerradas(120))
        assertEquals(50, v.anteriores.size)
        assertEquals(cerradas(120).sortedByDescending { it.fecha }.take(50).map { it.id }, ids(v.anteriores), "las 50 más nuevas, de la más nueva a la más vieja")
        assertEquals(120, v.totalAnteriores)
        assertEquals(70, v.ocultas)
        assertTrue(v.hayMas)
    }

    @Test
    fun los_botones_dicen_lo_que_dice_la_spec() {
        assertEquals("Ver más", TEXTO_VER_MAS)
        assertEquals("Actualizar", TEXTO_ACTUALIZAR)
    }

    @Test
    fun ver_mas_suma_de_a_50_sobre_lo_ya_traido() {
        assertEquals(50, CONSULTAS_POR_VEZ)
        assertEquals(100, masConsultas(CONSULTAS_POR_VEZ))
        assertEquals(150, masConsultas(100))
        val v = vista(cerradas(120), visibles = masConsultas(CONSULTAS_POR_VEZ))
        assertEquals(100, v.anteriores.size)
        assertEquals(20, v.ocultas)
        assertTrue(v.hayMas)
        val todas = vista(cerradas(120), visibles = masConsultas(masConsultas(CONSULTAS_POR_VEZ)))
        assertEquals(120, todas.anteriores.size)
        assertEquals(0, todas.ocultas)
        assertFalse(todas.hayMas, "con todo a la vista ya no hay «Ver más»")
    }

    @Test
    fun hasta_50_justas_no_ofrecen_ver_mas() {
        val v = vista(cerradas(50))
        assertEquals(50, v.anteriores.size)
        assertEquals(0, v.ocultas)
        assertFalse(v.hayMas)
        assertFalse(vista(emptyList()).hayMas)
    }

    @Test
    fun el_tope_cuenta_las_abiertas_en_la_web_y_las_cerradas_juntas_por_fecha() {
        // 50 cerradas viejas y una abierta de la web más nueva: la abierta entra y la cerrada más vieja queda para «Ver más».
        val viejas = cerradas(50)
        val v = vista(viejas + web(9001, "2026-12-01", EstadoConsulta.ABIERTA))
        assertEquals(listOf(idConsultaWeb(9001)), ids(v.abiertasEnLaWeb))
        assertEquals(49, v.anteriores.size)
        assertEquals(1, v.ocultas)
        assertTrue(idConsultaWeb(8001) !in ids(v.anteriores), "la que queda para «Ver más» es la más vieja")
    }

    @Test
    fun una_abierta_en_la_web_muy_vieja_queda_para_ver_mas_como_cualquier_otra() {
        val v = vista(cerradas(60) + web(9001, "2020-01-01", EstadoConsulta.ABIERTA))
        assertEquals(emptyList(), ids(v.abiertasEnLaWeb))
        assertEquals(11, v.ocultas)
        val todas = vista(cerradas(60) + web(9001, "2020-01-01", EstadoConsulta.ABIERTA), visibles = 100)
        assertEquals(listOf(idConsultaWeb(9001)), ids(todas.abiertasEnLaWeb))
        assertEquals(0, todas.ocultas)
    }

    @Test
    fun la_consulta_en_curso_del_dispositivo_siempre_se_ve_y_no_cuenta_para_el_tope() {
        val v = vista(cerradas(60) + local(l1, "2001-01-01", EstadoConsulta.ABIERTA))
        assertEquals(listOf(l1), ids(v.enCurso), "es la única con Continuar y Anular: no se esconde detrás de «Ver más»")
        assertEquals(50, v.anteriores.size)
        assertEquals(10, v.ocultas)
    }

    @Test
    fun las_del_dispositivo_cerradas_cuentan_como_las_demas() {
        val v = vista(cerradas(50) + local(l2, "2001-01-01"))
        assertEquals(51, v.totalAnteriores)
        assertEquals(1, v.ocultas, "la del dispositivo es la más vieja")
        assertTrue(l2 !in ids(v.anteriores))
        val todas = vista(cerradas(50) + local(l2, "2001-01-01"), visibles = 100)
        assertEquals(listOf(l2), ids(todas.anteriores.filter { it.origen == OrigenConsulta.SOLO_EN_ESTE_DISPOSITIVO }))
    }

    @Test
    fun un_tope_menor_que_cero_no_rompe_y_nada_se_pierde() {
        val v = vista(cerradas(3), visibles = -5)
        assertEquals(0, v.anteriores.size)
        assertEquals(3, v.ocultas)
    }

    @Test
    fun ver_mas_no_cambia_el_orden_ni_repite_lo_ya_visto() {
        val c = cerradas(120)
        val primeras = ids(vista(c).anteriores)
        val dobles = ids(vista(c, visibles = 100).anteriores)
        assertEquals(primeras, dobles.take(50), "lo que ya se veía sigue arriba y en el mismo orden")
        assertEquals(dobles.size, dobles.toSet().size)
    }

    // ---------- Grupos, etiquetas y marcas (E7.1) ----------

    @Test
    fun la_marca_ya_no_figura_en_la_web_se_ve_en_su_grupo_y_con_su_etiqueta() {
        val extras = listOf(vinculado, extra(PREFIJO_MARCA_AUSENTE + idConsultaWeb(7001), "2026-10-20"))
        val v = vista(listOf(web(7003, "2026-10-01"), web(7001, "2026-08-15")), extras)
        assertEquals(listOf(idConsultaWeb(7003), idConsultaWeb(7001)), ids(v.anteriores), "no se oculta ni cambia de lugar")
        assertEquals(listOf("Web"), v.anteriores[0].etiquetas)
        assertEquals(listOf("Web", "Ya no figura en la web"), v.anteriores[1].etiquetas)
    }

    @Test
    fun una_abierta_de_otro_medico_dice_quien_la_cargo() {
        val otro = idMedicoWeb(14)
        val v = vistaDeLaHistoria(
            listOf(web(7010, "2026-10-10", EstadoConsulta.ABIERTA, medico = otro)), yo, mapOf(otro to "Médico Prueba Salud360"), listOf(vinculado),
        )
        assertEquals(listOf("Web", "Cargada por Médico Prueba Salud360"), v.abiertasEnLaWeb.single().etiquetas)
        assertTrue(v.abiertasEnLaWeb.single().soloLectura)
    }

    @Test
    fun las_anuladas_no_se_cuentan_ni_se_listan() {
        val v = vista(listOf(web(7001, "2026-08-15", EstadoConsulta.ANULADA), web(7002, "2026-08-16")))
        assertEquals(listOf(idConsultaWeb(7002)), ids(v.anteriores))
        assertEquals(1, v.totalAnteriores)
    }

    // ---------- Avisos de la historia (RF-43, RF-49, D1) ----------

    @Test
    fun el_paciente_sin_ficha_en_la_web_lo_dice_y_el_vinculado_no() {
        assertEquals(listOf("Este paciente todavía no está vinculado con la historia clínica de la web"), vista(emptyList(), extras = emptyList()).avisos.todos)
        assertEquals(emptyList(), vista(emptyList()).avisos.todos)
    }

    @Test
    fun el_vinculo_es_la_ficha_de_la_hc_general_con_un_numero() {
        assertTrue(vinculadoConLaWeb(listOf(vinculado)))
        assertFalse(vinculadoConLaWeb(emptyList()))
        assertFalse(vinculadoConLaWeb(listOf(extra(PacientesHcRepository.EXTRA_HC_PACIENTE_ID, "no es un número"))))
        assertFalse(vinculadoConLaWeb(listOf(extra(PacientesHcRepository.EXTRA_HC_PACIENTE_ID, "0"))))
        assertFalse(
            vinculadoConLaWeb(listOf(extra(PacientesHcRepository.EXTRA_HC_PACIENTE_ID, "5001", especialidad = "pediatria"))),
            "la ficha de pediatría no vincula con la HC general",
        )
    }

    @Test
    fun las_consultas_omitidas_y_las_cargas_sin_fecha_son_dos_avisos() {
        val v = vista(
            emptyList(),
            listOf(vinculado, extra(CLAVE_OMITIDAS, "2|1|4"), extra(CLAVE_OMITIDAS_LABORATORIO, "3")),
        )
        assertEquals("7 consultas de la web de un tipo que esta versión no muestra", v.avisos.consultasOmitidas)
        assertEquals("3 cargas de laboratorio/peso sin fecha no se muestran", v.avisos.cargasSinFecha)
        assertNull(v.avisos.sinVinculo)
    }

    @Test
    fun los_avisos_no_dependen_de_cuantas_consultas_se_ven() {
        val extras = listOf(vinculado, extra(CLAVE_OMITIDAS, "0|0|2"))
        assertEquals(vista(cerradas(5), extras).avisos, vista(cerradas(120), extras).avisos)
    }
}
