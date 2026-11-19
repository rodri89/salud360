package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_CARGAS
import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura
import com.salud360.core.data.hcgeneral.TIPO_PESO
import com.salud360.core.data.hcgeneral.seccionesDe
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec 005 RF-15, RF-32, RF-41, RF-42, RF-55, RF-56, RF-61, RF-68, RF-69 (T85-a): la cola de envío manda las **cargas de laboratorio general y de peso**
 * de una consulta propia, **una por pedido** (P23) con lo que arma el plan (T84-a) y lo último que la web confirmó (`LoEnviado.cargaEnviada`), después de los
 * estudios y antes del cierre. El `id` de la web deja el `remoto_id` de la fila en la **misma transacción** que la limpia (RF-61); lo que la web no confirma
 * no se limpia ni figura como enviado y la parte queda «No se pudo enviar»; lo que cambió mientras viajaba sigue pendiente (RF-55); una baja sale como
 * `DELETE` de la carga que la web tiene. Contra el [ServidorFalso] con memoria y con la subentrega E3 habilitada **explícitamente** (el candado de la
 * versión —`SECCIONES_HABILITADAS`— todavía no la enciende). Los pendientes quedan fuera de alcance (T85-b) y no llegan.
 */
class HcClinicaEnvioCargasTest {
    private val e3 = seccionesDe(SubentregaDeEscritura.E3)

    /** Una propia ya creada en la web (la 7001), sin nada sucio más que lo que siembre cada test. */
    private suspend fun Escena.creada(estado: EstadoConsulta = EstadoConsulta.ABIERTA, dirty: Boolean = false) {
        db.sembrarPropia("u1", remotoId = "7001", estado = estado, dirty = dirty)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
    }

    private fun rechazo422() = TobbException(422, "datos_invalidos", "Datos que la web no acepta")

    private fun json(texto: String) = Json.parseToJsonElement(texto) as JsonObject

    // ---------- Un pedido por carga, en su lugar ----------

    @Test
    fun las_cargas_van_entre_los_estudios_y_el_cierre() = conServidor(habilitadas = e3) {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        db.sembrarExamen("u1", peso = "70")
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        db.sembrarAntecedente("a1", "u1", clave = "hta")
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        db.sembrarCarga("l1", "u1", valores = mapOf("hb" to "13"))
        db.sembrarCarga("p1", "u1", tipo = TIPO_PESO, valores = mapOf("peso" to "72.5"))

        envio.empujarYa("u1").join()

        assertEquals(
            listOf(
                "crear c:u1", "secciones 7001 nota", "examen 7001 peso", "formularios 7001 serologias.hiv",
                "antecedentes 5001 7001 hta", "registros 7001 r:r-eco", "laboratorio 7001 l:l1", "peso 7001 l:p1",
                "cerrar 7001 c:u1-cierre",
            ),
            servidor.pedidos,
        )
        assertEquals(emptySet<String>(), db.cargasSucias("u1"))
        assertEquals(emptyList(), db.filasSucias().flatten())
    }

