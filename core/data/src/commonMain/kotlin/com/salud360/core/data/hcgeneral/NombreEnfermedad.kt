package com.salud360.core.data.hcgeneral

/** Largo máximo del nombre de una enfermedad en caracteres (`EnfermedadesReglas::LARGO_MAXIMO` de hclinica). */
const val LARGO_NOMBRE_ENFERMEDAD = 255

/**
 * Nombre normalizado (spec 003): recortado y con los espacios seguidos juntados en uno, con mayúsculas y tildes como
 * vinieron. Solo cuentan como espacios los seis de la spec (espacio, tabulación, salto de línea, retorno de carro,
 * tabulación vertical y salto de página): NBSP y los demás quedan, y [errorDeNombreEnfermedad] los rechaza. Es la
 * misma regla que `EnfermedadesReglas::normalizar` de hclinica.
 */
fun nombreNormalizado(nombre: String): String = buildString {
    var espacio = false
    for (c in nombre) {
        if (c in ESPACIOS) {
            // Los del principio no dejan espacio; los del final no llegan a escribirse.
            espacio = isNotEmpty()
        } else {
            if (espacio) append(' ')
            espacio = false
            append(c)
        }
    }
}

/**
 * Error del nombre ya normalizado (RF-43), con el texto de la spec (el de `MENSAJES_NOMBRE` de hclinica), o null si
 * vale: vacío, más de [LARGO_NOMBRE_ENFERMEDAD] caracteres (como `mb_strlen`) o con caracteres que no se ven (Cc, Cf,
 * Zl, Zp y cualquier Zs que no sea el espacio común, como `EnfermedadesReglas::INVISIBLES`).
 */
fun errorDeNombreEnfermedad(normalizado: String): String? = when {
    normalizado.isEmpty() -> "El nombre está vacío"
    caracteres(normalizado) > LARGO_NOMBRE_ENFERMEDAD -> "El nombre tiene más de 255 caracteres: acortalo"
    tieneInvisibles(normalizado) -> "El nombre tiene caracteres que no se ven: escribilo de nuevo"
    else -> null
}

/** Dos nombres son el mismo si tienen la misma [claveDeNombre] (RF-42, RF-44). */
fun mismoNombre(a: String, b: String): Boolean = claveDeNombre(a) == claveDeNombre(b)

/** Los espacios de la spec, explícitos: los de la biblioteca estándar y las regex cambian según la plataforma. */
private val ESPACIOS = setOf(' ', '\t', '\n', '\r', '\u000B', '\u000C')

private val CATEGORIAS_INVISIBLES = setOf(
    CharCategory.CONTROL,
    CharCategory.FORMAT,
    CharCategory.LINE_SEPARATOR,
    CharCategory.PARAGRAPH_SEPARATOR,
    CharCategory.SPACE_SEPARATOR,
)

/**
 * Los `Cf` fuera del plano básico: common no da la categoría de un par sustituto, por eso la tabla. Un sustituto
 * suelto no cuenta como invisible.
 */
private val FORMATO_FUERA_DEL_PLANO_BASICO = listOf(
    0x1BCA0..0x1BCA3,
    0x1D173..0x1D17A,
    0xE0001..0xE0001,
    0xE0020..0xE007F,
)

private fun tieneInvisibles(texto: String): Boolean {
    var i = 0
    while (i < texto.length) {
        val c = texto[i]
        if (c.isHighSurrogate() && i + 1 < texto.length && texto[i + 1].isLowSurrogate()) {
            val punto = 0x10000 + ((c.code - 0xD800) shl 10) + (texto[i + 1].code - 0xDC00)
            if (FORMATO_FUERA_DEL_PLANO_BASICO.any { punto in it }) return true
            i += 2
        } else {
            if (c != ' ' && c.category in CATEGORIAS_INVISIBLES) return true
            i++
        }
    }
    return false
}
