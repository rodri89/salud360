package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.PARTE_CREAR
import com.salud360.core.data.hcgeneral.PARTE_EXAMEN
import com.salud360.core.data.hcgeneral.PARTE_SECCIONES
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T26 (RF-6, RF-50, RF-51): la cola envía lo pendiente de un médico **solo con la sesión de ese médico**. Con otra sesión
 * (otro médico en el mismo dispositivo) o sin sesión, lo del primero ni se envía ni se programa ni se toca: sigue como estaba hasta que
 * vuelva su dueño. Es importante de verdad: enviar con el token de otro médico crearía la consulta a nombre de ese otro (RF-14). El médico
 * de la sesión se mira al programar, al empezar a enviar y antes de **cada** paso. Y una sesión que vence a mitad de un envío (`401`) deja
 * lo que faltaba de esa consulta «En espera: sesión vencida», sin perder nada. Servidor falso con memoria; sin red.
 */
class SesionYEnvioTest {
    private val otro = "m-99000077"

    // ---- Solo las consultas del médico de la sesión ----

    @Test
    fun la_cola_toma_solo_las_consultas_del_medico_de_la_sesion() = conServidor {
        db.sembrarPropia("mia", medico = MEDICO)
        db.sembrarPropia("ajena", medico = otro)
        db.sembrarTexto("ajena")
        val ajenaAntes = db.filasSucias().map { fila -> fila.filter { it.toString().contains("ajena") } }

        envio.empujarYa("mia").join()
        envio.empujarYa("ajena").join()
        envio.marcarSucia("ajena")
        reloj.avanzar(3_600_000)

        assertEquals(listOf("crear c:mia"), servidor.pedidos, "solo la del médico de la sesión")
        assertEquals(ajenaAntes, db.filasSucias().map { fila -> fila.filter { it.toString().contains("ajena") } }, "lo del otro, intacto")
        assertEquals(emptyMap(), db.estadosDe("ajena"), "sin estado nuevo: no se modifica nada de lo del otro")
    }

    @Test
    fun otro_medico_en_el_mismo_dispositivo_no_envia_ni_modifica_lo_del_primero_y_cuando_vuelve_su_dueno_sale() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        db.sembrarExamen("u1", peso = "70")
        val antes = db.filasSucias()

        medicoDeLaSesion = otro // inicia sesión otro médico en el mismo dispositivo
        envio.empujarYa("u1").join()
        envio.marcarSucia("u1")
        reloj.avanzar(3_600_000)

        assertEquals(emptyList(), servidor.pedidos, "nada sale con la sesión de otro")
        assertEquals(antes, db.filasSucias(), "ni se modifica nada del primero")
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(0, reloj.esperando, "ni siquiera queda una espera programada")

