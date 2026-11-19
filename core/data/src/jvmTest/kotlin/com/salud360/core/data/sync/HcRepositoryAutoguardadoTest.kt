package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.AvisoDeCambios
import com.salud360.core.data.hcgeneral.EstadoDeLaConsulta
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T36 (RF-2, RF-24, RF-27, RF-28, RF-62): lo que `ConsultaViewModel` usa para que la propia de la HC general se edite y se
 * envíe por la cola. El autoguardado (cada 600 ms) guarda en el dispositivo y **avisa** a la cola (`HcRepository.avisarCambio`), que no
 * hace ningún pedido: programa un envío para 4 s después del último cambio. Lo de la web no avisa a nadie; el histórico llega a la cola, que
 * lo ignora; pediatría sigue con su motor. Y el estado de envío de la consulta se puede observar (`observarEstadoDeEnvio`), con la cola real,
 * el servidor falso y el reloj falso: sin red ni tiempo real.
 */
class HcRepositoryAutoguardadoTest {

    private suspend fun Escena.consulta(id: Id): Consulta = db.historiaClinicaQueries.consultaPorId(id).lista { it.toModel() }.single()

    // ---- El autoguardado avisa, no pide ----

    @Test
    fun cambios_cada_600_ms_durante_30_segundos_no_hacen_ningun_pedido_y_sale_un_envio_a_los_4_segundos_del_ultimo() = conServidor {
        db.sembrarPropia("u1")
        val c = consulta("u1")

        repeat(50) { i -> // el autoguardado: guarda en el dispositivo y avisa
            assertTrue(hc.guardarSeccion("u1", "nota", mapOf("texto" to "texto $i")))
            assertEquals(AvisoDeCambios.COLA_DE_LA_HC_GENERAL, hc.avisarCambio(c))
            reloj.avanzar(600)
        }

        assertEquals(emptyList<String>(), servidor.pedidos, "ni un pedido mientras el médico escribe (RF-27)")
        assertEquals(1, reloj.esperando, "una sola espera")
        reloj.avanzar(3_399)
        assertEquals(emptyList(), servidor.pedidos)
        reloj.avanzar(1)

        assertEquals(listOf("crear c:u1", "secciones 7001 nota"), servidor.pedidos, "un solo envío, a los 4 s del último cambio (RF-28)")
        assertEquals(mapOf("nota" to "texto 49"), servidor.textosEnviados.last())
    }

    @Test
    fun una_propia_ya_enviada_sigue_siendo_editable_y_avisa() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")

        assertTrue(hc.guardarSeccion("u1", "nota", mapOf("texto" to "más texto")))
        assertEquals(AvisoDeCambios.COLA_DE_LA_HC_GENERAL, hc.avisarCambio(consulta("u1")))
        reloj.avanzar(HcClinicaEnvio.ESPERA_MS)

