package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cliente de la API `/api/salud360/...` de una historia clínica (hc_pediatria, hclinica, ...).
 *
 * Cada especialidad vive en su propio sistema Laravel, pero todas hablan el mismo contrato que
 * turnosonlinebb: respuestas `{ok, mensaje, codigo, ...}` y el **token de turnosonlinebb** en los dos
 * encabezados, porque turnos es la identidad única y estas APIs no emiten tokens propios.
 *
 * Es un gemelo del método genérico de `TurnosOnlineClient`. No se extrajo una base común a propósito:
 * ese refactor toca la agenda, que funciona, y queda para una fase posterior.
 */
/**
 * Clientes de historia clínica por código de especialidad.
 *
 * Va envuelto en un tipo propio a propósito: Koin indexa cada definición por la clase **sin** sus
 * genéricos, así que `Map<String, HcApiClient>` y `Map<String, HcBackend>` serían la misma definición.
 * La segunda pisaría a la primera y pedir los clientes desde los backends terminaría resolviéndose a
 * sí mismo: recursión infinita al arrancar la app.
 */
class HcApiClients(val porEspecialidad: Map<String, HcApiClient>)

class HcApiClient(
    baseUrl: String,
    /** Código de la especialidad, solo para mensajes de error entendibles. */
    private val especialidad: String,
    engine: io.ktor.client.engine.HttpClientEngine? = null,
) {
    private val base = baseUrl.trimEnd('/') + "/api/salud360/"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; encodeDefaults = true }
    private val http = (if (engine != null) HttpClient(engine) else HttpClient()).config {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
        expectSuccess = false
    }

    /** Token de turnosonlinebb. Lo copia `AuthRepository` al iniciar sesión, igual que al cliente de turnos. */
    var token: String? = null

    private fun auth(builder: HttpRequestBuilder) {
        token?.let {
            builder.header(HttpHeaders.Authorization, "Bearer $it")
            // Algunos hostings descartan Authorization cuando PHP corre como CGI.
            builder.header("X-Salud360-Token", it)
        }
    }

    /**
     * Llama a la API y devuelve el objeto JSON. Si responde `ok: false` o un error HTTP lanza [TobbException]
     * con su código, que es lo que permite distinguir "falta vincular al médico" de "se cayó la red".
     */
    suspend fun pedir(metodo: HttpMethod, ruta: String, params: Map<String, Any?> = emptyMap(), cuerpo: String? = null): JsonObject {
        val r = try {
            http.request(base + ruta.trimStart('/')) {
                method = metodo
                auth(this)
                accept(ContentType.Application.Json)
                params.forEach { (k, v) -> if (v != null) parameter(k, v) }
                if (cuerpo != null) { contentType(ContentType.Application.Json); setBody(cuerpo) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // En web el fallo de red llega como kotlin.Error, que no hereda de Exception.
            throw TobbException(0, "red", "Sin conexión con la historia clínica de $especialidad: ${e.message ?: "error de red"}")
        }
        val texto = runCatching { r.bodyAsText() }.getOrDefault("")
        val obj = runCatching { json.parseToJsonElement(texto).jsonObject }.getOrNull()
        val ok = obj?.get("ok")?.jsonPrimitive?.booleanOrNull
        if (!r.status.isSuccess() || ok == false || obj == null) {
            val mensaje = obj?.get("mensaje")?.jsonPrimitive?.contentOrNull
                ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
                ?: texto.ifBlank { "La historia clínica de $especialidad respondió ${r.status.value}" }
            throw TobbException(r.status.value, obj?.get("codigo")?.jsonPrimitive?.contentOrNull, mensaje)
        }
        return obj
    }

    suspend fun get(ruta: String, params: Map<String, Any?> = emptyMap()): JsonObject = pedir(HttpMethod.Get, ruta, params)
    suspend fun post(ruta: String, cuerpo: String? = null): JsonObject = pedir(HttpMethod.Post, ruta, emptyMap(), cuerpo)
    suspend fun put(ruta: String, cuerpo: String? = null): JsonObject = pedir(HttpMethod.Put, ruta, emptyMap(), cuerpo)

    /** Serializa el cuerpo de un pedido. */
    fun <T> cuerpo(serializer: KSerializer<T>, valor: T): String = json.encodeToString(serializer, valor)

    /** Decodifica una parte de la respuesta (`paciente`, `consulta`, ...). */
    fun <T> leer(serializer: KSerializer<T>, obj: JsonObject, clave: String): T =
        json.decodeFromJsonElement(serializer, obj[clave] ?: throw TobbException(0, "respuesta", "Falta '$clave' en la respuesta de $especialidad"))

    /** Decodifica una parte opcional; null si no vino o vino nula. */
    fun <T> leerOpcional(serializer: KSerializer<T>, obj: JsonObject, clave: String): T? {
        val el = obj[clave] ?: return null
        return runCatching { json.decodeFromJsonElement(serializer, el) }.getOrNull()
    }
}
