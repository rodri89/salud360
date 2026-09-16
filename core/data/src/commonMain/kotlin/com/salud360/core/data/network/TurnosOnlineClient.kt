package com.salud360.core.data.network

import com.salud360.core.model.tobb.TobbPerfil
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cliente de la API `/api/salud360/...` de turnosonlinebb (Laravel), llamado directo desde la app
 * (sin pasar por el servidor propio de Salud 360, que hoy no está desplegado). Laravel espera el
 * login como formulario (`application/x-www-form-urlencoded`), no JSON.
 */
class TurnosOnlineClient(baseUrl: String = "https://turnosonlinebb.com", engine: io.ktor.client.engine.HttpClientEngine? = null) {
    private val base = baseUrl.trimEnd('/') + "/api/salud360/"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val http = (if (engine != null) HttpClient(engine) else HttpClient()).config {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
        expectSuccess = false
    }

    /** Token de sesión de turnosonlinebb, obtenido en [login] y guardado por `AuthRepository`. */
    var token: String? = null

    private fun auth(builder: HttpRequestBuilder) {
        token?.let { builder.header(HttpHeaders.Authorization, "Bearer $it"); builder.header("X-Salud360-Token", it) }
    }

    /**
     * Llama a `/api/salud360/<ruta>` de turnosonlinebb directo, con el token de sesión del usuario.
     * Devuelve el objeto JSON de la respuesta; si la API responde `ok: false` o un error HTTP lanza [TobbException].
     */
    suspend fun tobb(metodo: HttpMethod, ruta: String, params: Map<String, Any?> = emptyMap(), cuerpo: Any? = null): JsonObject {
        val r = try {
            http.request(base + ruta.trimStart('/')) {
                method = metodo
                auth(this)
                params.forEach { (k, v) -> if (v != null) parameter(k, v) }
                // Se manda como texto JSON ya armado (no `setBody(cuerpo)` con tipo Any) porque Ktor/kotlinx.serialization
                // no encuentra el serializador de las clases internas de JsonElement (ej. JsonLiteral) sin el tipo estático.
                if (cuerpo != null) { contentType(ContentType.Application.Json); setBody(aTexto(cuerpo)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // En web, Ktor envuelve el fallo de red en un kotlin.Error (no hereda de Exception): se normaliza a TobbException
            // para que los `catch (e: Exception)` de la app lo traten como "sin conexión" en vez de tumbar la pantalla.
            throw TobbException(0, "red", "Sin conexión con turnosonlinebb: ${e.message ?: "error de red"}")
        }
        val texto = runCatching { r.bodyAsText() }.getOrDefault("")
        val obj = runCatching { json.parseToJsonElement(texto).jsonObject }.getOrNull()
        val ok = obj?.get("ok")?.jsonPrimitive?.booleanOrNull
        if (!r.status.isSuccess() || ok == false || obj == null) {
            val mensaje = obj?.get("mensaje")?.jsonPrimitive?.contentOrNull
                ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
                ?: texto.ifBlank { "turnosonlinebb respondió ${r.status.value}" }
            throw TobbException(r.status.value, obj?.get("codigo")?.jsonPrimitive?.contentOrNull, mensaje)
        }
        return obj
    }

    /** Convierte el cuerpo (un [JsonObject] ya armado, o un `Map` suelto) a texto JSON. */
    private fun aTexto(cuerpo: Any): String = when (cuerpo) {
        is JsonElement -> cuerpo.toString()
        is Map<*, *> -> buildJsonObject { cuerpo.forEach { (k, v) -> if (k is String) put(k, aElemento(v)) } }.toString()
        else -> cuerpo.toString()
    }

    private fun aElemento(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Iterable<*> -> JsonArray(v.map { aElemento(it) })
        is Map<*, *> -> buildJsonObject { v.forEach { (k, x) -> if (k is String) put(k, aElemento(x)) } }
        else -> JsonPrimitive(v.toString())
    }

    /** Fotos ya descargadas (o null si el médico no tiene), por número de médico en turnosonlinebb. */
    private val fotos = mutableMapOf<Long, ByteArray?>()

    /** Foto del médico (`GET medicos/{id}/foto`, pública) o null si no tiene o falla. Se recuerda en memoria. */
    suspend fun fotoMedico(numero: Long): ByteArray? {
        if (numero in fotos) return fotos[numero]
        val bytes = runCatching {
            val r = http.get(base + "medicos/$numero/foto")
            if (r.status.isSuccess() && r.contentType()?.match(ContentType.Image.Any) == true) r.body<ByteArray>() else null
        }.getOrNull()
        fotos[numero] = bytes
        return bytes
    }

    suspend fun tobbGet(ruta: String, params: Map<String, Any?> = emptyMap()): JsonObject = tobb(HttpMethod.Get, ruta, params)
    suspend fun tobbPost(ruta: String, cuerpo: Any? = null, params: Map<String, Any?> = emptyMap()): JsonObject = tobb(HttpMethod.Post, ruta, params, cuerpo)
    suspend fun tobbPut(ruta: String, cuerpo: Any? = null): JsonObject = tobb(HttpMethod.Put, ruta, emptyMap(), cuerpo)
    suspend fun tobbDelete(ruta: String): JsonObject = tobb(HttpMethod.Delete, ruta)

    /**
     * Perfil actual del usuario logueado (`auth/perfil`): permite refrescar médico/consultorio sin volver a
     * loguearse, por si cambiaron en turnosonlinebb desde la última vez (por ejemplo, el consultorio asignado).
     * Null si falla (sin conexión, token vencido, etc.) — la sesión guardada queda como estaba.
     */
    suspend fun perfil(): TobbPerfil? = runCatching {
        json.decodeFromJsonElement(TobbPerfil.serializer(), tobbGet("auth/perfil").getValue("perfil"))
    }.getOrNull()

    sealed interface ResultadoLogin {
        data class Ok(val token: String, val expira: String?, val perfil: TobbPerfil) : ResultadoLogin
        data class Rechazado(val mensaje: String, val status: Int) : ResultadoLogin
        data class Error(val mensaje: String) : ResultadoLogin
    }

    /** POST auth/login con el mail y la contraseña de la web de turnos. */
    suspend fun login(email: String, password: String): ResultadoLogin = try {
        val r = http.submitForm(
            url = base + "auth/login",
            formParameters = Parameters.build {
                append("email", email)
                append("password", password)
            },
        ) { accept(ContentType.Application.Json) }
        val texto = r.bodyAsText()
        if (r.status.isSuccess()) {
            val body = json.decodeFromString(LoginResponse.serializer(), texto)
            if (body.ok && body.accessToken != null && body.perfil != null) ResultadoLogin.Ok(body.accessToken, body.expiresAt, body.perfil)
            else ResultadoLogin.Rechazado(body.mensaje ?: "Respuesta inválida de turnosonlinebb", r.status.value)
        } else {
            val error = runCatching { json.decodeFromString(ErrorResponse.serializer(), texto) }.getOrNull()
            val mensaje = error?.mensaje ?: error?.message?.takeIf { r.status.value < 500 }
                ?: if (r.status.value >= 500) "turnosonlinebb respondió ${r.status.value} (error interno de la web de turnos)" else "turnosonlinebb respondió ${r.status.value}"
            ResultadoLogin.Rechazado(mensaje, r.status.value)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Throwable y no Exception: en web el fallo de red llega como kotlin.Error.
        ResultadoLogin.Error(e.message ?: "sin conexión con turnosonlinebb")
    }

    /** Error de la API (`ok`/`mensaje`/`codigo`) o de Laravel (`message`). */
    @Serializable
    private data class ErrorResponse(
        val ok: Boolean = false, val mensaje: String? = null, val codigo: String? = null, val message: String? = null,
    )

    @Serializable
    private data class LoginResponse(
        val ok: Boolean = false,
        val mensaje: String? = null,
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("expires_at") val expiresAt: String? = null,
        val perfil: TobbPerfil? = null,
    )
}
