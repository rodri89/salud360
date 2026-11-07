package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_SECCIONES
import com.salud360.core.data.hcgeneral.TipoDeBanner
import com.salud360.core.data.hcgeneral.bannersDeEnvio
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec 005, T37 (RF-6, RF-30): «Reintentar» y «Descartar» de la franja de estado llegan a la cola por `HcRepository` (el `ConsultaViewModel`
 * no tiene lógica propia), y el estado de la consulta dice qué partes se descartaron para la franja «Descartado: no se envió a la web».
 * Cola real, servidor falso y reloj falso: sin red ni tiempo real.
 */
class HcRepositoryAccionesDeEnvioTest {
    private val rechazo422 = { TobbException(422, "datos_invalidos", "Datos que la web no acepta") }

    private suspend fun Escena.estado() = envio.estadoDeLaConsulta("u1")

    private suspend fun Escena.propiaRechazada() {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1")
        servidor.falla = { if (it == "secciones") rechazo422() else null }
        envio.empujarYa("u1").join()
    }

    @Test
    fun descartar_deja_la_parte_descartada_y_la_franja_dice_descartado() = conServidor {
        propiaRechazada()
        assertIs<EstadoEnvio.NoSePudoEnviar>(estado()!!.envio)
        assertEquals(listOf(TipoDeBanner.NO_SE_PUDO_ENVIAR), bannersDeEnvio(estado()).map { it.tipo })

        hc.descartarEnvio("u1")

        val despues = estado()!!
        assertEquals(null, despues.envio, "ya no hay nada pendiente")
        assertEquals(setOf(PARTE_SECCIONES), despues.partesDescartadas)
        assertEquals(listOf(TipoDeBanner.DESCARTADO), bannersDeEnvio(despues).map { it.tipo })
        assertEquals("Descartado: no se envió a la web", bannersDeEnvio(despues).single().texto)
    }

    @Test
    fun reintentar_vuelve_a_mandar_lo_rechazado_y_la_franja_desaparece() = conServidor {
        propiaRechazada()
        servidor.falla = null // el médico corrigió el problema

        hc.reintentarEnvio("u1")

        assertEquals(emptyList(), bannersDeEnvio(estado()))
        assertEquals(listOf("secciones 7001 nota", "secciones 7001 nota"), servidor.pedidos, "la misma clave y el mismo cuerpo, una vez más")
    }

    @Test
    fun reintentar_a_mano_una_espera_no_aguarda_los_15_segundos() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1")
        servidor.falla = { TobbException(0, "red", "Sin conexión") }
        envio.empujarYa("u1").join()
        assertEquals(TipoDeBanner.EN_ESPERA, bannersDeEnvio(estado()).single().tipo)
        servidor.falla = null

        hc.reintentarEnvio("u1")

        assertEquals(emptyList(), bannersDeEnvio(estado()))
        assertTrue(servidor.pedidos.any { it.startsWith("secciones") })
    }

    @Test
    fun sin_cola_no_hace_nada() = conServidor {
        db.sembrarPropia("u1")

        repositorio(conCola = false).reintentarEnvio("u1")
        repositorio(conCola = false).descartarEnvio("u1")

        assertEquals(emptyList<String>(), servidor.pedidos)
    }
}