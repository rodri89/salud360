package com.salud360.core.data.sync

import com.salud360.core.data.lista
import com.salud360.core.data.network.TobbException
import com.salud360.core.model.hc.EstadoConsulta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005, T25 (RF-55, D27): si el médico cambia algo **mientras viaja** un envío, la confirmación no lo da por enviado. Solo se
 * limpia lo que viajó y no cambió desde que se armó el envío (se compara el `updated_at` de cada fila), y lo nuevo sale en el envío
 * siguiente. Lo que la web confirmó se anota igual como «lo último que tiene la web» (`LoEnviado`): así el envío siguiente compara
 * contra eso y un vaciado a propósito sale vacío (RF-69) mientras un vacío que el médico no tocó no sale nunca. El cambio en vuelo se
 * simula con `ServidorFalso.alRecibir`, que corre con el servidor ya aplicado y antes de contestar.
 */
class HcClinicaEnvioEnVueloTest {

    @Test
    fun un_texto_cambiado_en_vuelo_no_se_da_por_enviado_y_sale_en_el_envio_siguiente() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Primera versión")
        db.sembrarTexto("u1", "habitos", "Hábitos")
        servidor.alRecibir = { operacion ->
            if (operacion == "secciones") db.escribirTexto("u1", "nota", "Segunda versión", actualizadaEn = 500)
        }

        envio.empujarYa("u1").join()

        assertEquals(setOf("nota/texto"), db.textosSucios("u1"), "la nota cambió en vuelo: sigue pendiente; los hábitos, no")
        assertEquals(
            "Segunda versión",
            db.historiaClinicaQueries.valoresDeConsulta("u1").lista { it }.single { it.seccion == "nota" }.valor,
            "y lo que escribió el médico no se pisó",
        )
        assertEquals("Primera versión", db.loEnviadoDe("u1").valores.getValue("nota/texto"), "la web tiene lo que viajó, no lo nuevo")

        servidor.alRecibir = null
        envio.empujarYa("u1").join()

        assertEquals(listOf("secciones 7001 habitos,nota", "secciones 7001 nota"), servidor.pedidos, "el segundo envío lleva solo lo nuevo")
        assertEquals("Segunda versión", servidor.consultas.getValue(7001).secciones.getValue("nota"))
        assertEquals(emptySet(), db.textosSucios("u1"))
        assertEquals("Segunda versión", db.loEnviadoDe("u1").valores.getValue("nota/texto"))
    }

    @Test
    fun un_campo_del_examen_cambiado_en_vuelo_sale_solo_en_el_envio_siguiente() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarExamen("u1", peso = "70")
        servidor.alRecibir = { operacion ->
            if (operacion == "examen") db.sembrarExamen("u1", peso = "75", talla = "1.70", actualizadaEn = 500)
        }

        envio.empujarYa("u1").join()

        assertTrue(db.examenSucio("u1"), "el examen cambió en vuelo: no se da por enviado")
        assertEquals(mapOf("peso" to "70"), db.loEnviadoDe("u1").examen)

        servidor.alRecibir = null
        envio.empujarYa("u1").join()

        assertEquals(mapOf("peso" to "70"), servidor.camposEnviados[0])
        assertEquals(mapOf("peso" to "75", "talla" to "1.70"), servidor.camposEnviados[1], "ahora sí, lo nuevo")
        assertFalse(db.examenSucio("u1"))
    }

    @Test
    fun una_consulta_cerrada_en_vuelo_mientras_se_crea_no_se_da_por_limpia_y_el_cierre_sale_despues() = conServidor {
        db.sembrarPropia("u1")
        servidor.alRecibir = { operacion ->
            // El médico cierra la consulta mientras viaja la creación (la pantalla guarda la fila entera, sin el id de la web todavía).
            if (operacion == "crear") db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA, actualizadaEn = 500)
        }

        envio.empujarYa("u1").join()

        assertEquals("7001", db.consultaLocal("u1")!!.remotoId, "la creación quedó anotada")
        assertTrue(db.consultaSucia("u1"), "pero la fila cambió en vuelo (ahora está cerrada): sigue pendiente")
        assertEquals(listOf("crear c:u1"), servidor.pedidos)

        servidor.alRecibir = null
        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "cerrar 7001 c:u1-cierre"), servidor.pedidos, "no se vuelve a crear: sale el cierre")
        assertFalse(db.consultaSucia("u1"))
    }

    @Test
    fun una_consulta_anulada_en_vuelo_mientras_se_crea_manda_la_baja_en_el_envio_siguiente() = conServidor {
        db.sembrarPropia("u1")
        servidor.alRecibir = { operacion ->
            if (operacion == "crear") db.sembrarPropia("u1", estado = EstadoConsulta.ANULADA, actualizadaEn = 500)
        }

        envio.empujarYa("u1").join()
        servidor.alRecibir = null
        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "baja 7001 c:u1-baja"), servidor.pedidos)
        assertFalse(db.consultaSucia("u1"))
    }

    // ---- LoEnviado: el vaciado a propósito y el vacío que no se tocó (RF-69, D27) ----

    @Test
    fun un_texto_vaciado_a_proposito_despues_de_enviarse_sale_vacio_y_se_cuenta_como_pendiente() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Algo escrito")
        envio.empujarYa("u1").join()
        assertEquals(0, envio.contarPendientes().total)

        db.escribirTexto("u1", "nota", "", actualizadaEn = 600) // el médico borró la nota a propósito

        assertEquals(1, envio.contarPendientes().total, "RF-69: es un cambio del médico y se cuenta")
        envio.empujarYa("u1").join()

        assertEquals(mapOf("nota" to ""), servidor.textosEnviados.last(), "sale vacío")
        assertEquals("", servidor.consultas.getValue(7001).secciones.getValue("nota"))
        assertEquals(emptySet(), db.textosSucios("u1"))
        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun un_vacio_que_el_medico_no_toco_no_sale_nunca() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "") // el autoguardado dejó la fila vacía sin que el médico escribiera nada

        assertEquals(0, envio.contarPendientes().total)
        envio.empujarYa("u1").join()

        assertEquals(emptyList(), servidor.pedidos, "RF-34, D27: nunca se manda un vacío que el médico no tocó")
        assertEquals(emptySet(), db.textosSucios("u1"), "y no queda como pendiente")
    }

    @Test
    fun el_texto_igual_al_que_ya_tiene_la_web_no_se_vuelve_a_mandar_ni_despues_de_reabrir_la_app() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Igual")
        envio.empujarYa("u1").join()

        // Se reabre la app (otra cola, la misma base) y el autoguardado reescribe lo mismo.
        db.escribirTexto("u1", "nota", "Igual", actualizadaEn = 700)
        nuevoEnvio().empujarYa("u1").join()

        assertEquals(1, servidor.pedidos.size, "lo que la web confirmó sobrevive a cerrar la app")
        assertEquals(emptySet(), db.textosSucios("u1"))
    }

    @Test
    fun lo_confirmado_se_anota_aunque_despues_falle_otra_parte() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Va")
        db.sembrarExamen("u1", peso = "70")
        servidor.falla = { if (it == "examen") TobbException(0, "red", "Sin conexión") else null }

        envio.empujarYa("u1").join()

        assertEquals(mapOf("nota/texto" to "Va"), db.loEnviadoDe("u1").valores, "el texto que llegó queda anotado")
        assertEquals(emptyMap(), db.loEnviadoDe("u1").examen, "y el examen que no llegó, no")
    }
}
