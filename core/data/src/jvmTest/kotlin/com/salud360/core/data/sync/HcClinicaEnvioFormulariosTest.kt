package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.PARTE_FORMULARIOS
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura
import com.salud360.core.data.hcgeneral.seccionesDe
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.LoEnviadoGuardado
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec 005 RF-15, RF-32, RF-55, RF-56, RF-69 (T63-a): la cola de envío manda los **formularios** (alimentación, serologías, Child-Pugh) de una consulta
 * propia a `PUT consultas/{id}/formularios`, con lo que arma el plan (T62-a) y lo último que la web confirmó (`LoEnviado`). Un solo pedido por paso, después
 * del examen y antes de los antecedentes y del cierre; lo que la web no confirma en `guardadas` no se limpia ni figura como enviado y la parte queda «No se
 * pudo enviar»; una respuesta perdida se repite sin duplicar; lo que cambió mientras viajaba sigue pendiente. Contra el [ServidorFalso] con memoria y con la
 * subentrega E2 habilitada **explícitamente** (el candado de la versión —`SECCIONES_HABILITADAS`— habilita desde T67 reducida solo una parte de E2, así que acá se pasa el conjunto completo). Los registros son de T63-b.
 */
class HcClinicaEnvioFormulariosTest {
    private val e2 = seccionesDe(SubentregaDeEscritura.E2)

    /** Una propia ya creada en la web (la 7001), sin nada sucio más que lo que siembre cada test. */
    private suspend fun Escena.creada(estado: EstadoConsulta = EstadoConsulta.ABIERTA, dirty: Boolean = false) {
        db.sembrarPropia("u1", remotoId = "7001", estado = estado, dirty = dirty)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
    }

    private fun rechazo422() = TobbException(422, "datos_invalidos", "Datos que la web no acepta")

    // ---------- Un pedido, en su lugar ----------

    @Test
    fun los_formularios_van_despues_del_examen_y_antes_de_los_antecedentes_y_del_cierre() = conServidor(habilitadas = e2) {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        db.sembrarExamen("u1", peso = "70")
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        db.sembrarAntecedente("a1", "u1", clave = "hta")

        envio.empujarYa("u1").join()

        assertEquals(
            listOf("crear c:u1", "secciones 7001 nota", "examen 7001 peso", "formularios 7001 serologias.hiv", "antecedentes 5001 7001 hta", "cerrar 7001 c:u1-cierre"),
            servidor.pedidos,
        )
        assertEquals(emptyList(), db.filasSucias().flatten())
    }

    @Test
    fun varias_secciones_salen_en_un_solo_pedido_y_se_limpian_y_se_anotan_por_campo() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarFormulario("u1", "alimentacion", "hiposodica", "1|sin sal")
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        db.sembrarFormulario("u1", "child_pugh", "ascitis", "2")

        envio.empujarYa("u1").join()

        assertEquals(listOf("formularios 7001 alimentacion.hiposodica,child_pugh.ascitis,serologias.hiv"), servidor.pedidos, "un solo pedido para los tres formularios")
        assertEquals(
            mapOf("alimentacion.hiposodica" to "1|sin sal", "child_pugh.ascitis" to "2", "serologias.hiv" to "NO"),
            servidor.consultas.getValue(7001).formularios,
        )
        assertEquals(emptySet(), db.formulariosSucios("u1"))
        assertEquals(
            mapOf("alimentacion/hiposodica" to "1|sin sal", "child_pugh/ascitis" to "2", "serologias/hiv" to "NO"),
            db.loEnviadoDe("u1").formularios,
            "lo que la web confirmó queda anotado por campo",
        )
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun solo_viaja_lo_sucio_y_el_resto_de_la_consulta_no_se_toca() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        db.sembrarTexto("u1")

        envio.empujarYa("u1").join()

