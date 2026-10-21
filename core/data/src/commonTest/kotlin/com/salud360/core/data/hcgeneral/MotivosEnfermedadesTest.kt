package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.HcPacienteCandidato
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Motivos de la sección "Enfermedades" y cuál se muestra (spec 003, RF-9 a RF-14, RF-30, RF-31; PD2, PD3). Los
 * generales salen de [motivoHc], con el mismo texto que el resto de la HC general. Pacientes inventados.
 */
class MotivosEnfermedadesTest {

    private val nombreHc = "Clínica"

    private fun error(status: Int, codigo: String?, mensaje: String = "Mensaje de la web") =
        TobbException(status, codigo, mensaje)

    private fun deError(status: Int, codigo: String?, mensaje: String = "Mensaje de la web") =
        motivoDeError(error(status, codigo, mensaje), nombreHc)

    private val candidatas = listOf(
        HcPacienteCandidato(id = 7, nombre = "ANA", apellido = "PRUEBA", dni = "30111222", fechaNacimiento = "1990-01-02"),
        HcPacienteCandidato(id = 9, nombre = "ANA", apellido = "PRUEBA", dni = "30111222", fechaNacimiento = "1990-01-02"),
    )

    // RF-9, RF-10: el vínculo

    @Test
    fun vinculado_no_es_motivo() {
        assertNull(motivoDeVinculo(ResultadoVinculo.Vinculado(12), nombreHc))
    }

    @Test
    fun elegir_ficha_con_las_candidatas() {
        val m = motivoDeVinculo(ResultadoVinculo.ElegirFicha(candidatas), nombreHc)
        assertEquals(MotivoEnfermedades.ElegirFicha(candidatas), m)
        assertEquals(6, m!!.prioridad)
        assertEquals("Elegí la ficha del paciente", m.texto)
    }

    @Test
    fun documento_invalido_es_sin_ficha_con_el_texto_de_la_002() {
        val m = motivoDeVinculo(ResultadoVinculo.DocumentoInvalido(mensajeDocumentoInvalido("12")), nombreHc)
        assertEquals(MotivoEnfermedades.SinFicha(mensajeDocumentoInvalido("12")), m)
        assertEquals(7, m!!.prioridad)
        assertEquals("El documento «12» no sirve para la historia clínica: corregilo en la ficha del paciente", m.texto)
    }

    @Test
    fun ficha_de_otro_paciente_es_sin_ficha_con_el_texto_de_la_002() {
        val resultado = resultadoDe(error(409, "paciente_ya_vinculado", "Lo que diga la web"))
        val m = motivoDeVinculo(resultado, nombreHc)
        assertIs<MotivoEnfermedades.SinFicha>(m)
        assertEquals(7, m.prioridad)
        assertEquals(
            "La ficha de la web con ese documento ya corresponde a otro paciente de turnosonlinebb: avisale al administrador",
            m.texto,
        )
    }

    @Test
    fun ficha_no_elegible_es_sin_ficha_con_el_texto_de_rf_10() {
        val m = motivoDeVinculo(ResultadoVinculo.FichaNoElegible, nombreHc)
        assertIs<MotivoEnfermedades.SinFicha>(m)
        assertEquals(7, m.prioridad)
        assertEquals(
            "La ficha elegida no corresponde a este paciente. Elegí otra o pedile al administrador que revise el documento.",
            m.texto,
        )
    }

    @Test
    fun no_se_pudo_pasa_por_motivo_de_error() {
        val e = error(403, "licencia_vencida")
        assertEquals(motivoDeError(e, nombreHc), motivoDeVinculo(ResultadoVinculo.NoSePudo(e), nombreHc))
        assertEquals(
            MotivoEnfermedades.General(motivoHc(nombreHc, e)),
            motivoDeVinculo(ResultadoVinculo.NoSePudo(e), nombreHc),
        )
    }

    // RF-11, RF-12: lo que responde la sección

    @Test
    fun paciente_no_encontrado_es_ficha_no_disponible() {
        val m = deError(404, "paciente_no_encontrado")
        assertSame(MotivoEnfermedades.FichaNoDisponible, m)
        assertEquals(8, m.prioridad)
        assertEquals("La ficha de este paciente no está disponible en la web", m.texto)
    }

    @Test
    fun fuera_de_cartera() {
        val m = deError(403, "fuera_de_cartera")
        assertSame(MotivoEnfermedades.FueraDeCartera, m)
        assertEquals(9, m.prioridad)
        assertEquals("Este paciente no está en tu cartera", m.texto)
    }

    // RF-13: los de la HC general, con el mismo texto

