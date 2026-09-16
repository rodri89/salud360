package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.json
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.Credenciales
import com.salud360.core.model.auth.PerfilMedico
import com.salud360.core.model.auth.PerfilSecretaria
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.toLocalDateTime

sealed interface ResultadoLogin {
    data class Ok(val sesion: Sesion, val offline: Boolean = false) : ResultadoLogin
    data object CredencialesInvalidas : ResultadoLogin
    data object SinConexionYSinSesionPrevia : ResultadoLogin
    data class LicenciaVencida(val sesion: Sesion) : ResultadoLogin
    data class Error(val mensaje: String) : ResultadoLogin
}

/**
 * Autenticación y sesión.
 *
 * El médico no elige especialidad: a partir de su email el servidor (o la base local si no hay
 * conexión) devuelve su [PerfilMedico] con las historias clínicas habilitadas y si tiene agenda
 * de turnos. Un médico puede tener más de una historia clínica.
 *
 * La última sesión se guarda cifrada por el sistema operativo (base local) para permitir abrir
 * la app sin conexión.
 */
/**
 * @param settings copia de la sesión fuera de la base (SharedPreferences / NSUserDefaults / localStorage). En web la base
 * local vive en memoria y se pierde al recargar la página; con esta copia la sesión sobrevive y el perfil se vuelve a
 * traer de turnosonlinebb.
 */
class AuthRepository(
    private val db: Salud360Db,
    private val api: ApiClient,
    private val turnos: TurnosOnlineClient = TurnosOnlineClient(),
    private val settings: Settings? = null,
) {
    private val importador = TurnosImportador(db)
    private val _sesion = MutableStateFlow<Sesion?>(null)
    val sesion: StateFlow<Sesion?> = _sesion.asStateFlow()

    val usuarioActual: Usuario? get() = _sesion.value?.usuario

    /**
     * Restaura la sesión guardada al abrir la app y refresca médico/consultorio desde turnosonlinebb
     * (por si cambiaron desde el último login; si falla —sin conexión, etc.— sigue con lo guardado).
     */
    suspend fun restaurar(): Sesion? {
        val fila = db.authQueries.sesionActual().uno { it }
        // Sin fila en la base (web recién cargada) se usa la copia de Settings y se vuelve a dejar en la base.
        val texto = fila?.sesion_json ?: settings?.getStringOrNull(CLAVE_SESION)?.takeIf { it.isNotBlank() } ?: return null
        val s = runCatching { json.decodeFromString<Sesion>(texto) }.getOrNull() ?: return null
        api.token = s.token
        turnos.token = s.token
        _sesion.value = s
        if (fila == null) guardarSesion(s)
        refrescarPerfilRemoto(s)
        return _sesion.value
    }

    private suspend fun refrescarPerfilRemoto(actual: Sesion) {
        val perfil = turnos.perfil() ?: return
        val usuario = importador.importar(perfil)
        val nueva = resolverPerfil(db, usuario)?.copy(token = actual.token) ?: return
        guardarSesion(nueva)
    }

    suspend fun login(credenciales: Credenciales): ResultadoLogin {
        val email = credenciales.email.trim().lowercase()
        // 1) intento online: se valida directo contra turnosonlinebb y se importa el perfil a la base local
        when (val r = turnos.login(email, credenciales.password)) {
            is TurnosOnlineClient.ResultadoLogin.Ok -> {
                val usuario = importador.importar(r.perfil)
                val sesion = resolverPerfil(db, usuario)?.copy(token = r.token)
                    ?: return ResultadoLogin.Error("El usuario no tiene perfil asignado")
                guardarSesion(sesion)
                return if (sesion.medico?.licenciaVencida() == true) ResultadoLogin.LicenciaVencida(sesion) else ResultadoLogin.Ok(sesion)
            }
            is TurnosOnlineClient.ResultadoLogin.Rechazado -> return ResultadoLogin.CredencialesInvalidas
            is TurnosOnlineClient.ResultadoLogin.Error -> {
                // 2) sin conexión: solo si ya había una sesión previa de este mismo usuario en el dispositivo
                val previa = db.authQueries.sesionActual().uno { it }
                if (previa != null) {
                    val s = runCatching { json.decodeFromString<Sesion>(previa.sesion_json) }.getOrNull()
                    if (s != null && s.usuario.email.equals(email, ignoreCase = true)) {
                        // Se rearma el perfil desde la base local por si cambió (especialidades, consultorios)
                        val actualizada = resolverPerfilLocal(s.usuario)?.copy(token = s.token) ?: s
                        _sesion.value = actualizada
                        api.token = actualizada.token
                        turnos.token = actualizada.token
                        return ResultadoLogin.Ok(actualizada, offline = true)
                    }
                }
                return ResultadoLogin.SinConexionYSinSesionPrevia
            }
        }
    }

    /**
     * Arma la sesión a partir de la base local (mismo algoritmo que usa el servidor):
     * usuario → rol → médico (especialidades HC, agenda) o secretaria (consultorios, médicos).
     */
    suspend fun resolverPerfilLocal(usuario: Usuario): Sesion? = resolverPerfil(db, usuario)

    suspend fun logout() {
        db.authQueries.borrarSesion()
        settings?.remove(CLAVE_SESION)
        api.token = null
        turnos.token = null
        _sesion.value = null
    }

    /** Refresca el perfil (por ejemplo tras una sincronización que trajo nuevas especialidades). */
    suspend fun refrescarPerfil() {
        val s = _sesion.value ?: return
        val nueva = resolverPerfilLocal(s.usuario)?.copy(token = s.token) ?: return
        guardarSesion(nueva)
    }

    private suspend fun guardarSesion(s: Sesion) {
        api.token = s.token
        turnos.token = s.token
        _sesion.value = s
        val texto = json.encodeToString(s)
        db.authQueries.guardarSesion(s.usuario.id, s.token, texto, ahoraMillis())
        settings?.putString(CLAVE_SESION, texto)
        // guarda el usuario localmente para consultas offline
        db.authQueries.upsertUsuario(s.usuario.toRow(ahoraMillis(), dirty = false))
    }

    suspend fun cambiarPassword(actual: String, nueva: String) = api.cambiarPassword(actual, nueva)

    private companion object {
        const val CLAVE_SESION = "auth.sesion"
    }
}

