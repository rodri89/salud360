package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.PARTE_REGISTROS
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec 005 RF-15, RF-32, RF-39, RF-55, RF-56, RF-61, RF-70 (T63-b): la cola de envío manda los **cinco estudios** de una consulta propia a
 * `PUT consultas/{id}/registros`, con lo que arma el plan (T62-b) y lo último que la web confirmó (`LoEnviado.registroEnviado`). Un solo pedido por paso,
 * después de los antecedentes y antes del cierre; el `ids` de la respuesta deja el `remoto_id` de cada fila en la **misma transacción** que la limpia; lo
 * que la web no confirma no se limpia ni figura como enviado y la parte queda «No se pudo enviar»; una respuesta perdida se repite con los mismos `ref`
 * sin duplicar; lo que cambió mientras viajaba sigue pendiente. Contra el [ServidorFalso] con memoria y con la subentrega E2 habilitada
 * **explícitamente** (el candado de la versión —`SECCIONES_HABILITADAS`— habilita desde T67 reducida solo una parte de E2, así que acá se pasa el conjunto completo). Los exámenes complementarios y las interconsultas quedan fuera de alcance (T52-b) y no llegan.
 */
class HcClinicaEnvioRegistrosTest {
    private val e2 = seccionesDe(SubentregaDeEscritura.E2)

    /** Una propia ya creada en la web (la 7001), sin nada sucio más que lo que siembre cada test. */
    private suspend fun Escena.creada(estado: EstadoConsulta = EstadoConsulta.ABIERTA, dirty: Boolean = false) {
        db.sembrarPropia("u1", remotoId = "7001", estado = estado, dirty = dirty)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
    }

    private fun rechazo422() = TobbException(422, "datos_invalidos", "Datos que la web no acepta")

    private fun json(texto: String) = Json.parseToJsonElement(texto) as JsonObject

    private fun ServidorFalso.cuerpoDelUltimoPedidoDeRegistros() = cuerposDeRegistros.last()

    // ---------- Un pedido, en su lugar ----------

    @Test
    fun los_registros_van_entre_los_antecedentes_y_el_cierre() = conServidor(habilitadas = e2) {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        db.sembrarTexto("u1")
        db.sembrarExamen("u1", peso = "70")
        db.sembrarFormulario("u1", "serologias", "hiv", "NO")
        db.sembrarAntecedente("a1", "u1", clave = "hta")
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")

        envio.empujarYa("u1").join()

        assertEquals(
            listOf(
                "crear c:u1", "secciones 7001 nota", "examen 7001 peso", "formularios 7001 serologias.hiv",
                "antecedentes 5001 7001 hta", "registros 7001 r:r-eco", "cerrar 7001 c:u1-cierre",
            ),
            servidor.pedidos,
        )
        assertEquals(emptyList(), db.filasSucias().flatten())
    }

    @Test
    fun un_estudio_nuevo_se_da_de_alta_con_fecha_y_detalle_y_queda_con_su_id_de_la_web() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso", actualizadaEn = 140)

        envio.empujarYa("u1").join()

        assertEquals(listOf("registros 7001 r:r-eco"), servidor.pedidos)
        val enLaWeb = servidor.registros.values.single()
        assertEquals("eco_abdominal", enLaWeb.tipo)
        assertEquals("2026-11-06", enLaWeb.fecha)
        assertEquals("hígado graso", enLaWeb.campos["detalle"])
        val fila = db.registroLocal("r-eco")!!
        assertEquals(enLaWeb.id.toString(), fila.remotoId, "el id que contestó la web queda en la fila")
        assertFalse(db.registroSucio("r-eco"), "y la fila se limpia")
        assertEquals(140, db.registroActualizadoEn("r-eco"), "sin mover el updated_at (RF-55)")
        assertEquals(
            mapOf(enLaWeb.id.toString() to mapOf("fecha" to "2026-11-06", "detalle" to "hígado graso")),
            db.loEnviadoDe("u1").registros,
            "lo confirmado queda anotado por estudio",
        )
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun el_cuerpo_lleva_la_fecha_y_el_detalle_que_armo_el_plan_y_el_id_si_la_web_ya_lo_tenia() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso", remotoId = "7010")

        envio.empujarYa("u1").join()

