package com.salud360.core.data.network.hc

/**
 * Los topes de largo de lo que la app escribe en la HC general (spec 005, E2, RF-39), tal como los midió T48 sobre la base real
 * (`notas-para-el-plan.md` §11.0). **Solo los expone: todavía no se aplican a la pantalla ni al guardado** (eso es T64, a la espera de
 * la respuesta del usuario a Q-E2-3): nada de lo que hay acá recorta, rechaza ni avisa. El servidor ya rechaza con `422
 * texto_demasiado_largo` lo que se pasa de largo (sin cortar nada).
 *
 * Qué mide cada tope y con qué regla (la misma del servidor):
 * - **65535 bytes** ([TEXTO]): columna `text` (el detalle de alimentación y de antecedentes, `nota`, `solicito`, `respuesta`, `especialista`); el
 *   tope de E1 para los textos. Cuenta los bytes de UTF-8 (`strlen`). Las descripciones de ecografía, videoendoscopia, biopsia y TC/RMN son `longtext`
 *   en la base local: el contrato les aplica el mismo tope general.
 * - **191 caracteres** ([ESTUDIO_CARDIOLOGICO]): `estudios_cardiologicos.descripcion`, `varchar(191)`, **la única columna con un tope corto**.
 *   Cuenta caracteres Unicode (`mb_strlen`), no unidades de UTF-16: un emoji cuenta uno.
 * - Las serologías y el Child-Pugh tienen valores cerrados (SI/NO, 1..3): no tienen tope de largo.
 */
enum class UnidadDeLargo { CARACTERES, BYTES }

data class TopeDeLargo(val maximo: Int, val unidad: UnidadDeLargo) {
    /** El largo de [texto] en la unidad de este tope. */
    fun largoDe(texto: String): Int = when (unidad) {
        UnidadDeLargo.BYTES -> texto.encodeToByteArray().size
        UnidadDeLargo.CARACTERES -> caracteresUnicode(texto)
    }

    /** [texto] no entra en este tope. Solo lo dice: nunca lo recorta. */
    fun excede(texto: String): Boolean = largoDe(texto) > maximo
}

/** Cuenta caracteres Unicode (un par suplente de UTF-16, como un emoji, es uno), como `mb_strlen` del servidor. */
private fun caracteresUnicode(texto: String): Int {
    var cantidad = 0
    var i = 0
    while (i < texto.length) {
        i += if (texto[i].isHighSurrogate() && i + 1 < texto.length && texto[i + 1].isLowSurrogate()) 2 else 1
        cantidad++
    }
    return cantidad
}

object TopesDeEscritura {
    /** Columna `text` de la base de la web: 65535 bytes. */
    val TEXTO = TopeDeLargo(65_535, UnidadDeLargo.BYTES)

    /** `estudios_cardiologicos.descripcion`, `varchar(191)`. */
    val ESTUDIO_CARDIOLOGICO = TopeDeLargo(191, UnidadDeLargo.CARACTERES)

    private val ESTUDIOS_CON_TEXTO_LARGO = setOf("eco_abdominal", "veda", "biopsia_hepatica", "tc_rmn")
    private val CASILLAS_DE_ALIMENTACION = setOf("hiposodica", "diabetes", "celiaco", "otra")
    private val CASILLAS_DE_FAMILIARES = setOf(
        "hta", "dbt_nir", "dbt_ir", "acv", "dsl", "fa", "mp", "analfabeto", "postrado", "af_ca_mama", "ca_mama", "institucionalizado", "psicofarmacos",
        "dependiente_terceros", "otro",
    )

    /** El tope de [campo] de un registro de tipo [tipo], o `null` si el contrato no escribe ese campo. */
    fun delCampoDeRegistro(tipo: String, campo: String): TopeDeLargo? = when {
        tipo == "estudios_cardiologicos" && campo == "detalle" -> ESTUDIO_CARDIOLOGICO
        tipo in ESTUDIOS_CON_TEXTO_LARGO && campo == "detalle" -> TEXTO
        tipo == "examen_complementario" && (campo == "solicito" || campo == "respuesta") -> TEXTO
        tipo == "interconsulta" && (campo == "especialista" || campo == "solicito" || campo == "respuesta") -> TEXTO
        else -> null
    }

    /** El tope de [clave] de la sección [seccion] de los formularios, o `null` si no tiene (valores cerrados) o no se escribe. */
    fun delFormulario(seccion: String, clave: String): TopeDeLargo? =
        if (seccion == "alimentacion" && clave in CASILLAS_DE_ALIMENTACION) TEXTO else null

    /** El tope del detalle de la casilla [clave] de los antecedentes familiares, o de la `nota`; `null` si esa clave no se escribe. */
    fun delAntecedente(clave: String): TopeDeLargo? = if (clave == "nota" || clave in CASILLAS_DE_FAMILIARES) TEXTO else null
}