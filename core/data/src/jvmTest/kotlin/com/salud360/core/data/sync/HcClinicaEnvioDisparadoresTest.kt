package com.salud360.core.data.sync

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.TurnosPorConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec 005, T31 (RF-57, RF-29, RF-50, RF-7): los disparadores **abrir la app / iniciar sesión** (`alAbrirOIniciar`) y **recuperar la
 * conexión** (`alRecuperarConexion`, que llama el `ObservadorDeConexion` de cada plataforma) retoman lo **propio** del médico de la sesión
 * que quedó pendiente o «En espera». Todos pasan por la misma espera de 4 s del envío diferido —nunca por el ritmo del autoguardado (600 ms)—,
 * no corren en paralelo con un envío en curso de esa consulta y no multiplican: varios eventos seguidos arman una sola espera por consulta.
 * No envían nada del histórico, de lo que trajo la web, de otro médico ni de una consulta sin nada pendiente. Servidor falso con memoria,
 * reloj falso y un observador falso; sin red ni tiempo real.
 */
class HcClinicaEnvioDisparadoresTest {

    /** El observador de la plataforma, a mano: el test decide cuándo «vuelve la red». */
    private class ObservadorFalso : ObservadorDeConexion {
        private var aviso: (() -> Unit)? = null
        var iniciados = 0
        var detenidos = 0

        override fun iniciar(alRecuperar: () -> Unit) { aviso = alRecuperar; iniciados++ }
        override fun detener() { aviso = null; detenidos++ }
        fun volvioLaRed() { aviso?.invoke() }
    }

    private val espera = HcClinicaEnvio.ESPERA_MS

    /** Una propia que el médico dejó con un texto sin enviar (la app se cerró antes de que saliera). */
    private suspend fun Escena.pendiente(id: String = "u1", medico: String = MEDICO) {
        db.sembrarPropia(id, medico = medico)
        db.sembrarTexto(id)
    }

    private val envioCompleto = listOf("crear c:u1", "secciones 7001 nota")

    @Test
    fun la_espera_de_los_disparadores_es_la_de_4_segundos() {
        assertEquals(4_000L, espera)
    }

    @Test
    fun al_abrir_la_app_lo_pendiente_sale_a_los_4_segundos_y_no_antes() = conServidor {
        pendiente()

        envio.alAbrirOIniciar().join()
        assertEquals(1, reloj.esperando, "una espera, la de la consulta")
        reloj.avanzar(600) // el ritmo del autoguardado
        reloj.avanzar(espera - 601)
        assertEquals(emptyList(), servidor.pedidos, "ni a los 600 ms ni a 3,999 s")
        reloj.avanzar(1)

        assertEquals(envioCompleto, servidor.pedidos)
        assertEquals(emptySet(), db.textosSucios("u1"))
    }

    @Test
    fun al_recuperar_la_conexion_pasa_por_la_misma_espera() = conServidor {
        pendiente()

        envio.alRecuperarConexion().join()
        reloj.avanzar(espera - 1)
        assertEquals(emptyList(), servidor.pedidos)
        reloj.avanzar(1)

        assertEquals(envioCompleto, servidor.pedidos)
    }

    @Test
    fun el_disparador_y_el_autoguardado_comparten_una_sola_espera_por_consulta() = conServidor {
        pendiente()

        envio.marcarSucia("u1")
        envio.alAbrirOIniciar().join()
        envio.alRecuperarConexion().join()

        assertEquals(1, reloj.esperando, "los tres arman la misma espera, no tres")
        reloj.avanzar(espera)
        assertEquals(envioCompleto, servidor.pedidos, "y sale un solo envío")
        reloj.avanzar(3_600_000)
        assertEquals(envioCompleto, servidor.pedidos)
    }

    @Test
    fun varios_eventos_de_conexion_seguidos_no_hacen_rafaga_ni_envios_duplicados() = conServidor {
        pendiente()
        val observador = ObservadorFalso()
        envio.observar(observador)

        repeat(8) {
            observador.volvioLaRed() // flapping: la red va y viene cada segundo
            assertTrue(reloj.esperando <= 1, "nunca más de una espera")
            assertTrue(servidor.pedidos.size <= envioCompleto.size, "nunca más de un envío")
            reloj.avanzar(1_000)
        }

        // Un disparador adelanta pero no atrasa: sale a los 4 s del primer evento, una sola vez, con la creación una sola vez.
        assertEquals(envioCompleto, servidor.pedidos)
        reloj.avanzar(3_600_000)
        assertEquals(envioCompleto, servidor.pedidos)
        assertEquals(0, reloj.esperando)
    }

