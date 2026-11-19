@file:OptIn(ExperimentalSerializationApi::class)

package com.salud360.core.data.network.hc

import com.salud360.core.data.hcgeneral.Enfermedad
import com.salud360.core.data.hcgeneral.EstadoAdministrar
import com.salud360.core.data.hcgeneral.EstadoSeccionEnfermedades
import com.salud360.core.data.hcgeneral.Marcable
import com.salud360.core.data.hcgeneral.ResultadoCrear
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Enfermedades de la HC general en hclinica (`enfermedades/...`), con la forma de la sección "Enfermedades" de
 * `md/API_SALUD360_CLINICA.md`. Las listas vienen siempre (aunque vacías) y ya ordenadas: sin valores por omisión, una
 * respuesta a la que le falte algo es inesperada. `tiene` y `en_lista` viajan como booleanos de JSON, que es lo único
 * que acepta la web.
 */

@Serializable
data class EnfermedadDto(val id: Long, val nombre: String)

@Serializable
data class MarcableDto(val id: Long, val nombre: String, val tiene: Boolean)

@Serializable
data class SeccionEnfermedadesDto(val marcables: List<MarcableDto>, val otras: List<EnfermedadDto>)

@Serializable
data class AdministrarEnfermedadesDto(val lista: List<EnfermedadDto>, val catalogo: List<EnfermedadDto>)

/** `GET enfermedades/pacientes/{ficha}` y el `PUT` de marcar. */
@Serializable
data class RespuestaSeccionDto(val seccion: SeccionEnfermedadesDto)

/** `GET enfermedades/lista`. */
@Serializable
data class RespuestaAdministrarDto(val administrar: AdministrarEnfermedadesDto)

/** `PUT enfermedades/lista/{enfermedad}`: [seccion] solo si se mandó una ficha activa de la cartera. */
@Serializable
data class RespuestaListaDto(val administrar: AdministrarEnfermedadesDto, val seccion: SeccionEnfermedadesDto? = null)

/**
 * `POST enfermedades`, `201` o `200`. [creada] es lo que distingue los dos (E5) y siempre es lo opuesto de
 * [reutilizada]; [enfermedad] trae el nombre del catálogo. [sinMarcarPor] queda crudo (puede llegar uno que la app no
 * conoce) y [seccion] solo viene si se marcó al paciente.
 */
@Serializable
data class RespuestaCrearDto(
    val administrar: AdministrarEnfermedadesDto,
    val enfermedad: EnfermedadDto,
    val creada: Boolean,
    val reutilizada: Boolean,
    val reactivada: Boolean,
    @SerialName("ya_en_lista") val yaEnLista: Boolean,
    val marcada: Boolean,
    @SerialName("sin_marcar_por") val sinMarcarPor: String? = null,
    val seccion: SeccionEnfermedadesDto? = null,
)

/** Cuerpo de `PUT enfermedades/pacientes/{ficha}/{enfermedad}`: el estado elegido, nunca "invertir". */
@Serializable
data class MarcarRequest(val tiene: Boolean)

/** Cuerpo de `PUT enfermedades/lista/{enfermedad}`; sin ficha, `paciente_id` no viaja. */
@Serializable
data class ListaRequest(
    @SerialName("en_lista") val enLista: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("paciente_id") val pacienteId: Long? = null,
)

/** Cuerpo de `POST enfermedades`: el nombre como se escribió (la web lo normaliza); sin ficha, `paciente_id` no viaja. */
@Serializable
data class CrearRequest(
    val nombre: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("paciente_id") val pacienteId: Long? = null,
)

fun EnfermedadDto.toModel() = Enfermedad(id, nombre)

fun MarcableDto.toModel() = Marcable(id, nombre, tiene)

fun SeccionEnfermedadesDto.toModel() = EstadoSeccionEnfermedades(marcables.map { it.toModel() }, otras.map { it.toModel() })

fun AdministrarEnfermedadesDto.toModel() = EstadoAdministrar(lista.map { it.toModel() }, catalogo.map { it.toModel() })

fun RespuestaCrearDto.toResultado() = ResultadoCrear(enfermedad.toModel(), reutilizada, reactivada, yaEnLista, marcada, sinMarcarPor)
