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
class AdminRepository(
    private val db: Salud360Db,
    private val api: ApiClient,
    /**
     * Historias clínicas con API propia (hoy pediatría). Son ellas las dueñas de las licencias: la
     * licencia es el permiso para entrar a *esa* historia clínica y se cobra ahí, así que el panel la
     * lee y la escribe contra la especialidad, no contra la base del dispositivo.
     */
    private val backends: Map<String, HcBackend> = emptyMap(),
) {
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

    /**
     * Trae las licencias de cada historia clínica y las deja en el dispositivo.
     *
     * Quedan limpias (`dirty = false`): el dueño del dato es la especialidad, no este dispositivo, así
     * que no hay nada que empujar después. Las escribe solamente el administrador; para un médico la
     * API no informa ninguna y la pestaña queda como estaba.
     */
    suspend fun sincronizarLicencias() {
        backends.values.forEach { backend ->
            val ahora = ahoraMillis()
            backend.traerLicencias().forEach { q.upsertLicencia(it.toRow(ahora, dirty = false)) }
        }
    }

    /**
     * Guarda la licencia en el dispositivo y en la historia clínica que la cobra.
     *
     * Primero local, para que la pantalla no espere a la red, y después el envío. Devuelve false si la
     * especialidad no la aceptó: el médico seguiría entrando (o sin entrar) con lo que hay allá, que
     * es lo que manda, así que ese caso hay que mostrarlo y no dejarlo pasar en silencio.
     */
    suspend fun guardarLicencia(l: Licencia): Boolean {
        q.upsertLicencia(l.toRow(ahoraMillis()))
        val destinos = (medico(l.medicoId)?.especialidadesHc ?: emptyList()).mapNotNull { backends[it] }
        if (destinos.isEmpty()) return true
        val guardada = destinos.map { it.guardarLicencia(l) }.any { it }
        if (guardada) q.upsertLicencia(l.toRow(ahoraMillis(), dirty = false))
        return guardada
    }

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