        assertEquals(
            json("""{"items":[{"ref":"r:r-eco","tipo":"eco_abdominal","id":"7010","fecha":"2026-11-06","campos":{"detalle":"hígado graso"}}]}"""),
            servidor.cuerpoDelUltimoPedidoDeRegistros(),
            "el servidor exige la fecha; el id va cuando se conoce",
        )
        assertFalse("borrado" in servidor.cuerpoDelUltimoPedidoDeRegistros().toString(), "un estudio nunca se da de baja (Q-E2-2)")
        assertEquals("7010", db.registroLocal("r-eco")!!.remotoId)
    }

    @Test
    fun la_edicion_manda_solo_el_estudio_que_cambio() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        db.sembrarRegistro("r-veda", "u1", "veda", detalle = "várices I")

        envio.empujarYa("u1").join()
        val eco = db.registroLocal("r-eco")!!
        assertTrue(eco.remotoId.isNotBlank() && db.registroLocal("r-veda")!!.remotoId.isNotBlank())

        db.escribirRegistro("r-eco", detalle = "hígado con esteatosis", actualizadaEn = 500)
        envio.empujarYa("u1").join()

        assertEquals(listOf("registros 7001 r:r-eco,r:r-veda", "registros 7001 r:r-eco"), servidor.pedidos, "solo el que cambió")
        assertEquals("hígado con esteatosis", servidor.registros.getValue(eco.remotoId.toLong()).campos["detalle"])
        assertEquals("várices I", servidor.registros.values.single { it.tipo == "veda" }.campos["detalle"], "el otro no se tocó")
        assertFalse(db.registroSucio("r-eco") || db.registroSucio("r-veda"))
    }

    @Test
    fun un_estudio_vaciado_a_proposito_se_manda_vacio_y_queda_vaciado_sin_baja() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        envio.empujarYa("u1").join()
        val id = db.registroLocal("r-eco")!!.remotoId

        db.escribirRegistro("r-eco", detalle = "", actualizadaEn = 500)
        envio.empujarYa("u1").join()

        assertEquals("", servidor.registros.getValue(id.toLong()).campos["detalle"], "el vaciado a propósito llega vacío")
        assertTrue(servidor.registros.getValue(id.toLong()).activo, "y la fila de la web no se da de baja (Q-E2-2)")
        assertEquals("", db.loEnviadoDe("u1").registros.getValue(id)["detalle"])
        assertFalse(db.registroSucio("r-eco"))
        assertTrue(db.registroLocal("r-eco")!!.activo)
        assertEquals(1, servidor.registros.size, "sigue siendo una sola fila")
        assertFalse("borrado" in servidor.cuerpoDelUltimoPedidoDeRegistros().toString())
    }

    @Test
    fun un_estudio_igual_a_lo_que_la_web_tiene_no_se_manda_y_se_limpia() = conServidor(habilitadas = e2) {
        creada()
        LoEnviadoGuardado(db).guardar(
            PACIENTE, "u1",
            LoEnviado(registros = mapOf("7010" to mapOf("fecha" to "2026-11-06", "detalle" to "hígado graso"))),
        )
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso", remotoId = "7010")

        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos, "lo que ya está igual en la web no sale")
        assertFalse(db.registroSucio("r-eco"), "y se limpia")
        assertEquals(
            mapOf("7010" to mapOf("fecha" to "2026-11-06", "detalle" to "hígado graso")),
            db.loEnviadoDe("u1").registros,
            "sin cambiar lo confirmado",
        )
    }

    // ---------- Respuesta perdida y reenvío (RF-15) ----------

    @Test
    fun si_se_pierde_la_respuesta_el_reenvio_repite_los_mismos_ref_y_no_duplica_nada() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        db.sembrarRegistro("r-veda", "u1", "veda", detalle = "várices I")
        servidor.respuestasPerdidas += "registros"

        envio.empujarYa("u1").join()

        // El servidor lo aplicó, pero el dispositivo no se enteró: las filas siguen sucias, sin id y sin anotar.
        assertEquals(2, servidor.registros.size)
        assertTrue(db.registroSucio("r-eco") && db.registroSucio("r-veda"))
        assertEquals("", db.registroLocal("r-eco")!!.remotoId)
        assertEquals(emptyMap(), db.loEnviadoDe("u1").registros)
        assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_REGISTROS).estado).causa)

        val idDelEco = servidor.registros.values.single { it.tipo == "eco_abdominal" }.id
        servidor.respuestasPerdidas.clear()
        envio.empujarYa("u1").join()

        assertEquals(List(2) { "registros 7001 r:r-eco,r:r-veda" }, servidor.pedidos, "se repiten los mismos ref")
        assertEquals(servidor.cuerposDeRegistros[0], servidor.cuerposDeRegistros[1], "byte a byte: el mismo cuerpo")
        assertEquals(2, servidor.registros.size, "y no nace ninguna fila nueva")
        assertEquals(idDelEco.toString(), db.registroLocal("r-eco")!!.remotoId, "el id de siempre")
        assertFalse(db.registroSucio("r-eco") || db.registroSucio("r-veda"))
        assertEquals(2, db.loEnviadoDe("u1").registros.size)
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    // ---------- Parcial (T25 (b)) ----------

    @Test
    fun de_los_estudios_solo_se_limpia_y_se_anota_lo_que_la_web_confirmo() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        db.sembrarRegistro("r-veda", "u1", "veda", detalle = "várices I")
        db.sembrarRegistro("r-tc", "u1", "tc_rmn", detalle = "sin particularidades")
        servidor.noConoce = setOf("r:r-veda")

        envio.empujarYa("u1").join()

        assertEquals(setOf("r-veda"), db.registrosSucios("u1"), "lo confirmado queda limpio y lo que la web no guardó, pendiente")
        val anotado = db.loEnviadoDe("u1").registros
        assertEquals(2, anotado.size, "y solo lo confirmado figura como lo que tiene la web")
        assertTrue(anotado.values.any { it["detalle"] == "hígado graso" } && anotado.values.any { it["detalle"] == "sin particularidades" })
        assertEquals(2, servidor.registros.size)
        assertEquals("", db.registroLocal("r-veda")!!.remotoId, "una fila sin confirmar no recibe id")
        assertEquals(listOf("registros 7001 r:r-eco,r:r-tc,r:r-veda"), servidor.pedidos, "y el cierre no sale")
        val estado = assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_REGISTROS).estado)
        assertEquals(CausaDeRechazo.OTRO, estado.causa)
        assertTrue("r:r-veda" in estado.motivo, "el motivo dice qué no se guardó: ${estado.motivo}")
        assertTrue(db.consultaSucia("u1"))
        assertEquals(1, envio.contarPendientes().noSePudoEnviar)
    }

    @Test
    fun lo_que_la_web_no_conoce_no_se_limpia_ni_se_anota_y_no_deja_salir_el_cierre() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        servidor.noConoce = setOf("r:r-eco")

        envio.empujarYa("u1").join()
        repeat(3) { envio.empujarYa("u1").join() }
        reloj.avanzar(3_600_000)

        assertEquals(listOf("registros 7001 r:r-eco"), servidor.pedidos, "ni se reenvía solo lo rechazado ni se cierra")
        assertEquals(setOf("r-eco"), db.registrosSucios("u1"))
        assertEquals(emptyMap(), db.loEnviadoDe("u1").registros)
        assertEquals(emptyMap(), servidor.registros)
    }

    // ---------- Rechazos ----------

    @Test
    fun un_422_deja_no_se_pudo_enviar_y_no_se_reintenta_solo() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        servidor.falla = { if (it == "registros") rechazo422() else null }

        envio.empujarYa("u1").join()
        val pedidos = servidor.pedidos.size
        repeat(3) { envio.empujarYa("u1").join() }
        reloj.avanzar(3_600_000)

        assertEquals(CausaDeRechazo.DATOS_INVALIDOS, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_REGISTROS).estado).causa)
        assertEquals(pedidos, servidor.pedidos.size, "ni solo ni en una hora")
        assertEquals(0, reloj.esperando)
        assertEquals(setOf("r-eco"), db.registrosSucios("u1"), "el dato sigue en el dispositivo")
        assertEquals(emptyMap(), db.loEnviadoDe("u1").registros)
        assertTrue(db.consultaSucia("u1"), "y el cierre espera")
        assertFalse("cerrar" in servidor.operaciones)
    }

    @Test
    fun una_consulta_cerrada_en_la_web_rechaza_los_estudios_y_no_los_da_por_enviados() = conServidor(habilitadas = e2) {
        creada()
        servidor.consultas.getValue(7001).estado = "CERRADA"
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")

        envio.empujarYa("u1").join()

        assertEquals(setOf("r-eco"), db.registrosSucios("u1"))
        assertEquals(CausaDeRechazo.CONSULTA_CERRADA, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_REGISTROS).estado).causa)
        assertEquals(emptyMap(), servidor.registros, "la web no escribió nada")
        assertEquals("", db.registroLocal("r-eco")!!.remotoId)
    }

    @Test
    fun reintentar_a_mano_vuelve_a_mandar_lo_rechazado_y_el_cierre_sale() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        var rechazar = true
        servidor.falla = { if (it == "registros" && rechazar) rechazo422() else null }
        envio.empujarYa("u1").join()

        rechazar = false
        envio.reintentar("u1").join()

        assertEquals(listOf("registros 7001 r:r-eco", "registros 7001 r:r-eco", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(emptyList(), db.filasSucias().flatten())
        assertEquals(1, servidor.registros.size, "sin duplicar el estudio")
    }

    // ---------- Sesión ----------

    @Test
    fun con_la_sesion_de_otro_medico_no_sale_nada_ni_se_modifica_nada() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
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

        assertEquals(listOf("registros 7001 r:r-eco", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
    }

    // ---------- Lo que cambió mientras viajaba (RF-55) ----------

    @Test
    fun un_estudio_cambiado_en_vuelo_no_se_da_por_enviado_y_sale_en_el_envio_siguiente() = conServidor(habilitadas = e2) {
        creada()
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso", actualizadaEn = 140)
        db.sembrarRegistro("r-veda", "u1", "veda", detalle = "várices I", actualizadaEn = 145)
        servidor.alRecibir = { operacion ->
            if (operacion == "registros") db.escribirRegistro("r-eco", detalle = "hígado con esteatosis", actualizadaEn = 500)
        }

        envio.empujarYa("u1").join()

        assertEquals(setOf("r-eco"), db.registrosSucios("u1"), "el eco cambió en vuelo: sigue pendiente; la veda, no")
        assertEquals(145, db.registroActualizadoEn("r-veda"), "la limpia no le mueve el updated_at")
        val eco = db.registroLocal("r-eco")!!
        assertEquals("hígado graso", db.loEnviadoDe("u1").registros.getValue(eco.remotoId)["detalle"], "la web tiene lo que viajó, no lo nuevo")
        assertEquals("hígado graso", servidor.registros.getValue(eco.remotoId.toLong()).campos["detalle"])
        assertEquals(500, db.registroActualizadoEn("r-eco"), "el cambio en vuelo conserva su updated_at")

        servidor.alRecibir = null
        envio.empujarYa("u1").join()

        assertEquals(listOf("registros 7001 r:r-eco,r:r-veda", "registros 7001 r:r-eco"), servidor.pedidos, "el segundo envío lleva solo lo nuevo")
        assertEquals("hígado con esteatosis", servidor.registros.getValue(eco.remotoId.toLong()).campos["detalle"])
        assertFalse(db.registroSucio("r-eco") || db.registroSucio("r-veda"))
        assertEquals("hígado con esteatosis", db.loEnviadoDe("u1").registros.getValue(eco.remotoId)["detalle"])
        assertEquals(2, servidor.registros.size, "los dos estudios, sin una fila nueva para el que se editó")
        assertEquals(1, servidor.registros.values.count { it.tipo == "eco_abdominal" })
    }

    @Test
    fun sin_conexion_deja_en_espera_los_estudios_y_lo_que_sigue_y_sale_solo_al_volver() = conServidor(habilitadas = e2) {
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        servidor.falla = { if (it == "registros") TobbException(0, "red", "Sin conexión") else null }

        envio.empujarYa("u1").join()

        assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_REGISTROS).estado).causa)
        assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CIERRE).estado)
        assertEquals(setOf("r-eco"), db.registrosSucios("u1"), "no se pierde nada")

        servidor.falla = null
        reloj.avanzar(15_000)

        assertEquals(emptySet<String>(), db.registrosSucios("u1"))
        assertEquals(emptyMap(), db.estadosDe("u1"))
        assertEquals(listOf("registros 7001 r:r-eco", "registros 7001 r:r-eco", "cerrar 7001 c:u1-cierre"), servidor.pedidos)
        assertEquals(1, servidor.registros.size, "el reintento no duplica")
    }

    // ---------- El candado ----------

    @Test
    fun con_un_conjunto_que_no_incluye_estudios_no_salen_ni_frenan_el_cierre() = conServidor(habilitadas = seccionesDe(SubentregaDeEscritura.E1)) {
        // Los cinco estudios se habilitaron por decisión del usuario (T67 reducida); el candado por conjunto se sigue probando con el de E1 explícito.
        creada(estado = EstadoConsulta.CERRADA, dirty = true)
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")

        envio.empujarYa("u1").join()

        assertEquals(listOf("cerrar 7001 c:u1-cierre"), servidor.pedidos, "sin las secciones en el conjunto, los estudios esperan y el cierre sale")
        assertEquals(setOf("r-eco"), db.registrosSucios("u1"), "siguen sucios, para cuando se habiliten")
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun con_un_conjunto_propio_solo_sale_la_seccion_habilitada() = conServidor(habilitadas = seccionesDe(SubentregaDeEscritura.E1) + setOf("veda")) {
        creada()
        db.sembrarRegistro("r-eco", "u1", "eco_abdominal", detalle = "hígado graso")
        db.sembrarRegistro("r-veda", "u1", "veda", detalle = "várices I")

        envio.empujarYa("u1").join()

        assertEquals(listOf("registros 7001 r:r-veda"), servidor.pedidos)
        assertEquals(setOf("r-eco"), db.registrosSucios("u1"), "el eco sigue apagado aunque sea de la misma subentrega")
    }
}