    @Test
    fun un_evento_no_atrasa_una_espera_que_vence_antes() = conServidor {
        pendiente()
        envio.marcarSucia("u1") // sale a los 4 s
        reloj.avanzar(3_000)

        envio.alRecuperarConexion().join() // si reiniciara la espera saldría recién a los 7 s
        reloj.avanzar(1_000)

        assertEquals(envioCompleto, servidor.pedidos)
    }
    @Test
    fun el_observador_al_registrarse_no_dispara_nada_aunque_haya_pendientes() = conServidor {
        pendiente()
        val observador = ObservadorFalso()

        envio.observar(observador)

        assertEquals(1, observador.iniciados)
        assertEquals(0, reloj.esperando, "registrarse no programa nada")
        reloj.avanzar(3_600_000)
        assertEquals(emptyList(), servidor.pedidos)
    }

    @Test
    fun un_evento_de_conexion_sin_pendientes_no_pide_ni_programa_nada() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false) // propia ya enviada y sin cambios
        val observador = ObservadorFalso()
        envio.observar(observador)

        observador.volvioLaRed()
        envio.alAbrirOIniciar().join()

        assertEquals(0, reloj.esperando)
        reloj.avanzar(3_600_000)
        assertEquals(emptyList(), servidor.pedidos)
    }

    @Test
    fun dejar_de_observar_corta_el_aviso() = conServidor {
        pendiente()
        val observador = ObservadorFalso()
        envio.observar(observador)
        envio.dejarDeObservar(observador)

        observador.volvioLaRed()

        assertEquals(1, observador.detenidos)
        assertEquals(0, reloj.esperando)
    }

    @Test
    fun solo_retoma_lo_propio_del_medico_de_la_sesion() = conServidor {
        pendiente("u1")
        pendiente("de-otro", medico = "m-99000077")
        db.sembrarHistorica("historica"); db.sembrarTexto("historica")
        db.sembrarDeLaWeb("hcc-c7003"); db.sembrarTexto("hcc-c7003")
        db.sembrarPropia("limpia", remotoId = "7010", dirty = false)
        envio.alAbrirOIniciar().join()
        envio.alRecuperarConexion().join()
        assertEquals(1, reloj.esperando, "solo la suya con algo pendiente")
        reloj.avanzar(3_600_000)

        assertEquals(envioCompleto, servidor.pedidos, "ni el otro médico, ni el histórico, ni lo de la web, ni la limpia")
        for (otra in listOf("de-otro", "historica", "hcc-c7003")) assertEquals(setOf("nota/texto"), db.textosSucios(otra), "$otra sigue como estaba")
        assertEquals(emptySet(), db.textosSucios("u1"))
    }

    @Test
    fun sin_sesion_no_hacen_nada_y_con_la_de_otro_medico_tampoco() = conServidor {
        pendiente()
        medicoDeLaSesion = null

        envio.alAbrirOIniciar().join()
        envio.alRecuperarConexion().join()
        assertEquals(0, reloj.esperando)
        medicoDeLaSesion = "m-99000077"
        envio.alAbrirOIniciar().join()
        assertEquals(0, reloj.esperando)
        reloj.avanzar(3_600_000)

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"), "lo del primero no se toca")

        medicoDeLaSesion = MEDICO // vuelve su dueño
        envio.alAbrirOIniciar().join()
        reloj.avanzar(espera)
        assertEquals(envioCompleto, servidor.pedidos)
    }

    @Test
    fun la_sesion_que_cambia_durante_la_espera_corta_el_envio() = conServidor {
        pendiente()
        envio.alAbrirOIniciar().join()
        medicoDeLaSesion = "m-99000077" // entra otro médico antes de que venza la espera

        reloj.avanzar(espera)

        assertEquals(emptyList(), servidor.pedidos, "RF-50: no sale con el token de otro")
    }

    @Test
    fun no_corre_en_paralelo_con_un_envio_en_curso_de_la_misma_consulta() = conServidor {
        pendiente()
        val enVuelo = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        servidor.alRecibir = { if (it == "secciones") { enVuelo.complete(Unit); soltar.await() } }
        val enviando = envio.empujarYa("u1")
        enVuelo.await() // el servidor ya aplicó el texto y todavía no contestó

        envio.alRecuperarConexion().join()
        reloj.avanzar(espera)
        assertEquals(envioCompleto, servidor.pedidos, "el disparador espera su turno: no manda nada mientras el otro viaja")

        soltar.complete(Unit)
        enviando.join()
        reloj.avanzar(3_600_000)
        assertEquals(envioCompleto, servidor.pedidos, "y después no encuentra nada que mandar: no se duplica")
        assertEquals(emptySet(), db.textosSucios("u1"))
    }

    @Test
    fun consultas_distintas_se_retoman_cada_una_con_su_espera() = conServidor {
        pendiente("u1")
        pendiente("u2")

        envio.alAbrirOIniciar().join()
        assertEquals(2, reloj.esperando)
        reloj.avanzar(espera)

        assertEquals(2, servidor.pedidos.count { it.startsWith("crear") })
        assertEquals(2, servidor.pedidos.count { it.startsWith("secciones") })
    }

    @Test
    fun al_reabrir_la_app_retoma_lo_que_quedo_en_espera_porque_la_cadena_de_reintentos_no_sobrevive() = conServidor {
        pendiente()
        // La app anterior: sin conexión, la consulta quedó «En espera» con un reintento a los 15 s... y la app se cerró.
        val relojViejo = RelojFalso()
        val scopeViejo = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viejo = HcClinicaEnvio(
            db, scopeViejo, relojViejo::esperar, { relojViejo.ahora }, escritura = servidor, pacientes = pacientes,
            dispositivo = Escena.DISPOSITIVO, nombreHc = "Clínica general", medicoDeLaSesion = { MEDICO }, turnos = TurnosPorConsulta(),
        )
        servidor.falla = { TobbException(0, "red", "Sin conexión") }
        viejo.empujarYa("u1").join()
        assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").values.first().estado)
        scopeViejo.cancel() // se cerró la app
        servidor.falla = null
        servidor.pedidos.clear()

        // La app nueva: nadie programó nada.
        assertEquals(0, reloj.esperando)
        envio.alAbrirOIniciar().join()
        reloj.avanzar(espera)

        assertEquals(envioCompleto, servidor.pedidos)
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun el_disparador_adelanta_un_reintento_largo_en_vez_de_esperarlo() = conServidor {
        pendiente()
        servidor.falla = { TobbException(0, "red", "Sin conexión") }
        envio.empujarYa("u1").join() // En espera: próximo intento a los 15 s
        reloj.avanzar(15_000)        // falla de nuevo: próximo a los 60 s
        reloj.avanzar(60_000)        // falla de nuevo: próximo a los 300 s
        assertEquals(1, reloj.esperando)
        servidor.falla = null // la red vuelve
        servidor.pedidos.clear()

        envio.alRecuperarConexion().join()
        assertEquals(1, reloj.esperando, "reemplaza la espera larga: no suma otra")
        reloj.avanzar(espera)

        assertEquals(envioCompleto, servidor.pedidos, "sale a los 4 s y no a los 300 s")
        reloj.avanzar(3_600_000)
        assertEquals(envioCompleto, servidor.pedidos)
    }

    @Test
    fun lo_rechazado_por_el_dato_no_se_reintenta_con_los_disparadores() = conServidor {
        pendiente()
        servidor.falla = { if (it == "secciones") TobbException(422, "datos_invalidos", "Datos que la web no acepta") else null }
        envio.empujarYa("u1").join()
        assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue("secciones").estado)
        val pedidos = servidor.pedidos.size

        envio.alAbrirOIniciar().join()
        envio.alRecuperarConexion().join()
        assertEquals(0, reloj.esperando, "«No se pudo enviar» espera al médico (Reintentar o Descartar), no a la conexión")
        reloj.avanzar(3_600_000)

        assertEquals(pedidos, servidor.pedidos.size)
    }

    @Test
    fun un_disparador_con_un_servidor_caido_sigue_la_cadena_de_reintentos_normal() = conServidor {
        pendiente()
        servidor.falla = { TobbException(503, "servidor", "Caído") }

        envio.alAbrirOIniciar().join()
        reloj.avanzar(espera) // primer intento: falla
        assertTrue(servidor.pedidos.isNotEmpty())
        val tras = servidor.pedidos.size
        assertEquals(1, reloj.esperando, "queda el reintento a los 15 s")
        reloj.avanzar(14_999)
        assertEquals(tras, servidor.pedidos.size, "no hay ráfaga: el reintento respeta su espera")
    }
}