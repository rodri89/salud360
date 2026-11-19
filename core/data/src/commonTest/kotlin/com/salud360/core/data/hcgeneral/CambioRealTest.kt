package com.salud360.core.data.hcgeneral

import com.salud360.core.data.hcgeneral.CambioReal.ESCRIBIR
import com.salud360.core.data.hcgeneral.CambioReal.NINGUNO
import com.salud360.core.data.hcgeneral.CambioReal.VACIAR
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005 RF-68, RF-69 y D27 (T18): cuándo un guardado es un cambio de verdad. El autoguardado corre cada 600 ms y guarda
 * todo lo que hay en pantalla; solo un cambio real marca algo para enviar. Un vacío que el médico no tocó no es un cambio
 * (nunca se envía, constitución punto 3); vaciar un campo que tenía texto sí lo es, y se envía vacío.
 */
class CambioRealTest {

    // ---------- Texto ----------

    @Test
    fun vacio_sobre_nada_no_es_un_cambio() {
        assertEquals(NINGUNO, cambioReal(null, ""))
        assertEquals(NINGUNO, cambioReal("", ""))
        assertEquals(NINGUNO, cambioReal(null, "   "), "en blanco también es vacío")
        assertEquals(NINGUNO, cambioReal("  ", ""))
    }

    @Test
    fun vacio_sobre_texto_es_un_vaciado_a_proposito() {
        assertEquals(VACIAR, cambioReal("Control de rutina", ""))
        assertEquals(VACIAR, cambioReal("Control de rutina", "   "))
        assertEquals(VACIAR, cambioReal("0", ""), "un 0 en un texto es un dato")
    }

    @Test
    fun igual_al_anterior_no_cambia() {
        assertEquals(NINGUNO, cambioReal("Control de rutina", "Control de rutina"))
        assertEquals(NINGUNO, cambioReal("línea 1\nlínea 2 ñandú 😀", "línea 1\nlínea 2 ñandú 😀"))
        assertEquals(NINGUNO, cambioReal("  con espacios  ", "  con espacios  "), "los textos se comparan tal cual vinieron")
    }

    @Test
    fun texto_nuevo_o_distinto_es_un_cambio() {
        assertEquals(ESCRIBIR, cambioReal(null, "Control"))
        assertEquals(ESCRIBIR, cambioReal("", "Control"))
        assertEquals(ESCRIBIR, cambioReal("Control", "Control de rutina"))
        assertEquals(ESCRIBIR, cambioReal("Control", "Control "), "un espacio de más es otro texto")
        assertEquals(ESCRIBIR, cambioReal(null, "0"), "en un texto, 0 es un dato")
        assertEquals(ESCRIBIR, cambioReal(null, "0.00"), "el 0.00 como vacío es solo de las medidas")
    }

    // ---------- Medidas del examen físico ----------

    @Test
    fun cero_coma_cero_cero_es_vacio_en_las_medidas() {
        assertTrue(esVacioDeMedida("0.00"))
        assertTrue(esVacioDeMedida(""))
        assertTrue(esVacioDeMedida(null))
        assertTrue(esVacioDeMedida("  "))
        assertEquals(NINGUNO, cambioRealDeMedida(null, "0.00"))
        assertEquals(NINGUNO, cambioRealDeMedida("", "0.00"))
        assertEquals(NINGUNO, cambioRealDeMedida("0.00", ""))
        assertEquals(NINGUNO, cambioRealDeMedida("0.00", "0.00"))
        assertEquals(VACIAR, cambioRealDeMedida("70", "0.00"))
        assertEquals(ESCRIBIR, cambioRealDeMedida("0.00", "70"))
    }

