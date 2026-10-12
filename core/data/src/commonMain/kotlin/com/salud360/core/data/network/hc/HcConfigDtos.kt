package com.salud360.core.data.network.hc

import com.salud360.core.model.especialidad.AsignacionPerfil
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.ResumenPerfil
import com.salud360.core.model.especialidad.SeccionCatalogoRemota
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuración de la HC general en hclinica (`config`, `config/catalogo`, `config/perfiles`, `config/asignaciones`),
 * con la forma de `md/API_SALUD360_CLINICA.md`. Los errores `{campo, mensaje}` del 422 son
 * [com.salud360.core.data.hcgeneral.ErrorPerfil] y los lee [HcApiClient].
 */

/** Perfil: se manda y se recibe con la misma forma. Al mandar, `id` se ignora (va en la URL). */
@Serializable
data class HcPerfilDto(
    val id: Long? = null,
    val nombre: String = "",
    val pestanias: List<HcPestaniaDto> = emptyList(),
)

@Serializable
data class HcPestaniaDto(
    val nombre: String = "",
    val secciones: List<HcSeccionPerfilDto> = emptyList(),
)

@Serializable
data class HcSeccionPerfilDto(
    val seccion: String,
    val nombre: String = "",
    val consulta: Boolean = false,
    val telemedicina: Boolean = false,
)

@Serializable
data class HcSeccionCatalogoDto(
    val id: String,
    val nombre: String = "",
    val descripcion: String = "",
)

@Serializable
data class HcResumenPerfilDto(
    val id: Long,
    val nombre: String = "",
    val medicos: Int = 0,
)

/** Si el número todavía no está vinculado a un usuario de hclinica, `nombre`, `activo` y `perfil_web` vienen null. */
@Serializable
data class HcAsignacionDto(
    @SerialName("medico_id_tobb") val medicoIdTobb: Long,
    val nombre: String? = null,
    val vinculado: Boolean = false,
    val activo: Boolean? = null,
    @SerialName("perfil_id") val perfilId: Long? = null,
    @SerialName("perfil_web") val perfilWeb: Int? = null,
)

/** Cuerpo de `PUT config/asignaciones/{medicoIdTobb}`: `perfil_id` tiene que ir siempre, null quita el perfil. */
@Serializable
data class HcAsignarRequest(@SerialName("perfil_id") val perfilId: Long?)

fun HcPerfilDto.toModel() = PerfilHc(id, nombre, pestanias.map { it.toModel() })

fun HcPestaniaDto.toModel() = PestaniaPerfil(nombre, secciones.map { it.toModel() })

fun HcSeccionPerfilDto.toModel() = SeccionPerfil(
    seccion,
    nombre,
    buildSet {
        if (consulta) add(TipoConsultaHc.CONSULTA)
        if (telemedicina) add(TipoConsultaHc.TELEMEDICINA)
    },
)

fun PerfilHc.toDto() = HcPerfilDto(
    id,
    nombre,
    pestanias.map { p ->
        HcPestaniaDto(
            p.nombre,
            p.secciones.map {
                HcSeccionPerfilDto(it.seccion, it.nombre, TipoConsultaHc.CONSULTA in it.tipos, TipoConsultaHc.TELEMEDICINA in it.tipos)
            },
        )
    },
)

fun HcSeccionCatalogoDto.toModel() = SeccionCatalogoRemota(id, nombre, descripcion)

fun HcResumenPerfilDto.toModel() = ResumenPerfil(id, nombre, medicos)

fun HcAsignacionDto.toModel() = AsignacionPerfil(medicoIdTobb, nombre, vinculado, activo, perfilId, perfilWeb)
