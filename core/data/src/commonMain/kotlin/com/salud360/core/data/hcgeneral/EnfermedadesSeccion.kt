package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.EspecialidadDefinition

/** Id de la sección "Enfermedades" en el catálogo de la HC general (spec 003, PD9). */
const val SECCION_ENFERMEDADES = "enfermedades"

/** RF-18: la lista del médico no tiene marcables. */
const val TEXTO_SIN_MARCABLES = "No tenés enfermedades en tu listado. Podés agregarlas con «Administrar enfermedades»."

/** RF-19. */
const val TITULO_OTRAS_ENFERMEDADES = "Otras enfermedades del paciente"

/** RF-35. */
const val LEYENDA_ESTADO_ACTUAL = "Estado actual del paciente"

/** RF-35: cerrada o solo lectura y el paciente no tiene ninguna. */
const val TEXTO_SIN_ENFERMEDADES = "Sin enfermedades registradas"

/** Lo común a [Enfermedad] y [Marcable], para ordenarlas con [ordenAlfabetico]. */
interface EnfermedadConNombre {
    val id: Long
    val nombre: String
}

/** Una enfermedad del catálogo de hclinica. */
data class Enfermedad(override val id: Long, override val nombre: String) : EnfermedadConNombre

/** Una enfermedad que el médico puede marcar (su lista activa ∩ catálogo activo, D8); [tiene] = el paciente la tiene. */
data class Marcable(override val id: Long, override val nombre: String, val tiene: Boolean) : EnfermedadConNombre

/**
 * Estado de la sección para un médico y una ficha, tal como lo arma la web (`EnfermedadesReglas::armarSeccion`):
 * [otras] son las del paciente que no son marcables para este médico (de otros médicos, fuera de su lista o dadas de
 * baja en el catálogo). Es del paciente, no de la consulta: no se guarda por consulta (RF-36).
 */
data class EstadoSeccionEnfermedades(val marcables: List<Marcable>, val otras: List<Enfermedad>)

/** "Administrar enfermedades": la lista del médico y las activas del catálogo que no están en ella. */
data class EstadoAdministrar(val lista: List<Enfermedad>, val catalogo: List<Enfermedad>)

/** Lo que muestra la sección según la consulta se pueda editar o no ([vistaSeccion]). */
sealed interface VistaSeccionEnfermedades {
    /** Consulta abierta propia: una casilla por marcable (RF-15) y, debajo, "Otras" solo para leer (RF-19). */
    data class Grilla(val marcables: List<Marcable>, val otras: List<Enfermedad>) : VistaSeccionEnfermedades {
        /** El texto de RF-18 en lugar de la grilla, o null si hay casillas. */
        val sinMarcables: String? get() = if (marcables.isEmpty()) TEXTO_SIN_MARCABLES else null

        /** Título del bloque "Otras", o null si no hay que mostrarlo (RF-19). */
        val tituloOtras: String? get() = if (otras.isEmpty()) null else TITULO_OTRAS_ENFERMEDADES
    }

    /** Cerrada o solo para leer: las que el paciente tiene hoy, sin casillas (RF-35). */
    data class EstadoActual(val enfermedades: List<Enfermedad>) : VistaSeccionEnfermedades {
        val leyenda: String get() = LEYENDA_ESTADO_ACTUAL

        /** "Sin enfermedades registradas" si no tiene ninguna, o null. */
        val vacio: String? get() = if (enfermedades.isEmpty()) TEXTO_SIN_ENFERMEDADES else null
    }
}

/**
 * Orden alfabético de la spec (RF-16, D14): por [claveDeNombre] y, a igual clave, por id. Es el de
 * `EnfermedadesReglas::ordenar` de hclinica, que compara las claves con `strcmp` (bytes de UTF-8, o sea puntos de
 * código); por eso no se usa `compareTo` de `String`, que compara unidades de UTF-16 y pone los pares sustitutos antes
 * de U+E000–U+FFFF.
 */
fun <T : EnfermedadConNombre> ordenAlfabetico(lista: List<T>): List<T> =
    lista.map { claveDeNombre(it.nombre) to it }
        .sortedWith { a, b -> compararPorPuntoDeCodigo(a.first, b.first).takeIf { it != 0 } ?: a.second.id.compareTo(b.second.id) }
        .map { it.second }

/** Abierta ([editable]) → grilla y "Otras"; cerrada o solo para leer → estado actual del paciente (RF-15 a RF-19, RF-35). */
fun vistaSeccion(estado: EstadoSeccionEnfermedades, editable: Boolean): VistaSeccionEnfermedades =
    if (editable) {
        VistaSeccionEnfermedades.Grilla(ordenAlfabetico(estado.marcables), ordenAlfabetico(estado.otras))
    } else {
        VistaSeccionEnfermedades.EstadoActual(enfermedadesDelPaciente(estado))
    }

/** Las que el paciente tiene: las marcadas de la grilla y las "Otras", juntas y ordenadas (RF-7, RF-35). */
fun enfermedadesDelPaciente(estado: EstadoSeccionEnfermedades): List<Enfermedad> =
    ordenAlfabetico(estado.marcables.filter { it.tiene }.map { Enfermedad(it.id, it.nombre) } + estado.otras)

/**
 * Si la sección se ve en una consulta de [tipo] (RF-5): está en el perfil para ese tipo y el médico no la ocultó
 * ([ocultas], la preferencia tal cual). Sin perfil la definición no tiene grupos: no se ve (RF-6).
 */
fun seccionEnfermedadesVisible(def: EspecialidadDefinition, tipo: String, ocultas: Set<String>): Boolean =
    def.tipoConsulta(tipo)?.let { t -> seccionesVisibles(t, ocultas).any { SECCION_ENFERMEDADES in it.secciones } } == true

/**
 * Si lo del paciente va en "Datos" (RF-6, RF-7, D12): sin perfil, fuera del perfil para **todos** los tipos u oculta.
 * Distinto de los antecedentes de la 001 (`seccionesDelPacienteSoloLectura`), donde una oculta del perfil no sale: acá
 * lo pide la spec, así el médico que la oculta igual ve lo del paciente. Nunca a la vez que [seccionEnfermedadesVisible].
 */
fun enfermedadesEnDatos(def: EspecialidadDefinition, ocultas: Set<String>): Boolean =
    SECCION_ENFERMEDADES in ocultas ||
        def.tiposConsulta.none { t -> t.grupos.any { SECCION_ENFERMEDADES in it.secciones } }

/** Como `strcmp` sobre UTF-8: por puntos de código. */
private fun compararPorPuntoDeCodigo(a: String, b: String): Int {
    for (i in 0 until minOf(a.length, b.length)) {
        if (a[i] != b[i]) return pesoUtf16(a[i]).compareTo(pesoUtf16(b[i]))
    }
    return a.length.compareTo(b.length)
}

/** Corre los sustitutos por encima de U+E000–U+FFFF: así el orden de las unidades de UTF-16 es el de los puntos de código. */
private fun pesoUtf16(c: Char): Int = when {
    c.isSurrogate() -> c.code + 0x2000
    c.code >= 0xE000 -> c.code - 0x800
    else -> c.code
}