    @Test
    fun cero_y_cero_coma_cero_son_datos_en_las_medidas() {
        assertFalse(esVacioDeMedida("0"))
        assertFalse(esVacioDeMedida("0.0"))
        assertEquals(ESCRIBIR, cambioRealDeMedida(null, "0"))
        assertEquals(ESCRIBIR, cambioRealDeMedida(null, "0.0"))
        assertEquals(ESCRIBIR, cambioRealDeMedida("0.00", "0"))
        assertEquals(VACIAR, cambioRealDeMedida("0", ""))
        assertEquals(VACIAR, cambioRealDeMedida("0.0", "0.00"))
        assertEquals(NINGUNO, cambioRealDeMedida("0", "0"))
    }

    // ---------- Una sección entera ----------

    @Test
    fun de_una_seccion_solo_se_devuelven_los_campos_que_cambiaron_de_verdad() {
        val antes = mapOf("texto" to "Control", "otro" to "igual")
        assertEquals(emptyMap(), cambiosRealesDeSeccion(antes, antes))
        assertEquals(emptyMap(), cambiosRealesDeSeccion(emptyMap(), mapOf("texto" to "", "otro" to " ")))
        assertEquals(
            mapOf("texto" to VACIAR, "nuevo" to ESCRIBIR, "otro2" to ESCRIBIR),
            cambiosRealesDeSeccion(antes, mapOf("texto" to "", "otro" to "igual", "nuevo" to "x", "otro2" to "y", "vacio" to "")),
        )
    }

    // ---------- Examen físico ----------

    private val consulta = "c1"

    @Test
    fun un_examen_vacio_sobre_nada_no_es_un_cambio() {
        assertFalse(cambioRealDelExamen(null, ExamenFisico(consulta)))
        assertFalse(cambioRealDelExamen(null, ExamenFisico(consulta, peso = "0.00", talla = "0.00", tensionArterial = "0.00", imc = "0.00")))
        assertFalse(cambioRealDelExamen(ExamenFisico(consulta), ExamenFisico(consulta, peso = "0.00")))
    }

    @Test
    fun el_examen_con_un_valor_nuevo_o_distinto_o_vaciado_es_un_cambio() {
        assertTrue(cambioRealDelExamen(null, ExamenFisico(consulta, peso = "70")))
        assertTrue(cambioRealDelExamen(null, ExamenFisico(consulta, peso = "0")), "0 es un dato")
        assertTrue(cambioRealDelExamen(null, ExamenFisico(consulta, talla = "0.0")), "0.0 es un dato")
        val antes = ExamenFisico(consulta, peso = "70", talla = "170", tensionArterial = "120/80", imc = "24.2", nota = "ok")
        assertTrue(cambioRealDelExamen(antes, antes.copy(peso = "71")))
        assertTrue(cambioRealDelExamen(antes, antes.copy(peso = "")), "vaciar a propósito")
        assertTrue(cambioRealDelExamen(antes, antes.copy(peso = "0.00")), "0.00 sobre un peso es vaciarlo")
        assertTrue(cambioRealDelExamen(antes, antes.copy(nota = "")))
        assertTrue(cambioRealDelExamen(antes, antes.copy(frecuenciaCardiaca = "72")), "cualquier campo del examen cuenta")
    }

    @Test
    fun el_mismo_examen_no_es_un_cambio() {
        val antes = ExamenFisico(consulta, peso = "70", talla = "170", tensionArterial = "120/80", imc = "24.2", nota = "ok")
        assertFalse(cambioRealDelExamen(antes, antes))
        assertFalse(cambioRealDelExamen(antes, antes.copy()))
        // El mismo examen con las medidas vacías escritas de otra forma.
        assertFalse(cambioRealDelExamen(antes.copy(imc = ""), antes.copy(imc = "0.00")))
    }

    // ---------- Antecedentes ----------

    private fun antecedente(flag: Boolean, detalle: String) = Antecedente("a1", "p1", "clinica", "familiares", "hta", flag, detalle)

