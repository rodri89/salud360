package com.salud360.core.data.sync

import com.salud360.core.data.repos.HcClinicaBackend
import com.salud360.core.data.repos.HcClinicaPruebas
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 005, T24 (RF-27, RF-28): el ritmo de [HcClinicaEnvio]. El autoguardado dispara cada 600 ms mientras el médico escribe; la cola
 * **solo marca y programa**: no manda nada hasta que pasan 4 segundos sin cambios nuevos en esa consulta, y entonces manda **una vez**.
 * Con un reloj falso ([RelojFalso]), sin tiempo real: dos corridas dan lo mismo. El envío de prueba no toca ninguna red.
 */
class HcClinicaEnvioRitmoTest {

    @Test
    fun cambios_cada_600_ms_durante_30_s_no_envian_nada_y_a_los_4_s_del_ultimo_sale_uno_solo() = conCola { db, envio, reloj ->
        db.sembrarPropia("c1")

        // 50 cambios, uno cada 600 ms: el último a los 29,4 s.
        repeat(50) { i ->
            envio.marcarSucia("c1")
            reloj.avanzar(600)
            assertEquals(emptyList(), envio.envios, "ningún envío durante la escritura (cambio ${i + 1}, t=${reloj.ahora})")
        }
        val ultimoCambio = 49 * 600L
        assertEquals(30_000L, reloj.ahora)

        // Faltando 1 ms para los 4 s del último cambio, todavía nada.
        reloj.avanzar(ultimoCambio + 4_000 - 1 - reloj.ahora)
        assertEquals(emptyList(), envio.envios, "a 3.999 ms del último cambio todavía no sale")

        // A los 4 s justos, uno solo, y a esa hora.
        reloj.avanzar(1)
        assertEquals(listOf("inicio c1"), envio.envios)
        assertEquals(listOf(ultimoCambio + 4_000), envio.momentos, "a los 4 s del último cambio, no antes")

        // Y no sale otro: ni más tarde ni con el tiempo.
        reloj.avanzar(120_000)
        assertEquals(1, envio.envios.size, "uno solo")
        assertEquals(0, reloj.esperando, "y no queda ninguna espera en pie")
    }

    @Test
    fun la_espera_es_de_cada_consulta_los_cambios_de_una_no_atrasan_a_la_otra() = conCola { db, envio, reloj ->
        db.sembrarPropia("c1")
        db.sembrarPropia("c2")

        envio.marcarSucia("c1") // t = 0
        reloj.avanzar(3_000)
        envio.marcarSucia("c2") // t = 3.000: escribir en c2 no atrasa a c1
        reloj.avanzar(999)
        assertEquals(emptyList(), envio.envios)

        reloj.avanzar(1) // t = 4.000
        assertEquals(listOf("inicio c1"), envio.envios)

        reloj.avanzar(2_999) // t = 6.999
        assertEquals(listOf("inicio c1"), envio.envios)
        reloj.avanzar(1) // t = 7.000
        assertEquals(listOf("inicio c1", "inicio c2"), envio.envios)
        assertEquals(listOf(4_000L, 7_000L), envio.momentos)
    }

    @Test
    fun marcarSucia_solo_programa_no_manda_ni_acumula_esperas() = conCola { db, envio, reloj ->
        db.sembrarPropia("c1")

        envio.marcarSucia("c1")
        assertEquals(emptyList(), envio.envios, "marcar no envía en el acto")
        assertEquals(1, reloj.esperando)

        repeat(20) { envio.marcarSucia("c1") }
        assertEquals(emptyList(), envio.envios, "ni marcando muchas veces seguidas")
        assertEquals(1, reloj.esperando, "cada cambio reemplaza la espera anterior: no se acumulan")
    }

    @Test
    fun la_espera_se_puede_acortar_a_pedido_y_el_valor_por_omision_es_la_de_pediatria() = conCola { db, envio, reloj ->
        db.sembrarPropia("c1")
        assertEquals(HcApiSync.ESPERA_MS, HcClinicaEnvio.ESPERA_MS, "la misma espera del envío diferido que ya usa pediatría")
        assertEquals(4_000L, HcClinicaEnvio.ESPERA_MS)

        envio.marcarSucia("c1", esperaMs = 1_500)
        reloj.avanzar(1_499)
        assertEquals(emptyList(), envio.envios)
        reloj.avanzar(1)
        assertEquals(1, envio.envios.size)
    }

    @Test
    fun un_cambio_mientras_viaja_un_envio_no_lo_cancela_y_arma_su_propia_espera() = conCola { db, envio, reloj ->
        db.sembrarPropia("c1")
        val puerta = kotlinx.coroutines.CompletableDeferred<Unit>()
        envio.puertas += puerta

        envio.marcarSucia("c1")
        reloj.avanzar(4_000)
        assertEquals(listOf("inicio c1"), envio.eventos, "el envío está en camino")

        // El médico sigue escribiendo mientras viaja: eso arma otra espera, y el envío en curso no se toca.
        envio.marcarSucia("c1")
        assertEquals(1, reloj.esperando)
        puerta.complete(Unit)
        assertEquals(listOf("inicio c1", "fin c1"), envio.eventos, "el envío en curso llegó a terminar, no lo cancelaron")

        reloj.avanzar(4_000)
        assertEquals(listOf("inicio c1", "fin c1", "inicio c1", "fin c1"), envio.eventos, "y lo que quedó sale después, a los 4 s")
    }

    // ---- Abrir no hace pedidos (RF-12) ----

    @Test
    fun abrir_una_consulta_y_escribir_en_ella_no_hace_ningun_pedido_a_la_web() = conCola { db, envio, reloj ->
        val api = HcClinicaPruebas.ApiFalsa()
        val escritura = EscrituraContada()
        val backend = HcClinicaBackend(db, api, HcClinicaPruebas.pacientes(db), { 0L }, HcClinicaBackend.MAX_PAGINAS, escritura)
        val repo = HcRepository(db, mapOf(backend.especialidad to backend))

        val consulta = repo.abrirConsulta(PACIENTE, MEDICO, "clinica", "consulta", FECHA_DE_PRUEBA, "45 años")
        assertTrue(consulta.propia)
        assertTrue(repo.guardarValor(consulta.id, "nota", "texto", "texto inventado"))
        reloj.avanzar(120_000)

        assertEquals(emptyList(), escritura.llamadas, "la web no recibió nada")
        assertEquals(0, api.totalDePedidos, "ni se le pidió nada")
        assertEquals(emptyList(), envio.envios, "y la cola no envió nada: abrir no es un disparador")
        assertEquals(0, reloj.esperando, "ni dejó una espera programada")
    }
}
