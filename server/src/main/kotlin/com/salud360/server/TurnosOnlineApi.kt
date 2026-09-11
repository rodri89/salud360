package com.salud360.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Cliente de la API `/api/salud360/...` de turnosonlinebb (Laravel).
 * Laravel sigue siendo el dueño de la base de turnos; este servidor actúa de intermediario.
 * Se configura con la variable de entorno `TURNOS_API_URL` (ej. https://turnosonlinebb.com).
 */
class TurnosOnlineApi(baseUrl: String) {
    private val log = LoggerFactory.getLogger("TurnosOnlineApi")
    private val base = baseUrl.trimEnd('/') + "/api/salud360/"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
        expectSuccess = false
    }

    sealed interface ResultadoLogin {
        data class Ok(val token: String, val expira: String?, val perfil: TobbPerfil) : ResultadoLogin
        data class Rechazado(val mensaje: String, val status: Int) : ResultadoLogin
        data class Error(val mensaje: String) : ResultadoLogin
    }

    /** POST auth/login con el mail y la contraseña de la web de turnos. */
    suspend fun login(email: String, password: String): ResultadoLogin = try {
        val r = http.post(base + "auth/login") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(LoginBody(email, password))
        }
        val texto = r.bodyAsText()
        if (r.status.isSuccess()) {
            val body = json.decodeFromString(LoginResponse.serializer(), texto)
            if (body.ok && body.accessToken != null && body.perfil != null) ResultadoLogin.Ok(body.accessToken, body.expiresAt, body.perfil)
            else ResultadoLogin.Rechazado(body.mensaje ?: "Respuesta inválida de turnosonlinebb", r.status.value)
        } else {
            val error = runCatching { json.decodeFromString(ErrorResponse.serializer(), texto) }.getOrNull()
            if (r.status.value >= 500 || error?.exception != null) {
                // Error interno de Laravel: se deja el detalle en el log (con APP_DEBUG=true trae excepción, archivo y línea).
                log.warn("turnosonlinebb respondió ${r.status.value} al login de '$email': ${error?.exception ?: ""} ${error?.message ?: ""} ${error?.file ?: ""}:${error?.line ?: ""} | cuerpo: ${texto.take(600)}")
            }
            val mensaje = error?.mensaje ?: error?.message?.takeIf { r.status.value < 500 }
                ?: if (r.status.value >= 500) "turnosonlinebb respondió ${r.status.value} (error interno de la web de turnos; revisar storage/logs/laravel.log)" else "turnosonlinebb respondió ${r.status.value}"
            ResultadoLogin.Rechazado(mensaje, r.status.value)
        }
    } catch (e: Exception) {
        log.warn("No se pudo consultar turnosonlinebb: ${e.message}")
        ResultadoLogin.Error(e.message ?: "sin conexión con turnosonlinebb")
    }

    /** GET auth/perfil con un token guardado (para refrescar datos). */
    suspend fun perfil(token: String): TobbPerfil? = try {
        // Se manda también X-Salud360-Token por si el hosting descarta el encabezado Authorization.
        val r = http.get(base + "auth/perfil") { accept(ContentType.Application.Json); bearerAuth(token); header("X-Salud360-Token", token) }
        if (r.status == HttpStatusCode.OK) json.decodeFromString(PerfilResponse.serializer(), r.bodyAsText()).perfil else null
    } catch (e: Exception) {
        log.warn("No se pudo leer el perfil de turnosonlinebb: ${e.message}"); null
    }

    /** Respuesta cruda de turnosonlinebb (estado HTTP + cuerpo JSON) para devolverla tal cual a la app. */
    data class Reenviada(val status: Int, val cuerpo: String)

    /**
     * Reenvía un pedido de la app a `/api/salud360/<ruta>` con el token de turnosonlinebb del usuario.
     * El cuerpo (si hay) ya viene como JSON; Laravel lo lee igual que un formulario.
     */
    suspend fun reenviar(token: String, metodo: HttpMethod, ruta: String, query: Map<String, List<String>>, cuerpo: String?): Reenviada {
        val r = http.request(base + ruta.trimStart('/')) {
            method = metodo
            accept(ContentType.Application.Json)
            bearerAuth(token); header("X-Salud360-Token", token)
            query.forEach { (k, valores) -> valores.forEach { parameter(k, it) } }
            if (!cuerpo.isNullOrBlank()) { contentType(ContentType.Application.Json); setBody(cuerpo) }
        }
        return Reenviada(r.status.value, r.bodyAsText())
    }

    @Serializable
    private data class LoginBody(val email: String, val password: String)

    /** Error de la API (`ok`/`mensaje`/`codigo`) o de Laravel (`message`, y con APP_DEBUG `exception`/`file`/`line`). */
    @Serializable
    private data class ErrorResponse(
        val ok: Boolean = false, val mensaje: String? = null, val codigo: String? = null,
        val message: String? = null, val exception: String? = null, val file: String? = null, val line: Int? = null,
    )

    @Serializable
    private data class LoginResponse(
        val ok: Boolean = false,
        val mensaje: String? = null,
        @kotlinx.serialization.SerialName("access_token") val accessToken: String? = null,
        @kotlinx.serialization.SerialName("expires_at") val expiresAt: String? = null,
        val perfil: TobbPerfil? = null,
    )

    @Serializable
    private data class PerfilResponse(val ok: Boolean = false, val perfil: TobbPerfil? = null)
}

// ---- Estructuras que devuelve la API de turnosonlinebb (ver API_SALUD360.md en ese repo) ----

@Serializable
data class TobbPerfil(
    val usuario: TobbUsuario,
    val rol: String,
    val medico: TobbMedico? = null,
    val secretaria: TobbSecretaria? = null,
)

@Serializable
data class TobbUsuario(val id: Long, val nombre: String = "", val email: String, val tipo: Int, val perfil: Int = 0)

@Serializable
data class TobbConsultorio(val id: Long, val nombre: String = "", val direccion: String? = "", val telefono: String? = "")

@Serializable
data class TobbCupoPrimerControl(val id: Long = 0, val dia: Int, val consultorio: Long = 0, val cantidad: Int)

@Serializable
data class TobbMedico(
    val id: Long,
    val nombre: String = "",
    val apellido: String = "",
    val mail: String? = "",
    val telefono: String? = "",
    val sexo: String? = "",
    val foto: String? = null,
    @kotlinx.serialization.SerialName("especialidad_id") val especialidadId: Long? = null,
    val especialidad: String? = null,
    @kotlinx.serialization.SerialName("consultorio_id") val consultorioId: Long? = null,
    val activo: Int = 1,
    val consultorio: TobbConsultorio? = null,
    val modulos: List<Int> = emptyList(),
    @kotlinx.serialization.SerialName("ventana_dias") val ventanaDias: Int = 180,
    @kotlinx.serialization.SerialName("cupo_primer_control") val cupoPrimerControl: List<TobbCupoPrimerControl> = emptyList(),
)

@Serializable
data class TobbSecretaria(
    val id: Long,
    val nombre: String = "",
    val apellido: String = "",
    val consultorios: List<TobbConsultorio> = emptyList(),
    val medicos: List<TobbMedico> = emptyList(),
)