        assertEquals(listOf("secciones 7001 nota"), servidor.pedidos)
    }

    @Test
    fun una_consulta_de_la_web_no_avisa_ni_programa_nada() = conServidor {
        db.sembrarDeLaWeb("hcc-c7003")
        db.sembrarTexto("hcc-c7003")

        assertEquals(AvisoDeCambios.NINGUNO, hc.avisarCambio(consulta("hcc-c7003")))

        assertEquals(0, reloj.esperando)
        reloj.avanzar(3_600_000)
        assertEquals(emptyList(), servidor.pedidos)
    }

    @Test
    fun una_consulta_del_historico_llega_a_la_cola_pero_la_cola_no_la_programa() = conServidor {
        db.sembrarHistorica("historica")
        db.sembrarTexto("historica")

        assertEquals(AvisoDeCambios.COLA_DE_LA_HC_GENERAL, hc.avisarCambio(consulta("historica")))

        assertEquals(0, reloj.esperando, "nada del histórico sale solo (RF-7, D2)")
        reloj.avanzar(3_600_000)
        assertEquals(emptyList(), servidor.pedidos)
    }

    @Test
    fun la_propia_de_otro_medico_no_se_programa() = conServidor {
        db.sembrarPropia("u1", medico = "m-99000077")
        db.sembrarTexto("u1")

        hc.avisarCambio(consulta("u1"))

        assertEquals(0, reloj.esperando, "RF-50")
        assertEquals(emptyList(), servidor.pedidos)
    }

    @Test
    fun pediatria_sigue_con_su_motor_y_no_toca_la_cola() = conServidor {
        db.historiaClinicaQueries.upsertConsulta(Consulta("pedi-1", PACIENTE, MEDICO, "pediatria", "control", FECHA_DE_PRUEBA, EstadoConsulta.ABIERTA).toRow(100))

        assertEquals(AvisoDeCambios.MOTOR_DE_LA_ESPECIALIDAD, hc.avisarCambio(consulta("pedi-1")))

        assertEquals(0, reloj.esperando)
        assertEquals(emptyList(), servidor.pedidos)
    }

    @Test
    fun sin_cola_avisar_no_rompe_ni_programa_nada() = conServidor {
        db.sembrarPropia("u1")

        assertEquals(AvisoDeCambios.COLA_DE_LA_HC_GENERAL, repositorio(conCola = false).avisarCambio(consulta("u1")))

        assertEquals(0, reloj.esperando)
    }

    // ---- El estado de envío, observable ----
    // Las consultas a la base se observan en otro hilo (`flujoLista`): el test espera, en tiempo real y con tope, a que la última emisión sea la esperada.

    private suspend fun CopyOnWriteArrayList<EstadoDeLaConsulta?>.hasta(motivo: String, condicion: (EstadoDeLaConsulta?) -> Boolean): EstadoDeLaConsulta? {
        try {
            withTimeout(10_000) { while (!isNotEmpty() || !condicion(last())) delay(10) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("nunca llegó: $motivo (emisiones: $this)")
        }
        return last()
    }

    @Test
    fun el_estado_de_envio_sigue_a_la_consulta_falta_enviar_enviando_el_cierre_y_cierre_enviado() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false) // ya creada en la web, sin cambios
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        val estados = CopyOnWriteArrayList<EstadoDeLaConsulta?>()
        val coleccion = scope.launch { hc.observarEstadoDeEnvio(consulta("u1")).collect { estados += it } }

        assertNull(estados.hasta("estado inicial") { it != null }?.envio, "recién creada y sin nada que mandar")
        hc.guardarSeccion("u1", "nota", mapOf("texto" to "Texto de prueba"))
        estados.hasta("«Falta enviar» apenas se guarda el cambio") { it?.envio == EstadoEnvio.Pendiente }

        envio.empujarYa("u1").join()
        val enviado = estados.hasta("al enviarse, la marca desaparece") { it != null && it.envio == null }
        assertFalse(enviado!!.cierreEnviado)

        // El cierre: se queda esperando la respuesta del servidor ya aplicado.
        val enVuelo = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        servidor.alRecibir = { if (it == "cerrar") { enVuelo.complete(Unit); soltar.await() } }
        assertTrue(hc.cerrarConsulta("u1"))
        enVuelo.await()
        val viajando = estados.hasta("«Enviando el cierre.»") { it?.enviandoElCierre == true }!!
        assertFalse(viajando.puedeReabrir)
        assertFalse(viajando.puedeAnular)
        assertEquals("Enviando el cierre.", viajando.marca)

        soltar.complete(Unit)
        val ultimo = estados.hasta("la web ya la tiene cerrada") { it != null && it.cierreEnviado && !it.enviandoElCierre }!!
        assertFalse(ultimo.puedeReabrir)
        assertFalse(ultimo.puedeAnular)
        assertNull(ultimo.envio)
        coleccion.cancel()
    }

    @Test
    fun el_estado_de_envio_muestra_en_espera_cuando_la_web_no_contesta() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1")
        val estados = CopyOnWriteArrayList<EstadoDeLaConsulta?>()
        val coleccion = scope.launch { hc.observarEstadoDeEnvio(consulta("u1")).collect { estados += it } }
        estados.hasta("«Falta enviar»") { it?.envio == EstadoEnvio.Pendiente }
        servidor.falla = { TobbException(0, "red", "Sin conexión") }

        envio.empujarYa("u1").join()

        assertIs<EstadoEnvio.EnEspera>(estados.hasta("«En espera»") { it?.envio is EstadoEnvio.EnEspera }?.envio)
        coleccion.cancel()
    }
    @Test
    fun solo_una_propia_de_la_hc_general_con_cola_tiene_estado_de_envio() = conServidor {
        db.sembrarDeLaWeb("hcc-c7003")
        db.sembrarHistorica("historica")
        db.sembrarPropia("u1")
        val web = hc.observarEstadoDeEnvio(consulta("hcc-c7003")).toList()
        val historica = hc.observarEstadoDeEnvio(consulta("historica")).toList()
        val sinCola = repositorio(conCola = false).observarEstadoDeEnvio(consulta("u1")).toList()

        assertEquals(listOf(null), web)
        assertEquals(listOf(null), historica)
        assertEquals(listOf(null), sinCola)
    }
}