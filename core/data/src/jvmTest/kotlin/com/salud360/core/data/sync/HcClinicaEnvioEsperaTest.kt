package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.PARTE_CREAR
import com.salud360.core.data.hcgeneral.PARTE_EXAMEN
import com.salud360.core.data.hcgeneral.PARTE_SECCIONES
import com.salud360.core.data.hcgeneral.textoDelEstado
import com.salud360.core.data.network.TobbException
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T27 (RF-6, RF-29): cada causa ajena al dato deja **todo lo que faltaba de la consulta** «En espera: <motivo>» nombrando la
 * HC, sin perder ni modificar nada, y sale **sola** cuando la causa se va. Sesión vencida, HC no habilitada, médico no vinculado,
 * licencia vencida, paciente fuera de la cartera o de baja, sin permiso, servidor caído, «demasiados pedidos», respuesta que no se
 * entiende y sin red. Las pasajeras reintentan a los 15 s; las que dependen de una cuenta o de alguien, cada 5 minutos. Servidor falso
 * con memoria y reloj falso; sin red.
 */
class HcClinicaEnvioEsperaTest {

    private class Caso(val nombre: String, val error: TobbException, val causa: CausaEnEspera, val pasajera: Boolean)

    private val casos = listOf(
        Caso("401 token", TobbException(401, "token", "Sesión vencida"), CausaEnEspera.SESION_VENCIDA, false),
        Caso("401 sin_token", TobbException(401, "sin_token", "Falta el token"), CausaEnEspera.SESION_VENCIDA, false),
        Caso("403 hc_no_habilitada", TobbException(403, "hc_no_habilitada", "No habilitada"), CausaEnEspera.HC_NO_HABILITADA, false),
        Caso("403 medico_no_vinculado", TobbException(403, "medico_no_vinculado", "Sin vincular"), CausaEnEspera.MEDICO_NO_VINCULADO, false),
        Caso("403 licencia_vencida", TobbException(403, "licencia_vencida", "Licencia vencida"), CausaEnEspera.LICENCIA_VENCIDA, false),
        Caso("403 fuera_de_cartera", TobbException(403, "fuera_de_cartera", "Fuera de cartera"), CausaEnEspera.FUERA_DE_CARTERA, false),
        Caso("403 sin_permiso", TobbException(403, "sin_permiso", "Solo los médicos"), CausaEnEspera.SIN_PERMISO, false),
        Caso("404 paciente_no_encontrado", TobbException(404, "paciente_no_encontrado", "Paciente no encontrado."), CausaEnEspera.PACIENTE_DE_BAJA, false),
        Caso("503 tobb_caido", TobbException(503, "tobb_caido", "turnos caído"), CausaEnEspera.SERVIDOR, true),
        Caso("500 escritura", TobbException(500, "escritura", "No se pudo guardar"), CausaEnEspera.SERVIDOR, true),
        Caso("429", TobbException(429, null, "Too Many Attempts."), CausaEnEspera.DEMASIADOS_PEDIDOS, true),
        Caso("sin red", TobbException(0, "red", "Sin conexión"), CausaEnEspera.SIN_CONEXION, true),
        Caso("respuesta ilegible", TobbException(0, "respuesta", "JSON cortado"), CausaEnEspera.SERVIDOR, true),
    )

