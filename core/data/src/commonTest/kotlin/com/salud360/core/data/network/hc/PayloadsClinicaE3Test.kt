package com.salud360.core.data.network.hc

import com.salud360.core.model.hc.Laboratorio
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005 RF-41, RF-42 y RF-69 (T81): los cuerpos de las cargas de laboratorio general y de peso (E3). Puros: sin red, sin
 * reloj, sin base. Contrato en `plan.md` («Contrato de E3») y en `md/API_SALUD360_CLINICA.md` de hclinica. La clave es
 * `l:<id local>` (estable entre reintentos, RF-15); la `fecha` va siempre y nunca es el marcador de «sin fecha»; al alta
 * viajan todos los valores con contenido y al actualizar solo los tocados (RF-34); el vaciado a propósito sale `""`
 * (RF-69); el peso viaja como `valores: {peso}` (no `valor`, la columna de la web); si no queda nada que mandar **no se
 * arma cuerpo ni se hace pedido**. El laboratorio reducido y los pendientes quedaron fuera de alcance (T73): no se mandan.
 */
class PayloadsClinicaE3Test {
    private fun json(texto: String): JsonObject = Json.parseToJsonElement(texto) as JsonObject

    private val fecha = LocalDate(2026, 11, 6)
    private val idCarga = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
    private val clave = "l:$idCarga"

    private fun carga(
        id: String = idCarga,
        valores: Map<String, String> = mapOf("hb" to "13.5", "otro" to "control en 3 meses"),
        tipo: String = "laboratorio_general",
        fecha: LocalDate = this.fecha,
    ) = Laboratorio(
        id = id, pacienteId = "p1", consultaId = "c1", especialidad = "clinica", tipo = tipo, fecha = fecha, valores = valores,
    )

    private fun armar(
        carga: Laboratorio = carga(),
        idWeb: Long? = null,
        confirmada: Laboratorio? = null,
        fecha: LocalDate = this.fecha,
        tipo: String = carga.tipo,
    ) = PayloadsClinica.carga(clave, "disp-1", 2040, fecha, carga, idWeb, confirmada, tipo)

