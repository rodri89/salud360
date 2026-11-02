package com.salud360.core.data.repos

import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.Id
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.PerfilSecretaria
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Spec 004, T3: `AuthRepository.decidirLicencia` decide con lo que contesta el servidor de la HC y deja la copia local al
 * día. Todavía no está cableada a `login`/`restaurar`/`refrescarPerfil` (T4 a T6): se llama directo.
 *
 * SQLite temporal, turnos apuntado a un puerto cerrado (nunca el cliente por defecto, que apunta a producción) y un
 * backend falso programable. Ids, fechas (relativas a `hoy()`) y tokens inventados.
 */
class AuthRepositoryLicenciaServidorTest {
    private val ayer = hoy().minus(DatePeriod(days = 1)).toString()
    private val manana = hoy().plus(DatePeriod(days = 1)).toString()
    private val lejos = hoy().plus(DatePeriod(years = 1)).toString()
    private val avisoYa = hoy().minus(DatePeriod(days = 10)).toString()
    private val avisoViejo = hoy().minus(DatePeriod(days = 40)).toString()

    private val vigente = EstadoLicenciaRemoto.Vigente(vence = lejos, avisoDesde = avisoYa, porVencer = false)

    // ---- Quién se consulta (RF-4) ----

    @Test
    fun el_medico_con_una_hc_con_licencia_consulta_una_vez() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        val (sesion, bloqueada) = e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        assertEquals(1, e.pediatria.consultas.get())
        assertFalse(bloqueada)
        assertEquals("tok-a", sesion.token)
    }

    @Test
    fun el_administrador_no_consulta_nada() = runBlocking {
        val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
        val admin = Sesion(Usuario("u-adm", "admin@ejemplo.test", "Admin", "Prueba", Rol.ADMIN), "tok-a")
        val (sesion, bloqueada) = e.repo.decidirLicencia(admin, TOPE)
        assertEquals(0, e.pediatria.consultas.get())
        assertFalse(bloqueada)
        assertEquals(admin, sesion)
    }

    @Test
    fun la_secretaria_no_consulta_nada() = runBlocking {
        val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
        val secretaria = Sesion(
            Usuario("u-sec", "secretaria@ejemplo.test", "Secre", "Prueba", Rol.SECRETARIA), "tok-a",
            secretaria = PerfilSecretaria("tobb-s1", listOf("c1"), listOf(MEDICO_ID)),
        )
        val (sesion, bloqueada) = e.repo.decidirLicencia(secretaria, TOPE)
        assertEquals(0, e.pediatria.consultas.get())
        assertFalse(bloqueada)
        assertEquals(secretaria, sesion)
    }

    @Test
    fun el_medico_sin_hc_con_licencia_no_consulta_nada() = runBlocking {
        // Solo la HC general (sin backend con licencia en el mapa): el servidor de pediatría no se entera.
        val e = escena(especialidades = listOf("clinica")).also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
        val (_, bloqueada) = e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        assertEquals(0, e.pediatria.consultas.get())
        assertFalse(bloqueada)

        // Sin ninguna HC (solo agenda).
        val soloAgenda = escena(especialidades = emptyList()).also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
        assertFalse(soloAgenda.repo.decidirLicencia(soloAgenda.sesionMedico(), TOPE).second)
        assertEquals(0, soloAgenda.pediatria.consultas.get())
    }

    @Test
    fun el_medico_de_pediatria_sin_backend_registrado_decide_la_copia_sin_consultar() = runBlocking {
        val e = escena(conBackend = false)
        e.copia(vence = ayer)
        assertTrue(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        e.copia(vence = lejos)
        assertFalse(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        assertEquals(0, e.pediatria.consultas.get())
    }

    @Test
    fun sin_backend_con_licencia_la_copia_decide_como_hoy_y_no_se_toca() = runBlocking {
        // Médico sin HC con licencia pero con una fila de licencia vencida en el dispositivo: la regla de siempre.
        val e = escena(especialidades = listOf("clinica"))
        e.copia(vence = ayer)
        val antes = e.copiaLocal()
        val (_, bloqueada) = e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        assertTrue(bloqueada)
        assertEquals(antes, e.copiaLocal())
        assertEquals(1L, e.actualizadaEn())
    }

    // ---- Vigente (RF-6, RF-11, RF-12) ----

    @Test
    fun vigente_escribe_la_copia_sin_dirty_y_conserva_el_importe() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        e.copia(vence = ayer, aviso = avisoViejo, importe = 123.5, activo = false)
        val (sesion, bloqueada) = e.repo.decidirLicencia(e.sesionMedico(), TOPE)

        assertFalse(bloqueada)
        assertEquals(Licencia(MEDICO_ID, lejos, avisoYa, importe = 123.5, activo = true), e.copiaLocal())
        assertTrue(e.dirty().isEmpty(), "el dueño es el servidor: la copia no queda pendiente de envío")
        // La sesión que vuelve muestra lo que quedó en la copia.
        assertEquals(lejos, sesion.medico?.licenciaVence)
        assertEquals(avisoYa, sesion.medico?.licenciaAviso)
        assertEquals(true, sesion.medico?.licenciaActiva)
    }

    @Test
    fun vigente_sin_copia_previa_la_crea_con_importe_cero() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        assertFalse(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        assertEquals(Licencia(MEDICO_ID, lejos, avisoYa, importe = 0.0, activo = true), e.copiaLocal())
        assertTrue(e.dirty().isEmpty())
    }

    @Test
    fun vigente_con_la_copia_ya_igual_no_la_reescribe() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        e.copia(vence = lejos, aviso = avisoYa, importe = 10.0)
        assertFalse(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        assertEquals(1L, e.actualizadaEn(), "sin cambios no se reescribe (updated_at intacto)")
    }

    @Test
    fun vigente_sin_aviso_conserva_el_de_la_copia() = runBlocking {
        val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.Vigente(lejos, avisoDesde = "", porVencer = false)) }
        e.copia(vence = ayer, aviso = avisoViejo, importe = 5.0)
        e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        assertEquals(Licencia(MEDICO_ID, lejos, avisoViejo, importe = 5.0, activo = true), e.copiaLocal())
    }

    @Test
    fun vigente_manda_aunque_la_copia_diga_vencida_inactiva_o_no_exista() = runBlocking {
        listOf<suspend (Escena) -> Unit>({}, { it.copia(vence = ayer) }, { it.copia(vence = manana, activo = false) }).forEach { preparar ->
            val e = escena().also { it.pediatria.responde(vigente) }
            preparar(e)
            assertFalse(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        }
    }

    // ---- Vencida (RF-5, RF-11) ----

    @Test
    fun vencida_pone_la_copia_en_inactiva_aunque_diga_vigente() = runBlocking {
        val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
        e.copia(vence = lejos, aviso = avisoYa, importe = 7.0)
        val (sesion, bloqueada) = e.repo.decidirLicencia(e.sesionMedico(), TOPE)

        assertTrue(bloqueada)
        assertEquals(Licencia(MEDICO_ID, lejos, avisoYa, importe = 7.0, activo = false), e.copiaLocal())
        assertTrue(e.dirty().isEmpty())
        assertEquals(false, sesion.medico?.licenciaActiva)
    }

    @Test
    fun vencida_sin_copia_bloquea_y_no_inventa_una_fila() = runBlocking {
        val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
        assertTrue(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        assertNull(e.copiaLocal())
    }

    // ---- SinDato (RF-7, RF-8) ----

    @Test
    fun sin_dato_decide_la_copia_con_la_regla_de_hoy_y_no_la_toca() = runBlocking {
        data class Caso(val vence: String?, val activa: Boolean, val bloquea: Boolean)
        listOf(
            Caso(lejos, true, false), Caso(ayer, true, true), Caso(lejos, false, true), Caso(null, true, false),
        ).forEach { caso ->
            val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.SinDato) }
            if (caso.vence != null) e.copia(vence = caso.vence, activo = caso.activa)
            val antes = e.copiaLocal()
            assertEquals(caso.bloquea, e.repo.decidirLicencia(e.sesionMedico(), TOPE).second, "caso $caso")
            assertEquals(antes, e.copiaLocal())
            assertTrue(e.dirty().isEmpty())
        }
    }

    @Test
    fun sin_dato_y_sin_copia_entra() = runBlocking {
        val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.SinDato) }
        assertFalse(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        assertNull(e.copiaLocal())
    }

    @Test
    fun un_backend_que_falla_es_sin_dato() = runBlocking {
        val e = escena()
        e.pediatria.respuesta = { throw IllegalStateException("se cayó") }
        e.copia(vence = ayer)
        assertTrue(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        e.copia(vence = lejos)
        e.reloj.ahora += 61_000
        assertFalse(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
    }

    // ---- SinLicencia (RF-10) ----

    @Test
    fun sin_licencia_no_bloquea_aunque_la_copia_diga_vencida_o_inactiva_y_no_la_toca() = runBlocking {
        listOf<suspend (Escena) -> Unit>({ it.copia(vence = ayer) }, { it.copia(vence = lejos, activo = false) }, {}).forEach { preparar ->
            val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.SinLicencia) }
            preparar(e)
            val antes = e.copiaLocal()
            assertFalse(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
            assertEquals(antes, e.copiaLocal())
            assertTrue(e.dirty().isEmpty())
        }
    }

    // ---- La memoria de 60 segundos (RF-3, RF-14) ----

    @Test
    fun a_los_59_segundos_con_el_mismo_token_reutiliza_y_a_los_61_consulta_de_nuevo() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        assertEquals(1, e.pediatria.consultas.get())

        e.reloj.ahora = 59_000
        assertFalse(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        assertEquals(1, e.pediatria.consultas.get(), "a los 59 s no hay un pedido más")

        e.reloj.ahora = 61_000
        e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        assertEquals(2, e.pediatria.consultas.get(), "a los 61 s consulta de nuevo")
    }

    @Test
    fun a_los_60_segundos_justos_ya_consulta_de_nuevo() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        e.reloj.ahora = 60_000
        e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        assertEquals(2, e.pediatria.consultas.get())
    }

    @Test
    fun con_otro_token_consulta_de_nuevo() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        e.repo.decidirLicencia(e.sesionMedico("tok-a"), TOPE)
        e.repo.decidirLicencia(e.sesionMedico("tok-a"), TOPE)
        assertEquals(1, e.pediatria.consultas.get())
        e.repo.decidirLicencia(e.sesionMedico("tok-b"), TOPE)
        assertEquals(2, e.pediatria.consultas.get())
    }

    @Test
    fun la_memoria_repite_la_decision_y_no_reescribe_la_copia() = runBlocking {
        val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
        e.copia(vence = lejos)
        assertTrue(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        val escrita = e.actualizadaEn()
        e.reloj.ahora = 30_000
        assertTrue(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        assertEquals(1, e.pediatria.consultas.get())
        assertEquals(escrita, e.actualizadaEn())
    }

    @Test
    fun sin_dato_tambien_queda_en_la_memoria_y_sin_licencia_tambien() = runBlocking {
        listOf(EstadoLicenciaRemoto.SinDato, EstadoLicenciaRemoto.SinLicencia).forEach { estado ->
            val e = escena().also { it.pediatria.responde(estado) }
            e.repo.decidirLicencia(e.sesionMedico(), TOPE)
            e.reloj.ahora = 59_000
            e.repo.decidirLicencia(e.sesionMedico(), TOPE)
            assertEquals(1, e.pediatria.consultas.get(), "$estado: un pedido ya hecho no se repite")
        }
    }

    @Test
    fun logout_borra_la_memoria() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        e.repo.logout()
        e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        assertEquals(2, e.pediatria.consultas.get())
    }

    // ---- Cuando el servidor no contesta (RF-15) ----

    @Test
    fun la_consulta_que_no_contesta_vuelve_al_tope_con_sin_dato() = runBlocking {
        val compuerta = CompletableDeferred<EstadoLicenciaRemoto>()
        try {
            val e = escena()
            e.pediatria.respuesta = { compuerta.await() }

            e.copia(vence = ayer)
            assertTrue(e.repo.decidirLicencia(e.sesionMedico(), 100.milliseconds).second, "decide la copia vencida")
            assertEquals(1, e.pediatria.consultas.get())

            // Vencido el tope queda como «sin dato» en la memoria: no se vuelve a esperar.
            e.copia(vence = lejos)
            e.reloj.ahora = 10_000
            assertFalse(e.repo.decidirLicencia(e.sesionMedico(), 100.milliseconds).second)
            assertEquals(1, e.pediatria.consultas.get())
            assertEquals(1L, e.actualizadaEn(), "la copia no se tocó")
        } finally {
            compuerta.complete(EstadoLicenciaRemoto.SinDato)
        }
    }

    @Test
    fun la_cancelacion_no_se_traga_ni_deja_memoria() = runBlocking {
        val e = escena()
        val entro = CompletableDeferred<Unit>()
        val compuerta = CompletableDeferred<EstadoLicenciaRemoto>()
        e.pediatria.respuesta = { entro.complete(Unit); compuerta.await() }

        val trabajo = async { e.repo.decidirLicencia(e.sesionMedico(), 30.seconds) }
        entro.await()
        trabajo.cancelAndJoin()
        assertTrue(trabajo.isCancelled)

        e.pediatria.responde(vigente)
        e.repo.decidirLicencia(e.sesionMedico(), TOPE)
        assertEquals(2, e.pediatria.consultas.get(), "lo cancelado no dejó nada en la memoria")
    }

    // ---- Más de una HC con licencia (hoy solo pediatría) ----

    @Test
    fun con_dos_hc_con_licencia_se_consultan_las_dos_y_una_vencida_bloquea() = runBlocking {
        val otra = HcFalso("otra").also { it.responde(vigente) }
        val e = escena(especialidades = listOf("pediatria", "otra"), otras = listOf(otra))
        e.pediatria.responde(EstadoLicenciaRemoto.Vencida)
        assertTrue(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        assertEquals(1, e.pediatria.consultas.get())
        assertEquals(1, otra.consultas.get())
    }

    @Test
    fun con_dos_hc_vigente_le_gana_a_sin_licencia_y_sin_dato() = runBlocking {
        val otra = HcFalso("otra").also { it.responde(EstadoLicenciaRemoto.SinLicencia) }
        val e = escena(especialidades = listOf("pediatria", "otra"), otras = listOf(otra))
        e.pediatria.responde(vigente)
        assertFalse(e.repo.decidirLicencia(e.sesionMedico(), TOPE).second)
        assertEquals(lejos, e.copiaLocal()?.fechaExpiracion)

        val e2 = escena(especialidades = listOf("pediatria", "otra"), otras = listOf(HcFalso("otra")))
        e2.pediatria.responde(EstadoLicenciaRemoto.SinDato)
        e2.copia(vence = ayer)
        assertTrue(e2.repo.decidirLicencia(e2.sesionMedico(), TOPE).second, "todo sin dato: decide la copia")
    }

    // ---- Armado ----

    private class Reloj { var ahora = 0L }

    private inner class Escena(
        val db: Salud360Db,
        val repo: AuthRepository,
        val reloj: Reloj,
        val pediatria: HcFalso,
        val usuario: Usuario,
    ) {
        /** La sesión del médico armada como la arma la app: desde la copia local, con el token que se le pasa. */
        suspend fun sesionMedico(token: String = "tok-a"): Sesion = resolverPerfil(db, usuario)!!.copy(token = token)

        /** Copia local de la licencia, ya limpia (`dirty = 0`) y con `updated_at = 1`. */
        suspend fun copia(vence: String, aviso: String = avisoYa, importe: Double = 0.0, activo: Boolean = true) {
            db.authQueries.upsertLicencia(Licencia(MEDICO_ID, vence, aviso, importe, activo).toRow(1L, dirty = false))
        }

        suspend fun copiaLocal(): Licencia? = db.authQueries.licenciaPorMedico(MEDICO_ID).uno { it.toModel() }
        suspend fun actualizadaEn(): Long? = db.authQueries.licenciaPorMedico(MEDICO_ID).uno { it.updated_at }
        suspend fun dirty(): List<Id> = db.authQueries.licenciasDirty().lista { it.medico_id }
    }

    private suspend fun escena(
        especialidades: List<String> = listOf("pediatria"),
        conBackend: Boolean = true,
        otras: List<HcFalso> = emptyList(),
    ): Escena {
        val archivo = File.createTempFile("salud360-auth-licencia", ".db").also { it.delete(); it.deleteOnExit() }
        val db = createDatabase(DriverFactory(archivo.absolutePath))
        val usuario = Usuario("u-med", "medico@ejemplo.test", "Medico", "Prueba", Rol.MEDICO)
        db.authQueries.upsertMedico(
            Medico(MEDICO_ID, usuario.id, "Medico", "Prueba", especialidadesHc = especialidades, tieneTurnos = true)
                .toRow(1L, dirty = false),
        )
        val pediatria = HcFalso("pediatria")
        val reloj = Reloj()
        val backends = buildMap<String, HcBackend> {
            if (conBackend) put("pediatria", pediatria)
            otras.forEach { put(it.especialidad, it) }
        }
        // Turnos y API propia a un puerto cerrado: si algo los llamara, fallaría rápido y no tocaría producción.
        val repo = AuthRepository(
            db, ApiClient("http://127.0.0.1:1"), TurnosOnlineClient("http://127.0.0.1:1"),
            backendsHc = backends, ahora = { reloj.ahora },
        )
        return Escena(db, repo, reloj, pediatria, usuario)
    }

    /** Backend falso: cuenta las consultas de licencia y contesta lo que el test le programe. */
    private class HcFalso(override val especialidad: String) : HcBackend {
        val consultas = AtomicInteger()
        var respuesta: suspend () -> EstadoLicenciaRemoto = { EstadoLicenciaRemoto.SinDato }

        fun responde(estado: EstadoLicenciaRemoto) { respuesta = { estado } }

        override suspend fun licenciaDelServidor(): EstadoLicenciaRemoto {
            consultas.incrementAndGet()
            return respuesta()
        }

        override suspend fun asegurarConsulta(consulta: Consulta): String? = null
        override suspend fun enviarSecciones(remotoId: String, secciones: Map<String, Map<String, String>>) = Unit
        override suspend fun enviarExamen(remotoId: String, campos: Map<String, String>) = Unit
        override suspend fun enviarRegistros(remotoId: String, registros: List<RegistroClinico>): Map<Id, String> = emptyMap()
        override suspend fun enviarEstado(consulta: Consulta, remotoId: String) = Unit
        override suspend fun subirArchivo(remotoId: String, archivo: Archivo, bytes: ByteArray): String? = null
        override suspend fun borrarArchivo(remotoId: String, archivo: Archivo) = Unit
        override suspend fun bajarArchivo(remotoId: String, archivo: Archivo): ByteArray? = null
        override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> = emptyList()
        override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) = Unit
    }

    private companion object {
        const val MEDICO_ID: Id = "tobb-m7"

        /** Tope largo: ningún test que no pruebe el tope debe depender de él. */
        val TOPE: Duration = 30.seconds
    }
}
