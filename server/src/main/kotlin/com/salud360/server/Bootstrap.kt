package com.salud360.server

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.mappers.toRow
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Secretaria
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.newId
import com.salud360.core.model.sync.Tablas
import com.salud360.core.model.turnos.EspecialidadTurnos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

/** Acceso al driver para tablas propias del servidor (credenciales, registro de cambios). */
fun Salud360Db.driverInterno(): SqlDriver = ServerDb.driver

object ServerDb {
    lateinit var driver: SqlDriver
}

object Bootstrap {
    private val log = LoggerFactory.getLogger("Bootstrap")
    private val json = Json { encodeDefaults = true }

    /** Si no hay usuarios, crea el administrador inicial y el catálogo de especialidades. */
    suspend fun crearAdminSiHaceFalta(db: Salud360Db, credenciales: Credenciales_) {
        if (db.authQueries.usuarios().awaitAsList().isNotEmpty()) return
        val email = System.getenv("ADMIN_EMAIL") ?: "admin@salud360.local"
        val password = System.getenv("ADMIN_PASSWORD") ?: "admin123"
        val admin = Usuario(newId(), email.lowercase(), "Administrador", "Salud 360", Rol.ADMIN)
        db.authQueries.upsertUsuario(admin.toRow(ahoraMillis(), dirty = false))
        credenciales.guardar(admin.id, password)
        listOf("Clínica médica" to "clinica", "Pediatría" to "pediatria", "Ginecología" to "gineco", "Cardiología" to "cardiologia",
            "Endocrinología" to "endocrinologia", "Hematología" to "hematologia", "Desarrollo infantil" to "desarrollo_infantil", "Hepatología" to "hepatologia")
            .forEach { (n, c) -> db.authQueries.upsertEspecialidad(EspecialidadTurnos(newId(), n, c).toRow(ahoraMillis(), dirty = false)) }
        log.warn("Se creó el administrador inicial $email (cambiá la contraseña al ingresar)")
    }

    /** Alta de usuario desde el administrador: usuario + credencial + perfil vacío de médico/secretaria. */
    suspend fun crearUsuario(db: Salud360Db, credenciales: Credenciales_, nombre: String, apellido: String, email: String, password: String, rol: Rol, sync: SyncService): Usuario {
        val u = Usuario(newId(), email.trim().lowercase(), nombre.trim(), apellido.trim(), rol)
        db.authQueries.upsertUsuario(u.toRow(ahoraMillis(), dirty = false))
        credenciales.guardar(u.id, password)
        sync.registrarInterno(Tablas.USUARIOS, u.id, json.encodeToJsonElement(Usuario.serializer(), u).jsonObject)
        when (rol) {
            Rol.MEDICO -> {
                val m = Medico(newId(), u.id, u.nombre, u.apellido, mail = u.email)
                db.authQueries.upsertMedico(m.toRow(ahoraMillis(), dirty = false))
                sync.registrarInterno(Tablas.MEDICOS, m.id, json.encodeToJsonElement(Medico.serializer(), m).jsonObject)
            }
            Rol.SECRETARIA -> {
                val s = Secretaria(newId(), u.id, u.nombre, u.apellido)
                db.authQueries.upsertSecretaria(s.toRow(ahoraMillis(), dirty = false))
                sync.registrarInterno(Tablas.SECRETARIAS, s.id, json.encodeToJsonElement(Secretaria.serializer(), s).jsonObject)
            }
            Rol.ADMIN -> Unit
        }
        return u
    }
}