        medicoDeLaSesion = MEDICO // vuelve su dueño
        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "secciones 7001 nota", "examen 7001 peso", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(emptyList(), db.filasSucias().flatten())
    }

    @Test
    fun sin_sesion_no_se_envia_nada() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1")
        val antes = db.filasSucias()
        medicoDeLaSesion = null

        envio.empujarYa("u1").join()
        envio.marcarSucia("u1")
        reloj.avanzar(3_600_000)

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(antes, db.filasSucias())
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun la_cola_sin_saber_de_la_sesion_no_envia_nada() = conServidor {
        db.sembrarPropia("u1")

        // La cola armada sin sesión conocida (el valor por omisión) falla cerrada: ante la duda, no manda con el token de nadie.
        HcClinicaEnvio(db, scope, reloj::esperar, escritura = servidor, pacientes = pacientes, dispositivo = "disp").empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos)
    }

    @Test
    fun si_la_sesion_cambia_mientras_espera_los_4_s_no_sale() = conServidor {
        db.sembrarPropia("u1")
        envio.marcarSucia("u1")
        assertEquals(1, reloj.esperando)

        medicoDeLaSesion = otro
        reloj.avanzar(4_000)

        assertEquals(emptyList(), servidor.pedidos, "la espera venció con la sesión de otro: no se envía con el token equivocado")
    }

    @Test
    fun si_la_sesion_cambia_a_mitad_de_un_envio_no_sale_el_paso_siguiente() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        // Entra otro médico mientras viaja la creación.
        servidor.alRecibir = { if (it == "crear") medicoDeLaSesion = otro }

        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1"), servidor.pedidos, "lo que ya viajaba terminó, pero nada más sale con la sesión del otro")
        assertEquals("7001", db.consultaLocal("u1")!!.remotoId, "y la creación quedó anotada")
        assertEquals(emptyMap(), db.estadosDe("u1"), "sin marcar nada")
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"))

        servidor.alRecibir = null
        medicoDeLaSesion = MEDICO
        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "secciones 7001 nota", "cerrar 7001 c:u1-cierre"), servidor.pedidos, "al volver su dueño, sigue donde quedó")
    }

    // ---- 401 a mitad de un envío ----

    @Test
    fun un_401_a_mitad_de_un_envio_deja_el_resto_en_espera_por_sesion_vencida_sin_perder_nada() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        db.sembrarExamen("u1", peso = "70")
        servidor.falla = { if (it == "secciones") TobbException(401, "token", "Sesión vencida") else null }

        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "secciones 7001 nota"), servidor.pedidos, "después del 401 no sale nada más")
        val estados = db.estadosDe("u1")
        for (parte in listOf(PARTE_SECCIONES, PARTE_EXAMEN, PARTE_CIERRE)) {
            val espera = assertIs<EstadoEnvio.EnEspera>(estados.getValue(parte).estado, parte)
            assertEquals(CausaEnEspera.SESION_VENCIDA, espera.causa, parte)
            assertTrue(espera.motivo.contains("Clínica general") && espera.motivo.contains("sesión vencida"), espera.motivo)
        }
        assertNull(estados[PARTE_CREAR], "la creación sí llegó: no está en espera")
        // No se pierde ni se modifica nada: el texto, el examen y la fila de la consulta siguen pendientes.
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"))
        assertTrue(db.examenSucio("u1"))
        assertTrue(db.consultaSucia("u1"))
        assertEquals("7001", db.consultaLocal("u1")!!.remotoId)
        assertEquals(1, envio.contarPendientes().enEspera)

        // Vuelve a iniciar sesión: sale lo que faltaba, y los estados se limpian solos.
        servidor.falla = null
        envio.empujarYa("u1").join()

        assertEquals(
            listOf("crear c:u1", "secciones 7001 nota", "secciones 7001 nota", "examen 7001 peso", "cerrar 7001 c:u1-cierre"),
            servidor.pedidos,
        )
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun un_401_al_crear_deja_todo_en_espera_por_sesion_vencida() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        servidor.falla = { TobbException(401, "sin_token", "Sin sesión") }

        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1"), servidor.pedidos)
        for (parte in listOf(PARTE_CREAR, PARTE_SECCIONES, PARTE_CIERRE)) {
            assertEquals(CausaEnEspera.SESION_VENCIDA, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(parte).estado, parte).causa, parte)
        }
    }

    @Test
    fun cualquier_causa_ajena_deja_en_espera_todo_lo_que_faltaba_no_solo_la_sesion_vencida() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        db.sembrarExamen("u1", peso = "70")
        servidor.falla = { if (it == "secciones") TobbException(503, "tobb_caido", "turnos caído") else null }

        envio.empujarYa("u1").join()

        // T27 define lo que T26 dejó abierto: la regla de RF-6 vale para toda causa ajena al dato, no solo para el 401.
        assertEquals(setOf(PARTE_SECCIONES, PARTE_EXAMEN, PARTE_CIERRE), db.estadosDe("u1").keys)
        assertEquals(CausaEnEspera.SERVIDOR, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CIERRE).estado).causa)
    }
    @Test
    fun un_401_al_resolver_el_vinculo_tambien_deja_en_espera_por_sesion_vencida() {
        val resolver = ResolverFalso().apply { respuesta = { _, _ -> throw TobbException(401, "token", "Sesión vencida") } }
        conServidor(vinculado = false, pacientesApi = resolver) {
            db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
            db.sembrarTexto("u1")

            envio.empujarYa("u1").join()

            assertEquals(emptyList(), servidor.pedidos)
            for (parte in listOf(PARTE_CREAR, PARTE_SECCIONES, PARTE_CIERRE)) {
                assertEquals(CausaEnEspera.SESION_VENCIDA, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(parte).estado, parte).causa, parte)
            }
        }
    }

    // ---- Lo que se cuenta (RF-51) ----

    @Test
    fun el_contador_cuenta_solo_lo_pendiente_del_medico_de_la_sesion() = conServidor {
        db.sembrarPropia("a1")
        db.sembrarPropia("a2")
        db.sembrarPropia("b1", medico = otro)

        assertEquals(2, envio.contarPendientes().total, "el médico de la sesión ve las suyas")
        medicoDeLaSesion = otro
        assertEquals(listOf("b1"), envio.contarPendientes().consultas.map { it.consultaId }, "el otro, solo las suyas: no ve lo del primero")
        medicoDeLaSesion = null
        assertEquals(0, envio.contarPendientes().total, "sin sesión no hay nada que mostrar")
    }

    @Test
    fun cerrar_sesion_no_borra_ni_envia_nada() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1")
        val antes = db.filasSucias()

        medicoDeLaSesion = null // cierra sesión con datos sin enviar
        envio.marcarSucia("u1")
        reloj.avanzar(3_600_000)

        assertEquals(antes, db.filasSucias(), "nada se borra")
        assertEquals(emptyList(), servidor.pedidos, "y nada se envía")
        medicoDeLaSesion = MEDICO
        assertEquals(1, envio.contarPendientes().total, "cuando vuelve su dueño sigue pendiente")
    }
}
