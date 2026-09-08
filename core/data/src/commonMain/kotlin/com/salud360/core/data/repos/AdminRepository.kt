package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.flujoLista
import com.salud360.core.data.flujoUno
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Secretaria
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.newId
import com.salud360.core.model.turnos.EspecialidadTurnos
import kotlinx.coroutines.flow.Flow

/**
 * Administración: usuarios, médicos, secretarias, especialidades y licencias.
 * El alta de usuario (con contraseña) requiere conexión porque la contraseña se guarda solo en el servidor.
 */
class AdminRepository(private val db: Salud360Db, private val api: ApiClient) {
    private val q get() = db.authQueries

    fun observarUsuarios(): Flow<List<Usuario>> = q.usuarios().flujoLista { it.toModel() }
    fun observarUsuariosPorRol(rol: Rol): Flow<List<Usuario>> = q.usuariosPorRol(rol.name).flujoLista { it.toModel() }
    suspend fun usuario(id: Id): Usuario? = q.usuarioPorId(id).uno { it.toModel() }
    suspend fun usuarioPorEmail(email: String): Usuario? = q.usuarioPorEmail(email).uno { it.toModel() }

    fun observarMedicos(): Flow<List<Medico>> = q.medicos().flujoLista { it.toModel() }
    fun observarMedico(id: Id): Flow<Medico?> = q.medicoPorId(id).flujoUno { it.toModel() }
    suspend fun medico(id: Id): Medico? = q.medicoPorId(id).uno { it.toModel() }
    suspend fun medicos(ids: List<Id>): List<Medico> = if (ids.isEmpty()) emptyList() else q.medicosPorIds(ids).lista { it.toModel() }
    suspend fun medicosDeConsultorio(consultorioId: Id): List<Medico> = q.medicosPorConsultorio(consultorioId).lista { it.toModel() }
    suspend fun medicoDeUsuario(usuarioId: Id): Medico? = q.medicoPorUsuario(usuarioId).uno { it.toModel() }

    fun observarSecretarias(): Flow<List<Secretaria>> = q.secretarias().flujoLista { it.toModel() }
    suspend fun secretaria(id: Id): Secretaria? = q.secretariaPorId(id).uno { it.toModel() }

    fun observarEspecialidades(): Flow<List<EspecialidadTurnos>> = q.especialidades().flujoLista { it.toModel() }
    suspend fun guardarEspecialidad(e: EspecialidadTurnos) = q.upsertEspecialidad(e.toRow(ahoraMillis()))

    fun observarLicencias(): Flow<List<Licencia>> = q.licencias().flujoLista { it.toModel() }
    suspend fun licencia(medicoId: Id): Licencia? = q.licenciaPorMedico(medicoId).uno { it.toModel() }
    suspend fun guardarLicencia(l: Licencia) = q.upsertLicencia(l.toRow(ahoraMillis()))

    /**
     * Crea un usuario en el servidor (con contraseña) y su perfil de médico o secretaria.
     * Devuelve el usuario creado. Requiere conexión.
     */
    suspend fun crearUsuario(nombre: String, apellido: String, email: String, password: String, rol: Rol): Usuario {
        val creado = api.crearUsuario(nombre.trim(), apellido.trim(), email.trim().lowercase(), password, rol)
        q.upsertUsuario(creado.toRow(ahoraMillis(), dirty = false))
        when (rol) {
            Rol.MEDICO -> if (medicoDeUsuario(creado.id) == null) {
                q.upsertMedico(Medico(newId(), creado.id, creado.nombre, creado.apellido, mail = creado.email).toRow(ahoraMillis()))
            }
            Rol.SECRETARIA -> if (q.secretariaPorUsuario(creado.id).uno { it } == null) {
                q.upsertSecretaria(Secretaria(newId(), creado.id, creado.nombre, creado.apellido).toRow(ahoraMillis()))
            }
            Rol.ADMIN -> Unit
        }
        return creado
    }

    suspend fun guardarUsuario(u: Usuario) = q.upsertUsuario(u.toRow(ahoraMillis()))
    suspend fun guardarMedico(m: Medico) = q.upsertMedico(m.toRow(ahoraMillis()))
    suspend fun guardarSecretaria(s: Secretaria) = q.upsertSecretaria(s.toRow(ahoraMillis()))

    /** Habilita o deshabilita una historia clínica para el médico (puede tener varias). */
    suspend fun setEspecialidadHc(medicoId: Id, codigo: String, habilitada: Boolean) {
        val m = medico(medicoId) ?: return
        val nuevas = if (habilitada) (m.especialidadesHc + codigo).distinct() else m.especialidadesHc - codigo
        guardarMedico(m.copy(especialidadesHc = nuevas))
    }

    suspend fun vincularSecretariaMedico(secretariaId: Id, medicoId: Id, vincular: Boolean = true) {
        val s = secretaria(secretariaId) ?: return
        guardarSecretaria(s.copy(medicoIds = if (vincular) (s.medicoIds + medicoId).distinct() else s.medicoIds - medicoId))
    }

    suspend fun vincularSecretariaConsultorio(secretariaId: Id, consultorioId: Id, vincular: Boolean = true) {
        val s = secretaria(secretariaId) ?: return
        guardarSecretaria(s.copy(consultorioIds = if (vincular) (s.consultorioIds + consultorioId).distinct() else s.consultorioIds - consultorioId))
    }
}