    @Test
    fun una_casilla_sin_marcar_y_sin_detalle_sobre_nada_no_es_un_cambio() {
        assertEquals(NINGUNO, cambioRealDeAntecedente(null, false, ""))
        assertEquals(NINGUNO, cambioRealDeAntecedente(null, false, "   "))
        assertEquals(NINGUNO, cambioRealDeAntecedente(antecedente(false, ""), false, ""))
    }

    @Test
    fun marcar_o_escribir_un_detalle_es_un_cambio() {
        assertEquals(ESCRIBIR, cambioRealDeAntecedente(null, true, ""))
        assertEquals(ESCRIBIR, cambioRealDeAntecedente(null, false, "madre"))
        assertEquals(ESCRIBIR, cambioRealDeAntecedente(antecedente(true, "madre"), true, "madre y tío"))
        assertEquals(ESCRIBIR, cambioRealDeAntecedente(antecedente(false, ""), true, ""))
    }

    @Test
    fun desmarcar_y_borrar_el_detalle_de_algo_cargado_es_un_vaciado_a_proposito() {
        assertEquals(VACIAR, cambioRealDeAntecedente(antecedente(true, "madre"), false, ""))
        assertEquals(VACIAR, cambioRealDeAntecedente(antecedente(true, ""), false, ""))
        assertEquals(VACIAR, cambioRealDeAntecedente(antecedente(false, "madre"), false, ""))
    }

    // ---------- Filas de registro ----------

    private fun registro(vararg campos: Pair<String, String>, fecha: String? = "2026-11-01", activo: Boolean = true) =
        RegistroClinico("r1", "p1", "c1", "clinica", "examen_complementario", fecha?.let { LocalDate.parse(it) }, mapOf(*campos), activo)

    @Test
    fun una_fila_nueva_siempre_se_escribe() {
        assertTrue(cambioRealDeRegistro(null, registro()), "agregar es una acción del médico")
    }

    @Test
    fun la_misma_fila_o_con_campos_vacios_de_mas_no_es_un_cambio() {
        val antes = registro("evaluacion" to "pedido", "respuesta" to "")
        assertFalse(cambioRealDeRegistro(antes, antes))
        assertFalse(cambioRealDeRegistro(antes, registro("evaluacion" to "pedido")), "un campo vacío es lo mismo que no estar")
        assertFalse(cambioRealDeRegistro(registro("evaluacion" to "pedido"), registro("evaluacion" to "pedido", "respuesta" to "  ")))
    }

    @Test
    fun cambiar_un_campo_la_fecha_o_vaciar_algo_cargado_es_un_cambio() {
        val antes = registro("evaluacion" to "pedido", "respuesta" to "normal")
        assertTrue(cambioRealDeRegistro(antes, registro("evaluacion" to "pedido", "respuesta" to "alterada")))
        assertTrue(cambioRealDeRegistro(antes, registro("evaluacion" to "pedido", "respuesta" to "")), "vaciar a propósito")
        assertTrue(cambioRealDeRegistro(antes, registro("evaluacion" to "pedido")), "campo quitado")
        assertTrue(cambioRealDeRegistro(antes, registro("evaluacion" to "pedido", "respuesta" to "normal", "otro" to "x")))
        assertTrue(cambioRealDeRegistro(antes, antes.copy(fecha = LocalDate.parse("2026-11-02"))))
        assertTrue(cambioRealDeRegistro(antes, antes.copy(fecha = null)))
        assertTrue(cambioRealDeRegistro(antes, antes.copy(activo = false)), "la baja es un cambio")
        assertTrue(cambioRealDeRegistro(antes, antes.copy(consultaId = "c2")), "pasarla a otra consulta es un cambio")
    }

    @Test
    fun el_mismo_antecedente_no_cambia() {
        assertEquals(NINGUNO, cambioRealDeAntecedente(antecedente(true, "madre"), true, "madre"))
        assertEquals(NINGUNO, cambioRealDeAntecedente(antecedente(true, ""), true, ""))
    }
}
