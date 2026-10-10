package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.SeccionCatalogoRemota
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc

/** Largo máximo de los nombres de perfil, pestaña y sección (`PerfilesService::LARGO_NOMBRE` de hclinica). */
const val LARGO_NOMBRE_PERFIL = 60

/**
 * Un error de validación de un perfil: la misma forma que `errores` en el 422 `validacion` de hclinica, así la
 * pantalla muestra igual los de la app y los del servidor. [campo] dice dónde (`nombre`, `pestanias.1.nombre`,
 * `pestanias.1.secciones.0.seccion`...) y [mensaje] qué corregir.
 */
data class ErrorPerfil(val campo: String, val mensaje: String)

/**
 * Reglas de un perfil antes de mandarlo (RF-2, RF-4, RF-8, RF-9): espejo de `PerfilesService::validar` de hclinica,
 * con los mismos `campo` y las repeticiones marcadas en la segunda aparición. La autoridad es el servidor; esto avisa
 * antes. Los nombres de perfil y de pestaña se comparan además sin tildes (el servidor no, pero el `unique` de su
 * base sí y contesta 422 igual).
 *
 * [otrosNombres]: los de los demás perfiles (al editar, sin el propio). [catalogoIds]: los del catálogo **remoto**
 * (`config/catalogo`), no los de la app: una sección que la app no sabe mostrar sigue siendo válida (RF-16).
 */
fun validarPerfil(
    perfil: PerfilHc,
    otrosNombres: Collection<String>,
    catalogoIds: Collection<String>,
): List<ErrorPerfil> {
    val errores = mutableListOf<ErrorPerfil>()
    fun agregar(campo: String, mensaje: String) {
        errores += ErrorPerfil(campo, mensaje)
    }

    val nombre = perfil.nombre.trim()
    val errorNombre = errorDeNombre(nombre, "El nombre del perfil")
    if (errorNombre != null) {
        agregar("nombre", errorNombre)
    } else {
        val clave = claveDeNombre(nombre)
        otrosNombres.firstOrNull { claveDeNombre(it) == clave }?.let { otro ->
            agregar("nombre", "Ya hay otro perfil llamado \"${otro.trim()}\": elegí otro nombre.")
        }
    }

    val nombresPestanias = mutableSetOf<String>()
    val seccionesVistas = mutableSetOf<String>()
    var cantidadSecciones = 0
    perfil.pestanias.forEachIndexed { i, pestania ->
        val nombrePestania = pestania.nombre.trim()
        val errorPestania = errorDeNombre(nombrePestania, "El nombre de la pestaña")
        if (errorPestania != null) {
            agregar("pestanias.$i.nombre", errorPestania)
        } else if (!nombresPestanias.add(claveDeNombre(nombrePestania))) {
            agregar(
                "pestanias.$i.nombre",
                "Ya hay otra pestaña llamada \"$nombrePestania\" en este perfil: cambiá el nombre de una.",
            )
        }

        pestania.secciones.forEachIndexed { j, seccion ->
            cantidadSecciones++
            val lugar = "pestanias.$i.secciones.$j"
            val nombreSeccion = seccion.nombre.trim()

            errorDeNombre(nombreSeccion, "El nombre de la sección")?.let { agregar("$lugar.nombre", it) }

            if (seccion.seccion !in catalogoIds) {
                agregar("$lugar.seccion", "La sección no está en el catálogo: elegí una de la lista.")
            } else if (!seccionesVistas.add(seccion.seccion)) {
                // El servidor nombra la sección con el nombre del catálogo; acá solo están los ids, así que va el
                // nombre que le puso el administrador (el que ve en pantalla).
                val cual = nombreSeccion.ifEmpty { seccion.seccion }
                agregar("$lugar.seccion", "La sección \"$cual\" está dos veces en el perfil: dejala en una sola pestaña.")
            }

            if (seccion.tipos.isEmpty()) {
                agregar(lugar, "La sección no está marcada para ningún tipo: marcá Consulta, Telemedicina o las dos.")
            }
        }
    }

    if (cantidadSecciones == 0) {
        agregar("pestanias", "El perfil no tiene secciones: agregá al menos una en alguna pestaña.")
    }
    return errores
}

/**
 * Sección del catálogo recién agregada a un perfil (RF-8): con el nombre por defecto (el del catálogo de hclinica,
 * "Decidido" B1) y solo en Consulta. [seccionId] tiene que estar en [catalogo].
 */
fun nuevaSeccion(seccionId: String, catalogo: List<SeccionCatalogoRemota>): SeccionPerfil {
    val delCatalogo = requireNotNull(catalogo.firstOrNull { it.id == seccionId }) {
        "La sección $seccionId no está en el catálogo"
    }
    return SeccionPerfil(seccionId, delCatalogo.nombre, setOf(TipoConsultaHc.CONSULTA))
}

/**
 * Forma de comparar nombres de perfil y de pestaña: sin espacios de más, sin mayúsculas y sin tildes. Pública para
 * que el resto de la HC general (por ejemplo, el nombre de una copia) compare igual.
 */
fun claveDeNombre(nombre: String): String =
    nombre.trim().replace(ESPACIOS, " ").lowercase().map { SIN_TILDE[it] ?: it }.joinToString("")

/** Mensaje si el nombre (ya recortado) está vacío o es largo; null si está bien. */
private fun errorDeNombre(nombre: String, que: String): String? = when {
    nombre.isEmpty() -> "$que está vacío: escribí uno."
    caracteres(nombre) > LARGO_NOMBRE_PERFIL -> "$que tiene más de $LARGO_NOMBRE_PERFIL caracteres: acortalo."
    else -> null
}

/** Largo en caracteres como `mb_strlen` (code points): un par sustituto UTF-16 cuenta uno. */
internal fun caracteres(texto: String): Int {
    var n = 0
    var i = 0
    while (i < texto.length) {
        i += if (texto[i].isHighSurrogate() && i + 1 < texto.length && texto[i + 1].isLowSurrogate()) 2 else 1
        n++
    }
    return n
}

private val ESPACIOS = Regex("\\s+")

/**
 * Letras con tilde (ya en minúscula) y su base, como las iguala `utf8mb4_unicode_ci` de hclinica (también ñ = n).
 * `Normalizer` no existe en common, por eso la tabla.
 */
private val SIN_TILDE: Map<Char, Char> = buildMap {
    fun base(letras: String, base: Char) = letras.forEach { put(it, base) }
    base("àáâãäå", 'a')
    base("èéêë", 'e')
    base("ìíîï", 'i')
    base("òóôõö", 'o')
    base("ùúûü", 'u')
    base("ýÿ", 'y')
    base("ñ", 'n')
    base("ç", 'c')
}
