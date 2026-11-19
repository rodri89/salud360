package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Una página de la cartera del médico: las fichas y si quedan más después. */
data class PaginaCartera(val fichas: List<HcPacienteFicha>, val hayMas: Boolean)

/**
 * Pacientes de una historia clínica: la cartera del médico y el vínculo de un paciente con su ficha. Si falla lanza
 * [TobbException] con su código; en un `409 paciente_ambiguo` trae las fichas para elegir en
 * [TobbException.candidatos]. Es una interfaz para poder probar el repositorio sin red.
 */
interface PacientesHcApi {
    /** `GET pacientes?desde=&limite=`. */
    suspend fun pagina(desde: Int, limite: Int): PaginaCartera

    /** `POST pacientes/resolver`; [elegida] solo cuando el médico eligió una de las candidatas. */
    suspend fun resolver(pedido: HcPacienteRequest, elegida: Long?): HcResolverRespuesta
}

/**
 * [PacientesHcApi] contra el Laravel de una especialidad. Las dos APIs hablan igual salvo en cómo se llama la ficha
 * elegida: `pediatria_paciente_id` en pediatría, `hc_paciente_id` en la HC general.
 */
class PacientesHcApiRemota(private val cliente: HcApiClient, private val claveElegida: String) : PacientesHcApi {
    override suspend fun pagina(desde: Int, limite: Int): PaginaCartera =
        paginaDe(cliente.get("pacientes", mapOf("desde" to desde, "limite" to limite)))

    override suspend fun resolver(pedido: HcPacienteRequest, elegida: Long?): HcResolverRespuesta =
        resolucionDe(cliente.post("pacientes/resolver", cuerpo(pedido, elegida)))

    /** El pedido con la elegida agregada con la clave de esta API, solo si hay una. */
    internal fun cuerpo(pedido: HcPacienteRequest, elegida: Long?): String {
        val base = cliente.cuerpo(HcPacienteRequest.serializer(), pedido)
        if (elegida == null) return base
        return JsonObject(Json.parseToJsonElement(base).jsonObject + (claveElegida to JsonPrimitive(elegida))).toString()
    }

    internal fun paginaDe(obj: JsonObject): PaginaCartera =
        cliente.leerTodo(HcCarteraPagina.serializer(), obj).let { PaginaCartera(it.pacientes, it.hayMas != 0) }

    internal fun resolucionDe(obj: JsonObject): HcResolverRespuesta = cliente.leerTodo(HcResolverRespuesta.serializer(), obj)
}

/**
 * APIs de pacientes por código de especialidad. Envuelta por lo mismo que [HcApiClients]: Koin no distingue los
 * genéricos de un `Map`.
 */
class PacientesHcApis(val porEspecialidad: Map<String, PacientesHcApi>)
