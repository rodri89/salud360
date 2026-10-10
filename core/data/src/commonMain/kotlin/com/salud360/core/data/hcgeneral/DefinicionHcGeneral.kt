package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaDef
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.especialidad.TipoConsultaHc

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

/** Id de la pestaña por su lugar en el perfil: el mismo en Consulta y en Telemedicina aunque una no la muestre. */
private fun idPestania(indice: Int) = "pestania_$indice"
