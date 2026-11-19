package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.PARTE_CREAR
import com.salud360.core.data.hcgeneral.PARTE_EXAMEN
import com.salud360.core.data.hcgeneral.PARTE_SECCIONES
import com.salud360.core.data.lista
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ConsultaAbiertaDto
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T25 (RF-14, RF-56): el orden del envío de una consulta propia de la HC general. **Primero la creación**, con la clave
 * `c:<id local>`; **después el contenido** (los textos y el examen); **al final el cierre**, y el cierre sale **solo cuando todo lo
 * anterior llegó**. Una parte «En espera» o rechazada corta el envío en ese punto y frena el cierre. Contra un servidor falso con
 * memoria ([ServidorFalso]) y una base SQLite de verdad; sin red, sin tiempo real.
 */
class HcClinicaEnvioOrdenTest {

    @Test
    fun la_creacion_va_primero_despues_los_textos_y_el_examen_y_el_cierre_al_final() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1", "nota", "Nota de prueba")
        db.sembrarTexto("u1", "habitos", "Hábitos de prueba")
        db.sembrarExamen("u1", peso = "70", talla = "1.70")

        envio.empujarYa("u1").join()

        assertEquals(
            listOf("crear c:u1", "secciones 7001 habitos,nota", "examen 7001 peso,talla", "cerrar 7001 c:u1-cierre"),
            servidor.pedidos,
        )
        // Lo que viajó es lo que cargó el médico: su fecha, su edad, su tipo, la ficha de la web y el dispositivo.
        val creacion = servidor.creaciones.single()
        assertEquals(ServidorFalso.CrearRecibido("c:u1", Escena.DISPOSITIVO, 5001, "control", FECHA_DE_PRUEBA, "45 años"), creacion)
        assertEquals(mapOf("habitos" to "Hábitos de prueba", "nota" to "Nota de prueba"), servidor.textosEnviados.single())
        assertEquals(mapOf("peso" to "70", "talla" to "1.70"), servidor.camposEnviados.single())
        assertEquals(ServidorFalso.CerrarRecibido(7001, "c:u1-cierre", Escena.DISPOSITIVO, FECHA_DE_PRUEBA, "45 años"), servidor.cierres.single())
    }

    @Test
    fun cuando_todo_llego_la_consulta_queda_identificada_y_limpia() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1", "nota")
        db.sembrarExamen("u1", peso = "70")

        envio.empujarYa("u1").join()

        assertEquals("7001", db.consultaLocal("u1")?.remotoId, "queda el id de la web")
        assertTrue(db.consultaLocal("u1")!!.propia, "y sigue siendo propia")
        assertEquals(emptyList(), db.filasSucias().flatten(), "nada queda pendiente")
        assertEquals(emptyMap(), db.estadosDe("u1"), "ni con un estado")
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun solo_crear_una_consulta_abierta_vacia_hace_un_pedido_y_la_deja_limpia() = conServidor {
        db.sembrarPropia("u1")

        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1"), servidor.pedidos)
        assertEquals("7001", db.consultaLocal("u1")?.remotoId)
        assertFalse(db.consultaSucia("u1"), "una abierta y creada no tiene nada más que enviar")
    }

    @Test
    fun una_cerrada_sin_crear_manda_la_creacion_y_el_cierre_y_la_fila_sigue_sucia_hasta_el_cierre() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        // El cierre falla: la creación ya llegó, pero la fila tiene que seguir sucia (T21, decisión b).
        servidor.falla = { if (it == "cerrar") TobbException(503, "tobb_caido", "turnos caído") else null }

        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals("7001", db.consultaLocal("u1")?.remotoId, "la creación quedó anotada")
        assertTrue(db.consultaSucia("u1"), "pero falta el cierre: la fila sigue sucia")
        assertEquals(1, envio.contarPendientes().total)

        servidor.falla = null
        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "cerrar 7001 c:u1-cierre", "cerrar 7001 c:u1-cierre"), servidor.pedidos, "no se crea de nuevo: solo el cierre")
        assertFalse(db.consultaSucia("u1"))
        assertEquals("CERRADA", servidor.consultas.getValue(7001).estado)
    }

    @Test
    fun una_ya_creada_y_cerrada_manda_solo_el_cierre() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.CERRADA)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")

        envio.empujarYa("u1").join()

        assertEquals(listOf("cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertFalse(db.consultaSucia("u1"))
    }

    @Test
    fun una_ya_creada_y_abierta_sin_cambios_no_hace_ningun_pedido() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)

        envio.empujarYa("u1").join()
        envio.marcarSucia("u1")
        reloj.avanzar(10_000)

        assertEquals(emptyList(), servidor.pedidos)
    }

    @Test
    fun el_contenido_de_una_ya_creada_va_sin_volver_a_crearla() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Otra nota")

        envio.empujarYa("u1").join()

        assertEquals(listOf("secciones 7001 nota"), servidor.pedidos)
        assertEquals(emptySet(), db.textosSucios("u1"))
    }

    // ---- El cierre solo sale cuando todo llegó ----

    @Test
    fun una_parte_en_espera_corta_el_envio_y_el_cierre_no_sale() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1", "nota")
        db.sembrarExamen("u1", peso = "70")
        servidor.falla = { if (it == "secciones") TobbException(503, "tobb_caido", "turnos caído") else null }

        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "secciones 7001 nota"), servidor.pedidos, "ni el examen ni el cierre salen detrás de lo que falló")
        val estado = assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado)
        assertEquals(CausaEnEspera.SERVIDOR, estado.causa)
        assertTrue("Clínica general" in estado.motivo, "el motivo nombra la HC: ${estado.motivo}")
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"), "el texto sigue pendiente")
        assertTrue(db.examenSucio("u1"))
        assertTrue(db.consultaSucia("u1"))
        assertEquals(1, envio.contarPendientes().enEspera)

        // Pasa la causa: sale lo que faltaba, en orden, sin volver a crear, y el estado desaparece.
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
    fun una_parte_rechazada_frena_el_cierre_y_no_se_reintenta_sola_pero_no_frena_las_demas_partes() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1", "nota")
        db.sembrarExamen("u1", peso = "70")
        servidor.falla = { if (it == "secciones") TobbException(422, "datos_invalidos", "Datos que la web no acepta") else null }

        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "secciones 7001 nota"), servidor.pedidos)
        val rechazo = assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado)
        assertEquals(CausaDeRechazo.DATOS_INVALIDOS, rechazo.causa)
        assertEquals(1, envio.contarPendientes().noSePudoEnviar)

        // Otro intento: el texto rechazado no se reenvía solo, el examen sí sale, y el cierre sigue frenado.
        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "secciones 7001 nota", "examen 7001 peso"), servidor.pedidos)
        assertTrue(db.consultaSucia("u1"), "falta el cierre")
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"), "el texto rechazado se conserva en el dispositivo")
        assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado)
    }

    @Test
    fun un_error_en_el_examen_no_deshace_lo_que_ya_llego_y_frena_el_cierre() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1", "nota")
        db.sembrarExamen("u1", peso = "70")
        servidor.falla = { if (it == "examen") TobbException(401, "token", "Sesión vencida") else null }

        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "secciones 7001 nota", "examen 7001 peso"), servidor.pedidos)
        assertEquals(emptySet(), db.textosSucios("u1"), "los textos ya llegaron y quedaron limpios")
        assertEquals(CausaEnEspera.SESION_VENCIDA, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_EXAMEN).estado).causa)
        assertNull(db.estadosDe("u1")[PARTE_SECCIONES])
        assertNull(db.estadosDe("u1")[PARTE_CREAR])
        assertTrue(db.consultaSucia("u1"))
    }

    @Test
    fun si_falla_la_creacion_no_sale_nada_mas_y_queda_en_espera() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1", "nota")
        servidor.falla = { TobbException(403, "licencia_vencida", "Licencia vencida") }

        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1"), servidor.pedidos)
        assertEquals(CausaEnEspera.LICENCIA_VENCIDA, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CREAR).estado).causa)
        assertEquals("", db.consultaLocal("u1")!!.remotoId)
        assertTrue(db.consultaSucia("u1"))
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"))
    }

    @Test
    fun si_falla_el_cierre_queda_en_espera_y_no_se_pierde_lo_enviado() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        servidor.falla = { if (it == "cerrar") TobbException(0, "red", "Sin conexión") else null }

        envio.empujarYa("u1").join()

        assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CIERRE).estado).causa)
        assertNull(db.estadosDe("u1")[PARTE_CREAR])
    }

    // ---- Anular ----

    @Test
    fun una_anulada_ya_creada_manda_solo_la_baja_y_descarta_lo_pendiente() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.ANULADA)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota")

        envio.empujarYa("u1").join()

        assertEquals(listOf("baja 7001 c:u1-baja"), servidor.pedidos)
        assertTrue(servidor.consultas.getValue(7001).baja)
        assertEquals(emptyList(), db.filasSucias().flatten(), "la baja llegó y lo pendiente se descartó (RF-26)")
    }

    @Test
    fun una_anulada_que_nunca_se_creo_no_hace_ningun_pedido_y_su_contenido_queda_solo_en_el_dispositivo() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.ANULADA)
        db.sembrarTexto("u1", "nota")

        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(emptySet(), db.textosSucios("u1"), "no queda como pendiente de nada")
        assertFalse(db.consultaSucia("u1"), "tampoco la fila de la consulta: no hay nada que enviar de ella")
    }

    // ---- Nada que no sea propio, nada que no viaja (D2, RF-7, RF-60) ----

    @Test
    fun el_historico_y_lo_de_la_web_no_salen_nunca_ni_a_pedido() = conServidor {
        db.sembrarHistorica("hist-1")
        db.sembrarTexto("hist-1")
        db.sembrarDeLaWeb("hcc-c7003")
        db.sembrarTexto("hcc-c7003")
        val antes = db.filasSucias()

        envio.empujarYa("hist-1").join()
        envio.empujarYa("hcc-c7003").join()
        envio.marcarSucia("hist-1")
        reloj.avanzar(3_600_000)

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(antes, db.filasSucias())
    }

    @Test
    fun un_antecedente_desactivado_nunca_se_envia_y_se_limpia_sin_figurar_como_enviado() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        db.sembrarAntecedente("a-desactivado", "u1", activo = false)
        db.sembrarAntecedente("a-tabaquismo", "u1", clave = "tabaquismo")
        // Uno del histórico: es de otro, no se toca.
        db.sembrarHistorica("hist-1")
        db.sembrarAntecedente("a-historico", "hist-1", activo = false)

        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos, "no hay nada que viaje")
        assertFalse(db.antecedenteSucio("a-desactivado"), "RF-9: el desactivado no queda como pendiente")
        assertFalse(db.antecedenteSucio("a-tabaquismo"), "RF-60: lo que la web no guarda tampoco")
        assertTrue(db.antecedenteSucio("a-historico"), "lo del histórico no se toca")
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun lo_que_la_web_no_guarda_se_limpia_y_no_viaja_junto_con_lo_que_si() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Va")
        db.sembrarTexto("u1", "diagnostico", "No va: la web no lo guarda")

        envio.empujarYa("u1").join()

        assertEquals(mapOf("nota" to "Va"), servidor.textosEnviados.single())
        assertEquals(emptySet(), db.textosSucios("u1"))
    }

    @Test
    fun lo_que_esta_version_todavia_no_envia_queda_sucio_y_no_frena_el_cierre() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.CERRADA)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "alimentacion", "mate") // un formulario: E2

        envio.empujarYa("u1").join()

        assertEquals(listOf("cerrar 7001 c:u1-cierre"), servidor.pedidos, "el cierre sale: lo de E2 no cuenta como pendiente (RF-4)")
        assertEquals(setOf("alimentacion/texto"), db.textosSucios("u1"), "pero el texto se conserva sin enviar")
    }

    @Test
    fun sin_api_de_escritura_no_se_envia_nada_ni_se_toca_nada() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1")
        val antes = db.filasSucias()
        val sinApi = nuevoEnvio(escritura = null)

        sinApi.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(antes, db.filasSucias())
    }

    @Test
    fun sin_id_de_dispositivo_no_se_envia_nada_porque_la_web_lo_exige_en_cada_pedido() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1")
        val antes = db.filasSucias()

        nuevoEnvio(dispositivo = "").empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(antes, db.filasSucias())
    }

    @Test
    fun el_cuerpo_es_el_mismo_byte_a_byte_en_cada_intento() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1", "nota", "A")
        db.sembrarTexto("u1", "habitos", "B")
        db.sembrarTexto("u1", "somnia", "C")
        var intentos = 0
        servidor.falla = { if (it == "secciones" && intentos++ < 2) TobbException(0, "red", "Sin conexión") else null }

        repeat(3) { envio.empujarYa("u1").join() }

        assertEquals(3, servidor.textosEnviados.size)
        assertEquals(1, servidor.textosEnviados.map { it.entries.toList() }.toSet().size, "mismo contenido y mismo orden")
        assertEquals(1, servidor.cantidadDeConsultas)
    }

    @Test
    fun la_creacion_informa_las_otras_abiertas_del_paciente_sin_bloquear_nada() = conServidor {
        db.sembrarPropia("u1")
        servidor.otrasAbiertas = listOf(
            ConsultaAbiertaDto(6500, "Dra. Prueba"),
            ConsultaAbiertaDto(6400, null),
        )
        val avisos = mutableListOf<ConsultaCreadaEnLaWeb>()
        val recogida = scope.launch { envio.consultasCreadas.collect { avisos += it } }

        envio.empujarYa("u1").join()

        val aviso = avisos.single()
        assertEquals("u1", aviso.consultaId)
        assertEquals(7001L, aviso.consultaWebId)
        assertEquals(listOf(6500L to "Dra. Prueba", 6400L to null), aviso.otrasAbiertas.map { it.id to it.medicoNombre })
        assertEquals("7001", db.consultaLocal("u1")?.remotoId, "y la consulta se creó igual")
        recogida.cancel()
    }
}
