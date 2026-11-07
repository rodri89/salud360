package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.PARTE_CREAR
import com.salud360.core.data.hcgeneral.PARTE_EXAMEN
import com.salud360.core.data.hcgeneral.PARTE_SECCIONES
import com.salud360.core.data.hcgeneral.TEXTO_DESCARTADO
import com.salud360.core.data.hcgeneral.textoDelEstado
import com.salud360.core.data.lista
import com.salud360.core.data.network.TobbException
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T27 (RF-30, RF-56, D19): lo que la web **rechaza por el dato** (`422`, `404 consulta_no_encontrada`, `409`, `403
 * consulta_no_propia`) queda «No se pudo enviar: <motivo>» nombrando la HC. **No se reintenta solo** y no frena a las demás consultas.
 * El médico puede **reintentar** a mano (vuelve a mandar lo rechazado, con la misma clave y el mismo cuerpo) o **descartar**: la parte
 * queda «Descartado: no se envió a la web», el dato se conserva solo en el dispositivo, lo que ya está en la web no cambia y el cierre
 * (que esperaba) sale. Servidor falso con memoria y reloj falso; sin red.
 */
class HcClinicaEnvioRechazoTest {

    private class Caso(val nombre: String, val error: TobbException, val causa: CausaDeRechazo)

    private val casos = listOf(
        Caso("422 datos_invalidos", TobbException(422, "datos_invalidos", "Datos que la web no acepta"), CausaDeRechazo.DATOS_INVALIDOS),
        Caso("404 consulta_no_encontrada", TobbException(404, "consulta_no_encontrada", "No existe la consulta"), CausaDeRechazo.CONSULTA_DE_BAJA),
        Caso("409 consulta_cerrada", TobbException(409, "consulta_cerrada", "Cerrada o dada de baja"), CausaDeRechazo.CONSULTA_CERRADA),
        Caso("403 consulta_no_propia", TobbException(403, "consulta_no_propia", "No es tuya"), CausaDeRechazo.CONSULTA_NO_PROPIA),
    )

    private val rechazo422 = { TobbException(422, "datos_invalidos", "Datos que la web no acepta") }

    // ---- Rechazo: no se reintenta solo y no frena a las demás ----

