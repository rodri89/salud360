package com.salud360.core.data.sync

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.model.hc.EstadoConsulta
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005, T27 (RF-29, RF-15, RF-32, D2): lo que no llegó se reintenta **solo**, con el reloj falso. A los 15 s, al minuto y a los 5
 * minutos, y después cada 5 minutos mientras la app esté abierta; nunca más rápido y sin ráfagas (ni siquiera con «demasiados pedidos»,
 * que frena a todas las consultas). Un reintento no duplica (misma clave), manda solo lo que falta y no manda nada de lo que no sale
 * solo. Corre en el `scope` de la cola: sin app abierta no hay reintentos, y no hay procesos en segundo plano. Servidor falso con
 * memoria; sin red ni tiempo real.
 */
class HcClinicaEnvioReintentoTest {
    private val sinConexion = { TobbException(0, "red", "Sin conexión") }

    /** Hace fallar todos los pedidos con [error] y anota el instante (del reloj falso) de cada uno. */
    private fun Escena.fallarSiempre(error: () -> Throwable = sinConexion): MutableList<Long> {
        val instantes = mutableListOf<Long>()
        servidor.falla = { instantes += reloj.ahora; error() }
        return instantes
    }

    private fun esperado(hasta: Long): List<Long> =
        listOf(0L, 15_000L, 75_000L) + generateSequence(375_000L) { it + 300_000L }.takeWhile { it <= hasta }.toList()

    @Test
    fun tras_un_fallo_reintenta_a_los_15_60_y_300_segundos_y_despues_cada_300_mientras_la_app_siga_abierta() = conServidor {
        db.sembrarPropia("u1")
        val instantes = fallarSiempre()

        envio.empujarYa("u1").join()
        assertEquals(1, reloj.esperando, "queda un reintento programado")
        reloj.avanzar(3_600_000)

        assertEquals(esperado(3_600_000), instantes, "0 s, +15 s, +60 s, +300 s y después cada 300 s")
        assertEquals(1, reloj.esperando, "siempre uno solo: no se acumulan")
    }

    @Test
    fun no_se_adelanta_ni_un_milisegundo() = conServidor {
        db.sembrarPropia("u1")
        val instantes = fallarSiempre()

        envio.empujarYa("u1").join()
        reloj.avanzar(14_999)
        assertEquals(1, instantes.size)
        reloj.avanzar(1)
        assertEquals(2, instantes.size)
        reloj.avanzar(59_999)
        assertEquals(2, instantes.size)
        reloj.avanzar(1)
        assertEquals(3, instantes.size)
        reloj.avanzar(299_999)
        assertEquals(3, instantes.size)
        reloj.avanzar(1)
        assertEquals(4, instantes.size)
    }

    @Test
    fun un_servidor_caido_un_rato_que_vuelve_hace_que_salga_solo_sin_hacer_nada() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        var fallos = 0
        servidor.falla = { if (fallos++ < 2) TobbException(503, "tobb_caido", "turnos caído") else null }

        envio.empujarYa("u1").join()
        assertEquals(emptyList(), servidor.consultas.keys.toList(), "todavía no llegó nada")

        reloj.avanzar(75_000) // 15 s + 60 s: el tercer intento, con el servidor ya de vuelta

        assertEquals(listOf("crear c:u1", "crear c:u1", "crear c:u1", "secciones 7001 nota", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(emptyMap(), db.estadosDe("u1"), "y ya no está en espera")
        assertEquals(emptyList(), db.filasSucias().flatten())
        assertEquals(0, reloj.esperando, "ni queda nada programado")
        reloj.avanzar(3_600_000)
        assertEquals(5, servidor.pedidos.size, "y no vuelve a mandar")
    }

    @Test
    fun un_exito_reinicia_la_cuenta_el_proximo_fallo_vuelve_a_empezar_en_15_s() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Uno")
        val instantes = mutableListOf<Long>()
        var fallar = true
        servidor.falla = { instantes += reloj.ahora; if (fallar) TobbException(0, "red", "Sin conexión") else null }

        envio.empujarYa("u1").join() // 0 s: falla
        reloj.avanzar(15_000) // 15 s: falla
        fallar = false
        reloj.avanzar(60_000) // 75 s: llega
        assertEquals(listOf(0L, 15_000L, 75_000L), instantes)
        assertEquals(0, reloj.esperando)

        // Más tarde el médico escribe otra cosa y vuelve a fallar: la cuenta no sigue de la vez anterior.
        fallar = true
        reloj.avanzar(25_000) // 100 s
        db.escribirTexto("u1", "nota", "Dos", actualizadaEn = 900)
        envio.marcarSucia("u1")
        reloj.avanzar(4_000) // 104 s: falla
        reloj.avanzar(15_000) // 119 s: reintenta a los 15 s, no a los 300

        assertEquals(listOf(0L, 15_000L, 75_000L, 104_000L, 119_000L), instantes)
    }

