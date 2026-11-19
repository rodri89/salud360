package com.salud360.core.data.network.hc

import com.salud360.core.data.hcgeneral.EstadoAdministrar
import com.salud360.core.data.hcgeneral.EstadoSeccionEnfermedades
import com.salud360.core.data.hcgeneral.ResultadoCrear
import com.salud360.core.data.network.TobbException
import kotlinx.serialization.json.JsonObject

/** Respuesta de agregar o quitar de la lista: [seccion] solo si se mandó una ficha activa de la cartera. */
data class RespuestaLista(val administrar: EstadoAdministrar, val seccion: EstadoSeccionEnfermedades?)

/** Respuesta de crear: [resultado] para el aviso (F2.5) y [seccion] solo si se marcó al paciente. */
data class RespuestaCrear(val administrar: EstadoAdministrar, val resultado: ResultadoCrear, val seccion: EstadoSeccionEnfermedades?)

/**
 * Enfermedades de la HC general (RF-50 a RF-54): la sección de un paciente, marcar, la lista del médico y crear. Los ids
 * son el de la ficha de la web (el de `pacientes/resolver`) y el de la enfermedad en el catálogo. Si falla lanza
 * [TobbException] con su código (el 429 de Laravel llega sin código). Es una interfaz para probar sin red.
 */
interface EnfermedadesApi {
    /** `GET enfermedades/pacientes/{fichaId}`. */
    suspend fun seccion(fichaId: Long): EstadoSeccionEnfermedades

    /** `PUT enfermedades/pacientes/{fichaId}/{enfermedadId}` con el estado elegido. */
    suspend fun marcar(fichaId: Long, enfermedadId: Long, tiene: Boolean): EstadoSeccionEnfermedades

    /** `GET enfermedades/lista`. */
    suspend fun administrar(): EstadoAdministrar

    /** `PUT enfermedades/lista/{enfermedadId}`; con [fichaId], la respuesta trae también la sección de ese paciente. */
    suspend fun enLista(enfermedadId: Long, enLista: Boolean, fichaId: Long?): RespuestaLista

    /** `POST enfermedades`; con [fichaId], además se la marca a ese paciente. */
    suspend fun crear(nombre: String, fichaId: Long?): RespuestaCrear
}

/** [EnfermedadesApi] contra hclinica, sobre el cliente de la HC general. */
class EnfermedadesApiRemota(private val cliente: HcApiClient) : EnfermedadesApi {
    override suspend fun seccion(fichaId: Long): EstadoSeccionEnfermedades = seccionDe(cliente.get(rutaSeccion(fichaId)))

    override suspend fun marcar(fichaId: Long, enfermedadId: Long, tiene: Boolean): EstadoSeccionEnfermedades =
        seccionDe(cliente.put(rutaMarcar(fichaId, enfermedadId), cuerpoMarcar(tiene)))

    override suspend fun administrar(): EstadoAdministrar = administrarDe(cliente.get(RUTA_LISTA))

    override suspend fun enLista(enfermedadId: Long, enLista: Boolean, fichaId: Long?): RespuestaLista =
        listaDe(cliente.put(rutaLista(enfermedadId), cuerpoLista(enLista, fichaId)))

    override suspend fun crear(nombre: String, fichaId: Long?): RespuestaCrear =
        crearDe(cliente.post(RUTA_CREAR, cuerpoCrear(nombre, fichaId)))

    internal fun rutaSeccion(fichaId: Long) = "enfermedades/pacientes/$fichaId"

    internal fun rutaMarcar(fichaId: Long, enfermedadId: Long) = "${rutaSeccion(fichaId)}/$enfermedadId"

    internal fun rutaLista(enfermedadId: Long) = "$RUTA_LISTA/$enfermedadId"

    internal fun cuerpoMarcar(tiene: Boolean): String = cliente.cuerpo(MarcarRequest.serializer(), MarcarRequest(tiene))

    internal fun cuerpoLista(enLista: Boolean, fichaId: Long?): String =
        cliente.cuerpo(ListaRequest.serializer(), ListaRequest(enLista, fichaId))

    internal fun cuerpoCrear(nombre: String, fichaId: Long?): String =
        cliente.cuerpo(CrearRequest.serializer(), CrearRequest(nombre, fichaId))

    internal fun seccionDe(obj: JsonObject): EstadoSeccionEnfermedades =
        cliente.leerTodo(RespuestaSeccionDto.serializer(), obj).seccion.toModel()

    internal fun administrarDe(obj: JsonObject): EstadoAdministrar =
        cliente.leerTodo(RespuestaAdministrarDto.serializer(), obj).administrar.toModel()

    internal fun listaDe(obj: JsonObject): RespuestaLista =
        cliente.leerTodo(RespuestaListaDto.serializer(), obj).let { RespuestaLista(it.administrar.toModel(), it.seccion?.toModel()) }

    internal fun crearDe(obj: JsonObject): RespuestaCrear =
        cliente.leerTodo(RespuestaCrearDto.serializer(), obj)
            .let { RespuestaCrear(it.administrar.toModel(), it.toResultado(), it.seccion?.toModel()) }

    internal companion object {
        const val RUTA_LISTA = "enfermedades/lista"
        const val RUTA_CREAR = "enfermedades"
    }
}