/**
 * Arma la sesión de un usuario a partir de la base (misma lógica en la app y en el servidor):
 * usuario → rol → médico (historias clínicas habilitadas, agenda, licencia) o secretaria (consultorios, médicos).
 * El médico no elige especialidad: se deduce de lo que el administrador le habilitó.
 */
suspend fun resolverPerfil(db: Salud360Db, usuario: Usuario): Sesion? {
    val q = db.authQueries
    return when (usuario.rol) {
        Rol.MEDICO -> {
            val m = q.medicoPorUsuario(usuario.id).uno { it.toModel() } ?: return null
            val lic = q.licenciaPorMedico(m.id).uno { it.toModel() }
            Sesion(
                usuario = usuario, token = "",
                medico = PerfilMedico(m.id, m.especialidadesHc, m.tieneTurnos, m.consultorioId, lic?.fechaExpiracion, lic?.fechaAviso),
            )
        }
        Rol.SECRETARIA -> {
            val s = q.secretariaPorUsuario(usuario.id).uno { it.toModel() } ?: return null
            val medicosDeConsultorios = s.consultorioIds.flatMap { c -> q.medicosPorConsultorio(c).lista { it.id } }
            Sesion(usuario, "", secretaria = PerfilSecretaria(s.id, s.consultorioIds, (s.medicoIds + medicosDeConsultorios).distinct()))
        }
        Rol.ADMIN -> Sesion(usuario, "")
    }
}

private fun PerfilMedico.licenciaVencida(): Boolean {
    val venc = licenciaVence ?: return false
    return runCatching { kotlinx.datetime.LocalDate.parse(venc) < hoy() }.getOrDefault(false)
}

fun hoy(): kotlinx.datetime.LocalDate =
    kotlin.time.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
