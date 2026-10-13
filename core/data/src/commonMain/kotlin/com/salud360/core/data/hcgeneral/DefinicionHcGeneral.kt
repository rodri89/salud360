package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaDef
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.especialidad.TipoConsultaHc
import com.salud360.core.model.especialidad.TipoSeccion

/**
 * La HC general armada para un médico. [sinPerfil] = no tiene perfil asignado (RF-19): los tipos no tienen secciones.
 * [desconocidas] son los ids del perfil que esta versión de la app no sabe mostrar (RF-27), en el orden del perfil.
 */
data class DefinicionArmada(
    val definicion: EspecialidadDefinition,
    val sinPerfil: Boolean,
    val desconocidas: List<String>,
)

/**
 * Definición "clinica" a partir del [catalogo] de la app y el [perfil] del médico (RF-20, RF-21, RF-24).
 *
 * - Secciones: todas las que la app conoce, del catálogo y las legadas (las de solo lectura de RF-22 las necesitan),
 *   con el nombre que les da el perfil o, si no están en él, el por defecto (RF-7).
 * - Tipos: Consulta y Telemedicina, cada uno con las pestañas del perfil que tienen secciones para ese tipo, en orden
 *   y con sus nombres; la única pestaña fija es la ficha del paciente. "HC digitalizada" queda sin secciones: solo
 *   para leer las consultas viejas, no se ofrece para nuevas (sus fotos salen como solo lectura).
 * - Lo que el perfil tiene y la app no conoce (incluidas las legadas, que no van en perfiles) se omite y se lista.
 */
fun armarDefinicion(catalogo: CatalogoHc, perfil: PerfilHc?): DefinicionArmada {
    // Una legada nunca toma el nombre del perfil: no va en perfiles y se lee con el por defecto (RF-22).
    val nombres = perfil?.pestanias.orEmpty().flatMap { it.secciones }
        .filter { catalogo.seccion(it.seccion) != null }
        .associateBy({ it.seccion }, { it.nombre })
    val todas = catalogo.secciones + catalogo.legadas
    val desconocidas = perfil?.pestanias.orEmpty()
        .flatMap { p -> p.secciones.map { it.seccion } }
        .filter { catalogo.seccion(it) == null }
        .distinct()

    fun tipo(tipo: TipoConsultaHc, nombre: String, icono: String): TipoConsultaDef {
        val grupos = perfil?.pestanias.orEmpty().mapIndexedNotNull { i, p ->
            val secciones = p.secciones.filter { tipo in it.tipos && catalogo.seccion(it.seccion) != null }.map { it.seccion }
            if (secciones.isEmpty()) null else PestaniaDef(idPestania(i), p.nombre, secciones)
        }
        return TipoConsultaDef(
            tipo.codigo, nombre, icono,
            secciones = grupos.flatMap { it.secciones },
            pestanias = listOf(SeccionesComunes.DATOS_PACIENTE),
            grupos = grupos,
        )
    }

    val base = catalogo.base
    val definicion = base.copy(
        tiposConsulta = listOf(
            tipo(TipoConsultaHc.CONSULTA, "Consulta", "control"),
            tipo(TipoConsultaHc.TELEMEDICINA, "Telemedicina", "telemedicina"),
            TipoConsultaDef(
                SeccionesComunes.TIPO_FOTO, "HC digitalizada", "foto", secciones = emptyList(),
                pestanias = listOf(SeccionesComunes.DATOS_PACIENTE), descripcion = "Fotos de la historia en papel",
            ),
        ),
        secciones = todas.map { s -> nombres[s.id]?.let { s.def.copy(titulo = it) } ?: s.def },
        antecedentes = (base.antecedentes + todas.flatMap { it.antecedentes }).distinctBy { it.categoria },
        laboratorios = (base.laboratorios + todas.flatMap { it.laboratorios }).distinctBy { it.tipo },
        registros = (base.registros + todas.flatMap { it.registros }).distinctBy { it.tipo },
    )
    return DefinicionArmada(definicion, sinPerfil = perfil == null, desconocidas = desconocidas)
}

/**
 * Pestañas de [tipo] sin las secciones que el médico ocultó para sí (RF-28); las que quedan vacías no se muestran.
 * Solo filtra los grupos, así que nunca aparece algo fuera del perfil (RF-21). [ocultas] es la preferencia
 * `secciones_ocultas_clinica` tal cual: las que ya no están en el perfil se ignoran y no se podan, así vuelven ocultas
 * si el administrador repone la sección.
 */
fun seccionesVisibles(tipo: TipoConsultaDef, ocultas: Set<String>): List<PestaniaDef> =
    tipo.grupos.mapNotNull { g ->
        val visibles = g.secciones.filter { it !in ocultas }
        if (visibles.isEmpty()) null else g.copy(secciones = visibles)
    }