        assertEquals(listOf("secciones 7001 nota", "formularios 7001 serologias.hiv"), servidor.pedidos)
        assertEquals(mapOf("serologias.hiv" to "NO"), servidor.consultas.getValue(7001).formularios)
    }

    // ---------- Cambio real y vaciado a propósito ----------

    @Test
    fun un_valor_igual_a_lo_que_la_web_tiene_no_se_manda_y_se_limpia_sin_figurar_enviado() = conServidor(habilitadas = e2) {
        creada()
        LoEnviadoGuardado(db).guardar(PACIENTE, "u1", LoEnviado(formularios = mapOf("serologias/hiv" to "NO")))
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")

        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos, "lo que ya está igual en la web no sale")
        assertEquals(emptySet(), db.formulariosSucios("u1"), "y se limpia")
        assertEquals(mapOf("serologias/hiv" to "NO"), db.loEnviadoDe("u1").formularios, "sin cambiar lo confirmado")
    }

    @Test
    fun un_vacio_que_el_medico_no_toco_no_sale_nunca() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarFormulario("u1", "alimentacion", "diabetes", "0|")
        db.sembrarFormulario("u1", "alimentacion", "otra", "")
        db.sembrarFormulario("u1", "serologias", "ama", "")
        db.sembrarFormulario("u1", "child_pugh", "tp", "")

        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(emptySet(), db.formulariosSucios("u1"))
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"), "no se anota nada: nadie confirmó nada")
    }

    @Test
    fun vaciar_a_proposito_algo_que_la_web_tiene_se_manda_vacio_y_queda_vaciado() = conServidor(habilitadas = e2) {
        creada()
        servidor.consultas.getValue(7001).formularios += mapOf("serologias.hiv" to "NO", "alimentacion.hiposodica" to "1|sin sal", "child_pugh.tp" to "3")
        LoEnviadoGuardado(db).guardar(
            PACIENTE, "u1",
            LoEnviado(formularios = mapOf("serologias/hiv" to "NO", "alimentacion/hiposodica" to "1|sin sal", "child_pugh/tp" to "3")),
        )
        db.sembrarFormulario("u1", "serologias", "hiv", "")
        db.sembrarFormulario("u1", "alimentacion", "hiposodica", "0|")
        db.sembrarFormulario("u1", "child_pugh", "tp", "  ")

        envio.empujarYa("u1").join()

        assertEquals(
            mapOf("serologias.hiv" to "", "alimentacion.hiposodica" to "", "child_pugh.tp" to ""),
            servidor.consultas.getValue(7001).formularios,
            "el vaciado a propósito llega como vacío (nunca como «NO» ni como 0)",
        )
        assertEquals(mapOf("serologias/hiv" to "", "alimentacion/hiposodica" to "", "child_pugh/tp" to ""), db.loEnviadoDe("u1").formularios)
        assertEquals(emptySet(), db.formulariosSucios("u1"))

        // Ya vaciado en la web, guardar lo mismo otra vez no manda nada.
        db.sembrarFormulario("u1", "serologias", "hiv", "", actualizadaEn = 600)
        envio.empujarYa("u1").join()
        assertEquals(1, servidor.operaciones.count { it == "formularios" })
    }

    @Test
    fun el_cuerpo_es_el_mismo_en_cada_intento() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarFormulario("u1", "alimentacion", "celiaco", "1")
        db.sembrarFormulario("u1", "serologias", "fan", "SI")
        servidor.respuestasPerdidas += "formularios"

        envio.empujarYa("u1").join()
        servidor.respuestasPerdidas.clear()
        envio.reintentar("u1").join()

        assertEquals(2, servidor.formulariosEnviados.size)
        assertEquals(servidor.formulariosEnviados[0], servidor.formulariosEnviados[1], "byte a byte: el mismo cuerpo")
        assertEquals(mapOf("alimentacion" to mapOf("celiaco" to "1|"), "serologias" to mapOf("fan" to "SI")), servidor.formulariosEnviados[0], "el flag sale con la barra")
    }

    // ---------- Parcial (T25 (b)) ----------

    @Test
    fun de_los_formularios_solo_se_limpia_y_se_anota_lo_que_la_web_confirmo() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        db.sembrarFormulario("u1", "serologias", "fan", "SI")
        db.sembrarFormulario("u1", "child_pugh", "ascitis", "2")
        servidor.noConoce = setOf("serologias.fan")

        envio.empujarYa("u1").join()

        assertEquals(setOf("serologias/fan"), db.formulariosSucios("u1"), "lo confirmado queda limpio y lo que la web no guardó, pendiente")
        assertEquals(
            mapOf("serologias/hiv" to "NO", "child_pugh/ascitis" to "2"),
            db.loEnviadoDe("u1").formularios,
            "y solo lo confirmado figura como lo que tiene la web",
        )
        assertEquals(mapOf("serologias.hiv" to "NO", "child_pugh.ascitis" to "2"), servidor.consultas.getValue(7001).formularios)
        assertEquals(listOf("formularios 7001 child_pugh.ascitis,serologias.fan,serologias.hiv"), servidor.pedidos, "y el cierre no sale")
        val estado = assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_FORMULARIOS).estado)
        assertEquals(CausaDeRechazo.OTRO, estado.causa)
        assertTrue("serologias.fan" in estado.motivo, "el motivo dice qué no se guardó: ${estado.motivo}")
        assertTrue(db.consultaSucia("u1"))
        assertEquals(1, envio.contarPendientes().noSePudoEnviar)
    }

    @Test
    fun lo_no_confirmado_no_se_reenvia_solo_ni_deja_salir_el_cierre() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        servidor.noConoce = setOf("serologias.hiv")
        envio.empujarYa("u1").join()

        repeat(3) { envio.empujarYa("u1").join() }
        reloj.avanzar(3_600_000)

        assertEquals(1, servidor.pedidos.size, "ni vuelve a mandar lo rechazado ni cierra la consulta")
        assertEquals(setOf("serologias/hiv"), db.formulariosSucios("u1"))
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"))
    }

    @Test
    fun si_la_web_no_confirma_nada_no_se_marca_nada_como_enviado() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        servidor.noConoce = setOf("serologias.hiv")

        envio.empujarYa("u1").join()

        assertEquals(setOf("serologias/hiv"), db.formulariosSucios("u1"))
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"))
        assertEquals(emptyMap(), servidor.consultas.getValue(7001).formularios)
    }

    // ---------- Respuesta perdida y reenvío (RF-15) ----------

    @Test
    fun si_se_pierde_la_respuesta_el_reenvio_repite_el_pedido_sin_duplicar_nada() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        db.sembrarFormulario("u1", "child_pugh", "tp", "3")
        servidor.respuestasPerdidas += "formularios"

        envio.empujarYa("u1").join()

        // El servidor lo aplicó, pero el dispositivo no se enteró: sigue sucio, sin anotar y «En espera».
        assertEquals(mapOf("serologias.hiv" to "NO", "child_pugh.tp" to "3"), servidor.consultas.getValue(7001).formularios)
        assertEquals(setOf("serologias/hiv", "child_pugh/tp"), db.formulariosSucios("u1"))
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"))
        assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_FORMULARIOS).estado).causa)

        servidor.respuestasPerdidas.clear()
        envio.empujarYa("u1").join()

        assertEquals(List(2) { "formularios 7001 child_pugh.tp,serologias.hiv" }, servidor.pedidos, "se repite el mismo pedido")
        assertEquals(mapOf("serologias.hiv" to "NO", "child_pugh.tp" to "3"), servidor.consultas.getValue(7001).formularios, "y la web queda igual: un solo valor por campo")
        assertEquals(emptySet(), db.formulariosSucios("u1"))
        assertEquals(mapOf("serologias/hiv" to "NO", "child_pugh/tp" to "3"), db.loEnviadoDe("u1").formularios)
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun sin_conexion_deja_en_espera_los_formularios_y_lo_que_sigue_y_sale_solo_al_volver() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        servidor.falla = { if (it == "formularios") TobbException(0, "red", "Sin conexión") else null }

        envio.empujarYa("u1").join()

        assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_FORMULARIOS).estado).causa)
        assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CIERRE).estado)
        assertEquals(setOf("serologias/hiv"), db.formulariosSucios("u1"), "no se pierde nada")

        servidor.falla = null
        reloj.avanzar(15_000)

        assertEquals(emptySet(), db.formulariosSucios("u1"))
        assertEquals(listOf("formularios 7001 serologias.hiv", "formularios 7001 serologias.hiv", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    // ---------- Rechazos ----------

    @Test
    fun un_422_deja_no_se_pudo_enviar_y_no_se_reintenta_solo() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        servidor.falla = { if (it == "formularios") rechazo422() else null }

        envio.empujarYa("u1").join()
        val pedidos = servidor.pedidos.size
        repeat(3) { envio.empujarYa("u1").join() }
        reloj.avanzar(3_600_000)

        assertEquals(CausaDeRechazo.DATOS_INVALIDOS, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_FORMULARIOS).estado).causa)
        assertEquals(pedidos, servidor.pedidos.size, "ni solo ni en una hora")
        assertEquals(0, reloj.esperando)
        assertEquals(setOf("serologias/hiv"), db.formulariosSucios("u1"), "el dato sigue en el dispositivo")
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"))
        assertTrue(db.consultaSucia("u1"), "y el cierre espera")
        assertFalse("cerrar" in servidor.operaciones)
    }

    @Test
    fun una_consulta_cerrada_en_la_web_rechaza_los_formularios_y_no_los_da_por_enviados() = conServidor(habilitadas = e2) {
        creada()
        servidor.consultas.getValue(7001).estado = "CERRADA"
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")

        envio.empujarYa("u1").join()

        assertEquals(setOf("serologias/hiv"), db.formulariosSucios("u1"))
        assertEquals(CausaDeRechazo.CONSULTA_CERRADA, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_FORMULARIOS).estado).causa)
        assertEquals(emptyMap(), servidor.consultas.getValue(7001).formularios)
    }

    @Test
    fun reintentar_a_mano_vuelve_a_mandar_lo_rechazado_y_el_cierre_sale() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        var rechazar = true
        servidor.falla = { if (it == "formularios" && rechazar) rechazo422() else null }
        envio.empujarYa("u1").join()

        rechazar = false
        envio.reintentar("u1").join()

        assertEquals(listOf("formularios 7001 serologias.hiv", "formularios 7001 serologias.hiv", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(emptyList(), db.filasSucias().flatten())
    }

    // ---------- Sesión ----------

    @Test
    fun con_la_sesion_de_otro_medico_no_sale_nada_ni_se_modifica_nada() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        val antes = db.filasSucias()

        medicoDeLaSesion = "otro-medico"
        envio.empujarYa("u1").join()
        envio.marcarSucia("u1")
        reloj.avanzar(3_600_000)

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(antes, db.filasSucias())
        assertEquals(emptyMap(), db.estadosDe("u1"))

        medicoDeLaSesion = MEDICO
        envio.empujarYa("u1").join()

        assertEquals(listOf("formularios 7001 serologias.hiv", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
    }

    // ---------- Lo que cambió mientras viajaba (RF-55) ----------

    @Test
    fun un_formulario_cambiado_en_vuelo_no_se_da_por_enviado_y_sale_en_el_envio_siguiente() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        db.sembrarFormulario("u1", "serologias", "fan", "SI")
        servidor.alRecibir = { operacion ->
            if (operacion == "formularios") db.sembrarFormulario("u1", "serologias", "hiv", "SI", actualizadaEn = 500)
        }

        envio.empujarYa("u1").join()

        assertEquals(setOf("serologias/hiv"), db.formulariosSucios("u1"), "el hiv cambió en vuelo: sigue pendiente; el fan, no")
        assertEquals(mapOf("serologias/hiv" to "NO", "serologias/fan" to "SI"), db.loEnviadoDe("u1").formularios, "la web tiene lo que viajó, no lo nuevo")

        servidor.alRecibir = null
        envio.empujarYa("u1").join()

        assertEquals(listOf("formularios 7001 serologias.fan,serologias.hiv", "formularios 7001 serologias.hiv"), servidor.pedidos, "el segundo envío lleva solo lo nuevo")
        assertEquals("SI", servidor.consultas.getValue(7001).formularios.getValue("serologias.hiv"))
        assertEquals(emptySet(), db.formulariosSucios("u1"))
        assertEquals("SI", db.loEnviadoDe("u1").formularios.getValue("serologias/hiv"))
    }

    // ---------- El candado ----------

    @Test
    fun con_el_candado_de_la_version_la_alimentacion_no_sale_ni_frena_el_cierre() = conServidor {
        // T67 reducida: las serologías y Child-Pugh ya viajan; de los formularios, la alimentación es la que sigue afuera del alcance del usuario.
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarFormulario("u1", "alimentacion", "hiposodica", "1|sin sal")

        envio.empujarYa("u1").join()

        assertEquals(listOf("cerrar 7001 c:u1-cierre"), servidor.pedidos, "la alimentación espera y el cierre sale")
        assertEquals(setOf("alimentacion/hiposodica"), db.formulariosSucios("u1"), "sigue sucia, para cuando se habilite")
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun con_un_conjunto_propio_solo_sale_la_seccion_habilitada() = conServidor(habilitadas = seccionesDe(SubentregaDeEscritura.E1) + setOf("serologias")) {
        creada()
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        db.sembrarFormulario("u1", "alimentacion", "hiposodica", "1|sin sal")

        envio.empujarYa("u1").join()

        assertEquals(listOf("formularios 7001 serologias.hiv"), servidor.pedidos)
        assertEquals(setOf("alimentacion/hiposodica"), db.formulariosSucios("u1"), "alimentación sigue apagada aunque sea de la misma subentrega")
    }
}
