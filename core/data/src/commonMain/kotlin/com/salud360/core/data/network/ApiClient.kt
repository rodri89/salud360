package com.salud360.core.data.network

import com.salud360.core.model.auth.Credenciales
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.sync.PullResponse
import com.salud360.core.model.sync.PushRequest
import com.salud360.core.model.sync.PushResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ApiException(val codigo: Int, mensaje: String) : Exception(mensaje)

/**
 * Error devuelto por turnosonlinebb a través del servidor (`/tobb/...`).
 * @param status HTTP (409 para rechazos de agenda, 412 si el usuario no tiene sesión de turnosonlinebb, 401 si venció).
 * @param codigo código de la API (`ocupado`, `mismo_dia`, `cupo_primer_control`, `dia_con_turnos`, `sin_sesion_tobb`, ...).
 */
class TobbException(val status: Int, val codigo: String?, mensaje: String) : Exception(mensaje)

@Serializable
data class CrearUsuarioRequest(val nombre: String, val apellido: String, val email: String, val password: String, val rol: Rol)

@Serializable
data class CambiarPasswordRequest(val actual: String, val nueva: String)

@Serializable
data class ArchivoSubidoResponse(val id: String, val url: String)

/**
 * Cliente HTTP del servidor Salud 360 (módulo `server`). Todas las llamadas son opcionales:
 * la app funciona sin conexión y sincroniza cuando puede.
 */
class ApiClient(val baseUrl: String, engine: io.ktor.client.engine.HttpClientEngine? = null) {
    var token: String? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    val http: HttpClient = (if (engine != null) HttpClient(engine) else HttpClient()).config {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 15_000 }
        defaultRequest {
            url(baseUrl.trimEnd('/') + "/")
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }

    private suspend fun HttpResponse.verificar(): HttpResponse {
        if (!status.isSuccess()) throw ApiException(status.value, runCatching { bodyAsText() }.getOrDefault(status.description))
        return this
    }

    private fun auth(builder: io.ktor.client.request.HttpRequestBuilder) {
        token?.let { builder.header(HttpHeaders.Authorization, "Bearer $it") }
    }

    suspend fun login(credenciales: Credenciales): Sesion =
        http.post("auth/login") { contentType(ContentType.Application.Json); setBody(credenciales) }.verificar().body()

    suspend fun cambiarPassword(actual: String, nueva: String) {
        http.post("auth/password") { auth(this); contentType(ContentType.Application.Json); setBody(CambiarPasswordRequest(actual, nueva)) }.verificar()
    }

    suspend fun crearUsuario(nombre: String, apellido: String, email: String, password: String, rol: Rol): Usuario =
        http.post("admin/usuarios") { auth(this); contentType(ContentType.Application.Json); setBody(CrearUsuarioRequest(nombre, apellido, email, password, rol)) }.verificar().body()

    suspend fun push(request: PushRequest): PushResponse =
        http.post("sync/push") { auth(this); contentType(ContentType.Application.Json); setBody(request) }.verificar().body()

    suspend fun pull(tabla: String, desde: Long, limite: Int = 500): PullResponse =
        http.get("sync/pull") { auth(this); parameter("tabla", tabla); parameter("desde", desde); parameter("limite", limite) }.verificar().body()

    suspend fun subirArchivo(id: String, nombre: String, mime: String, bytes: ByteArray): ArchivoSubidoResponse =
        http.post("archivos/$id") {
            auth(this)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("archivo", bytes, Headers.build {
                            append(HttpHeaders.ContentType, mime)
                            append(HttpHeaders.ContentDisposition, "filename=\"$nombre\"")
                        })
                    },
                ),
            )
        }.verificar().body()

    suspend fun descargarArchivo(id: String): ByteArray = http.get("archivos/$id") { auth(this) }.verificar().body()

    suspend fun ping(): Boolean = runCatching { http.get("health").status.isSuccess() }.getOrDefault(false)

    // ---- turnosonlinebb (reenviado por el servidor con el token de la web de turnos del usuario) ----

    /**
     * Llama a `/api/salud360/<ruta>` de turnosonlinebb a través del servidor (`/tobb/<ruta>`), que agrega el token
     * del usuario. Devuelve el objeto JSON de la respuesta; si la API responde `ok: false` o un error HTTP lanza [TobbException].
     */
    suspend fun tobb(metodo: HttpMethod, ruta: String, params: Map<String, Any?> = emptyMap(), cuerpo: Any? = null): JsonObject {
        val r = http.request("tobb/" + ruta.trimStart('/')) {
            method = metodo
            auth(this)
            params.forEach { (k, v) -> if (v != null) parameter(k, v) }
            if (cuerpo != null) { contentType(ContentType.Application.Json); setBody(cuerpo) }
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

    suspend fun tobbGet(ruta: String, params: Map<String, Any?> = emptyMap()): JsonObject = tobb(HttpMethod.Get, ruta, params)
    suspend fun tobbPost(ruta: String, cuerpo: Any? = null, params: Map<String, Any?> = emptyMap()): JsonObject = tobb(HttpMethod.Post, ruta, params, cuerpo)
}
