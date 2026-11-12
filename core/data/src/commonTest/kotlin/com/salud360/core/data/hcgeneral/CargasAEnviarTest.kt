package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.PayloadsClinica
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.Laboratorio
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 005 RF-41, RF-34 y RF-69 (T82-a): qué manda una carga de laboratorio general o de peso y con qué fecha. **Puro**: sin
 * Compose, sin red, sin base y sin reloj. Dos preguntas, dos funciones:
 * - [fechaAEnviar]: la fecha de la carga o, si no tiene una que la web acepte, la de la consulta. Nunca un marcador de «sin
 *   fecha» (`2000-01-01`, `1000-01-01`): si no hay ninguna válida lo dice ([FechaAEnviar.SinFecha]) para que el plan la deje en
 *   reserva en vez de inventar un texto.
 * - [valoresAEnviar]: al alta, todos los valores con contenido; al actualizar, solo los que cambiaron contra lo último que la web
 *   confirmó de esa carga (`LoEnviado.cargas[remoto_id]`, T84). `""` es un vaciado a propósito (RF-69) y `0`/`0.00` son datos.
 */
class CargasAEnviarTest {
    private val fechaDeLaConsulta = LocalDate(2026, 11, 10)

    private fun consulta(fecha: LocalDate = fechaDeLaConsulta) =
        Consulta(id = "c1", pacienteId = "p1", medicoId = "m1", especialidad = "clinica", tipo = "control", fecha = fecha)

    private fun carga(
        id: String = "l1",
        valores: Map<String, String> = mapOf("hb" to "13.5"),
        tipo: String = TIPO_LABORATORIO_GENERAL,
        fecha: LocalDate = LocalDate(2026, 11, 6),
    ) = Laboratorio(
        id = id, pacienteId = "p1", consultaId = "c1", especialidad = "clinica", tipo = tipo, fecha = fecha, valores = valores,
    )

    // ---------- La fecha ----------

    @Test
    fun la_fecha_de_la_carga_es_la_que_viaja() {
        val enviar = fechaAEnviar(carga(fecha = LocalDate(2026, 11, 6)), consulta())
        assertEquals(FechaAEnviar.Valida(LocalDate(2026, 11, 6), OrigenDeFecha.DE_LA_CARGA), enviar)
    }

    @Test
    fun una_carga_sin_fecha_propia_viaja_con_la_de_la_consulta() {
        // La web dice «sin fecha» con 2000-01-01 y 1000-01-01; ninguna de las dos viaja.
        for (marcador in listOf(LocalDate(2000, 1, 1), LocalDate(1000, 1, 1))) {
            assertEquals(
                FechaAEnviar.Valida(fechaDeLaConsulta, OrigenDeFecha.DE_LA_CONSULTA),
                fechaAEnviar(carga(fecha = marcador), consulta()),
                "marcador $marcador",
            )
        }
    }

    @Test
    fun si_la_consulta_tampoco_tiene_una_fecha_util_lo_dice() {
        // Ni la carga ni la consulta: no hay fecha que mandar (el servidor contesta 422 fecha_invalida). No se inventa nada.
        for (marcador in listOf(LocalDate(2000, 1, 1), LocalDate(1000, 1, 1))) {
            assertEquals(
                FechaAEnviar.SinFecha,
                fechaAEnviar(carga(fecha = marcador), consulta(fecha = marcador)),
                "marcador $marcador en las dos",
            )
        }
    }

    @Test
    fun un_marcador_de_sin_fecha_nunca_viaja() {
        // Lo que se manda es siempre una fecha de calendario real, venga de donde venga.
        val casos = listOf(
            fechaAEnviar(carga(), consulta()),
            fechaAEnviar(carga(fecha = LocalDate(2000, 1, 1)), consulta()),
            fechaAEnviar(carga(fecha = LocalDate(1000, 1, 1)), consulta()),
            fechaAEnviar(carga(fecha = LocalDate(2000, 1, 1)), consulta(fecha = LocalDate(2026, 11, 10))),
        )
        for (enviar in casos) {
            assertTrue(enviar !is FechaAEnviar.Valida || enviar.fecha !in PayloadsClinica.FECHAS_SIN_DATO, "$enviar")
        }
        assertTrue(casos.last() is FechaAEnviar.Valida, "el marcador de la carga no deja a la carga sin fecha")
    }

    @Test
    fun la_fecha_propia_de_una_carga_cualquiera_viaja_tal_cual() {
        // Ninguna fecha con contenido se reemplaza por la de la consulta, aunque la consulta sea posterior.
        val enviar = fechaAEnviar(carga(fecha = LocalDate(2024, 2, 29)), consulta(fecha = LocalDate(2026, 11, 10)))
        assertEquals(FechaAEnviar.Valida(LocalDate(2024, 2, 29), OrigenDeFecha.DE_LA_CARGA), enviar)
    }

    @Test
    fun el_peso_usa_la_misma_regla_de_fecha() {
        val peso = carga(id = "p_1", tipo = TIPO_PESO, valores = mapOf("peso" to "72.5"), fecha = LocalDate(2000, 1, 1))
        assertEquals(FechaAEnviar.Valida(fechaDeLaConsulta, OrigenDeFecha.DE_LA_CONSULTA), fechaAEnviar(peso, consulta()))
    }

    // ---------- Los valores: alta ----------

    @Test
    fun al_alta_viajan_todos_los_valores_con_contenido() {
        val valores = linkedMapOf("hb" to "13.5", "glucemia" to " ", "otro" to "control en 3 meses", "alb_mina" to "")
        assertEquals(
            mapOf("hb" to "13.5", "otro" to "control en 3 meses"),
            valoresAEnviar(valores),
            "en blanco y vacío no viajan; con contenido, sí",
        )
    }

