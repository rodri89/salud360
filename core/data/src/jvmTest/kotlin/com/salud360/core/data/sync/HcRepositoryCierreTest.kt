package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.PARTE_SECCIONES
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura
import com.salud360.core.data.hcgeneral.seccionesDe
import com.salud360.core.data.lista
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.uno
import com.salud360.core.model.hc.EstadoConsulta
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T28 (RF-22 a RF-26, RF-55, D13, D14, D19): cerrar, reabrir y anular una consulta propia de la HC general **con estado de
 * envío**. Cerrar deja el cierre pendiente («Falta enviar») y dispara el envío ordenado; reabrir solo mientras el cierre no se envió y no
 * está viajando, sin tocar la web; anular una que nunca se creó no manda nada, una creada y abierta manda la baja y descarta lo
 * pendiente, y una con el cierre ya enviado no se anula. Nada pierde la marca de propia ni tira un dato del dispositivo. Servidor falso con
 * memoria y reloj falso; sin red.
 */
class HcRepositoryCierreTest {
    private val fecha = LocalDate.parse("2026-10-30")
    private val sinConexion = { TobbException(0, "red", "Sin conexión") }

    private suspend fun Escena.sembrarCreada(id: String = "u1", estado: EstadoConsulta = EstadoConsulta.ABIERTA, dirty: Boolean = false) {
        db.sembrarPropia(id, remotoId = "7001", estado = estado, dirty = dirty)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:$id", 5001, "control")
    }

    private suspend fun Escena.marca(id: String = "u1") = envio.estadoDeLaConsulta(id)?.marca

    // ---- Cerrar ----

