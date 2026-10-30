package com.salud360.core.data.hcgeneral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q15 = nunca borrar (RF-63): la conversión de la lectura no tiene forma de expresar un borrado ni un vaciado. Se
 * comprueba por reflexión sobre lo que `LecturaClinica.kt` expone: el resultado de `marcarAusentes` es solo marcar y
 * desmarcar, y ninguna función se llama como un espejo (`quitarDeLoTraido`, `borrar…`, `vaciar…`).
 */
class LecturaClinicaSinBorradoTest {
    private val prohibidas = Regex("(?i)(borr|elimin|vaci|delete|remove|quitar|purg|limpi|espejo|reemplazar|clear|drop|trunc)")

    @Test
    fun el_resultado_de_marcar_ausentes_solo_tiene_marcar_y_desmarcar() {
        assertEquals(setOf("marcar", "desmarcar"), MarcasAusentes::class.java.declaredFields.map { it.name }.toSet())
        val metodos = MarcasAusentes::class.java.declaredMethods.map { it.name }.toSet()
        val permitidos = setOf("getMarcar", "getDesmarcar", "component1", "component2", "copy", "copy\$default", "equals", "hashCode", "toString")
        assertEquals(emptySet(), metodos - permitidos, "el resultado no puede tener otras operaciones")
    }

    // Sin `else`: si alguien agrega otra forma de lectura, este `when` deja de compilar y obliga a revisar este test.
    private fun forma(l: LecturaWeb): String = when (l) {
        is LecturaWeb.Lista -> "lista"
        is LecturaWeb.UnaConsulta -> "consulta"
        is LecturaWeb.ConsultaNoEncontrada -> "no encontrada"
        is LecturaWeb.Historia -> "historia"
        is LecturaWeb.ConError -> "error"
    }

    private val lecturas = listOf(
        LecturaWeb.Lista::class.java, LecturaWeb.UnaConsulta::class.java, LecturaWeb.ConsultaNoEncontrada::class.java,
        LecturaWeb.Historia::class.java, LecturaWeb.ConError::class.java,
    )

    @Test
    fun la_lectura_solo_distingue_lo_que_la_web_mando_o_un_error() {
        assertEquals("error", forma(LecturaWeb.ConError("500")))
        assertTrue(lecturas.all { LecturaWeb::class.java.isAssignableFrom(it) })
    }

    @Test
    fun ninguna_funcion_ni_clase_de_la_lectura_borra_ni_vacia() {
        val archivo = Class.forName("com.salud360.core.data.hcgeneral.LecturaClinicaKt")
        val nombres = archivo.declaredMethods.map { it.name } + archivo.declaredFields.map { it.name }
        assertTrue(nombres.isNotEmpty())
        assertEquals(emptyList(), nombres.filter { prohibidas.containsMatchIn(it) }, "nombres que sugieren un borrado")
        val tipos = listOf(MarcasAusentes::class.java, Desconocido::class.java) + lecturas
        tipos.forEach { t ->
            val miembros = t.declaredMethods.map { it.name } + t.declaredFields.map { it.name }
            assertEquals(emptyList(), miembros.filter { prohibidas.containsMatchIn(it) }, t.simpleName)
        }
    }

    @Test
    fun la_lectura_no_conoce_ninguna_operacion_de_la_base() {
        // Pura: lo único de `core.database` que toca son las filas ya armadas (`filaDeLaWeb`), nunca los `Queries`.
        val archivo = Class.forName("com.salud360.core.data.hcgeneral.LecturaClinicaKt")
        val usados = archivo.declaredMethods.flatMap { m -> m.parameterTypes.toList() + m.returnType }.map { it.name }
        assertEquals(emptyList(), usados.filter { it.endsWith("Queries") || it.endsWith("Salud360Db") || it.contains("SqlDriver") })
    }
}
