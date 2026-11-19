package com.salud360.core.data.network.hc

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005 RF-12 a RF-15, RF-20 a RF-22, RF-26, RF-34 y RF-69 (T19): los cuerpos que la app manda a la HC general. Puros: sin
 * red, sin reloj. Llevan **solo lo que el médico tocó** —nunca un campo que no tocó (RF-34)—, el vaciado a propósito como `""`
 * (RF-69), y la fecha clínica y la edad que cargó el médico, no las del momento del envío (RF-14, RF-22). Contrato en
 * `md/API_SALUD360_CLINICA.md` de hclinica, «Escritura (E1)».
 */
class PayloadsClinicaTest {
    private fun json(texto: String): JsonObject = Json.parseToJsonElement(texto) as JsonObject

    private val fecha = LocalDate(2026, 10, 1)

    // ---------- Crear ----------

    @Test
    fun crear_lleva_la_clave_el_dispositivo_la_ficha_el_tipo_la_fecha_clinica_y_la_edad() {
        val cuerpo = PayloadsClinica.crear("c:3f2c9d1e", "disp-1", 5001, "control", fecha, "45 años")

        assertEquals(
            json("""{"clave":"c:3f2c9d1e","dispositivo":"disp-1","paciente_id":"5001","tipo":"control","fecha":"2026-10-01","edad_mostrar":"45 años"}"""),
            cuerpo,
        )
    }

    @Test
    fun crear_sin_edad_no_manda_edad_mostrar_ni_ningun_null() {
        for (edad in listOf(null, "", "   ")) {
            val cuerpo = PayloadsClinica.crear("c:x", "disp-1", 5001, "telemedicina", fecha, edad)
            assertEquals(setOf("clave", "dispositivo", "paciente_id", "tipo", "fecha"), cuerpo.keys, "edad=$edad")
            assertTrue(cuerpo.values.none { it is JsonNull })
        }
    }

    @Test
    fun crear_manda_la_fecha_y_la_edad_que_cargo_el_medico_y_dos_envios_son_identicos() {
        // No hay reloj: lo mismo que se guardó en el dispositivo sale igual hoy, mañana o en el reintento (RF-14, RF-15).
        val a = PayloadsClinica.crear("c:x", "disp-1", 5001, "control", LocalDate(2026, 3, 9), "3 años, 2 meses y 5 días")
        val b = PayloadsClinica.crear("c:x", "disp-1", 5001, "control", LocalDate(2026, 3, 9), "3 años, 2 meses y 5 días")
        assertEquals(a, b)
        assertEquals(JsonPrimitive("2026-03-09"), a["fecha"], "la fecha con ceros, AAAA-MM-DD")
        assertEquals(JsonPrimitive("3 años, 2 meses y 5 días"), a["edad_mostrar"])
    }

    @Test
    fun crear_solo_acepta_los_tipos_que_la_web_crea() {
        assertEquals(JsonPrimitive("control"), PayloadsClinica.crear("c:x", "d", 1, "control", fecha, null)["tipo"])
        assertEquals(JsonPrimitive("telemedicina"), PayloadsClinica.crear("c:x", "d", 1, "telemedicina", fecha, null)["tipo"])
        assertFailsWith<IllegalArgumentException> { PayloadsClinica.crear("c:x", "d", 1, "foto", fecha, null) }
        assertFailsWith<IllegalArgumentException> { PayloadsClinica.crear("c:x", "d", 1, "enfermedad", fecha, null) }
    }

    // ---------- Las claves ----------

    @Test
    fun las_claves_de_crear_cerrar_y_baja_son_distintas_y_validas() {
        val id = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
        val claves = listOf(PayloadsClinica.claveDeCrear(id), PayloadsClinica.claveDeCierre(id), PayloadsClinica.claveDeBaja(id))

        assertEquals(listOf("c:$id", "c:$id-cierre", "c:$id-baja"), claves)
        assertEquals(3, claves.toSet().size, "mezclarlas da 422 clave_en_uso")
        val valida = Regex("^c:[A-Za-z0-9._-]{1,180}$")
        assertTrue(claves.all { valida.matches(it) && it.length <= 191 })
    }

    @Test
    fun la_clave_es_estable_entre_reintentos_y_distingue_consultas() {
        assertEquals(PayloadsClinica.claveDeCrear("abc"), PayloadsClinica.claveDeCrear("abc"))
        assertNotEquals(PayloadsClinica.claveDeCrear("abc"), PayloadsClinica.claveDeCrear("abd"))
        assertNotEquals(PayloadsClinica.claveDeCierre("abc"), PayloadsClinica.claveDeBaja("abc"))
    }

    // ---------- Secciones de texto ----------

    @Test
    fun secciones_lleva_solo_las_que_se_tocaron() {
        val cuerpo = PayloadsClinica.secciones(mapOf("nota" to "Control sin novedades"))!!

        assertEquals(json("""{"secciones":{"nota":{"texto":"Control sin novedades"}}}"""), cuerpo)
    }

    @Test
    fun secciones_manda_el_vaciado_a_proposito_como_texto_vacio() {
        val cuerpo = PayloadsClinica.secciones(mapOf("conductas" to "", "nota" to "   ", "motivo_consulta" to "Control"))!!

        assertEquals(
            json("""{"secciones":{"conductas":{"texto":""},"nota":{"texto":""},"motivo_consulta":{"texto":"Control"}}}"""),
            cuerpo,
            "en blanco es vaciar: se manda vacío, nunca null",
        )
    }

    @Test
    fun secciones_manda_el_texto_tal_cual_sin_recortar() {
        val texto = "  línea 1\n\tlínea 2 \"con comillas\" ñandú 😀  "
        val cuerpo = PayloadsClinica.secciones(mapOf("habitos" to texto))!!

        assertEquals(JsonPrimitive(texto), cuerpo.seccion("habitos")["texto"])
    }

