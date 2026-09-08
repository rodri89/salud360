package com.salud360.features.hc

import androidx.compose.runtime.Composable
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.SeccionDef

/**
 * Contexto que reciben las secciones personalizadas (calendario de vacunas, dibujos, escalas)
 * para leer y escribir datos de la consulta sin conocer la infraestructura.
 */
interface SeccionContext {
    val consultaId: String
    val pacienteId: String
    val especialidad: String
    val soloLectura: Boolean
    /** Edad del paciente en meses a la fecha de la consulta (null si no hay fecha de nacimiento). */
    val edadMeses: Int?
    val sexo: String?
    val vm: ConsultaViewModel
    fun valor(seccion: String, campo: String): String
    fun setValor(seccion: String, campo: String, valor: String)
}

/** Composable de una sección personalizada. */
typealias SeccionRenderer = @Composable (SeccionDef, SeccionContext) -> Unit

/**
 * Registro de historias clínicas disponibles. Cada módulo de `especialidades` aporta su
 * [EspecialidadDefinition] y, si hace falta, renderizadores de secciones a medida.
 */
class EspecialidadRegistry(definiciones: List<EspecialidadDefinition>, renderers: Map<String, SeccionRenderer>) {
    private val porCodigo = definiciones.associateBy { it.codigo }
    private val custom = renderers

    val todas: List<EspecialidadDefinition> get() = porCodigo.values.toList()
    fun definicion(codigo: String): EspecialidadDefinition? = porCodigo[codigo]
    fun nombre(codigo: String): String = porCodigo[codigo]?.nombre ?: codigo
    fun renderer(key: String): SeccionRenderer? = custom[key]
    fun existe(codigo: String) = codigo in porCodigo
}

/** Aporte de un módulo de especialidad al registro. */
data class EspecialidadContribution(
    val definicion: EspecialidadDefinition,
    val renderers: Map<String, SeccionRenderer> = emptyMap(),
)