    @Test
    fun al_alta_lo_confirmado_vacio_es_lo_mismo_que_no_haber_confirmado_nada() {
        val valores = mapOf("hb" to "13.5", "glucemia" to "")
        assertEquals(valoresAEnviar(valores), valoresAEnviar(valores, emptyMap()))
    }

    @Test
    fun el_texto_viaja_tal_cual_sin_recortar() {
        val valores = mapOf("otro" to "  control en 3 meses  ", "hb" to " 13.5")
        assertEquals(valores, valoresAEnviar(valores), "un valor con contenido no se toca")
    }

    @Test
    fun el_cero_y_el_cero_coma_dos_ceros_son_datos() {
        // En una carga no hay medidas del examen: 0 y 0.00 son datos y viajan como vinieron.
        assertEquals(mapOf("peso" to "0"), valoresAEnviar(mapOf("peso" to "0")))
        assertEquals(mapOf("peso" to "0.00"), valoresAEnviar(mapOf("peso" to "0.00")))
        assertEquals(mapOf("hb" to "0", "otro" to "0.00"), valoresAEnviar(mapOf("hb" to "0", "otro" to "0.00")))
    }

    // ---------- Los valores: actualización ----------

    @Test
    fun al_actualizar_viaja_solo_lo_que_cambio() {
        val confirmada = mapOf("hb" to "13.5", "glucemia" to "0.95", "alb_mina" to "4.1")
        val valores = mapOf("hb" to "13.5", "glucemia" to "0.98", "alb_mina" to "4.1")
        assertEquals(mapOf("glucemia" to "0.98"), valoresAEnviar(valores, confirmada))
    }

    @Test
    fun sin_cambios_no_viaja_ningun_valor() {
        val valores = mapOf("hb" to "13.5", "glucemia" to "0.95")
        assertEquals(emptyMap(), valoresAEnviar(valores, valores))
    }

    @Test
    fun una_columna_que_ya_no_esta_es_un_vaciado() {
        // La columna se borró en la app (la web solo se puede vaciar): viaja "" y la web la deja sin dato (RF-69).
        val confirmada = mapOf("hb" to "13.5", "glucemia" to "0.95")
        assertEquals(mapOf("glucemia" to ""), valoresAEnviar(mapOf("hb" to "13.5"), confirmada))
    }

    @Test
    fun un_valor_vaciado_a_proposito_viaja_vacio() {
        assertEquals(
            mapOf("glucemia" to ""),
            valoresAEnviar(mapOf("hb" to "13.5", "glucemia" to "  "), mapOf("hb" to "13.5", "glucemia" to "0.95")),
            "en blanco sobre algo que la web tiene es un vaciado",
        )
    }

    @Test
    fun un_vacio_sobre_nada_no_viaja() {
        assertEquals(
            emptyMap(),
            valoresAEnviar(mapOf("hb" to "13.5", "otro" to ""), mapOf("hb" to "13.5")),
            "lo que la web nunca tuvo y no se tocó no se manda",
        )
    }

    @Test
    fun un_cero_sobre_nada_es_un_dato_y_un_cero_sobre_algo_es_un_cambio() {
        assertEquals(mapOf("peso" to "0"), valoresAEnviar(mapOf("peso" to "0"), mapOf("peso" to "")))
        assertEquals(mapOf("peso" to "0.00"), valoresAEnviar(mapOf("peso" to "0.00"), mapOf("peso" to "72.5")))
    }

    @Test
    fun el_orden_de_la_carga_se_conserva_y_los_vaciados_van_al_final() {
        val confirmada = linkedMapOf("hb" to "13.5", "glucemia" to "0.95", "alb_mina" to "4.1", "otro" to "viejo")
        val valores = linkedMapOf("otro" to "nuevo", "hb" to "14", "glucemia" to "0.95", "alb_mina" to "4.1")
        assertEquals(
            listOf("otro", "hb"),
            valoresAEnviar(valores, confirmada).keys.toList(),
            "primero el orden de la carga; lo que solo tenía la web, al final como vaciado",
        )
        assertEquals(emptyMap(), valoresAEnviar(valores, confirmada).filterKeys { it == "glucemia" || it == "alb_mina" })
    }

    @Test
    fun la_funcion_y_el_cuerpo_de_T81_deciden_lo_mismo() {
        // `PayloadsClinica.carga` delega en esta función: plan y cuerpo no pueden divergir.
        val valores = mapOf("hb" to "14", "glucemia" to "", "otro" to "control")
        val confirmada = mapOf("hb" to "13.5", "glucemia" to "0.95")

        val actualizacion = PayloadsClinica.carga(
            clave = PayloadsClinica.claveDeCarga("l1"),
            dispositivo = "disp-1",
            consultaWebId = 2040,
            fecha = fechaDeLaConsulta,
            carga = carga(valores = valores),
            idWeb = 731,
            confirmada = carga(valores = confirmada),
        )
        assertEquals(valoresAEnviar(valores, confirmada), valoresDe(actualizacion))

        val alta = PayloadsClinica.carga(
            clave = PayloadsClinica.claveDeCarga("l2"),
            dispositivo = "disp-1",
            consultaWebId = 2040,
            fecha = fechaDeLaConsulta,
            carga = carga(id = "l2", valores = valores),
        )
        assertEquals(valoresAEnviar(valores), valoresDe(alta))
    }

    private fun valoresDe(cuerpo: JsonObject?): Map<String, String> =
        (cuerpo!!["valores"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
}
