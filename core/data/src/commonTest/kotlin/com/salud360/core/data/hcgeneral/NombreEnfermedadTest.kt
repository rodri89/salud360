package com.salud360.core.data.hcgeneral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nombre de una enfermedad (spec 003, RF-42, RF-43, RF-44): normalizado, clave y validación. Las tablas `nombres` e
 * `invalidos` son copia de las de `tests/Unit/EnfermedadesReglasTest.php` de hclinica, caso por caso y con el mismo
 * nombre de caso: `\u{XXXX}` de PHP va como `\uXXXX` (o el par sustituto fuera del plano básico), `\f` y `\x0B` como
 * `\u000C` y `\u000B`, y `str_repeat` como `repeat`.
 *
 * No se copia `testUtf8InvalidoEsInvisible` (`"Diabetes \xC3"`): un `String` de Kotlin no puede tener UTF-8 inválido.
 *
 * Diferencia aceptada con la API (sin caso): `lowercase()` de Kotlin (JVM y JS) aplica la sigma final (`Σ` al final
 * de palabra → `ς`) y `mb_strtolower` de PHP 7.3 no (`σ`), así que un nombre en griego que termine en `Σ` puede tener
 * otra clave en la app que en la API. Solo afecta nombres en griego; la API decide (D7).
 *
 * commonTest solo corre en JVM (no hay tests en wasm). Lo de abajo no usa nada que dependa de la plataforma (nada de
 * `trim()`, `isWhitespace` ni `\s`), así que en wasm da lo mismo, pero allá no se verifica.
 */
class NombreEnfermedadTest {
    private class Nombre(val caso: String, val escrito: String, val normalizado: String, val clave: String)
    private class Invalido(val caso: String, val escrito: String, val error: String?)

    /** Escrito, nombre normalizado y clave de nombre (proveedor `nombres`). */
    private val nombres = listOf(
        Nombre("tal cual", "Diabetes", "Diabetes", "diabetes"),
        Nombre("espacio al final", "diabetes ", "diabetes", "diabetes"),
        Nombre("mayúsculas y tilde", "DIABÉTES", "DIABÉTES", "diabetes"),
        Nombre("dos espacios", "Diabetes  tipo 2", "Diabetes tipo 2", "diabetes tipo 2"),
        Nombre("un espacio", "Diabetes tipo 2", "Diabetes tipo 2", "diabetes tipo 2"),
        Nombre("tab, salto de línea y retorno", "\tDiabetes\n\r tipo 2\r\n", "Diabetes tipo 2", "diabetes tipo 2"),
        Nombre(
            "salto de página y tab vertical", "\u000CDiabetes\u000Btipo\u000C2\u000B", "Diabetes tipo 2",
            "diabetes tipo 2",
        ),
        Nombre(
            "tabla de tildes", "ÀÁÂÃÄÅ ÈÉÊË ÌÍÎÏ ÒÓÔÕÖ ÙÚÛÜ Ý Ÿ Ñ Ç", "ÀÁÂÃÄÅ ÈÉÊË ÌÍÎÏ ÒÓÔÕÖ ÙÚÛÜ Ý Ÿ Ñ Ç",
            "aaaaaa eeee iiii ooooo uuuu y y n c",
        ),
        Nombre(
            "tabla de tildes en minúscula", "àáâãäå èéêë ìíîï òóôõö ùúûü ý ÿ ñ ç", "àáâãäå èéêë ìíîï òóôõö ùúûü ý ÿ ñ ç",
            "aaaaaa eeee iiii ooooo uuuu y y n c",
        ),
        Nombre("ñ es n", "Ñandú", "Ñandú", "nandu"),
        Nombre("fuera de la tabla queda", "Søren Œdema", "Søren Œdema", "søren œdema"),
        Nombre("emojis", " 😀 Enfermedad  😀 ", "😀 Enfermedad 😀", "😀 enfermedad 😀"),
    )