    // ---- «Demasiados pedidos» (429): sin ráfaga ----

    @Test
    fun con_429_los_reintentos_van_espaciados_y_nunca_en_rafaga() = conServidor {
        db.sembrarPropia("u1")
        val instantes = fallarSiempre { TobbException(429, null, "Too Many Attempts.") }

        envio.empujarYa("u1").join()
        reloj.avanzar(3_600_000)

        assertEquals(esperado(3_600_000), instantes)
        assertTrue(instantes.zipWithNext().all { (a, b) -> b - a >= 15_000 }, "nunca dos pedidos a menos de 15 s")
    }

    @Test
    fun un_429_frena_a_todas_las_consultas_no_solo_a_la_que_lo_recibio() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarPropia("u2")
        val instantes = mutableListOf<Long>()
        var primera = true
        servidor.falla = {
            instantes += reloj.ahora
            if (primera) { primera = false; TobbException(429, null, "Too Many Attempts.") } else null
        }

        envio.empujarYa("u1").join() // 0 s: 429
        reloj.avanzar(2_000)
        envio.marcarSucia("u2") // escribe en otra consulta: saldría a los 6 s…
        reloj.avanzar(12_999) // …pero la web pidió esperar: hasta los 15 s no sale nada (t = 14.999 ms)

        assertEquals(listOf(0L), instantes, "entre el 429 y la espera no sale ningún pedido, de ninguna consulta")