/**
 * Pestañas de una consulta de [tipo] agrupado ("Decidido" 7 del plan): "Datos" (la ficha del paciente, en la que abre)
 * y después las del perfil con alguna sección visible, sin las [ocultas] por el médico ni las que no [cumple]n su
 * condición (sexo, edad). Vacío si el tipo no tiene grupos: la pantalla de siempre (sin perfil, HC digitalizada y las
 * demás especialidades, RF-33).
 */
fun pestaniasConsulta(tipo: TipoConsultaDef, ocultas: Set<String>, cumple: (String) -> Boolean = { true }): List<PestaniaDef> {
    if (tipo.grupos.isEmpty()) return emptyList()
    val delPerfil = seccionesVisibles(tipo, ocultas).mapNotNull { g ->
        val visibles = g.secciones.filter(cumple)
        if (visibles.isEmpty()) null else g.copy(secciones = visibles)
    }
    return listOf(PestaniaDef(SeccionesComunes.DATOS_PACIENTE, "Datos", emptyList())) + delPerfil
}

/** Id de la pestaña por su lugar en el perfil: el mismo en Consulta y en Telemedicina aunque una no la muestre. */
private fun idPestania(indice: Int) = "pestania_$indice"

/**
 * Si una consulta nueva arranca con los últimos diagnósticos del paciente ("Decidido" C2): solo si una sección de
 * diagnósticos está en algún tipo de consulta (secciones, pestañas o grupos), no si solo está en
 * [EspecialidadDefinition.secciones]: ahí la HC general tiene la legada, para leer datos viejos. Mira todos los tipos,
 * así hematología (diagnósticos solo en Control) copia igual que antes (RF-33).
 */
fun copiaDiagnosticos(def: EspecialidadDefinition): Boolean =
    def.tiposConsulta
        .flatMap { t -> t.secciones + t.pestanias + t.grupos.flatMap { it.secciones } }
        .any { def.seccion(it)?.tipo == TipoSeccion.DIAGNOSTICOS }

/**
 * La definición con la que se muestra una historia clínica. Las especialidades de siempre la tienen fija
 * ([estatica]); la HC general la arma con el perfil del médico ([resolverDefinicion]).
 *
 * [sinPerfil] (RF-19) y [primeraVezSinConexion] (RF-26, con el motivo) son excluyentes; en los dos casos la
 * definición conoce todos los tipos y secciones, así se leen las consultas guardadas, pero no se crean nuevas.
 */
data class DefinicionResuelta(
    val definicion: EspecialidadDefinition,
    val sinPerfil: Boolean = false,
    val primeraVezSinConexion: EstadoConfigHc.PrimeraVezSinConexion? = null,
    val desconocidas: List<String> = emptyList(),
    /** Armada con un perfil (HC general): los tipos sin pestañas no se ofrecen. */
    val porPerfil: Boolean = false,
) {
    /** Tipos para una consulta nueva: con perfil, solo los que tienen pestañas (nunca "HC digitalizada"). */
    val tiposNuevos: List<TipoConsultaDef>
        get() = when {
            !porPerfil -> definicion.tiposConsulta
            sinPerfil || primeraVezSinConexion != null -> emptyList()
            else -> definicion.tiposConsulta.filter { it.grupos.isNotEmpty() }
        }

    /** Sin perfil o sin la configuración la primera vez: lo guardado solo se lee. */
    private val sinConfiguracion: Boolean get() = porPerfil && (sinPerfil || primeraVezSinConexion != null)

    /**
     * Consultas de un tipo que ya no se carga y se abren solo para leer aunque hayan quedado abiertas: "HC digitalizada"
     * en la HC general ("Decidido" 5 del plan).
     */
    fun soloLecturaPorTipo(tipo: String): Boolean = porPerfil && tipo == SeccionesComunes.TIPO_FOTO

    /**
     * Si una consulta cerrada de [tipo] se puede reabrir. Sin configuración no ("Decidido" 8, RF-19, RF-26): las
     * anteriores solo se leen; la que ya estaba abierta se sigue y se cierra igual (C10).
     */
    fun puedeReabrir(tipo: String): Boolean = !soloLecturaPorTipo(tipo) && !sinConfiguracion

    companion object {
        /** Especialidad sin perfiles: la definición de siempre, sin cambios (RF-33). */
        fun estatica(def: EspecialidadDefinition) = DefinicionResuelta(def)
    }
}

/** HC general según el [estado] de la configuración del médico (RF-19, RF-24 a RF-26). */
fun resolverDefinicion(catalogo: CatalogoHc, estado: EstadoConfigHc): DefinicionResuelta {
    val armada = armarDefinicion(catalogo, (estado as? EstadoConfigHc.ConPerfil)?.perfil)
    return DefinicionResuelta(
        definicion = armada.definicion,
        sinPerfil = estado is EstadoConfigHc.SinPerfil,
        primeraVezSinConexion = estado as? EstadoConfigHc.PrimeraVezSinConexion,
        desconocidas = armada.desconocidas,
        porPerfil = true,
    )
}