    @Test
    fun cada_causa_ajena_deja_en_espera_con_el_nombre_de_la_hc_sin_perder_ni_modificar_nada_y_sale_sola() {
        for (caso in casos) {
            conServidor {
                db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
                db.sembrarTexto("u1")
                db.sembrarExamen("u1", peso = "70")
                servidor.falla = { if (it == "secciones") caso.error else null }

                envio.empujarYa("u1").join()

                // «En espera», no un rechazo, y nombrando la HC. Lo que faltaba de la consulta queda igual (RF-6).
                assertEquals(listOf("crear c:u1", "secciones 7001 nota"), servidor.pedidos, caso.nombre)
                for (parte in listOf(PARTE_SECCIONES, PARTE_EXAMEN, PARTE_CIERRE)) {
                    val espera = assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(parte).estado, "${caso.nombre} / $parte")
                    assertEquals(caso.causa, espera.causa, "${caso.nombre} / $parte")
                    assertTrue(espera.motivo.contains("Clínica general"), "${caso.nombre}: ${espera.motivo}")
                    assertTrue(textoDelEstado(espera).startsWith("En espera: Historia clínica de Clínica general"), textoDelEstado(espera))
                }
                assertNull(db.estadosDe("u1")[PARTE_CREAR], "la creación sí llegó: ${caso.nombre}")
                // Sin perder ni modificar nada.
                assertEquals(setOf("nota/texto"), db.textosSucios("u1"), caso.nombre)
                assertTrue(db.examenSucio("u1"), caso.nombre)
                assertTrue(db.consultaSucia("u1"), caso.nombre)
                assertEquals(LoEnviado(), db.loEnviadoDe("u1"), caso.nombre)
                val resumen = envio.contarPendientes()
                assertEquals(1, resumen.enEspera, caso.nombre)
                assertEquals(0, resumen.noSePudoEnviar, "una causa ajena no es un rechazo: ${caso.nombre}")

                // Se va la causa: sale sola. Las pasajeras a los 15 s; las de cuenta o de alguien, a los 5 minutos.
                servidor.falla = null
                val primera = if (caso.pasajera) 15_000L else 300_000L
                reloj.avanzar(primera - 1)
                assertEquals(2, servidor.pedidos.size, "todavía no es la hora: ${caso.nombre}")
                reloj.avanzar(1)

                assertEquals(
                    listOf("crear c:u1", "secciones 7001 nota", "secciones 7001 nota", "examen 7001 peso", "cerrar 7001 c:u1-cierre"),
                    servidor.pedidos,
                    caso.nombre,
                )
                assertEquals(emptyMap(), db.estadosDe("u1"), "ya no está en espera: ${caso.nombre}")
                assertEquals(emptyList(), db.filasSucias().flatten(), caso.nombre)
                assertEquals(0, reloj.esperando, caso.nombre)
            }
        }
    }

    @Test
    fun una_causa_que_sigue_igual_sigue_en_espera_y_no_cambia_nada_en_cada_vuelta() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        servidor.falla = { if (it == "secciones") TobbException(403, "licencia_vencida", "Licencia vencida") else null }
        envio.empujarYa("u1").join()
        val estados = db.estadosDe("u1").mapValues { it.value.estado }
        val sucias = db.filasSucias()

        reloj.avanzar(3_600_000)

        assertEquals(estados, db.estadosDe("u1").mapValues { it.value.estado }, "el mismo motivo")
        assertEquals(sucias, db.filasSucias(), "ni un dato tocado")
        // Una vuelta cada 5 minutos, nunca más: 12 en la hora.
        assertEquals(2 + 12, servidor.pedidos.size)
    }

    @Test
    fun cambia_la_causa_y_el_motivo_cambia_con_ella() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1")
        servidor.falla = { TobbException(0, "red", "Sin conexión") }
        envio.empujarYa("u1").join()
        assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado).causa)

        servidor.falla = { TobbException(401, "token", "Sesión vencida") }
        reloj.avanzar(15_000)

        assertEquals(CausaEnEspera.SESION_VENCIDA, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado).causa)
        // Y la cuenta se acomoda a la causa nueva: una de cuenta se mira cada 5 minutos.
        val antes = servidor.pedidos.size
        reloj.avanzar(299_999)
        assertEquals(antes, servidor.pedidos.size)
        reloj.avanzar(1)
        assertEquals(antes + 1, servidor.pedidos.size)
    }

    @Test
    fun una_causa_ajena_nunca_borra_ni_descarta_y_el_medico_puede_pedirlo_a_mano() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1")
        servidor.falla = { TobbException(403, "hc_no_habilitada", "No habilitada") }
        envio.empujarYa("u1").join()

        // «Descartar» es solo para lo que la web rechazó: una espera no se descarta (se perdería lo que falta mandar).
        envio.descartar("u1").join()
        assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado)
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"))

        // A mano, sin esperar los 5 minutos.
        servidor.falla = null
        envio.reintentar("u1").join()

        assertEquals(emptySet(), db.textosSucios("u1"))
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun la_espera_de_una_consulta_no_frena_a_las_demas() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        db.sembrarPropia("u2", remotoId = "7002", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        servidor.consultas[7002] = ServidorFalso.ConsultaWeb(7002, "c:u2", 5001, "control")
        db.sembrarTexto("u1")
        db.sembrarTexto("u2")
        var primera = true
        servidor.falla = { if (it == "secciones" && primera) { primera = false; TobbException(503, "tobb_caido", "turnos caído") } else null }

        envio.empujarYa("u1").join()
        envio.empujarYa("u2").join()

        assertEquals(1, envio.contarPendientes().enEspera)
        assertEquals(emptySet(), db.textosSucios("u2"), "la otra consulta salió igual")
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"))
    }
}