    @Test
    fun secciones_manda_las_ocho_de_texto_y_deja_afuera_lo_que_la_web_no_guarda_o_todavia_no_se_envia() {
        val ocho = listOf("motivo_consulta", "datos_subjetivos", "datos_objetivos", "conductas", "nota", "habitos", "diuresis_catarsis", "somnia")
        val cuerpo = PayloadsClinica.secciones(
            ocho.associateWith { "texto de $it" } + mapOf("tabaquismo" to "x", "audio" to "x", "diagnostico" to "x", "alimentacion" to "1|x", "laboratorio_general" to "x"),
        )!!

        assertEquals(ocho.toSet(), (cuerpo["secciones"] as JsonObject).keys)
        assertEquals(setOf("secciones"), cuerpo.keys)
    }

    @Test
    fun secciones_sin_nada_que_mandar_no_arma_ningun_cuerpo() {
        assertNull(PayloadsClinica.secciones(emptyMap()))
        assertNull(PayloadsClinica.secciones(mapOf("tabaquismo" to "x", "audio" to "")))
    }

    // ---------- Examen físico ----------

    @Test
    fun examen_lleva_solo_los_campos_tocados() {
        val cuerpo = PayloadsClinica.examen(mapOf("peso" to "70.5"))!!

        assertEquals(json("""{"peso":"70.5"}"""), cuerpo)
        assertEquals(setOf("peso"), cuerpo.keys, "talla, tensión, IMC y nota no se mandan si no se tocaron")
    }

    @Test
    fun examen_manda_los_cinco_campos_de_la_web_como_texto() {
        val cuerpo = PayloadsClinica.examen(
            mapOf("peso" to "70.5", "talla" to "1.70", "tension_arterial" to "120/80", "imc" to "24.4", "nota" to "ok"),
        )!!

        assertEquals(json("""{"peso":"70.5","talla":"1.70","tension_arterial":"120/80","imc":"24.4","nota":"ok"}"""), cuerpo)
        assertTrue(cuerpo.values.all { it is JsonPrimitive && it.isString }, "todo texto: un número JSON da 422 y 0.00 llegaría como 0")
    }

    @Test
    fun examen_manda_el_vaciado_a_proposito_como_texto_vacio() {
        val cuerpo = PayloadsClinica.examen(mapOf("peso" to "", "talla" to "0.00", "tension_arterial" to "  ", "imc" to "0.00", "nota" to ""))!!

        assertEquals(json("""{"peso":"","talla":"","tension_arterial":"","imc":"","nota":""}"""), cuerpo)
    }

    @Test
    fun examen_cero_y_cero_coma_cero_son_datos_y_en_la_nota_cero_coma_cero_cero_es_un_texto() {
        val cuerpo = PayloadsClinica.examen(mapOf("peso" to "0", "talla" to "0.0", "nota" to "0.00"))!!

        assertEquals(json("""{"peso":"0","talla":"0.0","nota":"0.00"}"""), cuerpo)
    }

    @Test
    fun examen_deja_afuera_lo_que_la_web_no_guarda() {
        val cuerpo = PayloadsClinica.examen(
            mapOf("peso" to "70", "frecuencia_cardiaca" to "72", "circunferencia_abdominal" to "95", "temperatura" to "36", "fc" to "72"),
        )!!

        assertEquals(setOf("peso"), cuerpo.keys)
    }

    @Test
    fun examen_sin_nada_que_mandar_no_arma_ningun_cuerpo() {
        assertNull(PayloadsClinica.examen(emptyMap()))
        assertNull(PayloadsClinica.examen(mapOf("frecuencia_cardiaca" to "72", "circunferencia_abdominal" to "")))
    }

    // ---------- Cerrar y baja ----------

    @Test
    fun cerrar_lleva_la_clave_de_cierre_la_fecha_clinica_y_la_edad_que_cargo_el_medico() {
        val cuerpo = PayloadsClinica.cerrar("c:x-cierre", "disp-1", LocalDate(2026, 11, 2), "46 años")

        assertEquals(json("""{"clave":"c:x-cierre","dispositivo":"disp-1","fecha":"2026-11-02","edad_mostrar":"46 años"}"""), cuerpo)
    }

    @Test
    fun cerrar_sin_edad_no_la_manda_y_la_consulta_conserva_la_que_tenia() {
        for (edad in listOf(null, "", "  ")) {
            val cuerpo = PayloadsClinica.cerrar("c:x-cierre", "disp-1", LocalDate(2026, 11, 2), edad)
            assertEquals(setOf("clave", "dispositivo", "fecha"), cuerpo.keys, "edad=$edad")
        }
    }

    @Test
    fun baja_lleva_solo_la_clave_y_el_dispositivo() {
        val cuerpo = PayloadsClinica.baja("c:x-baja", "disp-1")

        assertEquals(json("""{"clave":"c:x-baja","dispositivo":"disp-1"}"""), cuerpo)
    }

    // ---------- Nunca un null ----------

    @Test
    fun ningun_cuerpo_lleva_null() {
        val cuerpos = listOf(
            PayloadsClinica.crear("c:x", "d", 1, "control", fecha, null),
            PayloadsClinica.secciones(mapOf("nota" to "")),
            PayloadsClinica.examen(mapOf("peso" to "")),
            PayloadsClinica.cerrar("c:x-cierre", "d", fecha, null),
            PayloadsClinica.baja("c:x-baja", "d"),
        )
        for (c in cuerpos) assertTrue("null" !in c.toString().replace("\"null\"", ""), c.toString())
    }

    private fun JsonObject.seccion(id: String) = (this["secciones"] as JsonObject)[id] as JsonObject
}
