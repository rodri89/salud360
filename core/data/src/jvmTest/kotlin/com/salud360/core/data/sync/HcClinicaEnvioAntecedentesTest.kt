package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.PARTE_ANTECEDENTES
import com.salud360.core.data.hcgeneral.PARTE_CIERRE
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spec 005 RF-9, RF-15, RF-32, RF-55, RF-56, RF-68, RF-69 (T63-a): la cola de envío manda los **antecedentes familiares** de una consulta propia a
 * `PUT pacientes/{ficha}/antecedentes-familiares`, con la consulta de la web, lo que arma el plan (T62-a) y lo último que la web confirmó (`LoEnviado`). Un
 * solo pedido por paso; solo casillas activas y de la consulta propia que cambian de verdad; lo vaciado a propósito viaja `flag 0` y detalle vacío; lo que la
 * web no confirma en `guardadas` no se limpia ni figura como enviado; una respuesta perdida se repite sin duplicar. Contra el [ServidorFalso] con memoria y
 * con la subentrega E2 habilitada **explícitamente** (el candado de la versión sigue en E1). Sin `nota_perfil4` (Q-E2-1).
 */
class HcClinicaEnvioAntecedentesTest {
    private val e2 = seccionesDe(SubentregaDeEscritura.E2)

    private suspend fun Escena.creada(estado: EstadoConsulta = EstadoConsulta.ABIERTA, dirty: Boolean = false) {
        db.sembrarPropia("u1", remotoId = "7001", estado = estado, dirty = dirty)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
    }

    private fun rechazo422() = TobbException(422, "datos_invalidos", "Datos que la web no acepta")

    // ---------- Un pedido a la ficha, con la consulta ----------

    @Test
    fun las_casillas_y_la_nota_salen_en_un_solo_pedido_a_la_ficha_con_la_consulta_de_la_web() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarAntecedente("a1", "u1", clave = "hta", detalle = "padre")
        db.sembrarAntecedente("a2", "u1", clave = "acv", detalle = "madre")
        db.sembrarAntecedente("a3", "u1", clave = "nota", detalle = "Texto de la nota")

        envio.empujarYa("u1").join()

        assertEquals(listOf("antecedentes 5001 7001 acv,hta,nota"), servidor.pedidos, "un solo pedido a la ficha 5001, para la consulta 7001")
        val recibido = servidor.antecedentesRecibidos.single()
        assertEquals(5001L, recibido.pacienteWebId)
        assertEquals(7001L, recibido.consultaWebId)
        assertEquals(mapOf("hta" to "1|padre", "acv" to "1|madre", "nota" to "Texto de la nota"), servidor.consultas.getValue(7001).antecedentes)
        assertFalse(db.antecedenteSucio("a1") || db.antecedenteSucio("a2") || db.antecedenteSucio("a3"))
        assertEquals(
            mapOf("hta" to "1|padre", "acv" to "1|madre", "nota" to "1|Texto de la nota"),
            db.loEnviadoDe("u1").antecedentes,
            "lo que la web confirmó queda anotado por clave",
        )
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun solo_salen_los_activos_y_los_de_esta_consulta_y_lo_que_nunca_viaja_se_limpia() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarHistorica("hist-1")
        db.sembrarAntecedente("a-mia", "u1", clave = "hta")
        db.sembrarAntecedente("a-inactiva", "u1", clave = "acv", activo = false)
        db.sembrarAntecedente("a-tabaco", "u1", clave = "tabaquismo")
        db.sembrarAntecedente("a-personal", "u1", clave = "alergias", categoria = "personales")
        db.sembrarAntecedente("a-historica", "hist-1", clave = "dsl")
        db.sembrarAntecedente("a-ninguna", null, clave = "fa")

        envio.empujarYa("u1").join()

