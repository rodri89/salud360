package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException
import kotlinx.serialization.json.JsonObject

/**
 * Las consultas de la web de la HC general, **solo lectura** (RF-38 a RF-41, RF-47): lista, una consulta y el historial
 * del paciente. No tiene, ni puede tener, una operación que escriba: la clase remota solo recibe un [HcApiSoloLectura].
 * Los ids son los de la web (el de la ficha que informa `pacientes/resolver`, el de la consulta). Si falla lanza
 * [TobbException] con su estado y su código (`fuera_de_cartera`, `consulta_no_encontrada`, `paciente_no_encontrado`,
 * `datos_invalidos`, `consultas`); el `429` de Laravel llega sin código y **no se reintenta solo** (RF-57). Es una
 * interfaz para probar sin red.
 */
interface ConsultasClinicaApi {
    /** `GET consultas?paciente_id=&limite=&desde=`: una página de cabeceras, de la más nueva a la más vieja. */
    suspend fun listar(pacienteWebId: Long, limite: Int = LIMITE_POR_DEFECTO, desde: Int = 0): RespuestaListaConsultasDto

    /** `GET consultas/{id}`: una consulta con lo que la web guarda por consulta. */
    suspend fun consulta(id: Long): RespuestaConsultaDto

    /** `GET pacientes/{id}/historia`: lo que la web guarda por paciente y fecha. */
    suspend fun historia(pacienteWebId: Long): RespuestaHistoriaDto

    companion object {
        /** El `limite` que usa el servidor cuando no se lo piden. */
        const val LIMITE_POR_DEFECTO = 50
    }
}

/** [ConsultasClinicaApi] contra hclinica, sobre un cliente que solo sabe leer. */
class ConsultasClinicaApiRemota(private val cliente: HcApiSoloLectura) : ConsultasClinicaApi {
    override suspend fun listar(pacienteWebId: Long, limite: Int, desde: Int): RespuestaListaConsultasDto =
        listaDe(cliente.get(RUTA_LISTA, paramsLista(pacienteWebId, limite, desde)))

    override suspend fun consulta(id: Long): RespuestaConsultaDto = consultaDe(cliente.get(rutaConsulta(id)))

    override suspend fun historia(pacienteWebId: Long): RespuestaHistoriaDto = historiaDe(cliente.get(rutaHistoria(pacienteWebId)))

    internal fun rutaConsulta(id: Long) = "$RUTA_LISTA/$id"

    internal fun rutaHistoria(pacienteWebId: Long) = "pacientes/$pacienteWebId/historia"

    /** En la query, nunca en un cuerpo. */
    internal fun paramsLista(pacienteWebId: Long, limite: Int, desde: Int): Map<String, Any?> =
        mapOf("paciente_id" to pacienteWebId, "limite" to limite, "desde" to desde)

    internal fun listaDe(obj: JsonObject): RespuestaListaConsultasDto =
        cliente.leerTodo(RespuestaListaConsultasDto.serializer(), obj)

    internal fun consultaDe(obj: JsonObject): RespuestaConsultaDto =
        cliente.leerTodo(RespuestaConsultaDto.serializer(), obj)

    internal fun historiaDe(obj: JsonObject): RespuestaHistoriaDto =
        cliente.leerTodo(RespuestaHistoriaDto.serializer(), obj)

    internal companion object {
        const val RUTA_LISTA = "consultas"
    }
}