    @Test
    fun un_laboratorio_nuevo_se_da_de_alta_con_su_fecha_y_queda_con_su_id_de_la_web() = conServidor(habilitadas = e3) {
        creada()
        db.sembrarCarga("l1", "u1", valores = mapOf("hb" to "13", "glucosa" to "90"), actualizadaEn = 150)

        envio.empujarYa("u1").join()

        assertEquals(listOf("laboratorio 7001 l:l1"), servidor.pedidos)
        assertEquals(
            json("""{"clave":"l:l1","dispositivo":"disp-1","consulta_id":"7001","tipo":"laboratorio_general","fecha":"2026-11-06","valores":{"hb":"13","glucosa":"90"}}"""),
            servidor.cuerposDeCargas.single(),
            "la clave estable, la consulta de la web y todos los valores con contenido",
        )
        val enLaWeb = servidor.cargas.values.single()
        assertEquals("laboratorio_general", enLaWeb.tipo)
        assertEquals("2026-11-06", enLaWeb.fecha)
        assertEquals(mapOf("hb" to "13", "glucosa" to "90"), enLaWeb.valores)
        val fila = db.cargaLocal("l1")!!
        assertEquals(enLaWeb.id.toString(), fila.remotoId, "el id que contestó la web queda en la fila")
        assertFalse(db.cargaSucia("l1"), "y la fila se limpia")
        assertEquals(150, db.cargaActualizadaEn("l1"), "sin mover el updated_at (RF-55)")
        assertEquals(
            mapOf(enLaWeb.id.toString() to mapOf("hb" to "13", "glucosa" to "90")),
            db.loEnviadoDe("u1").cargas,
            "lo confirmado queda anotado por carga",
        )
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun un_peso_nuevo_sale_por_la_ruta_del_peso_sin_tipo_y_con_su_valor() = conServidor(habilitadas = e3) {
        creada()
        db.sembrarCarga("p1", "u1", tipo = TIPO_PESO, valores = mapOf("peso" to "72.5"))

        envio.empujarYa("u1").join()

        assertEquals(listOf("peso 7001 l:p1"), servidor.pedidos)
        assertEquals(
            json("""{"clave":"l:p1","dispositivo":"disp-1","consulta_id":"7001","fecha":"2026-11-06","valores":{"peso":"72.5"}}"""),
            servidor.cuerposDeCargas.single(),
            "el peso viaja como valores:{peso} y sin tipo",
        )
        val enLaWeb = servidor.cargas.values.single()
        assertEquals(TIPO_PESO, enLaWeb.tipo)
        assertEquals(mapOf("peso" to "72.5"), enLaWeb.valores)
        assertEquals(enLaWeb.id.toString(), db.cargaLocal("p1")!!.remotoId)
        assertFalse(db.cargaSucia("p1"))
    }

    @Test
    fun la_edicion_manda_solo_el_analito_que_cambio_y_la_web_conserva_el_resto() = conServidor(habilitadas = e3) {
        creada()
        db.sembrarCarga("l1", "u1", valores = mapOf("hb" to "13", "glucosa" to "90"))
        envio.empujarYa("u1").join()
        val id = db.cargaLocal("l1")!!.remotoId

        db.escribirCarga("l1", mapOf("hb" to "11", "glucosa" to "90"), actualizadaEn = 500)
        envio.empujarYa("u1").join()

        assertEquals(listOf("laboratorio 7001 l:l1", "laboratorio 7001 l:l1"), servidor.pedidos, "solo el que cambió")
        val cuerpo = servidor.cuerposDeCargas.last()
        assertEquals(id, (cuerpo["id"] as JsonPrimitive).content, "el id de la fila de la web")
        assertEquals(json("""{"valores":{"hb":"11"}}"""), JsonObject(cuerpo.filterKeys { it == "valores" }), "solo el analito tocado")
        assertEquals(mapOf("hb" to "11", "glucosa" to "90"), servidor.cargas.getValue(id.toLong()).valores, "la web conserva el resto")
        assertEquals(mapOf("hb" to "11", "glucosa" to "90"), db.loEnviadoDe("u1").cargas.getValue(id), "y lo confirmado queda al día")
        assertFalse(db.cargaSucia("l1"))
        assertEquals(500, db.cargaActualizadaEn("l1"))
    }

    @Test
    fun un_analito_vaciado_a_proposito_se_manda_vacio_y_queda_en_lo_confirmado() = conServidor(habilitadas = e3) {
        creada()
        db.sembrarCarga("l1", "u1", valores = mapOf("hb" to "13", "glucosa" to "90"))
        envio.empujarYa("u1").join()
        val id = db.cargaLocal("l1")!!.remotoId

        db.escribirCarga("l1", mapOf("hb" to "13", "glucosa" to ""), actualizadaEn = 500)
        envio.empujarYa("u1").join()

        assertEquals("", servidor.cargas.getValue(id.toLong()).valores["glucosa"], "el vaciado a propósito llega vacío (RF-69)")
        assertEquals(mapOf("hb" to "13", "glucosa" to ""), db.loEnviadoDe("u1").cargas.getValue(id))
        assertFalse(db.cargaSucia("l1"))
    }

    @Test
    fun la_baja_de_una_carga_que_la_web_tiene_sale_como_delete_y_se_limpia() = conServidor(habilitadas = e3) {
        creada()
        db.sembrarCarga("l1", "u1", valores = mapOf("hb" to "13"))
        envio.empujarYa("u1").join()
        val id = db.cargaLocal("l1")!!.remotoId

        // El repositorio deja la carga `activo = 0, dirty = 1` con su `remoto_id` (T83-a): así se da de baja.
        db.sembrarCarga("l1", "u1", valores = mapOf("hb" to "13"), remotoId = id, activo = false, actualizadaEn = 600)
        envio.empujarYa("u1").join()

        assertEquals(listOf("laboratorio 7001 l:l1", "bajaDeCarga 7001 l:l1-baja"), servidor.pedidos)
        assertFalse(servidor.cargas.getValue(id.toLong()).activo, "la web la da de baja (lógica, sin borrar la fila)")
        assertFalse(db.cargaSucia("l1"))
        assertEquals(id, db.cargaLocal("l1")!!.remotoId)
        assertEquals(mapOf("hb" to "13"), db.loEnviadoDe("u1").cargas.getValue(id), "la baja no toca lo confirmado")
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    // ---------- Parcial ----------

    @Test
    fun de_las_cargas_solo_se_limpia_y_se_anota_lo_que_la_web_guardo() = conServidor(habilitadas = e3) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarCarga("l1", "u1", valores = mapOf("hb" to "13", "glucosa" to "90"))
        db.sembrarCarga("l2", "u1", valores = mapOf("hba1c" to "6.1"))
        servidor.noConoce = setOf("glucosa")

        envio.empujarYa("u1").join()

        assertEquals(setOf("l1"), db.cargasSucias("u1"), "lo que la web no guardó sigue pendiente y lo confirmado queda limpio")
        assertEquals("", db.cargaLocal("l1")!!.remotoId, "una carga sin confirmar no recibe id")
        assertEquals(1, db.loEnviadoDe("u1").cargas.size, "solo lo confirmado figura como lo que tiene la web")
        assertEquals("6.1", db.loEnviadoDe("u1").cargas.values.single()["hba1c"])
        assertEquals(2, servidor.cargas.size, "la web escribió las dos")
        val estado = assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_CARGAS).estado)
        assertEquals(CausaDeRechazo.OTRO, estado.causa)
        assertTrue("l:l1" in estado.motivo, "el motivo dice qué no se guardó: ${estado.motivo}")
        assertTrue(db.consultaSucia("u1"), "y el cierre espera")
        assertFalse("cerrar" in servidor.operaciones)
        assertEquals(1, envio.contarPendientes().noSePudoEnviar)
    }

    // ---------- Rechazos ----------

    @Test
    fun un_422_deja_no_se_pudo_enviar_y_no_se_reintenta_solo() = conServidor(habilitadas = e3) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarCarga("l1", "u1")
        servidor.falla = { if (it == "laboratorio") rechazo422() else null }

        envio.empujarYa("u1").join()
        val pedidos = servidor.pedidos.size
        repeat(3) { envio.empujarYa("u1").join() }
        reloj.avanzar(3_600_000)

        assertEquals(CausaDeRechazo.DATOS_INVALIDOS, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_CARGAS).estado).causa)
        assertEquals(pedidos, servidor.pedidos.size, "ni solo ni en una hora")
        assertEquals(0, reloj.esperando)
        assertEquals(setOf("l1"), db.cargasSucias("u1"), "el dato sigue en el dispositivo")
        assertEquals(emptyMap(), db.loEnviadoDe("u1").cargas)
        assertTrue(db.consultaSucia("u1"), "y el cierre espera")
        assertFalse("cerrar" in servidor.operaciones)
    }

    @Test
    fun una_consulta_cerrada_en_la_web_rechaza_las_cargas_y_no_las_da_por_enviadas() = conServidor(habilitadas = e3) {
        creada()
        servidor.consultas.getValue(7001).estado = "CERRADA"
        db.sembrarCarga("l1", "u1")

        envio.empujarYa("u1").join()

        assertEquals(setOf("l1"), db.cargasSucias("u1"))
        assertEquals(CausaDeRechazo.CONSULTA_CERRADA, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_CARGAS).estado).causa)
        assertEquals(emptyMap(), servidor.cargas, "la web no escribió nada")
        assertEquals("", db.cargaLocal("l1")!!.remotoId)
    }

    @Test
    fun sin_conexion_deja_en_espera_las_cargas_y_el_cierre_y_sale_solo_al_volver() = conServidor(habilitadas = e3) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarCarga("l1", "u1")
        servidor.falla = { if (it == "laboratorio") TobbException(0, "red", "Sin conexión") else null }

        envio.empujarYa("u1").join()

        assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CARGAS).estado).causa)
        assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CIERRE).estado)
        assertEquals(setOf("l1"), db.cargasSucias("u1"), "no se pierde nada")

        servidor.falla = null
        reloj.avanzar(15_000)

        assertEquals(emptySet<String>(), db.cargasSucias("u1"))
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(listOf("laboratorio 7001 l:l1", "laboratorio 7001 l:l1", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(1, servidor.cargas.size, "el reintento no duplica")
    }

    // ---------- Sesión ----------

    @Test
    fun con_la_sesion_de_otro_medico_no_sale_nada_ni_se_modifica_nada() = conServidor(habilitadas = e3) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarCarga("l1", "u1")
        val antes = db.filasSucias()

        medicoDeLaSesion = "otro-medico"
        envio.empujarYa("u1").join()
        envio.marcarSucia("u1")
        reloj.avanzar(3_600_000)

        assertEquals(emptyList(), servidor.pedidos)
        assertEquals(antes, db.filasSucias())
        assertEquals(setOf("l1"), db.cargasSucias("u1"))
        assertEquals(emptyMap(), db.estadosDe("u1"))

        medicoDeLaSesion = MEDICO
        envio.empujarYa("u1").join()

        assertEquals(listOf("laboratorio 7001 l:l1", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
    }

    // ---------- Lo que cambió mientras viajaba (RF-55) ----------

    @Test
    fun una_carga_cambiada_en_vuelo_no_se_da_por_enviada_y_sale_en_el_envio_siguiente() = conServidor(habilitadas = e3) {
        creada()
        db.sembrarCarga("l1", "u1", valores = mapOf("hb" to "13"), actualizadaEn = 150)
        db.sembrarCarga("l2", "u1", valores = mapOf("hb" to "14"), actualizadaEn = 155)
        servidor.alRecibir = { operacion ->
            if (operacion == "laboratorio" && servidor.cargas.size == 1) db.escribirCarga("l1", mapOf("hb" to "11"), actualizadaEn = 500)
        }

        envio.empujarYa("u1").join()

        assertEquals(setOf("l1"), db.cargasSucias("u1"), "la que cambió en vuelo sigue pendiente; la otra, no")
        assertEquals(155, db.cargaActualizadaEn("l2"), "la limpieza no le mueve el updated_at")
        assertEquals(500, db.cargaActualizadaEn("l1"), "el cambio en vuelo conserva su updated_at")
        val id = db.cargaLocal("l1")!!.remotoId
        assertEquals("13", db.loEnviadoDe("u1").cargas.getValue(id)["hb"], "la web tiene lo que viajó, no lo nuevo")
        assertEquals("13", servidor.cargas.getValue(id.toLong()).valores["hb"])

        servidor.alRecibir = null
        envio.empujarYa("u1").join()

        assertEquals(
            listOf("laboratorio 7001 l:l1", "laboratorio 7001 l:l2", "laboratorio 7001 l:l1"),
            servidor.pedidos,
            "el segundo envío lleva solo lo nuevo de la que se editó",
        )
        assertEquals("11", servidor.cargas.getValue(id.toLong()).valores["hb"])
        assertFalse(db.cargaSucia("l1") || db.cargaSucia("l2"))
        assertEquals("11", db.loEnviadoDe("u1").cargas.getValue(id)["hb"])
        assertEquals(2, servidor.cargas.size, "sin una fila nueva para la que se editó")
    }

    // ---------- El contador y el candado ----------

    @Test
    fun contar_pendientes_cuenta_laboratorio_y_peso_cuando_la_version_los_envia() = conServidor(habilitadas = e3) {
        creada()
        db.sembrarPropia("u2", remotoId = "7002", dirty = false)
        servidor.consultas[7002] = ServidorFalso.ConsultaWeb(7002, "c:u2", 5001, "control")
        db.sembrarCarga("l1", "u1")
        db.sembrarCarga("p1", "u2", tipo = TIPO_PESO, valores = mapOf("peso" to "72.5"))

        assertEquals(setOf("u1", "u2"), envio.contarPendientes().consultas.map { it.consultaId }.toSet(), "el laboratorio y el peso son secciones de E3")

        envio.empujarYa("u1").join()
        envio.empujarYa("u2").join()

        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun con_un_conjunto_que_no_incluye_e3_las_cargas_no_salen_ni_frenan_el_cierre() = conServidor(habilitadas = seccionesDe(SubentregaDeEscritura.E2)) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarCarga("l1", "u1")

        envio.empujarYa("u1").join()

        assertEquals(listOf("cerrar 7001 c:u1-cierre"), servidor.pedidos, "sin las secciones en el conjunto, las cargas esperan y el cierre sale")
        assertEquals(setOf("l1"), db.cargasSucias("u1"), "siguen sucias, para cuando se habiliten (RF-4)")
        assertEquals(0, envio.contarPendientes().total)
    }
}
