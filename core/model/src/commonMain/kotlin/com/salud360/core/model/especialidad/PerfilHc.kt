package com.salud360.core.model.especialidad

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Perfiles de la HC general (spec 001) ----
// El perfil lo arma el administrador y lo guarda hclinica (`config/perfiles`); el médico recibe el suyo con
// `GET config` y la app lo guarda como caché (`hc_config`). La forma JSON de la API está en los DTOs de core/data;
// estos son los tipos con los que trabaja la app.

/** Códigos de historia clínica que la app trata de forma especial. */
object CodigosHc {
    /** HC general (hclinica): una sola HC habilitada en turnos, con secciones según el perfil del médico. */
    const val GENERAL = "clinica"
}

/** Tipo de consulta en el que se muestra una sección del perfil. [codigo] es el de [TipoConsultaDef.codigo]. */
@Serializable
enum class TipoConsultaHc(val codigo: String) {
    @SerialName(SeccionesComunes.TIPO_CONTROL)
    CONSULTA(SeccionesComunes.TIPO_CONTROL),

    @SerialName(SeccionesComunes.TIPO_TELEMEDICINA)
    TELEMEDICINA(SeccionesComunes.TIPO_TELEMEDICINA),
    ;

    companion object {
        fun deCodigo(codigo: String): TipoConsultaHc? = entries.firstOrNull { it.codigo == codigo }
    }
}

/** Sección del catálogo dentro de un perfil, con el nombre que se muestra y en qué tipos de consulta va. */
@Serializable
data class SeccionPerfil(
    /** Id del catálogo (`CatalogoSecciones.php` de hclinica). */
    val seccion: String,
    val nombre: String,
    val tipos: Set<TipoConsultaHc>,
)

@Serializable
data class PestaniaPerfil(
    val nombre: String,
    val secciones: List<SeccionPerfil>,
)

@Serializable
data class PerfilHc(
    /** Null mientras no se guardó en hclinica (perfil nuevo o copia). */
    val id: Long? = null,
    val nombre: String,
    val pestanias: List<PestaniaPerfil>,
)

/** Sección del catálogo de hclinica (`config/catalogo`), la puede conocer la app o no. */
@Serializable
data class SeccionCatalogoRemota(
    val id: String,
    val nombre: String,
    val descripcion: String = "",
)

/** Perfil en la lista del administrador, con cuántos médicos lo tienen asignado. */
@Serializable
data class ResumenPerfil(
    val id: Long,
    val nombre: String,
    val medicos: Int,
)

/**
 * Médico (por número de turnos) y su perfil, como `config/asignaciones`. Si todavía no está vinculado a un usuario de
 * hclinica, [nombre], [activo] y [perfilWeb] son null.
 */
@Serializable
data class AsignacionPerfil(
    val medicoIdTobb: Long,
    val nombre: String? = null,
    val vinculado: Boolean,
    val activo: Boolean? = null,
    val perfilId: Long? = null,
    /** `users.perfil` de hclinica: el perfil con el que la web arma la HC (2 = hepatología). */
    val perfilWeb: Int? = null,
)

/** Sección que la app sabe mostrar, con lo que necesita además de su [def]. */
data class SeccionCatalogo(
    /** Definición con el título por defecto (el del catálogo de hclinica). */
    val def: SeccionDef,
    val antecedentes: List<CategoriaAntecedentes> = emptyList(),
    val laboratorios: List<LaboratorioDef> = emptyList(),
    val registros: List<RegistroDef> = emptyList(),
) {
    val id: String get() = def.id
}

/**
 * Lo que la app sabe de la HC general: la [base] (código, nombre, ficha, examen físico) y las secciones que puede
 * mostrar, en orden de catálogo. [legadas] son secciones que la app tenía antes de los perfiles (diagnósticos, audio,
 * fotos...): no se ofrecen en los perfiles, solo se muestran para leer datos viejos.
 */
data class CatalogoHc(
    val base: EspecialidadDefinition,
    val secciones: List<SeccionCatalogo>,
    val legadas: List<SeccionCatalogo> = emptyList(),
) {
    val ids: List<String> get() = secciones.map { it.id }
    fun seccion(id: String): SeccionCatalogo? = secciones.firstOrNull { it.id == id }
    fun legada(id: String): SeccionCatalogo? = legadas.firstOrNull { it.id == id }
}
