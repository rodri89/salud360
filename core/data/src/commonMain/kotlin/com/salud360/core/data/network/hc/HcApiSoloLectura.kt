package com.salud360.core.data.network.hc

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * Un [HcApiClient] que solo sabe leer (RF-47): el único pedido que expone es `GET`, y el resto son las tres formas de
 * leer la respuesta. Quien lo recibe no tiene en la mano cómo escribir, subir ni borrar: no es una convención, no hay con
 * qué. El cliente completo queda privado y no se devuelve por ningún lado.
 *
 * Los errores llegan como `TobbException` con su estado y su `codigo`, igual que en [HcApiClient]. Un `GET` nunca lleva
 * cuerpo.
 */
class HcApiSoloLectura(private val cliente: HcApiClient) {
    /** `GET` de [ruta] (relativa a `/api/salud360/`) con [params] en la query; los nulos no se mandan. */
    suspend fun get(ruta: String, params: Map<String, Any?> = emptyMap()): JsonObject = cliente.get(ruta, params)

    /** Decodifica una parte de la respuesta (`consulta`, `secciones`, ...); si falta, `TobbException` `respuesta`. */
    fun <T> leer(serializer: KSerializer<T>, obj: JsonObject, clave: String): T = cliente.leer(serializer, obj, clave)

    /** Decodifica una parte opcional; `null` si no vino o vino nula. */
    fun <T> leerOpcional(serializer: KSerializer<T>, obj: JsonObject, clave: String): T? = cliente.leerOpcional(serializer, obj, clave)

    /** Decodifica la respuesta entera; lo que el DTO no tiene se ignora, y si no tiene la forma esperada, `TobbException`. */
    fun <T> leerTodo(serializer: KSerializer<T>, obj: JsonObject): T = cliente.leerTodo(serializer, obj)
}
