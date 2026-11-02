package com.salud360.core.data.repos

import com.salud360.core.data.lista
import com.salud360.core.data.mappers.json
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.Id
import com.salud360.core.model.auth.Credenciales
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.PerfilSecretaria
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.RegistroClinico
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Spec 004, T3: `AuthRepository.decidirLicencia` decide con lo que contesta el servidor de la HC y deja la copia local al
 * día. T4: `restaurar` la usa antes de publicar la sesión. T5: `login` también, con el token ya puesto en los clientes
 * de HC y antes de guardar la sesión. Todavía falta cablearla a `refrescarPerfil` (T6): se llama directo.
 *
 * SQLite temporal, turnos apuntado a un puerto cerrado (nunca el cliente por defecto, que apunta a producción; solo los
 * tests de `refrescarPerfilRemoto` y de `login` lo apuntan a un turnos falso en `127.0.0.1`) y un backend falso
 * programable. Ids, fechas (relativas a `hoy()`) y tokens inventados.
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

    // ---- restaurar (T4: RF-2, RF-5, RF-6, RF-7, RF-13, RF-15) ----

    @Test
    fun restaurar_con_servidor_vigente_y_copia_vencida_e_inactiva_entra_y_deja_la_copia_vigente() = runBlocking {
        // El caso reportado: la copia del navegador quedó vieja y el servidor dice que la licencia está al día.
        val e = escena().also { it.pediatria.responde(vigente) }
        e.copia(vence = ayer, aviso = avisoViejo, importe = 9.5, activo = false)
        e.guardarSesion()

        val sesion = e.repo.restaurar()

        assertNotNull(sesion)
        assertEquals("tok-guardado", sesion.token)
        assertEquals(lejos, sesion.medico?.licenciaVence)
        assertEquals(true, sesion.medico?.licenciaActiva)
        assertEquals(Licencia(MEDICO_ID, lejos, avisoYa, importe = 9.5, activo = true), e.copiaLocal())
        assertTrue(e.dirty().isEmpty(), "la copia no queda pendiente de envío")
        assertEquals(sesion, e.repo.sesion.value)
        assertNotNull(e.sesionGuardada(), "la sesión sigue guardada")
        assertEquals(1, e.pediatria.consultas.get())
    }

    @Test
    fun restaurar_con_servidor_vencida_y_copia_vigente_no_entra_y_la_sesion_nunca_se_publica() = runBlocking {
        val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
        e.copia(vence = lejos, aviso = avisoYa, importe = 7.0)
        e.guardarSesion()
        val vistos = mutableListOf<Sesion?>()
        val colector = launch(Dispatchers.Unconfined) { e.repo.sesion.collect { vistos += it } }

        val sesion = e.repo.restaurar()
        colector.cancel()

        assertNull(sesion)
        assertEquals(listOf<Sesion?>(null), vistos, "`sesion` nunca pasó por un valor no nulo")
        assertNull(e.repo.sesion.value)
        assertNull(e.sesionGuardada(), "la sesión guardada queda borrada")
        assertNull(e.cliente.token, "los clientes de HC quedan sin token")
        assertEquals(Licencia(MEDICO_ID, lejos, avisoYa, importe = 7.0, activo = false), e.copiaLocal())
        assertTrue(e.dirty().isEmpty())
    }

    @Test
    fun restaurar_consulta_con_el_token_de_la_sesion_guardada_y_antes_de_publicarla() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        e.guardarSesion()
        var tokenAlConsultar: String? = null
        var publicadaAlConsultar: Boolean? = null
        e.pediatria.alConsultar = {
            tokenAlConsultar = e.cliente.token
            publicadaAlConsultar = e.repo.sesion.value != null
        }

        assertNotNull(e.repo.restaurar())

        assertEquals("tok-guardado", tokenAlConsultar)
        assertEquals(false, publicadaAlConsultar, "se decide antes de publicar la sesión")
    }

    @Test
    fun restaurar_sin_dato_decide_la_copia_con_la_regla_de_hoy_y_no_la_toca() = runBlocking {
        data class Caso(val vence: String?, val activa: Boolean, val entra: Boolean)
        val sinRespuesta: List<Pair<String, suspend () -> EstadoLicenciaRemoto>> = listOf(
            "sin dato" to { EstadoLicenciaRemoto.SinDato },
            "el backend falla (5xx, 429, red)" to { throw IllegalStateException("se cayó") },
        )
        sinRespuesta.forEach { (nombre, respuesta) ->
            listOf(
                Caso(lejos, true, true), Caso(ayer, true, false), Caso(lejos, false, false), Caso(null, true, true),
            ).forEach { caso ->
                val e = escena().also { it.pediatria.respuesta = respuesta }
                if (caso.vence != null) e.copia(vence = caso.vence, activo = caso.activa)
                e.guardarSesion()
                val antes = e.copiaLocal()

                val sesion = e.repo.restaurar()

                assertEquals(caso.entra, sesion != null, "$nombre, $caso")
                assertEquals(antes, e.copiaLocal(), "$nombre, $caso: la copia no se toca")
                assertTrue(e.dirty().isEmpty())
                assertEquals(caso.entra, e.sesionGuardada() != null, "$nombre, $caso")
            }
        }
    }

    @Test
    fun restaurar_sin_licencia_entra_aunque_la_copia_diga_vencida_o_inactiva_y_no_la_toca() = runBlocking {
        listOf<suspend (Escena) -> Unit>({ it.copia(vence = ayer) }, { it.copia(vence = lejos, activo = false) }, {}).forEach { preparar ->
            val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.SinLicencia) }
            preparar(e)
            e.guardarSesion()
            val antes = e.copiaLocal()

            assertNotNull(e.repo.restaurar(), "P3: el médico sin fila entra a la app")

            assertEquals(antes, e.copiaLocal())
            assertTrue(e.dirty().isEmpty())
            assertNotNull(e.sesionGuardada())
        }
    }

    @Test
    fun restaurar_el_administrador_y_la_secretaria_no_consultan_nada() = runBlocking {
        val e = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
        val admin = Sesion(Usuario("u-adm", "admin@ejemplo.test", "Admin", "Prueba", Rol.ADMIN), "tok-adm")
        e.guardarSesion(admin)
        assertEquals("tok-adm", e.repo.restaurar()?.token)

        val secretaria = Sesion(
            Usuario("u-sec", "secretaria@ejemplo.test", "Secre", "Prueba", Rol.SECRETARIA), "tok-sec",
            secretaria = PerfilSecretaria("tobb-s1", listOf("c1"), listOf(MEDICO_ID)),
        )
        e.guardarSesion(secretaria)
        assertEquals("tok-sec", e.repo.restaurar()?.token)

        assertEquals(0, e.pediatria.consultas.get())
    }

    @Test
    fun restaurar_el_medico_sin_hc_con_licencia_no_consulta_y_decide_la_copia_como_hoy() = runBlocking {
        val e = escena(especialidades = listOf("clinica")).also { it.pediatria.responde(EstadoLicenciaRemoto.Vigente(lejos, "", false)) }
        e.copia(vence = ayer)
        e.guardarSesion()
        assertNull(e.repo.restaurar())
        assertEquals(0, e.pediatria.consultas.get())

        val vigenteLocal = escena(especialidades = listOf("clinica"))
        vigenteLocal.copia(vence = lejos)
        vigenteLocal.guardarSesion()
        assertTrue(vigenteLocal.repo.restaurar() != null)
    }

    @Test
    fun restaurar_sin_sesion_guardada_devuelve_null_y_no_consulta() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        assertNull(e.repo.restaurar())
        assertEquals(0, e.pediatria.consultas.get())
    }

    @Test
    fun restaurar_con_un_servidor_que_no_contesta_termina_por_el_tope_de_restaurar_y_usa_la_copia() = runBlocking {
        val compuerta = CompletableDeferred<EstadoLicenciaRemoto>()
        try {
            // El tope de iniciar sesión es largo a propósito: `restaurar` tiene que usar el suyo.
            val vencida = escena(esperaRestaurar = 100.milliseconds, esperaLogin = 30.seconds)
            vencida.pediatria.respuesta = { compuerta.await() }
            vencida.copia(vence = ayer)
            vencida.guardarSesion()
            withTimeout(20.seconds) { assertNull(vencida.repo.restaurar(), "sin respuesta decide la copia vencida") }
            assertEquals(1, vencida.pediatria.consultas.get())

            val vigenteLocal = escena(esperaRestaurar = 100.milliseconds, esperaLogin = 30.seconds)
            vigenteLocal.pediatria.respuesta = { compuerta.await() }
            vigenteLocal.copia(vence = lejos)
            vigenteLocal.guardarSesion()
            withTimeout(20.seconds) { assertNotNull(vigenteLocal.repo.restaurar(), "sin respuesta decide la copia vigente") }
            assertEquals(lejos, vigenteLocal.copiaLocal()?.fechaExpiracion, "la copia no se tocó")
        } finally {
            compuerta.complete(EstadoLicenciaRemoto.SinDato)
        }
    }

    @Test
    fun restaurar_hace_un_solo_pedido_y_la_memoria_de_60_segundos_evita_repetirlo() = runBlocking {
        TurnosFalso().use { turnos ->
            val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(vigente) }
            e.guardarSesion()

            assertNotNull(e.repo.restaurar())
            assertEquals(1, turnos.pedidos.get(), "restaurar refrescó el perfil con turnos")
            assertEquals(1, e.pediatria.consultas.get(), "el refresco del perfil reutiliza la respuesta: un solo pedido")

            // Otra recarga a los 30 s con el mismo token: tampoco pregunta de nuevo.
            e.reloj.ahora = 30_000
            assertNotNull(e.repo.restaurar())
            assertEquals(2, turnos.pedidos.get())
            assertEquals(1, e.pediatria.consultas.get())

            // Pasado el minuto, sí.
            e.reloj.ahora = 61_000
            assertNotNull(e.repo.restaurar())
            assertEquals(2, e.pediatria.consultas.get())
        }
    }

    @Test
    fun el_refresco_del_perfil_de_restaurar_usa_la_decision_del_servidor_y_no_la_regla_local() = runBlocking {
        // Sin fila de licencia en el servidor y una copia vencida: el servidor dijo que no hay licencia que decidir (P3).
        // Con la regla local, el refresco que sigue a la restauración le cerraría la sesión.
        TurnosFalso().use { turnos ->
            val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(EstadoLicenciaRemoto.SinLicencia) }
            e.copia(vence = ayer)
            e.guardarSesion()

            assertNotNull(e.repo.restaurar())

            assertEquals(1, turnos.pedidos.get())
            assertNotNull(e.repo.sesion.value, "la sesión sigue abierta")
            assertNotNull(e.sesionGuardada())
            assertEquals(1, e.pediatria.consultas.get())
        }
    }

    @Test
    fun el_refresco_del_perfil_de_restaurar_con_la_memoria_vencida_consulta_una_vez_mas_y_cierra_si_esta_vencida() = runBlocking {
        TurnosFalso().use { turnos ->
            val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(vigente) }
            e.copia(vence = lejos)
            e.guardarSesion()
            // Turnos tarda más de un minuto y, mientras tanto, dan de baja la licencia.
            turnos.alResponder = { e.reloj.ahora += 61_000; e.pediatria.responde(EstadoLicenciaRemoto.Vencida) }

            assertNull(e.repo.restaurar())

            assertEquals(2, e.pediatria.consultas.get())
            assertNull(e.repo.sesion.value)
            assertNull(e.sesionGuardada())
            assertEquals(false, e.copiaLocal()?.activo)
        }
    }

    // ---- login (T5: RF-1, RF-5, RF-6, RF-13, RF-14, RF-15) ----

    @Test
    fun login_con_servidor_vigente_y_copia_vencida_e_inactiva_entra_y_deja_la_copia_vigente() = runBlocking {
        // El caso reportado: la copia del navegador quedó vieja y el servidor dice que la licencia está al día.
        TurnosFalso().use { turnos ->
            val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(vigente) }
            e.copia(vence = ayer, aviso = avisoViejo, importe = 9.5, activo = false)

            val r = e.entrar()

            val ok = assertIs<ResultadoLogin.Ok>(r)
            assertFalse(ok.offline)
            assertEquals("tok-login", ok.sesion.token)
            assertEquals(lejos, ok.sesion.medico?.licenciaVence)
            assertEquals(true, ok.sesion.medico?.licenciaActiva)
            assertEquals(Licencia(MEDICO_ID, lejos, avisoYa, importe = 9.5, activo = true), e.copiaLocal())
            assertTrue(e.dirty().isEmpty(), "la copia no queda pendiente de envío")
            assertEquals(ok.sesion, e.repo.sesion.value, "la sesión se publicó")
            assertNotNull(e.sesionGuardada())
            assertEquals("tok-login", e.cliente.token)
            assertEquals(1, e.pediatria.consultas.get())
        }
    }

    @Test
    fun login_sin_copia_y_servidor_vigente_entra_y_crea_la_copia() = runBlocking {
        TurnosFalso().use { turnos ->
            val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(vigente) }
            assertIs<ResultadoLogin.Ok>(e.entrar())
            assertEquals(Licencia(MEDICO_ID, lejos, avisoYa, importe = 0.0, activo = true), e.copiaLocal())
        }
    }

    @Test
    fun login_con_servidor_vencida_y_copia_vigente_no_entra_y_la_sesion_nunca_se_publica() = runBlocking {
        TurnosFalso().use { turnos ->
            val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
            e.copia(vence = lejos, aviso = avisoYa, importe = 7.0)
            val vistos = mutableListOf<Sesion?>()
            val colector = launch(Dispatchers.Unconfined) { e.repo.sesion.collect { vistos += it } }

            val r = e.entrar()
            colector.cancel()

            val bloqueado = assertIs<ResultadoLogin.LicenciaVencida>(r)
            assertEquals("tok-login", bloqueado.sesion.token)
            assertEquals(false, bloqueado.sesion.medico?.licenciaActiva, "la sesión que vuelve muestra la copia ya puesta al día")
            assertEquals(listOf<Sesion?>(null), vistos, "`sesion` nunca pasó por un valor no nulo")
            assertNull(e.repo.sesion.value)
            assertNull(e.sesionGuardada(), "no quedó sesión guardada en la base")
            assertNull(e.cliente.token, "los clientes de HC quedan sin token")
            assertEquals(Licencia(MEDICO_ID, lejos, avisoYa, importe = 7.0, activo = false), e.copiaLocal())
            assertTrue(e.dirty().isEmpty())
            assertEquals(1, e.pediatria.consultas.get())
        }
    }

    @Test
    fun login_con_servidor_vencida_y_sin_copia_tampoco_entra() = runBlocking {
        TurnosFalso().use { turnos ->
            val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
            assertIs<ResultadoLogin.LicenciaVencida>(e.entrar())
            assertNull(e.repo.sesion.value)
            assertNull(e.sesionGuardada())
            assertNull(e.copiaLocal(), "no se inventa una fila")
        }
    }

    @Test
    fun login_consulta_con_el_token_nuevo_en_los_clientes_de_hc_y_antes_de_publicar_la_sesion() = runBlocking {
        TurnosFalso().use { turnos ->
            val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(vigente) }
            var tokenAlConsultar: String? = null
            var publicadaAlConsultar: Boolean? = null
            e.pediatria.alConsultar = {
                tokenAlConsultar = e.cliente.token
                publicadaAlConsultar = e.repo.sesion.value != null
            }

            assertIs<ResultadoLogin.Ok>(e.entrar())

            assertEquals("tok-login", tokenAlConsultar, "el backend consulta con el token recién emitido")
            assertEquals(false, publicadaAlConsultar, "se decide antes de publicar la sesión")
        }
    }

    @Test
    fun login_con_servidor_sin_licencia_entra_aunque_la_copia_diga_vencida_o_inactiva_y_no_la_toca() = runBlocking {
        listOf<suspend (Escena) -> Unit>({ it.copia(vence = ayer) }, { it.copia(vence = lejos, activo = false) }, {}).forEach { preparar ->
            TurnosFalso().use { turnos ->
                val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(EstadoLicenciaRemoto.SinLicencia) }
                preparar(e)
                val antes = e.copiaLocal()

                assertIs<ResultadoLogin.Ok>(e.entrar(), "P3: el médico sin fila entra a la app")

                assertEquals(antes, e.copiaLocal())
                assertTrue(e.dirty().isEmpty())
                assertNotNull(e.repo.sesion.value)
                assertNotNull(e.sesionGuardada())
            }
        }
    }

    @Test
    fun login_sin_dato_decide_la_copia_con_la_regla_de_hoy_y_no_la_toca() = runBlocking {
        data class Caso(val vence: String?, val activa: Boolean, val entra: Boolean)
        val sinRespuesta: List<Pair<String, suspend () -> EstadoLicenciaRemoto>> = listOf(
            "sin dato" to { EstadoLicenciaRemoto.SinDato },
            "el backend falla (5xx, 429, red)" to { throw IllegalStateException("se cayó") },
        )
        sinRespuesta.forEach { (nombre, respuesta) ->
            listOf(
                Caso(lejos, true, true), Caso(ayer, true, false), Caso(lejos, false, false), Caso(null, true, true),
            ).forEach { caso ->
                TurnosFalso().use { turnos ->
                    val e = escena(turnosUrl = turnos.url).also { it.pediatria.respuesta = respuesta }
                    if (caso.vence != null) e.copia(vence = caso.vence, activo = caso.activa)
                    val antes = e.copiaLocal()

                    val r = e.entrar()

                    if (caso.entra) assertIs<ResultadoLogin.Ok>(r, "$nombre, $caso") else assertIs<ResultadoLogin.LicenciaVencida>(r, "$nombre, $caso")
                    assertEquals(antes, e.copiaLocal(), "$nombre, $caso: la copia no se toca")
                    assertTrue(e.dirty().isEmpty())
                    assertEquals(caso.entra, e.sesionGuardada() != null, "$nombre, $caso")
                    assertEquals(caso.entra, e.repo.sesion.value != null, "$nombre, $caso")
                    if (!caso.entra) assertNull(e.cliente.token, "$nombre, $caso: bloqueado, sin token en los clientes")
                }
            }
        }
    }

    @Test
    fun login_del_administrador_y_de_la_secretaria_no_consulta_nada() = runBlocking {
        listOf("admin", "secretaria").forEach { rol ->
            TurnosFalso().use { turnos ->
                turnos.rol = rol
                val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
                val ok = assertIs<ResultadoLogin.Ok>(e.entrar(), rol)
                assertEquals("tok-login", ok.sesion.token)
                assertEquals(0, e.pediatria.consultas.get(), rol)
                assertNotNull(e.repo.sesion.value, rol)
            }
        }
    }

    @Test
    fun login_del_medico_sin_hc_con_licencia_no_consulta_y_decide_la_copia_como_hoy() = runBlocking {
        TurnosFalso().use { turnos ->
            turnos.historias = listOf("clinica")
            val vencida = escena(turnosUrl = turnos.url).also { it.pediatria.responde(vigente) }
            vencida.copia(vence = ayer)
            assertIs<ResultadoLogin.LicenciaVencida>(vencida.entrar())
            assertEquals(0, vencida.pediatria.consultas.get())

            val vigenteLocal = escena(turnosUrl = turnos.url).also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
            vigenteLocal.copia(vence = lejos)
            assertIs<ResultadoLogin.Ok>(vigenteLocal.entrar())
            assertEquals(0, vigenteLocal.pediatria.consultas.get())
        }
    }

    @Test
    fun login_con_un_servidor_que_no_contesta_termina_por_el_tope_de_iniciar_sesion_y_usa_la_copia() = runBlocking {
        val compuerta = CompletableDeferred<EstadoLicenciaRemoto>()
        try {
            TurnosFalso().use { turnos ->
                // El tope de restaurar es largo a propósito: `login` tiene que usar el suyo.
                val vencida = escena(turnosUrl = turnos.url, esperaLogin = 100.milliseconds, esperaRestaurar = 30.seconds)
                vencida.pediatria.respuesta = { compuerta.await() }
                vencida.copia(vence = ayer)
                withTimeout(20.seconds) { assertIs<ResultadoLogin.LicenciaVencida>(vencida.entrar(), "sin respuesta decide la copia vencida") }
                assertEquals(1, vencida.pediatria.consultas.get())
                assertNull(vencida.cliente.token)

                val vigenteLocal = escena(turnosUrl = turnos.url, esperaLogin = 100.milliseconds, esperaRestaurar = 30.seconds)
                vigenteLocal.pediatria.respuesta = { compuerta.await() }
                vigenteLocal.copia(vence = lejos)
                withTimeout(20.seconds) { assertIs<ResultadoLogin.Ok>(vigenteLocal.entrar(), "sin respuesta decide la copia vigente") }
                assertEquals(lejos, vigenteLocal.copiaLocal()?.fechaExpiracion, "la copia no se tocó")
                assertEquals(1L, vigenteLocal.actualizadaEn())

                val sinCopia = escena(turnosUrl = turnos.url, esperaLogin = 100.milliseconds, esperaRestaurar = 30.seconds)
                sinCopia.pediatria.respuesta = { compuerta.await() }
                withTimeout(20.seconds) { assertIs<ResultadoLogin.Ok>(sinCopia.entrar(), "P1: sin respuesta y sin copia entra") }
                assertNull(sinCopia.copiaLocal())
            }
        } finally {
            compuerta.complete(EstadoLicenciaRemoto.SinDato)
        }
    }

    @Test
    fun login_hace_un_solo_pedido_y_la_decision_queda_en_la_memoria_para_el_refresco() = runBlocking {
        TurnosFalso().use { turnos ->
            val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(vigente) }

            val ok = assertIs<ResultadoLogin.Ok>(e.entrar())

            assertEquals(1, turnos.logins.get())
            assertEquals(1, e.pediatria.consultas.get(), "a lo sumo un pedido al servidor de la HC por inicio de sesión")
            // El refresco de perfil que sigue al ingreso (T6) cae en la memoria de 60 s del mismo token.
            e.reloj.ahora = 30_000
            assertFalse(e.repo.decidirLicencia(ok.sesion, TOPE).second)
            assertEquals(1, e.pediatria.consultas.get())
        }
    }

    @Test
    fun login_con_credenciales_invalidas_no_consulta_ni_deja_token() = runBlocking {
        TurnosFalso().use { turnos ->
            turnos.rechazoDeLogin = 422 to """{"ok":false,"mensaje":"Credenciales incorrectas"}"""
            val e = escena(turnosUrl = turnos.url).also { it.pediatria.responde(vigente) }
            e.copia(vence = ayer)

            val r = e.entrar()

            val rechazo = assertIs<ResultadoLogin.CredencialesInvalidas>(r)
            assertEquals(422, rechazo.status)
            assertEquals(0, e.pediatria.consultas.get())
            assertNull(e.cliente.token)
            assertNull(e.repo.sesion.value)
            assertNull(e.sesionGuardada())
        }
    }

    @Test
    fun login_con_turnos_caido_y_sin_sesion_previa_no_consulta_nada() = runBlocking {
        val e = escena().also { it.pediatria.responde(vigente) }
        assertEquals(ResultadoLogin.SinConexionYSinSesionPrevia, e.entrar())
        assertEquals(0, e.pediatria.consultas.get())
        assertNull(e.cliente.token)
        assertNull(e.repo.sesion.value)
    }

    @Test
    fun login_sin_conexion_con_sesion_previa_decide_la_copia_y_no_consulta_nada() = runBlocking {
        // Sin conexión con turnos no se consulta la licencia (caso límite 11, RF-7): manda la copia, como hoy, aunque el
        // servidor de la HC dijera otra cosa.
        val vigenteLocal = escena().also { it.pediatria.responde(EstadoLicenciaRemoto.Vencida) }
        vigenteLocal.copia(vence = lejos)
        vigenteLocal.guardarSesion()
        val ok = assertIs<ResultadoLogin.Ok>(vigenteLocal.entrar())
        assertTrue(ok.offline)
        assertEquals("tok-guardado", ok.sesion.token)
        assertEquals("tok-guardado", vigenteLocal.cliente.token)
        assertNotNull(vigenteLocal.repo.sesion.value)
        assertEquals(0, vigenteLocal.pediatria.consultas.get())

        val vencidaLocal = escena().also { it.pediatria.responde(vigente) }
        vencidaLocal.copia(vence = ayer)
        vencidaLocal.guardarSesion()
        assertIs<ResultadoLogin.LicenciaVencida>(vencidaLocal.entrar())
        assertNull(vencidaLocal.repo.sesion.value)
        assertNull(vencidaLocal.sesionGuardada())
        assertNull(vencidaLocal.cliente.token)
        assertEquals(0, vencidaLocal.pediatria.consultas.get())
        assertEquals(Licencia(MEDICO_ID, ayer, avisoYa, importe = 0.0, activo = true), vencidaLocal.copiaLocal(), "la copia no se tocó")
    }

    @Test
    fun login_cancelado_mientras_consulta_no_deja_el_token_en_los_clientes_de_hc() = runBlocking {
        TurnosFalso().use { turnos ->
            val e = escena(turnosUrl = turnos.url)
            val entro = CompletableDeferred<Unit>()
            val compuerta = CompletableDeferred<EstadoLicenciaRemoto>()
            e.pediatria.respuesta = { entro.complete(Unit); compuerta.await() }

            val trabajo = async { e.entrar() }
            // Con tope: si `login` no llegara a consultar, el test falla en vez de colgarse.
            withTimeout(20.seconds) { entro.await() }
            assertEquals("tok-login", e.cliente.token)
            trabajo.cancelAndJoin()

            assertTrue(trabajo.isCancelled)
            assertNull(e.cliente.token, "sin sesión no puede quedar un token suelto en los clientes de HC")
            assertNull(e.repo.sesion.value)
            assertNull(e.sesionGuardada())
        }
    }

    // ---- Armado ----

    private class Reloj { @Volatile var ahora = 0L }

    private inner class Escena(
        val db: Salud360Db,
        val repo: AuthRepository,
        val reloj: Reloj,
        val pediatria: HcFalso,
        val usuario: Usuario,
        val cliente: HcApiClient,
    ) {
        /** La sesión del médico armada como la arma la app: desde la copia local, con el token que se le pasa. */
        suspend fun sesionMedico(token: String = "tok-a"): Sesion = resolverPerfil(db, usuario)!!.copy(token = token)

        /** Deja guardada la sesión de un ingreso anterior, que es lo que `restaurar` lee al recargar la página. */
        suspend fun guardarSesion(sesion: Sesion? = null) {
            val s = sesion ?: sesionMedico("tok-guardado")
            db.authQueries.guardarSesion(s.usuario.id, s.token, json.encodeToString(s), 1L)
        }

        suspend fun sesionGuardada() = db.authQueries.sesionActual().uno { it }

        /** El inicio de sesión del médico de la escena contra el turnos falso (el token que emite es `tok-login`). */
        suspend fun entrar(): ResultadoLogin = repo.login(Credenciales("medico@ejemplo.test", "clave-de-prueba"))

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
        esperaLogin: Duration = 10.seconds,
        esperaRestaurar: Duration = 5.seconds,
        turnosUrl: String = "http://127.0.0.1:1",
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
        // Turnos y API propia a un puerto cerrado (salvo el turnos falso local): si algo los llamara, fallaría rápido y no
        // tocaría producción. El cliente de HC también va a un puerto cerrado: solo se mira su token.
        val cliente = HcApiClient("http://127.0.0.1:1", "pediatria")
        val repo = AuthRepository(
            db, ApiClient("http://127.0.0.1:1"), TurnosOnlineClient(turnosUrl),
            hcClients = mapOf("pediatria" to cliente),
            backendsHc = backends, ahora = { reloj.ahora },
            esperaLogin = esperaLogin, esperaRestaurar = esperaRestaurar,
        )
        return Escena(db, repo, reloj, pediatria, usuario, cliente)
    }

    /**
     * turnos falso: contesta `auth/perfil` y `auth/login` con el perfil del médico de la escena (o el de un administrador
     * o una secretaria, según [rol]) y cuenta cuántas veces se lo piden. El login emite el token `tok-login`.
     */
    private class TurnosFalso : AutoCloseable {
        /** Pedidos de `auth/perfil` (cualquier ruta que no sea el login). */
        val pedidos = AtomicInteger()
        val logins = AtomicInteger()

        /** Corre en el hilo del servidor, antes de contestar. */
        @Volatile var alResponder: () -> Unit = {}

        /** `medico`, `admin` o `secretaria`. */
        @Volatile var rol = "medico"

        /** Historias clínicas habilitadas del médico que informa turnos. */
        @Volatile var historias = listOf("pediatria")

        /** Estado y cuerpo con que el login rechaza el ingreso; `null` = lo acepta. */
        @Volatile var rechazoDeLogin: Pair<Int, String>? = null
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val url: String get() = "http://127.0.0.1:${server.address.port}"

        private fun perfil(): String = when (rol) {
            "admin" -> """{"usuario":{"id":3,"nombre":"Admin","email":"medico@ejemplo.test","tipo":1},"rol":"admin"}"""
            "secretaria" -> """
                {"usuario":{"id":4,"nombre":"Secre","email":"medico@ejemplo.test","tipo":3},"rol":"secretaria",
                "secretaria":{"id":3,"nombre":"Secre","apellido":"Prueba"}}
            """.trimIndent()
            else -> """
                {"usuario":{"id":12,"nombre":"Medico","email":"medico@ejemplo.test","tipo":2},"rol":"medico",
                "medico":{"id":7,"nombre":"Medico","apellido":"Prueba","consultorio_id":0,
                "historias_clinicas":[${historias.joinToString(",") { "\"$it\"" }}]}}
            """.trimIndent()
        }

        private fun responder(x: com.sun.net.httpserver.HttpExchange, estado: Int, texto: String) {
            val cuerpo = texto.toByteArray()
            x.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            x.sendResponseHeaders(estado, cuerpo.size.toLong())
            x.responseBody.use { it.write(cuerpo) }
        }

        init {
            server.createContext("/") { x ->
                pedidos.incrementAndGet()
                alResponder()
                responder(x, 200, """{"ok":true,"perfil":${perfil()}}""")
            }
            server.createContext("/api/salud360/auth/login") { x ->
                logins.incrementAndGet()
                x.requestBody.readBytes()
                val rechazo = rechazoDeLogin
                if (rechazo != null) responder(x, rechazo.first, rechazo.second)
                else responder(x, 200, """{"ok":true,"access_token":"tok-login","expires_at":null,"perfil":${perfil()}}""")
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    /** Backend falso: cuenta las consultas de licencia y contesta lo que el test le programe. */
    private class HcFalso(override val especialidad: String) : HcBackend {
        val consultas = AtomicInteger()
        @Volatile var respuesta: suspend () -> EstadoLicenciaRemoto = { EstadoLicenciaRemoto.SinDato }

        /** Corre al empezar cada consulta, antes de contestar. */
        @Volatile var alConsultar: () -> Unit = {}

        fun responde(estado: EstadoLicenciaRemoto) { respuesta = { estado } }

        override suspend fun licenciaDelServidor(): EstadoLicenciaRemoto {
            consultas.incrementAndGet()
            alConsultar()
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
