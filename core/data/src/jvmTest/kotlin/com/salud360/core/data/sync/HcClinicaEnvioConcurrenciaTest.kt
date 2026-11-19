package com.salud360.core.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 005, T24 (RF-54): una consulta, un envío a la vez. Si se dispara otro mientras uno está en curso, **espera** a que termine y
 * recién entonces manda lo que quedó; consultas distintas sí van en paralelo. El envío de prueba se frena con una puerta que abre el
 * test, así se ve quién está adentro en cada momento. Reloj falso; sin red.
 */
class HcClinicaEnvioConcurrenciaTest {

    @Test
    fun dos_disparos_de_la_misma_consulta_son_un_envio_a_la_vez_el_segundo_espera_y_manda_lo_que_quedo() = conCola { db, envio, _ ->
        db.sembrarPropia("c1")
        val primera = CompletableDeferred<Unit>()
        val segunda = CompletableDeferred<Unit>()
        envio.puertas += listOf(primera, segunda)

        val uno = envio.empujarYa("c1")
        val dos = envio.empujarYa("c1")
        assertEquals(listOf("inicio c1"), envio.eventos, "el segundo disparo espera: todavía no empezó")

        primera.complete(Unit)
        assertEquals(listOf("inicio c1", "fin c1", "inicio c1"), envio.eventos, "recién ahora empieza el segundo, con lo que quedó")
        assertTrue(uno.isCompleted)
        assertTrue(dos.isActive, "el segundo sigue en curso")

        segunda.complete(Unit)
        dos.join()
        assertEquals(listOf("inicio c1", "fin c1", "inicio c1", "fin c1"), envio.eventos)
        assertEquals(1, envio.maximoALaVez, "nunca dos a la vez")
    }

    @Test
    fun el_disparo_por_la_espera_de_4_s_tambien_espera_a_un_envio_en_curso() = conCola { db, envio, reloj ->
        db.sembrarPropia("c1")
        val primera = CompletableDeferred<Unit>()
        val segunda = CompletableDeferred<Unit>()
        envio.puertas += listOf(primera, segunda)

        envio.marcarSucia("c1")
        reloj.avanzar(4_000)
        assertEquals(listOf("inicio c1"), envio.eventos)

        envio.marcarSucia("c1") // el médico siguió escribiendo mientras viajaba el primero
        reloj.avanzar(4_000)
        assertEquals(listOf("inicio c1"), envio.eventos, "venció la espera pero hay un envío en curso: hace cola")

        primera.complete(Unit)
        assertEquals(listOf("inicio c1", "fin c1", "inicio c1"), envio.eventos)
        segunda.complete(Unit)
        assertEquals(listOf("inicio c1", "fin c1", "inicio c1", "fin c1"), envio.eventos)
        assertEquals(1, envio.maximoALaVez)
    }

    @Test
    fun muchos_disparos_de_la_misma_consulta_pasan_de_a_uno_y_en_orden() = conCola { db, envio, _ ->
        db.sembrarPropia("c1")
        val puertas = List(5) { CompletableDeferred<Unit>() }
        envio.puertas += puertas

        val jobs = List(5) { envio.empujarYa("c1") }
        puertas.forEachIndexed { i, puerta ->
            assertEquals(i + 1, envio.envios.size, "hasta que no termina uno, no empieza el siguiente")
            puerta.complete(Unit)
        }
        jobs.forEach { it.join() }

        assertEquals(10, envio.eventos.size)
        assertEquals(List(5) { listOf("inicio c1", "fin c1") }.flatten(), envio.eventos, "inicio y fin se alternan: nunca solapados")
        assertEquals(1, envio.maximoALaVez)
    }

    @Test
    fun consultas_distintas_si_van_en_paralelo() = conCola { db, envio, _ ->
        db.sembrarPropia("c1")
        db.sembrarPropia("c2")
        val p1 = CompletableDeferred<Unit>()
        val p2 = CompletableDeferred<Unit>()
        envio.puertas += listOf(p1, p2)

        val uno = envio.empujarYa("c1")
        val dos = envio.empujarYa("c2")
        assertEquals(listOf("inicio c1", "inicio c2"), envio.eventos, "las dos están adentro a la vez: una no espera a la otra")
        assertEquals(2, envio.maximoALaVez)

        p2.complete(Unit)
        p1.complete(Unit)
        uno.join()
        dos.join()
        assertEquals(setOf("fin c1", "fin c2"), envio.eventos.filter { it.startsWith("fin") }.toSet())
    }

    @Test
    fun un_envio_que_falla_suelta_el_turno_y_no_rompe_la_cola() = conCola { db, envio, _ ->
        db.sembrarPropia("c1")
        envio.falla = IllegalStateException("sin conexión (simulada)")

        envio.empujarYa("c1").join()
        assertEquals(listOf("inicio c1"), envio.eventos, "falló, y no quedó trabado")

        envio.falla = null
        envio.empujarYa("c1").join()
        assertEquals(listOf("inicio c1", "inicio c1", "fin c1"), envio.eventos, "la consulta puede volver a enviarse")
    }

    @Test
    fun empujarYa_se_salta_la_espera_y_cancela_la_que_estaba_programada() = conCola { db, envio, reloj ->
        db.sembrarPropia("c1")

        envio.marcarSucia("c1")
        assertEquals(1, reloj.esperando)

        envio.empujarYa("c1").join()
        assertEquals(listOf("inicio c1", "fin c1"), envio.eventos, "sale en el acto, sin esperar los 4 s")
        assertEquals(0, reloj.esperando, "y la espera programada se canceló")
        reloj.avanzar(60_000)
        assertEquals(1, envio.envios.size, "no sale una segunda vez por la espera vieja")
    }
}