    @Test
    fun sesion_hc_medico_y_licencia_como_el_resto_de_la_hc_general() {
        val casos = listOf(
            error(401, "token") to 1,
            error(401, "sin_token") to 1,
            error(403, "hc_no_habilitada") to 2,
            error(403, "medico_no_vinculado") to 3,
            error(403, "licencia_vencida") to 4,
        )
        casos.forEach { (e, prioridad) ->
            val m = motivoDeError(e, nombreHc)
            assertEquals(MotivoEnfermedades.General(motivoHc(nombreHc, e)), m, e.codigo)
            assertEquals(prioridad, m.prioridad, e.codigo)
            assertEquals(motivoHc(nombreHc, e).texto, m.texto, e.codigo)
            assertFalse(muestraCopia(m), e.codigo)
        }
    }

    // RF-30, RF-31, PD3: lo no previsto va como sin conexión, con el texto de la web

    @Test
    fun errores_no_previstos_van_en_el_lugar_5_con_el_texto_de_la_web_y_la_copia() {
        val casos = listOf(
            error(500, null, "Server Error") to "Historia clínica de Clínica: Server Error",
            error(500, "enfermedades", "No se pudieron leer las enfermedades.") to
                "Historia clínica de Clínica: No se pudieron leer las enfermedades.",
            error(503, "ocupado", "Otro pedido está trabajando. Probá de nuevo.") to
                "Historia clínica de Clínica: Otro pedido está trabajando. Probá de nuevo.",
            error(0, "respuesta", "Respuesta ilegible") to "Historia clínica de Clínica: Respuesta ilegible",
        )
        casos.forEach { (e, texto) ->
            val m = motivoDeError(e, nombreHc)
            assertIs<MotivoEnfermedades.General>(m, e.codigo)
            assertEquals(MotivoHc.Tipo.OTRO, m.motivo.tipo, e.codigo)
            assertEquals(5, m.prioridad, e.codigo)
            assertEquals(texto, m.texto, e.codigo)
            assertTrue(muestraCopia(m), e.codigo)
        }
    }

    @Test
    fun red_y_tobb_caido_son_sin_conexion_en_el_lugar_5() {
        listOf(error(0, "red", "Failed to fetch"), error(503, "tobb_caido"), error(0, null)).forEach { e ->
            val m = motivoDeError(e, nombreHc)
            assertIs<MotivoEnfermedades.General>(m, e.codigo)
            assertEquals(MotivoHc.Tipo.SIN_CONEXION, m.motivo.tipo, e.codigo)
            assertEquals(5, m.prioridad, e.codigo)
            assertEquals("Historia clínica de Clínica: sin conexión. Intentá de nuevo en un momento.", m.texto, e.codigo)
            assertTrue(muestraCopia(m), e.codigo)
        }
    }

    // RF-14: el primero del orden

    /** Uno por lugar, del 1 al 9; el 5 dos veces (sin conexión y no previsto). */
    private val porLugar: List<MotivoEnfermedades> = listOf(
        deError(401, "token"),
        deError(403, "hc_no_habilitada"),
        deError(403, "medico_no_vinculado"),
        deError(403, "licencia_vencida"),
        deError(0, "red"),
        deError(500, null, "Server Error"),
        MotivoEnfermedades.ElegirFicha(candidatas),
        MotivoEnfermedades.SinFicha(mensajeDocumentoInvalido("12")),
        MotivoEnfermedades.FichaNoDisponible,
        MotivoEnfermedades.FueraDeCartera,
    )

    @Test
    fun cada_motivo_en_su_lugar() {
        assertEquals(listOf(1, 2, 3, 4, 5, 5, 6, 7, 8, 9), porLugar.map { it.prioridad })
    }

    @Test
    fun de_a_pares_gana_el_de_menor_lugar_en_cualquier_orden() {
        for (i in porLugar.indices) for (j in porLugar.indices) {
            val a = porLugar[i]
            val b = porLugar[j]
            if (a.prioridad == b.prioridad) continue
            val esperado = if (a.prioridad < b.prioridad) a else b
            assertEquals(esperado, primerMotivo(listOf(a, b)), "$i, $j")
        }
    }

    @Test
    fun todos_juntos_gana_la_sesion() {
        assertEquals(porLugar.first(), primerMotivo(porLugar.reversed()))
    }

    @Test
    fun sin_motivos_no_hay_motivo() {
        assertNull(primerMotivo(emptyList()))
    }

    @Test
    fun solo_el_lugar_5_muestra_la_copia() {
        assertEquals(
            listOf(false, false, false, false, true, true, false, false, false, false),
            porLugar.map { muestraCopia(it) },
        )
    }
}