    /** Escrito y error del nombre normalizado, null = válido (proveedor `invalidos`, más NBSP al final). */
    private val invalidos = listOf(
        Invalido("vacío", "", "vacio"),
        Invalido("solo espacios", "   ", "vacio"),
        Invalido("solo tab y saltos", "\t\n\r", "vacio"),
        Invalido("solo salto de página y tab vertical", "\u000C\u000B", "vacio"),
        Invalido("NBSP en el medio", "Diabetes\u00A0tipo 2", "invisible"),
        Invalido("NBSP al principio (no se recorta)", "\u00A0Diabetes", "invisible"),
        Invalido("solo NBSP", "\u00A0", "invisible"),
        Invalido("ancho cero", "Diabe\u200Btes", "invisible"),
        Invalido("solo ancho cero", "\u200B", "invisible"),
        Invalido("BOM", "\uFEFFDiabetes", "invisible"),
        Invalido("separador de línea", "Diabetes\u2028", "invisible"),
        Invalido("separador de párrafo", "Diabetes\u2029", "invisible"),
        Invalido("espacio ideográfico", "Diabetes\u3000tipo 2", "invisible"),
        Invalido("NEL", "Diabetes\u0085", "invisible"),
        Invalido("nulo", "Diabetes\u0000", "invisible"),
        Invalido("Cf fuera del plano básico", "Diabetes\uDB40\uDC01", "invisible"),
        Invalido("255 emojis", "😀".repeat(255), null),
        Invalido("256 emojis", "😀".repeat(256), "largo"),
        Invalido("254 letras y un emoji", "a".repeat(254) + "😀", null),
        Invalido("255 tildes (510 bytes)", "é".repeat(255), null),
        Invalido("256 letras", "a".repeat(256), "largo"),
        Invalido("255 con espacios a recortar", "  " + "a".repeat(255) + "  ", null),
        Invalido("un carácter", "a", null),
        // No está en el PHP: NBSP al final tampoco se recorta.
        Invalido("NBSP al final (no se recorta)", "Diabetes\u00A0", "invisible"),
    )

    /** Textos de RF-43 por código, como `MENSAJES_NOMBRE` de `EnfermedadesReglas`. */
    private val textos = mapOf(
        "vacio" to "El nombre está vacío",
        "largo" to "El nombre tiene más de 255 caracteres: acortalo",
        "invisible" to "El nombre tiene caracteres que no se ven: escribilo de nuevo",
    )

    @Test
    fun normalizado_y_clave() {
        for (n in nombres) {
            assertEquals(n.normalizado, nombreNormalizado(n.escrito), n.caso)
            assertEquals(n.clave, claveDeNombre(n.escrito), n.caso)
            assertNull(errorDeNombreEnfermedad(nombreNormalizado(n.escrito)), n.caso)
        }
    }

    @Test
    fun error_de_nombre() {
        for (i in invalidos) {
            assertEquals(i.error?.let { textos.getValue(it) }, errorDeNombreEnfermedad(nombreNormalizado(i.escrito)), i.caso)
        }
    }

    @Test
    fun mismo_nombre_por_clave() {
        for (otro in listOf("diabetes  tipo 2", " DIABÉTES TIPO 2 ", "Diabetes\ttipo\n2")) {
            assertTrue(mismoNombre("Diabetes tipo 2", otro), otro)
        }
        assertFalse(mismoNombre("Diabetes tipo 2", "Diabetes tipo2"))
        assertFalse(mismoNombre("Diabetes tipo 2", "Diabetes\u00A0tipo 2"))
    }

    @Test
    fun mensajes_de_rf43() {
        assertEquals("El nombre está vacío", errorDeNombreEnfermedad(""))
        assertEquals("El nombre tiene más de 255 caracteres: acortalo", errorDeNombreEnfermedad("a".repeat(256)))
        assertEquals("El nombre tiene caracteres que no se ven: escribilo de nuevo", errorDeNombreEnfermedad("\u200B"))
    }

    @Test
    fun nbsp_al_borde_no_es_el_mismo_nombre() {
        assertNotEquals(claveDeNombre("Diabetes"), claveDeNombre("\u00A0Diabetes"))
        assertNotEquals(claveDeNombre("Diabetes"), claveDeNombre("Diabetes\u00A0"))
    }

    @Test
    fun la_clave_deja_los_demas_espacios_al_borde_y_en_el_medio() {
        // Antes `trim()` los sacaba de los bordes en JVM; ahora solo se recortan y juntan los seis de la spec.
        for (c in listOf('\u00A0', '\u3000', '\u2028', '\u001F')) {
            assertEquals("${c}diabetes${c}tipo 2$c", claveDeNombre("${c}Diabetes${c}tipo 2$c"), c.code.toString(16))
        }
    }
}
