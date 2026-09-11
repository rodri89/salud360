package com.salud360.server

import app.cash.sqldelight.db.QueryResult
import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Secretaria
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.sync.Tablas
import com.salud360.core.model.turnos.ConfigAgenda
import com.salud360.core.model.turnos.Consultorio
import com.salud360.core.model.turnos.EspecialidadTurnos
import com.salud360.core.model.turnos.MedicoModulo
import com.salud360.core.model.turnos.ModuloTurnos
import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

/**
 * Puente entre turnosonlinebb y la base de Salud 360.
 *
 * Al iniciar sesión contra la API de turnos, replica en la base local (y en el registro de cambios
 * que sincronizan las apps) el usuario, el médico o la secretaria, sus consultorios, módulos y
 * configuración de agenda. Los ids se derivan de los de turnosonlinebb (`tobb-u12`, `tobb-m3`, ...)
 * para que sean estables entre inicios de sesión.
 *
 * Los datos propios de Salud 360 que ya existieran (historias clínicas habilitadas, licencia,
 * código de HC de una especialidad) se conservan.
 */
class TurnosBridge(private val db: Salud360Db, private val sync: SyncService) {
    private val log = LoggerFactory.getLogger("TurnosBridge")
    private val json = Json { encodeDefaults = true }
    private val driver get() = db.driverInterno()

    fun inicializar() {
        driver.execute(null, "CREATE TABLE IF NOT EXISTS tobb_sesion (usuario_id TEXT PRIMARY KEY, token TEXT NOT NULL, expira TEXT, medico_tobb_id INTEGER, actualizado_en INTEGER NOT NULL)", 0)
    }

    /** Importa el perfil que devolvió turnosonlinebb y guarda su token. Devuelve el usuario local. */
    suspend fun importarSesion(p: TobbPerfil, token: String, expira: String?): Usuario {
        val rol = when (p.rol) { "admin" -> Rol.ADMIN; "medico" -> Rol.MEDICO; "secretaria" -> Rol.SECRETARIA; else -> throw IllegalStateException("Rol desconocido: ${p.rol}") }
        val email = p.usuario.email.trim().lowercase()
        val existente = db.authQueries.usuarioPorEmail(email).uno { it.toModel() }
        val (nombre, apellido) = when {
            p.medico != null -> p.medico.nombre to p.medico.apellido
            p.secretaria != null -> p.secretaria.nombre to p.secretaria.apellido
            else -> p.usuario.nombre to ""
        }
        val usuario = Usuario(
            id = existente?.id ?: "tobb-u${p.usuario.id}",
            email = email, nombre = nombre.ifBlank { existente?.nombre ?: p.usuario.nombre }, apellido = apellido.ifBlank { existente?.apellido ?: "" },
            rol = rol, foto = existente?.foto, activo = true,
        )
        guardar(Tablas.USUARIOS, usuario.id, usuario, Usuario.serializer()) { db.authQueries.upsertUsuario(it.toRow(ahoraMillis(), dirty = false)) }

        when (rol) {
            Rol.MEDICO -> p.medico?.let { importarMedico(it, usuario.id) }
            Rol.SECRETARIA -> p.secretaria?.let { importarSecretaria(it, usuario.id) }
            Rol.ADMIN -> Unit
        }

        driver.execute(null, "INSERT OR REPLACE INTO tobb_sesion(usuario_id, token, expira, medico_tobb_id, actualizado_en) VALUES (?, ?, ?, ?, ?)", 5) {
            bindString(0, usuario.id); bindString(1, token); bindString(2, expira); bindLong(3, p.medico?.id); bindLong(4, System.currentTimeMillis())
        }
        log.info("Sesión de turnosonlinebb importada para $email (${rol.name})")
        return usuario
    }

    /** Token de turnosonlinebb guardado para un usuario local (para reenviar pedidos de turnos). */
    fun tokenDe(usuarioId: String): String? {
        var token: String? = null
        driver.executeQuery(null, "SELECT token FROM tobb_sesion WHERE usuario_id = ?", { c ->
            if (c.next().value) token = c.getString(0)
            QueryResult.Unit
        }, 1) { bindString(0, usuarioId) }
        return token
    }

    /** Olvida el token de turnosonlinebb de un usuario (por ejemplo cuando la web responde 401). */
    fun borrarSesion(usuarioId: String) {
        driver.execute(null, "DELETE FROM tobb_sesion WHERE usuario_id = ?", 1) { bindString(0, usuarioId) }
    }

