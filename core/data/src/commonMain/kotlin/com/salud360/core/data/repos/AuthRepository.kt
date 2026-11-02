package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.json
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.Credenciales
import com.salud360.core.model.auth.PerfilMedico
import com.salud360.core.model.auth.PerfilSecretaria
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import com.russhwolf.settings.Settings
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface ResultadoLogin {
    data class Ok(val sesion: Sesion, val offline: Boolean = false) : ResultadoLogin
    /**
     * turnosonlinebb rechazó el ingreso. [mensaje] es el motivo que informa la web: puede ser la contraseña,
     * pero también que el usuario no tenga permiso para usar Salud 360, así que se muestra tal cual.
     */
    data class CredencialesInvalidas(val mensaje: String, val status: Int) : ResultadoLogin
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
    /**
     * Clientes de las historias clínicas con API propia. Usan el mismo token de turnosonlinebb, que es
     * la identidad única: esas APIs no tienen login, validan este token contra turnos.
     */
    private val hcClients: Map<String, HcApiClient> = emptyMap(),
    /**
     * Backends de las historias clínicas con API por código de especialidad. Los que llevan licencia dicen cómo está la
     * del médico en el servidor ([decidirLicencia]); los demás contestan «sin dato» sin pedir nada.
     */
    private val backendsHc: Map<String, HcBackend> = emptyMap(),
    /** Reloj (epoch millis) de la memoria de la última consulta de licencia. */
    private val ahora: () -> Long = ::ahoraMillis,
    /** Cuánto se espera la consulta de licencia al iniciar sesión y al restaurarla; pasado el tope es «sin dato». */
    private val esperaLogin: Duration = 10.seconds,
    private val esperaRestaurar: Duration = 5.seconds,
) {
    private val log = Logger.withTag("Auth")
    private val importador = TurnosImportador(db)
    private val _sesion = MutableStateFlow<Sesion?>(null)
    val sesion: StateFlow<Sesion?> = _sesion.asStateFlow()

    /** Lo último que contestó el servidor de la HC sobre la licencia, para no repetir el pedido dentro de un minuto. */
    private class MemoriaLicencia(val token: String, val estado: EstadoLicenciaRemoto, val en: Long)

    private var memoriaLicencia: MemoriaLicencia? = null

    val usuarioActual: Usuario? get() = _sesion.value?.usuario

    /**
     * Restaura la sesión guardada al abrir la app y refresca médico/consultorio desde turnosonlinebb
     * (por si cambiaron desde el último login; si falla —sin conexión, etc.— sigue con lo guardado).
     */
    suspend fun restaurar(): Sesion? {
        val fila = db.authQueries.sesionActual().uno { it }
        // Sin fila en la base (web recién cargada) se usa la copia de Settings y se vuelve a dejar en la base.
        val texto = fila?.sesion_json ?: settings?.getStringOrNull(CLAVE_SESION)?.takeIf { it.isNotBlank() } ?: return null
        val guardada = runCatching { json.decodeFromString<Sesion>(texto) }.getOrNull() ?: return null
        api.token = guardada.token
        turnos.token = guardada.token
        hcClients.values.forEach { it.token = guardada.token }
        // El perfil se rearma desde la base: el administrador pudo dar de baja la licencia después del último ingreso,
        // y la copia guardada en JSON todavía la tendría vigente.
        val local = resolverPerfilLocal(guardada.usuario)?.copy(token = guardada.token) ?: guardada
        // La licencia la decide el servidor de la HC (con la copia como respaldo sin señal) y se decide ANTES de publicar la
        // sesión: la app navega apenas `sesion` deja de ser null y arranca la sincronización de quien quedaría afuera.
        // Los clientes de HC ya tienen el token de arriba.
        val (s, bloqueada) = decidirLicencia(local, esperaRestaurar)
        if (bloqueada) { cerrarPorLicencia(); return null }
        _sesion.value = s
        guardarSesion(s)
        refrescarPerfilRemoto(s)
        return _sesion.value
    }

    private suspend fun refrescarPerfilRemoto(actual: Sesion) {
        val perfil = turnos.perfil() ?: return
        val usuario = importador.importar(perfil)
        val local = resolverPerfil(db, usuario)?.copy(token = actual.token) ?: return
        // Pasa por la misma decisión: justo después de `restaurar` cae en la memoria de 60 s (sin pedido), y la regla local
        // no le cierra la sesión a quien el servidor acaba de dejar entrar.
        val (nueva, bloqueada) = decidirLicencia(local, esperaRestaurar)
        if (bloqueada) { cerrarPorLicencia(); return }
        guardarSesion(nueva)
    }

    suspend fun login(credenciales: Credenciales): ResultadoLogin {
        val email = credenciales.email.trim().lowercase()
        // 1) intento online: se valida directo contra turnosonlinebb y se importa el perfil a la base local
        when (val r = turnos.login(email, credenciales.password)) {
            is TurnosOnlineClient.ResultadoLogin.Ok -> {
                val usuario = importador.importar(r.perfil)
                val local = resolverPerfil(db, usuario)?.copy(token = r.token)
                    ?: return ResultadoLogin.Error("El usuario no tiene perfil asignado")
                // La licencia la decide el servidor de la HC, y para preguntarle los clientes de HC necesitan el token nuevo:
                // `guardarSesion` los asigna, pero corre después (publica en `sesion`). Se les da acá, sin publicar nada.
                hcClients.values.forEach { it.token = r.token }
                val (sesion, bloqueada) = try {
                    decidirLicencia(local, esperaLogin)
                } catch (e: Throwable) {
                    // Cancelado (o roto) a mitad de la consulta: sin sesión no queda un token suelto en los clientes de HC.
                    hcClients.values.forEach { it.token = null }
                    throw e
                }
                // El bloqueo va ANTES de guardar: `guardarSesion` publica en `sesion`, y la app navega al shell
                // apenas deja de ser null, sin darle tiempo a la pantalla de ingreso a mostrar el aviso.
                if (bloqueada) { cerrarPorLicencia(); return ResultadoLogin.LicenciaVencida(sesion) }
                guardarSesion(sesion)
                return ResultadoLogin.Ok(sesion)
            }
            is TurnosOnlineClient.ResultadoLogin.Rechazado -> return ResultadoLogin.CredencialesInvalidas(r.mensaje, r.status)
            is TurnosOnlineClient.ResultadoLogin.Error -> {
                // 2) sin conexión: solo si ya había una sesión previa de este mismo usuario en el dispositivo
                val previa = db.authQueries.sesionActual().uno { it }
                if (previa != null) {
                    val s = runCatching { json.decodeFromString<Sesion>(previa.sesion_json) }.getOrNull()
                    if (s != null && s.usuario.email.equals(email, ignoreCase = true)) {
                        // Se rearma el perfil desde la base local por si cambió (especialidades, consultorios, licencia)
                        val actualizada = resolverPerfilLocal(s.usuario)?.copy(token = s.token) ?: s
                        // Sin conexión con turnos no se consulta la licencia al servidor de la HC: decide la copia, como siempre.
                        if (actualizada.licenciaBloqueada()) { logout(); return ResultadoLogin.LicenciaVencida(actualizada) }
                        _sesion.value = actualizada
                        api.token = actualizada.token
                        turnos.token = actualizada.token
                        hcClients.values.forEach { it.token = actualizada.token }
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
        hcClients.values.forEach { it.token = null }
        memoriaLicencia = null
        _sesion.value = null
    }

    /**
     * Decide si el médico queda afuera por la licencia: con conexión manda lo que contesta el servidor de su HC (la copia
     * del dispositivo es solo respaldo), y la copia se pone al día con esa respuesta, siempre limpia (el dueño es el
     * servidor). Sin respuesta concluyente decide la copia con la regla de siempre. Devuelve la sesión rearmada desde la
     * copia y si queda bloqueada.
     *
     * No consulta nada al administrador, a la secretaria ni al médico sin HC con backend (decide la copia, como antes).
     * Repite el pedido solo si pasó un minuto o cambió el token; el tope de espera es [tope].
     */
    internal suspend fun decidirLicencia(sesion: Sesion, tope: Duration): Pair<Sesion, Boolean> {
        val medico = sesion.medico ?: return sesion to false
        val backends = medico.especialidades.distinct().mapNotNull { backendsHc[it] }
        if (backends.isEmpty()) return sesion to licenciaBloqueadaSegun(EstadoLicenciaRemoto.SinDato, sesion)

        val memoria = memoriaLicencia
        val estado = if (memoria != null && memoria.token == sesion.token && ahora() - memoria.en in 0 until REUSO_LICENCIA_MS) {
            memoria.estado
        } else {
            val consultado = combinarEstadosDeLicencia(backends.map { consultarLicencia(it, tope) })
            val previa = db.authQueries.licenciaPorMedico(medico.medicoId).uno { it.toModel() }
            copiaDeLicencia(consultado, previa, medico.medicoId)?.let { db.authQueries.upsertLicencia(it.toRow(ahoraMillis(), dirty = false)) }
            memoriaLicencia = MemoriaLicencia(sesion.token, consultado, ahora())
            consultado
        }
        val rearmada = resolverPerfil(db, sesion.usuario)?.copy(token = sesion.token) ?: sesion
        return rearmada to licenciaBloqueadaSegun(estado, rearmada)
    }

    /** Un pedido al backend con tope; todo lo que no sea una respuesta (tope, error) es «sin dato». La cancelación sigue. */
    private suspend fun consultarLicencia(backend: HcBackend, tope: Duration): EstadoLicenciaRemoto =
        withTimeoutOrNull(tope) {
            try {
                backend.licenciaDelServidor()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w { "no se pudo consultar la licencia en ${backend.especialidad}: ${e::class.simpleName}" }
                EstadoLicenciaRemoto.SinDato
            }
        } ?: EstadoLicenciaRemoto.SinDato

    /** El ingreso queda bloqueado por la licencia: no queda sesión abierta ni guardada. */
    private suspend fun cerrarPorLicencia() = logout()

    /**
     * Refresca el perfil (por ejemplo tras una sincronización que trajo nuevas especialidades).
     * Si en el camino la licencia dejó de estar vigente, cierra la sesión.
     *
     * La licencia la decide [decidirLicencia]: justo después de iniciar sesión o restaurar cae en la memoria de 60 s (sin
     * pedido nuevo) y no se vuelve a comparar la fecha de la copia con el reloj del dispositivo.
     */
    suspend fun refrescarPerfil() {
        val s = _sesion.value ?: return
        val local = resolverPerfilLocal(s.usuario)?.copy(token = s.token) ?: return
        val (nueva, bloqueada) = decidirLicencia(local, esperaLogin)
        // La consulta puede tardar: si mientras tanto se cerró la sesión (o entró otra), este refresco ya no es de nadie y
        // no debe volver a publicar la sesión cerrada ni cerrar la nueva.
        if (_sesion.value?.token != s.token) return
        if (bloqueada) { cerrarPorLicencia(); return }
        guardarSesion(nueva)
    }

    private suspend fun guardarSesion(s: Sesion) {
        api.token = s.token
        turnos.token = s.token
        hcClients.values.forEach { it.token = s.token }
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

        /** Una respuesta del servidor sobre la licencia vale este tiempo; después se vuelve a preguntar. */
        const val REUSO_LICENCIA_MS = 60_000L
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
                medico = PerfilMedico(
                    m.id, m.especialidadesHc, m.tieneTurnos, m.consultorioId,
                    lic?.fechaExpiracion, lic?.fechaAviso, licenciaActiva = lic?.activo ?: true,
                ),
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

/**
 * La licencia no está vigente: el administrador la dio de baja (falta de pago) o pasó la fecha de
 * vencimiento. Un médico sin licencia cargada no queda bloqueado. Misma regla que muestra la
 * pestaña Licencias de Administración.
 */
fun PerfilMedico.licenciaVencida(): Boolean {
    if (!licenciaActiva) return true
    val venc = licenciaVence ?: return false
    return runCatching { kotlinx.datetime.LocalDate.parse(venc) < hoy() }.getOrDefault(false)
}

/** La sesión corresponde a un médico con la licencia caída: no puede entrar a la app. */
fun Sesion.licenciaBloqueada(): Boolean = medico?.licenciaVencida() == true

fun hoy(): kotlinx.datetime.LocalDate =
    kotlin.time.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