    @Test
    fun cerrar_deja_el_cierre_pendiente_como_falta_enviar_y_al_enviarse_la_marca_desaparece() = conServidor {
        sembrarCreada()
        db.sembrarTexto("u1", "nota", "Una nota")
        val sinCola = repositorio(conCola = false)

        assertTrue(sinCola.cerrarConsulta("u1"))

        assertEquals(EstadoConsulta.CERRADA, db.consultaLocal("u1")!!.estado)
        assertTrue(db.consultaSucia("u1"), "el cierre está pendiente")
        assertEquals("Falta enviar", marca())
        assertEquals(emptyList(), servidor.pedidos, "sin cola no sale nada")
        assertTrue(envio.estadoDeLaConsulta("u1")!!.puedeReabrir)

        envio.empujarYa("u1").join()

        assertEquals(listOf("secciones 7001 nota", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertNull(marca(), "al enviarse, la marca desaparece (RF-24)")
        assertFalse(db.consultaSucia("u1"))
        assertTrue(envio.estadoDeLaConsulta("u1")!!.cierreEnviado)
    }

    @Test
    fun cerrar_dispara_el_envio_ordenado_con_la_fecha_y_la_edad_del_medico() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1", "nota", "Una nota")
        db.sembrarExamen("u1", peso = "70")

        assertTrue(hc.cerrarConsulta("u1", fecha, "46 años"))

        assertEquals(listOf("crear c:u1", "secciones 7001 nota", "examen 7001 peso", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        val cierre = servidor.cierres.single()
        assertEquals(fecha, cierre.fecha)
        assertEquals("46 años", cierre.edadMostrar)
        val local = db.consultaLocal("u1")!!
        assertEquals(EstadoConsulta.CERRADA, local.estado)
        assertEquals(fecha, local.fecha)
        assertEquals("7001", local.remotoId)
        assertTrue(local.propia)
        assertEquals(emptyList(), db.filasSucias().flatten())
    }

    @Test
    fun cerrar_sin_conexion_queda_en_espera_y_se_puede_reabrir_sin_tocar_la_web() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1")
        servidor.falla = { sinConexion() }

        assertTrue(hc.cerrarConsulta("u1"))

        assertTrue(marca()!!.startsWith("En espera: Historia clínica de Clínica general"), marca())
        assertTrue(envio.estadoDeLaConsulta("u1")!!.puedeReabrir)
        val pedidos = servidor.pedidos.size

        assertTrue(hc.reabrirConsulta("u1"))

        assertEquals(EstadoConsulta.ABIERTA, db.consultaLocal("u1")!!.estado)
        assertEquals(pedidos, servidor.pedidos.size, "reabrir no toca la web")
        assertNull(db.estadosDe("u1")[PARTE_CIERRE], "el cierre retirado no deja su estado")
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"), "ni se tira lo que faltaba enviar")
    }

    @Test
    fun un_cierre_rechazado_queda_no_se_pudo_enviar_y_todavia_se_puede_reabrir_o_anular() = conServidor {
        sembrarCreada()
        servidor.falla = { if (it == "cerrar") TobbException(409, "consulta_cerrada", "La consulta ya está cerrada o dada de baja.") else null }

        assertTrue(hc.cerrarConsulta("u1"))

        assertTrue(marca()!!.startsWith("No se pudo enviar: Historia clínica de Clínica general"), marca())
        val estado = envio.estadoDeLaConsulta("u1")!!
        assertTrue(estado.puedeReabrir, "el cierre no se envió")
        assertTrue(estado.puedeAnular)
    }

    @Test
    fun cerrar_con_un_envio_en_curso_deja_el_cierre_pendiente_hasta_que_ese_envio_termine() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1")
        var cerro = false
        // El médico cierra mientras viaja la creación: el cierre espera su turno detrás del envío en curso.
        servidor.alRecibir = { if (it == "crear" && !cerro) { cerro = true; assertTrue(hc.cerrarConsulta("u1")) } }

        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "secciones 7001 nota", "cerrar 7001 c:u1-cierre"), servidor.pedidos, "el cierre sale después, no en medio")
        assertEquals(EstadoConsulta.CERRADA, db.consultaLocal("u1")!!.estado)
        assertEquals(emptyList(), db.filasSucias().flatten(), "lo que cambió en vuelo no se dio por enviado hasta que salió")
    }

    // ---- Reabrir ----

    @Test
    fun reabrir_una_cerrada_cuyo_cierre_no_se_envio_la_vuelve_a_abierta_sin_tocar_la_web() = conServidor {
        sembrarCreada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarTexto("u1", "nota", "Una nota")
        db.sembrarExamen("u1", peso = "70")
        val sinCola = repositorio(conCola = false)

        assertTrue(sinCola.reabrirConsulta("u1"))

        val local = db.consultaLocal("u1")!!
        assertEquals(EstadoConsulta.ABIERTA, local.estado)
        assertTrue(local.propia)
        assertEquals("7001", local.remotoId)
        assertEquals(emptyList(), servidor.pedidos)
        assertFalse(db.consultaSucia("u1"), "la web ya la tiene abierta: no hay nada que mandar de la consulta")
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"))
        assertTrue(db.examenSucio("u1"))
        assertEquals("Falta enviar", marca(), "reabierta: ya no falta el cierre, pero sí los textos")
        assertFalse(envio.estadoDeLaConsulta("u1")!!.cierreEnviado)
        // Y se puede volver a cerrar.
        assertTrue(hc.cerrarConsulta("u1"))
        assertEquals("cerrar 7001 c:u1-cierre", servidor.pedidos.last())
    }

    @Test
    fun reabrir_una_cerrada_que_nunca_se_creo_sigue_pendiente_de_crearse() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        val sinCola = repositorio(conCola = false)

        assertTrue(sinCola.reabrirConsulta("u1"))

        assertEquals(EstadoConsulta.ABIERTA, db.consultaLocal("u1")!!.estado)
        assertTrue(db.consultaSucia("u1"), "falta crearla en la web")
    }

    @Test
    fun reabrir_una_cuyo_cierre_ya_se_envio_no_se_permite() = conServidor {
        sembrarCreada(estado = EstadoConsulta.CERRADA, dirty = true)
        envio.empujarYa("u1").join()
        val pedidos = servidor.pedidos.size

        assertFalse(hc.reabrirConsulta("u1"), "la web no reabre (D13)")

        assertEquals(EstadoConsulta.CERRADA, db.consultaLocal("u1")!!.estado)
        assertEquals(pedidos, servidor.pedidos.size)
        val estado = envio.estadoDeLaConsulta("u1")!!
        assertTrue(estado.cierreEnviado)
        assertFalse(estado.puedeReabrir)
    }

    @Test
    fun mientras_el_cierre_viaja_no_se_puede_reabrir_ni_anular_y_se_ve_enviando_el_cierre() = conServidor {
        sembrarCreada(estado = EstadoConsulta.CERRADA, dirty = true)
        var reabrio: Boolean? = null
        var anulo: Boolean? = null
        var durante: String? = null
        var puedeDurante: Pair<Boolean, Boolean>? = null
        servidor.alRecibir = {
            if (it == "cerrar") {
                reabrio = hc.reabrirConsulta("u1")
                anulo = hc.anularConsulta("u1")
                durante = marca()
                puedeDurante = envio.estadoDeLaConsulta("u1")!!.let { e -> e.puedeReabrir to e.puedeAnular }
            }
        }

        envio.empujarYa("u1").join()

        assertEquals(false, reabrio, "no se reabre mientras viaja el cierre")
        assertEquals(false, anulo, "ni se anula")
        assertEquals("Enviando el cierre.", durante)
        assertEquals(false to false, puedeDurante)
        assertEquals(EstadoConsulta.CERRADA, db.consultaLocal("u1")!!.estado)
        assertEquals(emptySet(), envio.consultasEnviandoElCierre.value, "y al terminar ya no figura")
        assertNull(marca())
    }

    @Test
    fun reabrir_limpia_el_estado_del_cierre_asi_un_cierre_nuevo_no_queda_frenado_por_el_viejo() = conServidor {
        sembrarCreada()
        var rechazar = true
        servidor.falla = { if (it == "cerrar" && rechazar) TobbException(422, "datos_invalidos", "No la acepta") else null }
        assertTrue(hc.cerrarConsulta("u1"))
        assertNotNull(db.estadosDe("u1")[PARTE_CIERRE])

        assertTrue(hc.reabrirConsulta("u1"))
        assertNull(db.estadosDe("u1")[PARTE_CIERRE])
        rechazar = false
        assertTrue(hc.cerrarConsulta("u1"))

        assertEquals(2, servidor.cierres.size, "el segundo cierre salió: no lo frenó el rechazo anterior")
        assertEquals(emptyList(), db.filasSucias().flatten())
    }

    @Test
    fun reabrir_espera_su_turno_y_revisa_si_el_cierre_salio_mientras_tanto() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1")
        var reabrio: Boolean? = null
        var uno = true
        // Con el texto viajando el médico cierra y enseguida toca «Reabrir»: el cierre sale primero y el reabrir lo encuentra enviado.
        servidor.alRecibir = {
            if (it == "secciones" && uno) {
                uno = false
                assertTrue(hc.cerrarConsulta("u1"))
                scope.launch { reabrio = hc.reabrirConsulta("u1") }
            }
        }

        envio.empujarYa("u1").join()

        assertEquals(listOf("secciones 7001 nota", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(false, reabrio, "cuando le tocó el turno el cierre ya estaba enviado")
        assertEquals(EstadoConsulta.CERRADA, db.consultaLocal("u1")!!.estado)
    }

    // ---- Anular ----

    @Test
    fun anular_una_que_no_se_creo_en_la_web_no_envia_nada_limpia_y_conserva_los_datos() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1", "nota", "Una nota")
        db.sembrarExamen("u1", peso = "70")

        assertTrue(hc.anularConsulta("u1"))

        assertEquals(emptyList(), servidor.pedidos, "queda anulada solo en el dispositivo (RF-26)")
        val local = db.consultaLocal("u1")!!
        assertEquals(EstadoConsulta.ANULADA, local.estado)
        assertTrue(local.propia)
        assertFalse(db.consultaSucia("u1"))
        assertEquals(emptySet(), db.textosSucios("u1"), "no queda pendiente de nada")
        assertFalse(db.examenSucio("u1"))
        assertEquals(listOf("nota/Una nota"), db.historiaClinicaQueries.valoresDeConsulta("u1").lista { "${it.seccion}/${it.valor}" }, "pero el texto sigue guardado")
        assertEquals("70", db.historiaClinicaQueries.examenFisicoDeConsulta("u1").uno { it.peso })
        assertEquals(0, envio.contarPendientes().total)
        assertNull(marca())
    }

    @Test
    fun anular_una_creada_y_abierta_manda_la_baja_y_descarta_lo_pendiente() = conServidor {
        sembrarCreada()
        db.sembrarTexto("u1", "nota", "Una nota")
        db.sembrarExamen("u1", peso = "70")

        assertTrue(hc.anularConsulta("u1"))

        assertEquals(listOf("baja 7001 c:u1-baja"), servidor.pedidos, "solo la baja: lo pendiente se descarta, no se manda")
        assertTrue(servidor.consultas.getValue(7001).baja)
        assertEquals(emptyList(), servidor.textosEnviados)
        val local = db.consultaLocal("u1")!!
        assertEquals(EstadoConsulta.ANULADA, local.estado)
        assertTrue(local.propia)
        assertEquals("7001", local.remotoId, "no se pierde el id de la web")
        assertEquals(emptyList(), db.filasSucias().flatten())
        assertEquals(listOf("nota/Una nota"), db.historiaClinicaQueries.valoresDeConsulta("u1").lista { "${it.seccion}/${it.valor}" }, "el dato local sigue ahí")
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun anular_con_un_envio_en_curso_lo_deja_terminar_lo_que_viaja_y_manda_la_baja_sin_el_resto() = conServidor {
        sembrarCreada()
        db.sembrarTexto("u1", "nota", "Una nota")
        db.sembrarExamen("u1", peso = "70")
        var anulo = false
        // Se anula mientras viajan los textos: el examen que seguía en el plan ya no sale.
        servidor.alRecibir = { if (it == "secciones" && !anulo) { anulo = true; assertTrue(hc.anularConsulta("u1")) } }

        envio.empujarYa("u1").join()

        assertEquals(listOf("secciones 7001 nota", "baja 7001 c:u1-baja"), servidor.pedidos)
        assertTrue(servidor.consultas.getValue(7001).baja)
        assertEquals(emptyList(), db.filasSucias().flatten())
        assertTrue(db.historiaClinicaQueries.examenFisicoDeConsulta("u1").uno { it.peso } == "70", "el examen se conserva en el dispositivo")
    }

    @Test
    fun anular_una_cerrada_con_el_cierre_ya_enviado_no_se_permite() = conServidor {
        sembrarCreada(estado = EstadoConsulta.CERRADA, dirty = true)
        envio.empujarYa("u1").join()
        val pedidos = servidor.pedidos.size

        assertFalse(hc.anularConsulta("u1"), "RF-26: queda solo para leer")

        assertEquals(EstadoConsulta.CERRADA, db.consultaLocal("u1")!!.estado)
        assertEquals(pedidos, servidor.pedidos.size)
        assertFalse(envio.estadoDeLaConsulta("u1")!!.puedeAnular)
    }

    @Test
    fun anular_una_cerrada_cuyo_cierre_no_se_envio_da_de_baja_la_que_la_web_sigue_viendo_abierta() = conServidor {
        sembrarCreada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarTexto("u1")

        assertTrue(hc.anularConsulta("u1"))

        assertEquals(listOf("baja 7001 c:u1-baja"), servidor.pedidos, "el cierre nunca salió")
        assertTrue(servidor.consultas.getValue(7001).baja)
        assertEquals(EstadoConsulta.ANULADA, db.consultaLocal("u1")!!.estado)
        assertEquals(emptyList(), db.filasSucias().flatten())
    }

    @Test
    fun anular_una_dada_de_baja_es_idempotente_y_una_anulada_no_se_cierra_ni_se_reabre() = conServidor {
        sembrarCreada()
        assertTrue(hc.anularConsulta("u1"))
        val pedidos = servidor.pedidos.size

        assertTrue(hc.anularConsulta("u1"))
        assertFalse(hc.cerrarConsulta("u1"))
        assertFalse(hc.reabrirConsulta("u1"))

        assertEquals(pedidos, servidor.pedidos.size)
        assertEquals(EstadoConsulta.ANULADA, db.consultaLocal("u1")!!.estado)
    }

    @Test
    fun anular_quita_los_estados_de_lo_que_ya_no_va_a_enviarse() = conServidor {
        sembrarCreada()
        db.sembrarTexto("u1")
        servidor.falla = { sinConexion() }
        envio.empujarYa("u1").join()
        assertNotNull(db.estadosDe("u1")[PARTE_SECCIONES])
        servidor.falla = null

        assertTrue(hc.anularConsulta("u1"))

        assertEquals(emptyMap(), db.estadosDe("u1"), "ni «En espera» ni nada de lo descartado")
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun los_datos_por_paciente_pendientes_de_una_anulada_quedan_en_reserva_ni_se_envian_ni_se_tiran() = conServidor(habilitadas = seccionesDe(SubentregaDeEscritura.E2)) {
        sembrarCreada()
        db.sembrarAntecedente("a1", "u1")

        assertTrue(hc.anularConsulta("u1"))

        assertEquals(listOf("baja 7001 c:u1-baja"), servidor.pedidos)
        assertTrue(db.antecedenteSucio("a1"), "se conserva, y sigue sin enviarse")
        assertEquals(0, envio.contarPendientes().total, "y no cuenta como pendiente")
    }

    // ---- Nada se pierde ----

    @Test
    fun cerrar_reabrir_y_anular_no_pierden_la_marca_de_propia_ni_el_id_de_la_web_ni_tiran_datos() = conServidor {
        sembrarCreada()
        db.sembrarTexto("u1", "nota", "Una nota")
        db.sembrarExamen("u1", peso = "70")
        val sinCola = repositorio(conCola = false)

        assertTrue(sinCola.cerrarConsulta("u1", fecha, "46 años"))
        assertTrue(db.consultaLocal("u1")!!.propia)
        assertTrue(sinCola.reabrirConsulta("u1"))
        assertTrue(db.consultaLocal("u1")!!.propia)
        assertTrue(sinCola.cerrarConsulta("u1"))
        assertTrue(sinCola.anularConsulta("u1"))

        val local = db.consultaLocal("u1")!!
        assertTrue(local.propia)
        assertEquals("7001", local.remotoId)
        assertEquals("CLINICA", local.especialidad.uppercase())
        assertEquals(fecha, local.fecha)
        assertEquals("46 años", local.edadMostrar)
        assertEquals(listOf("nota/Una nota"), db.historiaClinicaQueries.valoresDeConsulta("u1").lista { "${it.seccion}/${it.valor}" })
        assertEquals("70", db.historiaClinicaQueries.examenFisicoDeConsulta("u1").uno { it.peso })
    }

    // ---- Lo que no es propia no cambia ----

    @Test
    fun el_historico_se_cierra_reabre_y_anula_solo_en_el_dispositivo_y_lo_de_la_web_no() = conServidor {
        db.sembrarHistorica("hist-1")
        db.sembrarDeLaWeb("hcc-c7003")

        assertTrue(hc.reabrirConsulta("hist-1"))
        assertTrue(hc.cerrarConsulta("hist-1"))
        assertTrue(hc.anularConsulta("hist-1"))
        assertFalse(hc.cerrarConsulta("hcc-c7003"))
        assertFalse(hc.reabrirConsulta("hcc-c7003"))
        assertFalse(hc.anularConsulta("hcc-c7003"))

        assertEquals(emptyList(), servidor.pedidos, "el histórico no sale solo y lo de la web no se escribe")
        assertFalse(db.consultaLocal("hist-1")!!.propia)
    }

    @Test
    fun otro_medico_no_dispara_el_envio_pero_el_cambio_local_se_guarda() = conServidor {
        sembrarCreada()
        medicoDeLaSesion = "m-otro"

        // El repositorio no manda con la sesión de otro: la cola no toma la consulta (RF-50). El cambio local sí queda.
        assertTrue(hc.cerrarConsulta("u1"))

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(EstadoConsulta.CERRADA, db.consultaLocal("u1")!!.estado)
    }
}
