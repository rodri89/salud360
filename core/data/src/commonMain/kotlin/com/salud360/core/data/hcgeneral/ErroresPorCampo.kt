package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.PerfilHc

/**
 * Errores de un perfil (de [validarPerfil] o del 422 `validacion`, misma forma) agrupados por el lugar del editor donde
 * se muestran (RF-4, RF-13). Lo que no tiene lugar en el borrador (campo desconocido, índice que ya no existe o
 * `pestanias`, el perfil sin secciones) va a [generales]: ningún error se pierde.
 */
class ErroresPorCampo internal constructor(
    /** Del nombre del perfil. */
    val nombre: List<String>,
    val generales: List<String>,
    private val pestanias: Map<Int, List<String>>,
    private val nombresSeccion: Map<Pair<Int, Int>, List<String>>,
    private val secciones: Map<Pair<Int, Int>, List<String>>,
) {
    val vacio: Boolean
        get() = nombre.isEmpty() && generales.isEmpty() && pestanias.isEmpty() && nombresSeccion.isEmpty() && secciones.isEmpty()

    /** Del nombre de la pestaña [i] (o de la pestaña en general). */
    fun pestania(i: Int): List<String> = pestanias[i].orEmpty()

    /** Del nombre de la sección [j] de la pestaña [i]. */
    fun nombreSeccion(i: Int, j: Int): List<String> = nombresSeccion[i to j].orEmpty()

    /** De la sección [j] de la pestaña [i] salvo su nombre: repetida, fuera del catálogo, sin tipos. */
    fun seccion(i: Int, j: Int): List<String> = secciones[i to j].orEmpty()

    companion object {
        val NINGUNO = ErroresPorCampo(emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap())
    }
}

/** Ubica cada error según su `campo` (`nombre`, `pestanias.i.nombre`, `pestanias.i.secciones.j[.nombre|.seccion]`). */
fun ubicarErrores(errores: List<ErrorPerfil>, perfil: PerfilHc): ErroresPorCampo {
    val nombre = mutableListOf<String>()
    val generales = mutableListOf<String>()
    val pestanias = mutableMapOf<Int, MutableList<String>>()
    val nombresSeccion = mutableMapOf<Pair<Int, Int>, MutableList<String>>()
    val secciones = mutableMapOf<Pair<Int, Int>, MutableList<String>>()

    errores.forEach { e ->
        val deSeccion = CAMPO_SECCION.matchEntire(e.campo)
        val dePestania = CAMPO_PESTANIA.matchEntire(e.campo)
        when {
            e.campo == "nombre" -> nombre += e.mensaje
            deSeccion != null -> {
                val i = deSeccion.groupValues[1].toInt()
                val j = deSeccion.groupValues[2].toInt()
                val destino = if (deSeccion.groupValues[3] == ".nombre") nombresSeccion else secciones
                if (j in perfil.pestanias.getOrNull(i)?.secciones?.indices.orEmpty()) {
                    destino.getOrPut(i to j) { mutableListOf() } += e.mensaje
                } else {
                    generales += e.mensaje
                }
            }
            dePestania != null -> {
                val i = dePestania.groupValues[1].toInt()
                if (i in perfil.pestanias.indices) pestanias.getOrPut(i) { mutableListOf() } += e.mensaje else generales += e.mensaje
            }
            else -> generales += e.mensaje
        }
    }
    return ErroresPorCampo(nombre, generales, pestanias, nombresSeccion, secciones)
}

private fun IntRange?.orEmpty(): IntRange = this ?: IntRange.EMPTY

private val CAMPO_SECCION = Regex("""pestanias\.(\d{1,6})\.secciones\.(\d{1,6})(\..+)?""")
private val CAMPO_PESTANIA = Regex("""pestanias\.(\d{1,6})(\..+)?""")