    private fun valores(cuerpo: JsonObject?): Map<String, String> =
        (cuerpo!!["valores"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }

    // ---------- Las claves ----------

    @Test
    fun la_clave_de_una_carga_es_l_dos_puntos_y_el_id_local_y_sirve_para_el_servidor() {
        assertEquals("l:$idCarga", PayloadsClinica.claveDeCarga(idCarga))
        assertEquals(PayloadsClinica.claveDeCarga(idCarga), PayloadsClinica.claveDeCarga(idCarga), "estable entre reintentos")
        assertTrue(Regex("^l:[A-Za-z0-9._-]+\$").matches(clave) && clave.length <= 191)
        assertNotEquals(PayloadsClinica.claveDeCrear(idCarga), clave, "cada operación usa su prefijo: mezclarlas da 422 clave_en_uso")
        assertEquals("l:abc", PayloadsClinica.claveDeCarga("abc"))
    }

    @Test
    fun la_clave_de_la_baja_es_la_de_la_carga_con_sufijo_baja() {
        assertEquals("l:$idCarga-baja", PayloadsClinica.claveDeBajaDeCarga(idCarga))
        assertNotEquals(PayloadsClinica.claveDeCarga(idCarga), PayloadsClinica.claveDeBajaDeCarga(idCarga))
        assertEquals("l:abc-baja", PayloadsClinica.claveDeBajaDeCarga("abc"))
        assertTrue(Regex("^l:[A-Za-z0-9._-]+\$").matches(PayloadsClinica.claveDeBajaDeCarga(idCarga)))
    }

    @Test
    fun una_clave_que_el_servidor_rechazaria_no_se_arma() {
        for (mala in listOf("", "con espacio", "con:dos", "ñandú", "x".repeat(190))) {
            assertFailsWith<IllegalArgumentException>("id='$mala'") { PayloadsClinica.claveDeCarga(mala) }
            assertFailsWith<IllegalArgumentException>("id='$mala'") { PayloadsClinica.claveDeBajaDeCarga(mala) }
        }
        assertEquals(191, PayloadsClinica.claveDeCarga("x".repeat(189)).length, "l: + 189 = 191: el tope del servidor")
        assertEquals(191, PayloadsClinica.claveDeBajaDeCarga("x".repeat(184)).length, "l: + 184 + -baja = 191")
        assertFailsWith<IllegalArgumentException> { PayloadsClinica.claveDeBajaDeCarga("x".repeat(185)) }
    }

    // ---------- Alta ----------

    @Test
    fun un_alta_de_laboratorio_lleva_la_clave_el_dispositivo_la_consulta_la_fecha_y_todos_los_valores() {
        val cuerpo = armar()

        assertEquals(
            json("""{"clave":"$clave","dispositivo":"disp-1","consulta_id":"2040","tipo":"laboratorio_general","fecha":"2026-11-06","valores":{"hb":"13.5","otro":"control en 3 meses"}}"""),
            cuerpo,
        )
        assertEquals(setOf("clave", "dispositivo", "consulta_id", "tipo", "fecha", "valores"), cuerpo!!.keys)
    }

    @Test
    fun un_alta_de_peso_lleva_valores_peso_y_no_manda_tipo() {
        val cuerpo = armar(carga(valores = mapOf("peso" to "72.5"), tipo = "peso"))

        assertEquals(
            json("""{"clave":"$clave","dispositivo":"disp-1","consulta_id":"2040","fecha":"2026-11-06","valores":{"peso":"72.5"}}"""),
            cuerpo,
            "el peso viaja como `peso` (el id de la app), no como `valor` (la columna de la web)",
        )
        assertFalse("tipo" in cuerpo!!.keys, "la ruta de pesos no lleva tipo: el servidor lo toma de la ruta")
    }

    @Test
    fun el_laboratorio_reducido_y_un_tipo_que_no_es_el_del_pedido_no_se_mandan() {
        assertNull(armar(carga(valores = mapOf("hb" to "13"), tipo = "laboratorio_reducido"), tipo = "laboratorio_reducido"))
        assertNull(armar(carga(valores = mapOf("hb" to "13")), tipo = "peso"), "el pedido de peso no manda un laboratorio")
        assertNull(armar(carga(valores = mapOf("peso" to "72"), tipo = "peso"), tipo = "laboratorio_general"))
    }

    // ---------- Actualización ----------

    @Test
    fun al_actualizar_solo_viajan_los_valores_tocados_y_el_vaciado_sale_como_vacio() {
        val antes = carga(valores = mapOf("hb" to "13.5", "glucemia" to "0.95", "otro" to "control"))
        val ahora = antes.copy(valores = mapOf("hb" to "14", "glucemia" to "1.2", "otro" to "control"))
        val cuerpo = armar(ahora, idWeb = 731, confirmada = antes)

        assertEquals(
            json("""{"clave":"$clave","dispositivo":"disp-1","consulta_id":"2040","tipo":"laboratorio_general","id":"731","fecha":"2026-11-06","valores":{"hb":"14","glucemia":"1.2"}}"""),
            cuerpo,
            "solo lo tocado (RF-34); `otro` es lo mismo que la web ya tenía",
        )

        val vaciada = armar(ahora.copy(valores = mapOf("hb" to "14", "glucemia" to "", "otro" to "control")), idWeb = 731, confirmada = ahora)
        assertEquals(mapOf("glucemia" to ""), valores(vaciada), "el vaciado a propósito sale `\"\"` (RF-69)")
    }

    @Test
    fun una_columna_que_la_carga_ya_no_tiene_sale_vacia() {
        val antes = carga(valores = mapOf("hb" to "13.5", "ferritina" to "120"))
        val cuerpo = armar(carga(valores = mapOf("hb" to "13.5")), idWeb = 731, confirmada = antes)

        assertEquals(mapOf("ferritina" to ""), valores(cuerpo), "lo que estaba y ya no está es un vaciado, no un valor que se pierde")
    }

    @Test
    fun el_cambio_de_fecha_de_la_carga_tambien_viaja() {
        val antes = carga(fecha = LocalDate(2026, 11, 6))
        val cuerpo = armar(carga(fecha = LocalDate(2026, 11, 7)), idWeb = 731, confirmada = antes, fecha = LocalDate(2026, 11, 7))

        assertEquals(JsonPrimitive("2026-11-07"), cuerpo!!["fecha"])
        assertEquals(JsonPrimitive("731"), cuerpo["id"])
        assertNull(cuerpo["valores"], "no cambió ningún valor: no se mandan valores")
    }

    @Test
    fun la_fecha_va_siempre_y_nunca_es_el_marcador_de_sin_fecha() {
        assertEquals(JsonPrimitive("2026-11-06"), armar()!!["fecha"], "siempre presente")
        assertEquals(JsonPrimitive("2026-11-06"), armar(carga(valores = mapOf("hb" to "14")), idWeb = 731, confirmada = carga())!!["fecha"])

        for (marcador in listOf(LocalDate(2000, 1, 1), LocalDate(1000, 1, 1))) {
            assertFailsWith<IllegalArgumentException>("$marcador es «sin fecha»") {
                armar(carga(valores = mapOf("hb" to "13"), fecha = marcador), fecha = marcador)
            }
        }
    }

    // ---------- Valores ----------

    @Test
    fun el_peso_va_con_la_clave_peso_y_un_cero_es_un_dato() {
        assertEquals(mapOf("peso" to "72.5"), valores(armar(carga(valores = mapOf("peso" to "72.5"), tipo = "peso"))))

        val cero = armar(carga(valores = mapOf("hb" to "0", "glucemia" to "0.00", "otro" to "")))
        assertEquals(mapOf("hb" to "0", "glucemia" to "0.00"), valores(cero), "«0» y «0.00» son datos; un vacío no viaja en un alta")
    }

    @Test
    fun los_valores_van_como_texto_y_sin_recortar() {
        val detalle = " control | con  espacios y 🙂 "
        val cuerpo = armar(carga(valores = mapOf("otro" to detalle, "hb" to "13.5")))

        assertEquals(JsonPrimitive(detalle), (cuerpo!!["valores"] as JsonObject)["otro"])
        for ((_, valor) in cuerpo["valores"] as JsonObject) assertTrue(valor is JsonPrimitive && valor.isString, "todo es texto")
    }

    @Test
    fun lo_que_la_web_ya_tiene_no_se_manda() {
        assertNull(armar(carga(id = "hcc-llaboratorio_general-703", valores = mapOf("hb" to "13"))), "una fila `hcc-` es de la web")
    }

    @Test
    fun sin_nada_que_mandar_no_se_arma_cuerpo() {
        assertNull(armar(carga(valores = emptyMap())), "un alta sin ningún valor")
        assertNull(armar(carga(valores = mapOf("hb" to "", "otro" to "   "))), "un alta con todo vacío")
        assertNull(armar(carga(), idWeb = 731, confirmada = carga()), "una actualización sin cambios")
        assertNull(armar(carga(tipo = "peso", valores = mapOf("peso" to "")), tipo = "peso"))
    }

    @Test
    fun dos_armados_con_lo_mismo_son_identicos() {
        val c = carga(valores = mapOf("hb" to "13.5", "glucemia" to "0.95"))

        assertEquals(armar(c), armar(c))
        assertEquals(armar(c, idWeb = 731, confirmada = c.copy(valores = mapOf("hb" to "13"))), armar(c, idWeb = 731, confirmada = c.copy(valores = mapOf("hb" to "13"))))
    }

    // ---------- Baja ----------

    @Test
    fun la_baja_de_una_carga_lleva_la_clave_el_dispositivo_y_la_consulta() {
        assertEquals(
            json("""{"clave":"l:$idCarga-baja","dispositivo":"disp-1","consulta_id":"2040"}"""),
            PayloadsClinica.bajaDeCarga(PayloadsClinica.claveDeBajaDeCarga(idCarga), "disp-1", 2040),
        )
    }
}