    private suspend fun importarMedico(m: TobbMedico, usuarioId: String?) {
        val consultorioId = m.consultorio?.let { importarConsultorio(it) } ?: m.consultorioId?.let { "tobb-c$it" }
        val especialidadId = m.especialidadId?.let { importarEspecialidad(it, m.especialidad) }
        val id = "tobb-m${m.id}"
        val previo = db.authQueries.medicoPorId(id).uno { it.toModel() }
        val medico = Medico(
            id = id,
            // Si el médico todavía no inició sesión (lo trajo una secretaria) queda un usuario provisorio.
            usuarioId = usuarioId ?: previo?.usuarioId ?: "tobb-mu${m.id}",
            nombre = m.nombre, apellido = m.apellido,
            telefono = m.telefono ?: "", mail = (m.mail ?: "").lowercase(), sexo = m.sexo ?: "",
            foto = m.foto?.takeIf { it.isNotBlank() && it != "medico_sin_foto.png" } ?: previo?.foto,
            consultorioId = consultorioId, especialidadId = especialidadId,
            especialidadesHc = previo?.especialidadesHc ?: emptyList(),
            tieneTurnos = true, visibleEnTurnos = previo?.visibleEnTurnos ?: true, activo = m.activo == 1,
        )
        guardar(Tablas.MEDICOS, id, medico, Medico.serializer()) { db.authQueries.upsertMedico(it.toRow(ahoraMillis(), dirty = false)) }

        // Módulos de agenda: se marcan activos los que vienen y se desactivan los demás.
        val activos = m.modulos.toSet()
        for (modulo in ModuloTurnos.entries) {
            val mm = MedicoModulo(id, modulo, activo = modulo.codigo in activos)
            guardar(Tablas.MEDICO_MODULOS, "$id|${modulo.codigo}", mm, MedicoModulo.serializer()) { db.turnosQueries.upsertMedicoModulo(it.toRow(ahoraMillis(), dirty = false)) }
        }
        val cupo = m.cupoPrimerControl.filter { it.dia in 1..7 }.associate { DayOfWeek(it.dia) to it.cantidad }
        val previaConfig = db.turnosQueries.configDeMedico(id).uno { it.toModel() }
        val config = ConfigAgenda(id, ventanaDias = m.ventanaDias, valorConsulta = previaConfig?.valorConsulta ?: 0.0, cupoPrimerControl = cupo)
        guardar(Tablas.CONFIG_AGENDA, id, config, ConfigAgenda.serializer()) { db.turnosQueries.upsertConfigAgenda(it.toRow(ahoraMillis(), dirty = false)) }
    }

    private suspend fun importarSecretaria(s: TobbSecretaria, usuarioId: String) {
        val consultorios = s.consultorios.map { importarConsultorio(it) }
        val medicos = s.medicos.map { m -> importarMedico(m, null); "tobb-m${m.id}" }
        val id = "tobb-s${s.id}"
        val sec = Secretaria(id, usuarioId, s.nombre, s.apellido, consultorioIds = consultorios, medicoIds = medicos, activo = true)
        guardar(Tablas.SECRETARIAS, id, sec, Secretaria.serializer()) { db.authQueries.upsertSecretaria(it.toRow(ahoraMillis(), dirty = false)) }
    }

    private suspend fun importarConsultorio(c: TobbConsultorio): String {
        val id = "tobb-c${c.id}"
        val previo = db.turnosQueries.consultorioPorId(id).uno { it.toModel() }
        val consultorio = Consultorio(id, c.nombre.ifBlank { previo?.nombre ?: "Consultorio ${c.id}" }, c.direccion ?: "", c.telefono ?: "", previo?.foto, previo?.colorPrimario, previo?.colorSecundario, true)
        guardar(Tablas.CONSULTORIOS, id, consultorio, Consultorio.serializer()) { db.turnosQueries.upsertConsultorio(it.toRow(ahoraMillis(), dirty = false)) }
        return id
    }

    private suspend fun importarEspecialidad(tobbId: Long, nombre: String?): String {
        val id = "tobb-e$tobbId"
        val previa = db.authQueries.especialidadPorId(id).uno { it.toModel() }
        val esp = EspecialidadTurnos(id, nombre?.ifBlank { null } ?: previa?.nombre ?: "Especialidad $tobbId", codigoHc = previa?.codigoHc ?: codigoHcPorNombre(nombre), color = previa?.color, activo = true)
        guardar(Tablas.ESPECIALIDADES, id, esp, EspecialidadTurnos.serializer()) { db.authQueries.upsertEspecialidad(it.toRow(ahoraMillis(), dirty = false)) }
        return id
    }

    /** Sugerencia inicial del código de HC según el nombre de la especialidad de turnos; el admin puede cambiarlo. */
    private fun codigoHcPorNombre(nombre: String?): String? {
        val n = nombre?.lowercase() ?: return null
        return when {
            "pediatr" in n -> "pediatria"
            "ginec" in n || "obstet" in n -> "gineco"
            "cardio" in n -> "cardiologia"
            "endocrin" in n -> "endocrinologia"
            "hemato" in n -> "hematologia"
            "hepato" in n -> "hepatologia"
            "clínic" in n || "clinic" in n -> "clinica"
            "desarrollo" in n || "neurodesarrollo" in n -> "desarrollo_infantil"
            else -> null
        }
    }

    /** Escribe en la base y deja el cambio en el registro para que lo bajen las apps. */
    private suspend fun <T> guardar(tabla: String, registroId: String, modelo: T, serializer: KSerializer<T>, upsert: suspend (T) -> Unit) {
        upsert(modelo)
        sync.registrarInterno(tabla, registroId, json.encodeToJsonElement(serializer, modelo).jsonObject)
    }
}
