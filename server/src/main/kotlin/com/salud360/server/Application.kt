package com.salud360.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.network.CambiarPasswordRequest
import com.salud360.core.data.network.CrearUsuarioRequest
import com.salud360.core.data.repos.resolverPerfil
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.model.auth.Credenciales
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.sync.PushRequest
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Date

/**
 * Servidor Salud 360: autenticación (JWT), sincronización offline-first y adjuntos.
 *
 * Variables de entorno:
 * - PORT (8080), DB_PATH (salud360-server.db), ADJUNTOS_DIR (adjuntos), JWT_SECRET,
 * - ADMIN_EMAIL / ADMIN_PASSWORD para crear el primer administrador si la base está vacía.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dbPath = System.getenv("DB_PATH") ?: "salud360-server.db"
    val adjuntosDir = File(System.getenv("ADJUNTOS_DIR") ?: "adjuntos").apply { mkdirs() }
    val jwtSecret = System.getenv("JWT_SECRET") ?: "cambiar-este-secreto-en-produccion"

    val driver = runBlocking { DriverFactory(dbPath).createDriver() }
    ServerDb.driver = driver
    val db = com.salud360.core.database.Salud360Db(driver)
    val credenciales = Credenciales_(db)
    val sync = SyncService(db)
    runBlocking {
        credenciales.inicializar()
        Bootstrap.crearAdminSiHaceFalta(db, credenciales)
    }

    embeddedServer(Netty, port = port) { modulo(db, credenciales, sync, adjuntosDir, jwtSecret) }.start(wait = true)
}

fun Application.modulo(db: com.salud360.core.database.Salud360Db, credenciales: Credenciales_, sync: SyncService, adjuntosDir: File, jwtSecret: String) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    val algoritmo = Algorithm.HMAC256(jwtSecret)

    install(ContentNegotiation) { json(json) }
    install(CallLogging)
    install(CORS) {
        anyHost() // la app web puede servirse desde otro dominio; restringir en producción
        allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get); allowMethod(HttpMethod.Post); allowMethod(HttpMethod.Options)
    }
    install(StatusPages) {
        exception<Throwable> { call, e -> call.application.environment.log.error("Error", e); call.respondText(e.message ?: "Error", status = HttpStatusCode.InternalServerError) }
    }
    install(Authentication) {
        jwt("auth") {
            verifier(JWT.require(algoritmo).withIssuer("salud360").build())
            validate { cred -> cred.payload.getClaim("uid").asString()?.let { JWTPrincipal(cred.payload) } }
        }
    }

    fun emitirToken(u: Usuario): String = JWT.create().withIssuer("salud360").withClaim("uid", u.id).withClaim("rol", u.rol.name)
        .withExpiresAt(Date(System.currentTimeMillis() + 90L * 24 * 3600 * 1000)).sign(algoritmo)

    suspend fun usuarioDe(principal: JWTPrincipal?): Usuario? = principal?.payload?.getClaim("uid")?.asString()?.let { db.authQueries.usuarioPorId(it).uno { r -> r.toModel() } }

    routing {
        get("health") { call.respondText("ok") }

        post("auth/login") {
            val c = call.receive<Credenciales>()
            val u = db.authQueries.usuarioPorEmail(c.email.trim().lowercase()).uno { it.toModel() }
            if (u == null || !u.activo || !credenciales.verificar(u.id, c.password)) return@post call.respond(HttpStatusCode.Unauthorized, "Credenciales inválidas")
            val sesion = resolverPerfil(db, u) ?: return@post call.respond(HttpStatusCode.Forbidden, "El usuario no tiene perfil asignado")
            call.respond(sesion.copy(token = emitirToken(u)))
        }

        authenticate("auth") {
            post("auth/password") {
                val u = usuarioDe(call.principal()) ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val r = call.receive<CambiarPasswordRequest>()
                if (!credenciales.verificar(u.id, r.actual)) return@post call.respond(HttpStatusCode.BadRequest, "Contraseña actual incorrecta")
                credenciales.guardar(u.id, r.nueva)
                call.respond(HttpStatusCode.OK)
            }

            post("admin/usuarios") {
                val actual = usuarioDe(call.principal()) ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (actual.rol != Rol.ADMIN) return@post call.respond(HttpStatusCode.Forbidden, "Solo el administrador crea usuarios")
                val r = call.receive<CrearUsuarioRequest>()
                if (db.authQueries.usuarioPorEmail(r.email).uno { it } != null) return@post call.respond(HttpStatusCode.Conflict, "Ya existe un usuario con ese mail")
                val nuevo = Bootstrap.crearUsuario(db, credenciales, r.nombre, r.apellido, r.email, r.password, r.rol, sync)
                call.respond(nuevo)
            }

            post("sync/push") {
                val u = usuarioDe(call.principal()) ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val req = call.receive<PushRequest>()
                call.respond(sync.push(req, u))
            }

            get("sync/pull") {
                usuarioDe(call.principal()) ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val tabla = call.request.queryParameters["tabla"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Falta tabla")
                val desde = call.request.queryParameters["desde"]?.toLongOrNull() ?: 0L
                val limite = call.request.queryParameters["limite"]?.toIntOrNull() ?: 500
                call.respond(sync.pull(tabla, desde, limite))
            }

            post("archivos/{id}") {
                usuarioDe(call.principal()) ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val id = call.parameters["id"]!!.filter { it.isLetterOrDigit() || it == '-' }
                var nombre = "archivo"
                var bytes: ByteArray? = null
                call.receiveMultipart(formFieldLimit = 100L * 1024 * 1024).forEachPart { part ->
                    if (part is PartData.FileItem) { nombre = part.originalFileName ?: nombre; bytes = part.provider().toByteArray() }
                    part.dispose()
                }
                val b = bytes ?: return@post call.respond(HttpStatusCode.BadRequest, "Sin archivo")
                val ext = nombre.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }
                File(adjuntosDir, "$id.$ext").writeBytes(b)
                File(adjuntosDir, "$id.meta").writeText(ext)
                call.respond(mapOf("id" to id, "url" to "/archivos/$id"))
            }

            get("archivos/{id}") {
                usuarioDe(call.principal()) ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val id = call.parameters["id"]!!.filter { it.isLetterOrDigit() || it == '-' }
                val ext = File(adjuntosDir, "$id.meta").takeIf { it.exists() }?.readText() ?: return@get call.respond(HttpStatusCode.NotFound)
                val f = File(adjuntosDir, "$id.$ext")
                if (!f.exists()) return@get call.respond(HttpStatusCode.NotFound)
                call.respondBytes(f.readBytes(), ContentType.Application.OctetStream)
            }
        }
    }
}