    @Test
    fun cada_rechazo_queda_no_se_pudo_enviar_con_el_motivo_no_se_reintenta_solo_y_no_frena_a_las_demas() {
        for (caso in casos) {
            conServidor {
                db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
                db.sembrarTexto("u1")
                db.sembrarPropia("u2")
                var primera = true
                servidor.falla = { if (it == "secciones" && primera) { primera = false; caso.error } else null }

                envio.empujarYa("u1").join()
                envio.empujarYa("u2").join() // otra consulta: sale igual

                val estado = assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado, caso.nombre)
                assertEquals(caso.causa, estado.causa, caso.nombre)
                assertTrue(textoDelEstado(estado).startsWith("No se pudo enviar: Historia clínica de Clínica general"), textoDelEstado(estado))
                assertEquals("7002", db.consultaLocal("u2")!!.remotoId, "no frena a las demás: ${caso.nombre}")

                // No se reintenta solo, ni en una hora, y no queda nada programado.
                val pedidos = servidor.pedidos.size
                reloj.avanzar(3_600_000)
                assertEquals(pedidos, servidor.pedidos.size, caso.nombre)
                assertEquals(0, reloj.esperando, caso.nombre)
                // El dato sigue en el dispositivo, el cierre espera, y se cuenta como «No se pudo enviar».
                assertEquals(setOf("nota/texto"), db.textosSucios("u1"), caso.nombre)
                assertTrue(db.consultaSucia("u1"), caso.nombre)
                assertEquals(1, envio.contarPendientes().noSePudoEnviar, caso.nombre)
                assertEquals(0, envio.contarPendientes().enEspera, caso.nombre)
            }
        }
    }

    // ---- Reintentar a mano ----

    @Test
    fun reintentar_vuelve_a_mandar_lo_rechazado_con_el_mismo_cuerpo_y_el_cierre_sale() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1", "nota", "Texto de prueba")
        db.sembrarExamen("u1", peso = "70")
        var rechazar = true
        servidor.falla = { if (it == "secciones" && rechazar) rechazo422() else null }
        envio.empujarYa("u1").join()
        envio.empujarYa("u1").join() // el examen sale; lo rechazado se queda y el cierre espera
        assertEquals(listOf("crear c:u1", "secciones 7001 nota", "examen 7001 peso"), servidor.pedidos)

        rechazar = false // el médico corrigió el problema (o la web ya lo acepta)
        envio.reintentar("u1").join()

        assertEquals(
            listOf("crear c:u1", "secciones 7001 nota", "examen 7001 peso", "secciones 7001 nota", "cerrar 7001 c:u1-cierre"),
            servidor.pedidos,
            "vuelve a mandar solo lo rechazado y después el cierre",
        )
        assertEquals(servidor.textosEnviados[0], servidor.textosEnviados[1], "el mismo cuerpo")
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(emptyList(), db.filasSucias().flatten())
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun si_sigue_rechazado_reintentar_lo_deja_igual_y_sin_reintento_solo() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1")
        servidor.falla = { rechazo422() }
        envio.empujarYa("u1").join()

        envio.reintentar("u1").join()
        envio.reintentar("u1").join()

        assertEquals(listOf("secciones 7001 nota", "secciones 7001 nota", "secciones 7001 nota"), servidor.pedidos, "cada «Reintentar» es un pedido")
        assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado)
        assertEquals(0, reloj.esperando, "y no arma reintentos solos")
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"))
    }

    @Test
    fun reintentar_tambien_sirve_si_lo_rechazado_fue_la_creacion() = conServidor {
        db.sembrarPropia("u1")
        var rechazar = true
        servidor.falla = { if (it == "crear" && rechazar) rechazo422() else null }
        envio.empujarYa("u1").join()
        assertEquals(CausaDeRechazo.DATOS_INVALIDOS, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_CREAR).estado).causa)
        envio.empujarYa("u1").join()
        assertEquals(1, servidor.pedidos.size, "sin «Reintentar», una creación rechazada no sale")

        rechazar = false
        envio.reintentar("u1").join()

        assertEquals("7001", db.consultaLocal("u1")!!.remotoId)
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun reintentar_reinicia_la_cuenta_de_los_reintentos_solos() = conServidor {
        db.sembrarPropia("u1")
        val instantes = mutableListOf<Long>()
        servidor.falla = { instantes += reloj.ahora; TobbException(0, "red", "Sin conexión") }
        envio.empujarYa("u1").join() // 0 s
        reloj.avanzar(15_000) // 15 s
        reloj.avanzar(60_000) // 75 s: la cuenta ya va por los 300 s

        envio.reintentar("u1").join() // 75 s, a mano
        reloj.avanzar(15_000) // vuelve a empezar: 15 s

        assertEquals(listOf(0L, 15_000L, 75_000L, 75_000L, 90_000L), instantes)
    }

    // ---- Descartar ----

    @Test
    fun descartar_conserva_el_dato_no_cambia_lo_que_ya_esta_en_la_web_y_deja_descartado() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Uno")
        envio.empujarYa("u1").join() // la web ya tiene «Uno»
        db.escribirTexto("u1", "nota", "Dos", actualizadaEn = 500)
        servidor.falla = { rechazo422() }
        envio.empujarYa("u1").join() // «Dos» se rechaza
        servidor.falla = null
        val enLaWeb = servidor.consultas.getValue(7001).secciones.toMap()
        val pedidosAntes = servidor.pedidos.size
        val loEnviadoAntes = db.loEnviadoDe("u1")

        envio.descartar("u1", PARTE_SECCIONES).join()

        val estado = db.estadosDe("u1").getValue(PARTE_SECCIONES)
        assertEquals(EstadoEnvio.Descartado, estado.estado)
        assertEquals("Descartado: no se envió a la web", textoDelEstado(estado.estado))
        assertEquals(TEXTO_DESCARTADO, textoDelEstado(estado.estado))
        // El dato no se borra: sigue en el dispositivo, solo que ya no está pendiente.
        assertEquals(
            listOf("nota/Dos"),
            db.historiaClinicaQueries.valoresDeConsulta("u1").lista { "${it.seccion}/${it.valor}" },
            "el texto «Dos» sigue guardado en el dispositivo",
        )
        assertEquals(emptySet(), db.textosSucios("u1"))
        // Y la web no se tocó: ni un pedido, ni un cambio, ni lo que se anota como «lo que tiene la web».
        assertEquals(pedidosAntes, servidor.pedidos.size)
        assertEquals(enLaWeb, servidor.consultas.getValue(7001).secciones)
        assertEquals("Uno", servidor.consultas.getValue(7001).secciones.getValue("nota"))
        assertEquals(loEnviadoAntes, db.loEnviadoDe("u1"))
        assertEquals(0, envio.contarPendientes().total, "descartado no es un pendiente")
    }

    @Test
    fun descartar_libera_el_cierre_que_esperaba() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        db.sembrarExamen("u1", peso = "70")
        servidor.falla = { if (it == "secciones") rechazo422() else null }
        envio.empujarYa("u1").join()
        envio.empujarYa("u1").join()
        assertEquals(listOf("crear c:u1", "secciones 7001 nota", "examen 7001 peso"), servidor.pedidos, "el cierre espera a lo rechazado")

        envio.descartar("u1", PARTE_SECCIONES).join()

        assertEquals("cerrar 7001 c:u1-cierre", servidor.pedidos.last(), "y ahora sale")
        assertEquals("CERRADA", servidor.consultas.getValue(7001).estado)
        assertEquals(emptyMap(), servidor.consultas.getValue(7001).secciones, "sin el texto descartado")
        assertEquals(0, servidor.textosEnviados.size - 1, "solo se había intentado una vez")
        assertEquals(false, db.consultaSucia("u1"))
        assertEquals(EstadoEnvio.Descartado, db.estadosDe("u1").getValue(PARTE_SECCIONES).estado)
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun descartar_sin_decir_la_parte_descarta_todo_lo_rechazado_y_nada_mas() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        db.sembrarExamen("u1", peso = "70")
        servidor.falla = { if (it == "secciones" || it == "examen") rechazo422() else null }
        envio.empujarYa("u1").join()
        envio.empujarYa("u1").join()

        envio.descartar("u1").join()

        assertEquals(EstadoEnvio.Descartado, db.estadosDe("u1").getValue(PARTE_SECCIONES).estado)
        assertEquals(EstadoEnvio.Descartado, db.estadosDe("u1").getValue(PARTE_EXAMEN).estado)
        assertEquals(emptySet(), db.textosSucios("u1"))
        assertEquals(false, db.examenSucio("u1"))
        assertEquals("cerrar 7001 c:u1-cierre", servidor.pedidos.last())
    }

    @Test
    fun descartar_solo_descarta_lo_rechazado_no_lo_que_falta_enviar_ni_la_creacion() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1")
        servidor.falla = { if (it == "crear") rechazo422() else null }
        envio.empujarYa("u1").join()
        val antes = db.filasSucias()

        envio.descartar("u1").join() // la creación rechazada no se descarta: sin ella no hay consulta
        envio.descartar("u1", PARTE_CREAR).join()
        envio.descartar("u1", PARTE_SECCIONES).join() // no hay nada rechazado ahí
        envio.descartar("u1", "no-existe").join()

        assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_CREAR).estado)
        assertNull(db.estadosDe("u1")[PARTE_SECCIONES])
        assertEquals(antes, db.filasSucias(), "no se perdió ni se limpió nada")
    }

    @Test
    fun un_cierre_rechazado_tambien_se_puede_descartar() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.CERRADA)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        servidor.falla = { TobbException(409, "consulta_cerrada", "La consulta está dada de baja.") }
        envio.empujarYa("u1").join()
        assertEquals(CausaDeRechazo.CONSULTA_CERRADA, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_CIERRE).estado).causa)

        envio.descartar("u1", PARTE_CIERRE).join()

        assertEquals(EstadoEnvio.Descartado, db.estadosDe("u1").getValue(PARTE_CIERRE).estado)
        assertEquals(false, db.consultaSucia("u1"))
        assertEquals(1, servidor.pedidos.size, "y no se vuelve a mandar")
        assertEquals(EstadoConsulta.CERRADA, db.consultaLocal("u1")!!.estado, "la consulta local sigue como la dejó el médico")
    }

    @Test
    fun lo_que_se_edita_despues_de_descartar_viaja_y_borra_el_descartado() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Uno")
        servidor.falla = { rechazo422() }
        envio.empujarYa("u1").join()
        servidor.falla = null
        envio.descartar("u1", PARTE_SECCIONES).join()
        assertEquals(1, servidor.pedidos.size)

        db.escribirTexto("u1", "nota", "Otro texto", actualizadaEn = 700) // el médico lo corrige
        envio.empujarYa("u1").join()

        assertEquals("Otro texto", servidor.consultas.getValue(7001).secciones.getValue("nota"))
        assertEquals(emptyMap(), db.estadosDe("u1"), "ya no figura como descartado")
        assertEquals(emptySet(), db.textosSucios("u1"))
    }

    // ---- De quién es ----

    @Test
    fun reintentar_y_descartar_son_solo_del_medico_de_la_sesion() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1")
        servidor.falla = { rechazo422() }
        envio.empujarYa("u1").join()
        servidor.falla = null
        val antes = db.filasSucias()
        val estado = db.estadosDe("u1").mapValues { it.value.estado }

        medicoDeLaSesion = "m-otro"
        envio.reintentar("u1").join()
        envio.descartar("u1").join()

        assertEquals(1, servidor.pedidos.size)
        assertEquals(antes, db.filasSucias())
        assertEquals(estado, db.estadosDe("u1").mapValues { it.value.estado })
    }

    @Test
    fun el_historico_y_lo_de_la_web_no_se_reintentan_ni_se_descartan() = conServidor {
        db.sembrarHistorica("hist-1")
        db.sembrarTexto("hist-1")
        val antes = db.filasSucias()

        envio.reintentar("hist-1").join()
        envio.descartar("hist-1").join()

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(antes, db.filasSucias())
        assertEquals(LoEnviado(), db.loEnviadoDe("hist-1"))
    }
}
