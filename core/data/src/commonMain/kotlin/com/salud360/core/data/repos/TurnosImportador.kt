package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Secretaria
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.tobb.TobbConsultorio
import com.salud360.core.model.tobb.TobbMedico
import com.salud360.core.model.tobb.TobbPerfil
import com.salud360.core.model.tobb.TobbSecretaria
import com.salud360.core.model.turnos.ConfigAgenda
import com.salud360.core.model.turnos.Consultorio
import com.salud360.core.model.turnos.EspecialidadTurnos
import com.salud360.core.model.turnos.MedicoModulo
import com.salud360.core.model.turnos.ModuloTurnos
import kotlinx.datetime.DayOfWeek

/**
 * Importa a la base local el perfil que devuelve turnosonlinebb al iniciar sesión: usuario, médico o
 * secretaria, sus consultorios, módulos y configuración de agenda. Los ids se derivan de los de
 * turnosonlinebb (`tobb-u12`, `tobb-m3`, ...) para que sean estables entre inicios de sesión.
 *
 * Los datos propios de Salud 360 que ya existieran (historias clínicas habilitadas, licencia,
 * código de HC de una especialidad) se conservan. Réplica en el cliente de `TurnosBridge` (server),
 * sin el registro de cambios de sincronización entre dispositivos (eso requiere el servidor propio).
 */
class TurnosImportador(private val db: Salud360Db) {
    /** Importa el perfil que devolvió turnosonlinebb. Devuelve el usuario local. */
    suspend fun importar(p: TobbPerfil): Usuario {
        val rol = when (p.rol) { "admin" -> Rol.ADMIN; "medico" -> Rol.MEDICO; "secretaria" -> Rol.SECRETARIA; else -> throw IllegalStateException("Rol desconocido: ${p.rol}") }
        val email = p.usuario.email.trim().lowercase()
        val existente = db.authQueries.usuarioPorEmail(email).uno { it.toModel() }
        val medico = p.medico
        val secretaria = p.secretaria
        val (nombre, apellido) = when {
            medico != null -> medico.nombre to medico.apellido
            secretaria != null -> secretaria.nombre to secretaria.apellido
            else -> p.usuario.nombre to ""
        }
        val usuario = Usuario(
            id = existente?.id ?: "tobb-u${p.usuario.id}",
            email = email, nombre = nombre.ifBlank { existente?.nombre ?: p.usuario.nombre }, apellido = apellido.ifBlank { existente?.apellido ?: "" },
            rol = rol, foto = existente?.foto, activo = true,
        )
        db.authQueries.upsertUsuario(usuario.toRow(ahoraMillis(), dirty = false))

        when (rol) {
            Rol.MEDICO -> p.medico?.let { importarMedico(it, usuario.id) }
            Rol.SECRETARIA -> p.secretaria?.let { importarSecretaria(it, usuario.id) }
            Rol.ADMIN -> Unit
        }
        return usuario
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
        db.authQueries.upsertMedico(medico.toRow(ahoraMillis(), dirty = false))

        // Módulos de agenda: se marcan activos los que vienen y se desactivan los demás.
        val activos = m.modulos.toSet()
        for (modulo in ModuloTurnos.entries) {
            val mm = MedicoModulo(id, modulo, activo = modulo.codigo in activos)
            db.turnosQueries.upsertMedicoModulo(mm.toRow(ahoraMillis(), dirty = false))
        }
        val cupo = m.cupoPrimerControl.filter { it.dia in 1..7 }.associate { DayOfWeek(it.dia) to it.cantidad }
        val previaConfig = db.turnosQueries.configDeMedico(id).uno { it.toModel() }
        val config = ConfigAgenda(id, ventanaDias = m.ventanaDias, valorConsulta = previaConfig?.valorConsulta ?: 0.0, cupoPrimerControl = cupo)
        db.turnosQueries.upsertConfigAgenda(config.toRow(ahoraMillis(), dirty = false))
    }

    private suspend fun importarSecretaria(s: TobbSecretaria, usuarioId: String) {
        val consultorios = s.consultorios.map { importarConsultorio(it) }
        val medicos = s.medicos.map { m -> importarMedico(m, null); "tobb-m${m.id}" }
        val id = "tobb-s${s.id}"
        val sec = Secretaria(id, usuarioId, s.nombre, s.apellido, consultorioIds = consultorios, medicoIds = medicos, activo = true)
        db.authQueries.upsertSecretaria(sec.toRow(ahoraMillis(), dirty = false))
    }

    private suspend fun importarConsultorio(c: TobbConsultorio): String {
        val id = "tobb-c${c.id}"
        val previo = db.turnosQueries.consultorioPorId(id).uno { it.toModel() }
        val consultorio = Consultorio(id, c.nombre.ifBlank { previo?.nombre ?: "Consultorio ${c.id}" }, c.direccion ?: "", c.telefono ?: "", previo?.foto, previo?.colorPrimario, previo?.colorSecundario, true)
        db.turnosQueries.upsertConsultorio(consultorio.toRow(ahoraMillis(), dirty = false))
        return id
    }

    private suspend fun importarEspecialidad(tobbId: Long, nombre: String?): String {
        val id = "tobb-e$tobbId"
        val previa = db.authQueries.especialidadPorId(id).uno { it.toModel() }
        val esp = EspecialidadTurnos(id, nombre?.ifBlank { null } ?: previa?.nombre ?: "Especialidad $tobbId", codigoHc = previa?.codigoHc ?: codigoHcPorNombre(nombre), color = previa?.color, activo = true)
        db.authQueries.upsertEspecialidad(esp.toRow(ahoraMillis(), dirty = false))
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
}