        assertEquals(listOf("antecedentes 5001 7001 hta"), servidor.pedidos)
        assertFalse(db.antecedenteSucio("a-mia"))
        assertFalse(db.antecedenteSucio("a-inactiva"), "el desactivado no viaja y se limpia sin figurar enviado (RF-9)")
        assertFalse(db.antecedenteSucio("a-tabaco"), "lo que la web no guarda se limpia")
        assertFalse(db.antecedenteSucio("a-personal"))
        assertTrue(db.antecedenteSucio("a-historica") && db.antecedenteSucio("a-ninguna"), "lo de otra consulta ni se manda ni se toca (RF-68)")
        assertEquals(mapOf("hta" to "1|padre"), db.loEnviadoDe("u1").antecedentes)
    }

    @Test
    fun la_nota_del_perfil_4_y_lo_que_el_contrato_no_conoce_no_salen_ni_se_pierden() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarAntecedente("a-n4", "u1", clave = "nota_perfil4", detalle = "nota del perfil 4")
        db.sembrarAntecedente("a-raro", "u1", clave = "inventada")

        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos)
        assertTrue(db.antecedenteSucio("a-n4") && db.antecedenteSucio("a-raro"), "Q-E2-1: siguen en el dispositivo, sin contar como pendientes")
        assertEquals(0, envio.contarPendientes().total)
    }

    // ---------- Cambio real y vaciado a propósito ----------

    @Test
    fun una_casilla_igual_a_lo_que_la_web_tiene_no_sale_y_la_vacia_sobre_nada_tampoco() = conServidor(habilitadas = e2) {
        creada()
        LoEnviadoGuardado(db).guardar(PACIENTE, "u1", LoEnviado(antecedentes = mapOf("hta" to "1|padre")))
        db.sembrarAntecedente("a1", "u1", clave = "hta", detalle = "padre")
        db.sembrarAntecedente("a2", "u1", clave = "acv", flag = false, detalle = "")

        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos)
        assertFalse(db.antecedenteSucio("a1") || db.antecedenteSucio("a2"), "se limpian sin figurar enviadas")
        assertEquals(mapOf("hta" to "1|padre"), db.loEnviadoDe("u1").antecedentes)
    }

    @Test
    fun vaciar_a_proposito_una_casilla_o_la_nota_viaja_flag_0_detalle_vacio_y_nota_vacia() = conServidor(habilitadas = e2) {
        creada()
        servidor.consultas.getValue(7001).antecedentes += mapOf("hta" to "1|padre", "nota" to "antes")
        LoEnviadoGuardado(db).guardar(PACIENTE, "u1", LoEnviado(antecedentes = mapOf("hta" to "1|padre", "nota" to "1|antes")))
        db.sembrarAntecedente("a1", "u1", clave = "hta", flag = false, detalle = "")
        db.sembrarAntecedente("a2", "u1", clave = "nota", flag = true, detalle = "")

        envio.empujarYa("u1").join()

        assertEquals(mapOf("hta" to "0|", "nota" to ""), servidor.consultas.getValue(7001).antecedentes, "la casilla queda sin marcar y la nota vacía")
        assertEquals(mapOf("hta" to "0|", "nota" to "0|"), db.loEnviadoDe("u1").antecedentes)
        assertFalse(db.antecedenteSucio("a1") || db.antecedenteSucio("a2"))
    }

    @Test
    fun la_nota_viaja_solo_si_cambio() = conServidor(habilitadas = e2) {
        creada()
        LoEnviadoGuardado(db).guardar(PACIENTE, "u1", LoEnviado(antecedentes = mapOf("nota" to "1|igual")))
        db.sembrarAntecedente("a1", "u1", clave = "nota", detalle = "igual")

        envio.empujarYa("u1").join()
        assertEquals(emptyList(), servidor.pedidos, "la misma nota no sale")

        db.sembrarAntecedente("a1", "u1", clave = "nota", detalle = "distinta", actualizadaEn = 600)
        envio.empujarYa("u1").join()

        assertEquals(listOf("antecedentes 5001 7001 nota"), servidor.pedidos)
        assertEquals("distinta", servidor.consultas.getValue(7001).antecedentes.getValue("nota"))
    }

    // ---------- Parcial (T25 (b)) ----------

    @Test
    fun de_los_antecedentes_solo_se_limpia_y_se_anota_lo_que_la_web_confirmo() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarAntecedente("a1", "u1", clave = "hta", detalle = "padre")
        db.sembrarAntecedente("a2", "u1", clave = "acv", detalle = "madre")
        db.sembrarAntecedente("a3", "u1", clave = "dsl", detalle = "hermano")
        servidor.noConoce = setOf("acv")

        envio.empujarYa("u1").join()

        assertTrue(db.antecedenteSucio("a2"), "lo que la web no guardó sigue pendiente")
        assertFalse(db.antecedenteSucio("a1") || db.antecedenteSucio("a3"))
        assertEquals(mapOf("hta" to "1|padre", "dsl" to "1|hermano"), db.loEnviadoDe("u1").antecedentes, "solo lo confirmado figura como lo que tiene la web")
        assertEquals(mapOf("hta" to "1|padre", "dsl" to "1|hermano"), servidor.consultas.getValue(7001).antecedentes)
        assertEquals(listOf("antecedentes 5001 7001 acv,dsl,hta"), servidor.pedidos, "y el cierre no sale")
        val estado = assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_ANTECEDENTES).estado)
        assertEquals(CausaDeRechazo.OTRO, estado.causa)
        assertTrue("acv" in estado.motivo, "el motivo dice qué no se guardó: ${estado.motivo}")
        assertTrue(db.consultaSucia("u1"))
        assertEquals(1, envio.contarPendientes().noSePudoEnviar)
    }

    @Test
    fun lo_no_confirmado_no_se_reenvia_solo() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarAntecedente("a1", "u1", clave = "hta")
        servidor.noConoce = setOf("hta")
        envio.empujarYa("u1").join()

        repeat(3) { envio.empujarYa("u1").join() }
        reloj.avanzar(3_600_000)

        assertEquals(1, servidor.pedidos.size)
        assertTrue(db.antecedenteSucio("a1"))
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"))
    }

    // ---------- Respuesta perdida y reenvío (RF-15) ----------

    @Test
    fun si_se_pierde_la_respuesta_el_reenvio_repite_el_pedido_sin_duplicar_nada() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarAntecedente("a1", "u1", clave = "hta", detalle = "padre")
        db.sembrarAntecedente("a2", "u1", clave = "nota", detalle = "Texto")
        servidor.respuestasPerdidas += "antecedentes"

        envio.empujarYa("u1").join()

        assertEquals(mapOf("hta" to "1|padre", "nota" to "Texto"), servidor.consultas.getValue(7001).antecedentes)
        assertTrue(db.antecedenteSucio("a1") && db.antecedenteSucio("a2"))
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"))
        assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_ANTECEDENTES).estado).causa)

        servidor.respuestasPerdidas.clear()
        envio.empujarYa("u1").join()

        assertEquals(List(2) { "antecedentes 5001 7001 hta,nota" }, servidor.pedidos)
        assertEquals(servidor.antecedentesRecibidos[0].cuerpo, servidor.antecedentesRecibidos[1].cuerpo, "el mismo cuerpo")
        assertEquals(mapOf("hta" to "1|padre", "nota" to "Texto"), servidor.consultas.getValue(7001).antecedentes, "sin filas de más")
        assertFalse(db.antecedenteSucio("a1") || db.antecedenteSucio("a2"))
        assertEquals(mapOf("hta" to "1|padre", "nota" to "1|Texto"), db.loEnviadoDe("u1").antecedentes)
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun sin_conexion_deja_en_espera_los_antecedentes_y_el_cierre_y_sale_solo_al_volver() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarAntecedente("a1", "u1", clave = "hta")
        servidor.falla = { if (it == "antecedentes") TobbException(0, "red", "Sin conexión") else null }

        envio.empujarYa("u1").join()

        assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_ANTECEDENTES).estado)
        assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CIERRE).estado)
        assertTrue(db.antecedenteSucio("a1"), "no se pierde nada")

        servidor.falla = null
        reloj.avanzar(15_000)

        assertFalse(db.antecedenteSucio("a1"))
        assertEquals(listOf("antecedentes 5001 7001 hta", "antecedentes 5001 7001 hta", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    // ---------- Rechazos ----------

    @Test
    fun un_422_deja_no_se_pudo_enviar_y_no_se_reintenta_solo() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarAntecedente("a1", "u1", clave = "hta")
        servidor.falla = { if (it == "antecedentes") rechazo422() else null }

        envio.empujarYa("u1").join()
        val pedidos = servidor.pedidos.size
        repeat(3) { envio.empujarYa("u1").join() }
        reloj.avanzar(3_600_000)

        assertEquals(CausaDeRechazo.DATOS_INVALIDOS, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_ANTECEDENTES).estado).causa)
        assertEquals(pedidos, servidor.pedidos.size, "ni solo ni en una hora")
        assertEquals(0, reloj.esperando)
        assertTrue(db.antecedenteSucio("a1"), "el dato sigue en el dispositivo")
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"))
        assertFalse("cerrar" in servidor.operaciones)
    }

    @Test
    fun una_consulta_cerrada_en_la_web_rechaza_los_antecedentes_y_no_los_da_por_enviados() = conServidor(habilitadas = e2) {
        creada()
        servidor.consultas.getValue(7001).estado = "CERRADA"
        db.sembrarAntecedente("a1", "u1", clave = "hta")

        envio.empujarYa("u1").join()

        assertTrue(db.antecedenteSucio("a1"))
        assertEquals(CausaDeRechazo.CONSULTA_CERRADA, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_ANTECEDENTES).estado).causa)
        assertEquals(emptyMap(), servidor.consultas.getValue(7001).antecedentes)
    }

    @Test
    fun reintentar_a_mano_vuelve_a_mandar_lo_rechazado_y_el_cierre_sale() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarAntecedente("a1", "u1", clave = "hta")
        var rechazar = true
        servidor.falla = { if (it == "antecedentes" && rechazar) rechazo422() else null }
        envio.empujarYa("u1").join()

        rechazar = false
        envio.reintentar("u1").join()

        assertEquals(listOf("antecedentes 5001 7001 hta", "antecedentes 5001 7001 hta", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(emptyList(), db.filasSucias().flatten())
    }

    // ---------- La ficha y la sesión ----------

    @Test
    fun sin_la_ficha_de_la_web_los_antecedentes_no_salen_y_no_se_pierden() = conServidor(habilitadas = e2, vinculado = false) {
        creada()
        db.sembrarAntecedente("a1", "u1", clave = "hta")

        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos, "no se sabe a qué ficha mandarlos")
        assertTrue(db.antecedenteSucio("a1"))
        assertNotNull(db.estadosDe("u1")[PARTE_ANTECEDENTES], "queda marcado para que el médico lo vea")
        assertEquals(emptyMap(), servidor.consultas.getValue(7001).antecedentes)
    }

    @Test
    fun con_la_sesion_de_otro_medico_no_sale_nada_ni_se_modifica_nada() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarAntecedente("a1", "u1", clave = "hta")
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

        assertEquals(listOf("antecedentes 5001 7001 hta", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
    }

    // ---------- Lo que cambió mientras viajaba (RF-55) ----------

    @Test
    fun una_casilla_cambiada_en_vuelo_no_se_da_por_enviada_y_sale_en_el_envio_siguiente() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarAntecedente("a1", "u1", clave = "hta", detalle = "padre")
        db.sembrarAntecedente("a2", "u1", clave = "acv", detalle = "madre")
        servidor.alRecibir = { operacion ->
            if (operacion == "antecedentes") db.sembrarAntecedente("a1", "u1", clave = "hta", detalle = "abuelo", actualizadaEn = 500)
        }

        envio.empujarYa("u1").join()

        assertTrue(db.antecedenteSucio("a1"), "la hta cambió en vuelo: sigue pendiente")
        assertFalse(db.antecedenteSucio("a2"))
        assertEquals(mapOf("hta" to "1|padre", "acv" to "1|madre"), db.loEnviadoDe("u1").antecedentes, "la web tiene lo que viajó, no lo nuevo")

        servidor.alRecibir = null
        envio.empujarYa("u1").join()

        assertEquals(listOf("antecedentes 5001 7001 acv,hta", "antecedentes 5001 7001 hta"), servidor.pedidos, "el segundo envío lleva solo lo nuevo")
        assertEquals("1|abuelo", servidor.consultas.getValue(7001).antecedentes.getValue("hta"))
        assertFalse(db.antecedenteSucio("a1"))
        assertEquals("1|abuelo", db.loEnviadoDe("u1").antecedentes.getValue("hta"))
    }

    // ---------- El candado ----------

    @Test
    fun con_el_candado_de_la_version_los_antecedentes_no_salen_ni_frenan_el_cierre() = conServidor {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarAntecedente("a1", "u1", clave = "hta")

        envio.empujarYa("u1").join()

        assertEquals(listOf("cerrar 7001 c:u1-cierre"), servidor.pedidos, "E1: los antecedentes esperan y el cierre sale")
        assertTrue(db.antecedenteSucio("a1"))
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun con_un_conjunto_propio_sin_la_seccion_de_antecedentes_no_salen() = conServidor(habilitadas = seccionesDe(SubentregaDeEscritura.E1) + setOf("serologias")) {
        creada()
        db.sembrarAntecedente("a1", "u1", clave = "hta")
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")

        envio.empujarYa("u1").join()

        assertEquals(listOf("formularios 7001 serologias.hiv"), servidor.pedidos)
        assertTrue(db.antecedenteSucio("a1"))
    }
}