        reloj.avanzar(1)
        assertEquals(listOf(0L, 15_000L, 15_000L), instantes, "a los 15 s salen las dos")
        assertEquals(2, servidor.cantidadDeConsultas)
    }

    @Test
    fun el_pedido_a_mano_no_espera_al_freno_del_429() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarPropia("u2")
        var primera = true
        servidor.falla = { if (primera) { primera = false; TobbException(429, null, "Too Many Attempts.") } else null }

        envio.empujarYa("u1").join()
        envio.empujarYa("u2").join() // el médico lo pide: sale

        assertEquals(listOf("crear c:u1", "crear c:u2"), servidor.pedidos)
    }

    // ---- Un reintento no duplica y manda solo lo que falta ----

    @Test
    fun si_se_pierde_la_respuesta_de_la_creacion_el_reintento_repite_la_clave_y_queda_una_consulta() = conServidor {
        db.sembrarPropia("u1")
        servidor.respuestasPerdidas += "crear"

        envio.empujarYa("u1").join()
        servidor.respuestasPerdidas.clear()
        reloj.avanzar(15_000)

        assertEquals(listOf("c:u1", "c:u1"), servidor.creaciones.map { it.clave })
        assertEquals(1, servidor.cantidadDeConsultas)
        assertEquals("7001", db.consultaLocal("u1")!!.remotoId)
        assertEquals(0, reloj.esperando)
    }

    @Test
    fun un_envio_parcial_se_completa_solo_y_no_vuelve_a_mandar_lo_que_ya_llego() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        db.sembrarExamen("u1", peso = "70")
        var primera = true
        servidor.falla = { if (it == "examen" && primera) { primera = false; TobbException(0, "red", "Sin conexión") } else null }

        envio.empujarYa("u1").join()
        assertEquals(listOf("crear c:u1", "secciones 7001 nota", "examen 7001 peso"), servidor.pedidos)

        reloj.avanzar(15_000)

        assertEquals(
            listOf("crear c:u1", "secciones 7001 nota", "examen 7001 peso", "examen 7001 peso", "cerrar 7001 c:u1-cierre"),
            servidor.pedidos,
            "el reintento sigue donde quedó: ni crea ni manda otra vez los textos",
        )
        assertEquals(emptyList(), db.filasSucias().flatten())
    }

    @Test
    fun un_fallo_local_al_anotar_tambien_se_reintenta_solo() = conServidor {
        db.sembrarPropia("u1")
        driver.execute(
            null,
            "CREATE TRIGGER corte BEFORE UPDATE OF dirty ON consulta WHEN NEW.dirty = 0 BEGIN SELECT RAISE(ABORT, 'corte simulado'); END",
            0,
        ).value

        envio.empujarYa("u1").join()
        assertEquals(1, reloj.esperando, "se programó otro intento")

        driver.execute(null, "DROP TRIGGER corte", 0).value
        reloj.avanzar(15_000)

        assertEquals(listOf("crear c:u1", "crear c:u1"), servidor.pedidos)
        assertEquals(1, servidor.cantidadDeConsultas, "sin duplicar")
        assertFalse(db.consultaSucia("u1"))
    }

    // ---- Un cambio del médico adelanta el reintento; no lo multiplica ----

    @Test
    fun un_cambio_del_medico_adelanta_el_reintento_y_no_lo_multiplica() = conServidor {
        db.sembrarPropia("u1")
        val instantes = fallarSiempre()

        envio.empujarYa("u1").join() // 0 s: falla; el reintento quedó para los 15 s
        reloj.avanzar(5_000)
        envio.marcarSucia("u1") // escribe: se adelanta a los 9 s
        assertEquals(1, reloj.esperando, "reemplaza al reintento, no se suma")
        reloj.avanzar(10_000)

        assertEquals(listOf(0L, 9_000L), instantes, "sale a los 9 s y a los 15 s no sale otro")
        reloj.avanzar(60_000)
        assertEquals(listOf(0L, 9_000L, 69_000L), instantes, "y el siguiente sigue la cuenta: 60 s")
    }

    @Test
    fun un_cambio_mientras_viaja_un_envio_que_falla_no_se_pisa_con_el_reintento_mas_lento() = conServidor {
        db.sembrarPropia("u1")
        servidor.respuestasPerdidas += "crear"
        var escribio = false
        // El médico escribe mientras viaja la creación (el servidor ya la aplicó y todavía no contestó): eso arma una espera de 4 s.
        servidor.alRecibir = {
            if (!escribio) {
                escribio = true
                envio.marcarSucia("u1")
                yield()
            }
        }

        envio.empujarYa("u1").join() // 0 s: se pierde la respuesta; el reintento por fallo (15 s) no pisa la espera de 4 s ya armada

        assertEquals(1, reloj.esperando)
        servidor.respuestasPerdidas.clear()
        reloj.avanzar(3_999)
        assertEquals(1, servidor.creaciones.size)
        reloj.avanzar(1)
        assertEquals(2, servidor.creaciones.size, "sale a los 4 s del cambio, no a los 15 s del reintento")
    }
    // ---- Solo con la app abierta, y solo lo que corresponde ----

    @Test
    fun sin_la_app_abierta_no_hay_reintentos() = conServidor {
        db.sembrarPropia("u1")
        val instantes = fallarSiempre()
        envio.empujarYa("u1").join()

        scope.cancel() // se cierra la app
        reloj.avanzar(3_600_000)

        assertEquals(listOf(0L), instantes)
        assertEquals(0, reloj.esperando, "y no queda nada esperando")
    }

    @Test
    fun si_cambia_la_sesion_el_reintento_no_manda_nada_con_el_token_de_otro_y_la_cadena_termina() = conServidor {
        db.sembrarPropia("u1")
        val instantes = fallarSiempre()
        envio.empujarYa("u1").join()

        medicoDeLaSesion = "m-otro"
        reloj.avanzar(3_600_000)

        assertEquals(listOf(0L), instantes, "nada sale con la sesión de otro")
        assertEquals(0, reloj.esperando, "y no se sigue reintentando")

        // Vuelve su dueño: lo retoma lo que dispare el envío (abrir la app, iniciar sesión: T31) o el médico; acá, a mano.
        medicoDeLaSesion = MEDICO
        servidor.falla = null
        envio.empujarYa("u1").join()
        assertEquals(1, servidor.cantidadDeConsultas)
    }

    @Test
    fun el_reintento_no_manda_lo_que_no_sale_solo_el_historico_la_web_ni_lo_desactivado() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Va")
        db.sembrarHistorica("hist-1")
        db.sembrarTexto("hist-1")
        db.sembrarDeLaWeb("hcc-c7003")
        db.sembrarTexto("hcc-c7003")
        db.sembrarAntecedente("a-desactivado", "u1", activo = false)
        var primera = true
        servidor.falla = { if (primera) { primera = false; TobbException(0, "red", "Sin conexión") } else null }
        val historicoAntes = db.filasSucias().map { fila -> fila.filter { it.toString().contains("hist-1") || it.toString().contains("hcc-c7003") } }

        envio.empujarYa("u1").join()
        reloj.avanzar(3_600_000)

        assertEquals(listOf("secciones 7001 nota", "secciones 7001 nota"), servidor.pedidos, "solo lo de la propia, y nada del desactivado")
        assertEquals(historicoAntes, db.filasSucias().map { fila -> fila.filter { it.toString().contains("hist-1") || it.toString().contains("hcc-c7003") } })
        assertFalse(db.antecedenteSucio("a-desactivado"), "lo desactivado se limpia sin enviarse")
    }

    @Test
    fun una_consulta_que_ya_no_tiene_nada_pendiente_corta_su_cadena() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Uno")
        val instantes = fallarSiempre()
        envio.empujarYa("u1").join()
        assertEquals(1, reloj.esperando)

        // El médico vuelve el texto a lo que ya tiene la web: ya no hay nada que mandar.
        db.historiaClinicaQueries.limpiarSeccionValor("u1", "nota", "texto")
        reloj.avanzar(3_600_000)

        assertEquals(listOf(0L), instantes, "el reintento ve que no hay nada y no manda")
        assertEquals(0, reloj.esperando)
    }
}
