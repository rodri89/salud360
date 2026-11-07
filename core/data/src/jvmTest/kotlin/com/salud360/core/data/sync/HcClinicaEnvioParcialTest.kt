package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_EXAMEN
import com.salud360.core.data.hcgeneral.PARTE_SECCIONES
import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.EstadosDeEnvio
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T25 (RF-32): cuando un envío es **parcial** (llegó una parte y otra no), solo se marca como enviado lo que la web
 * confirmó y el resto queda pendiente. La web confirma en `guardadas` y lo que no conoce lo informa en `desconocidas`: nunca se da
 * por enviado lo que no figura en `guardadas`. Lo que no se confirmó queda «No se pudo enviar» (la web no lo acepta) y frena el cierre.
 */
class HcClinicaEnvioParcialTest {

    @Test
    fun de_los_textos_solo_se_marca_enviado_lo_que_la_web_confirmo_y_el_resto_queda_pendiente() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.CERRADA)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Una nota")
        db.sembrarTexto("u1", "habitos", "Unos hábitos")
        db.sembrarTexto("u1", "motivo_consulta", "Un motivo")
        servidor.noConoce = setOf("habitos")

        envio.empujarYa("u1").join()

        assertEquals(setOf("habitos/texto"), db.textosSucios("u1"), "los dos confirmados quedan limpios y el que no, pendiente")
        assertEquals(
            mapOf("nota/texto" to "Una nota", "motivo_consulta/texto" to "Un motivo"),
            db.loEnviadoDe("u1").valores,
            "y solo lo confirmado figura como lo que tiene la web",
        )
        assertEquals(mapOf("nota" to "Una nota", "motivo_consulta" to "Un motivo"), servidor.consultas.getValue(7001).secciones)
        // El cierre no sale: falta una parte. Y la parte queda marcada para que el médico lo vea.
        assertEquals(listOf("secciones 7001 habitos,motivo_consulta,nota"), servidor.pedidos)
        val estado = assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado)
        assertEquals(CausaDeRechazo.OTRO, estado.causa)
        assertTrue("habitos" in estado.motivo, "el motivo dice qué no se guardó: ${estado.motivo}")
        assertTrue(db.consultaSucia("u1"))
        assertEquals(1, envio.contarPendientes().noSePudoEnviar)
    }

    @Test
    fun lo_no_confirmado_no_se_reenvia_solo_ni_deja_salir_el_cierre() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.CERRADA)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota")
        db.sembrarTexto("u1", "habitos")
        servidor.noConoce = setOf("habitos")
        envio.empujarYa("u1").join()

        repeat(3) { envio.empujarYa("u1").join() }

        assertEquals(1, servidor.pedidos.size, "ni vuelve a mandar lo rechazado ni cierra la consulta")
        assertEquals(setOf("habitos/texto"), db.textosSucios("u1"))
    }

    @Test
    fun del_examen_se_anotan_los_campos_confirmados_y_la_fila_sigue_pendiente() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarExamen("u1", peso = "70", talla = "1.70")
        servidor.noConoce = setOf("talla")

        envio.empujarYa("u1").join()

        assertEquals(mapOf("peso" to "70"), servidor.consultas.getValue(7001).examen)
        assertEquals(mapOf("peso" to "70"), db.loEnviadoDe("u1").examen, "solo el peso figura como confirmado")
        assertTrue(db.examenSucio("u1"), "la fila queda pendiente: falta la talla")
        val estado = assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_EXAMEN).estado)
        assertTrue("talla" in estado.motivo, estado.motivo)
    }

    @Test
    fun si_la_web_no_confirma_nada_no_se_marca_nada_como_enviado() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota")
        servidor.noConoce = setOf("nota")

        envio.empujarYa("u1").join()

        assertEquals(setOf("nota/texto"), db.textosSucios("u1"))
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"))
    }

    @Test
    fun un_envio_completo_limpia_todo_y_quita_el_estado_que_tenia() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota")
        db.sembrarExamen("u1", peso = "70")
        // Venía «En espera» de un intento anterior.
        val espera = EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "sin conexión")
        EstadosDeEnvio(db).guardar(PACIENTE, "u1", PARTE_SECCIONES, espera, FECHA_DE_PRUEBA)
        EstadosDeEnvio(db).guardar(PACIENTE, "u1", PARTE_EXAMEN, espera, FECHA_DE_PRUEBA)

        envio.empujarYa("u1").join()

        assertEquals(emptyMap(), db.estadosDe("u1"), "al llegar, ya no está «En espera»")
        assertEquals(emptySet(), db.textosSucios("u1"))
        assertEquals(false, db.examenSucio("u1"))
        assertNull(db.estadosDe("u1")[PARTE_SECCIONES])
    }

    @Test
    fun una_consulta_cerrada_en_la_web_rechaza_el_texto_y_no_lo_da_por_enviado() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control").also { it.estado = "CERRADA" }
        db.sembrarTexto("u1", "nota")
        servidor.falla = null

        envio.empujarYa("u1").join()

        assertEquals(setOf("nota/texto"), db.textosSucios("u1"))
        assertEquals(CausaDeRechazo.CONSULTA_CERRADA, assertIs<EstadoEnvio.NoSePudoEnviar>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado).causa)
    }

    @Test
    fun un_error_que_no_es_de_la_api_se_trata_como_una_respuesta_ilegible_y_no_pierde_nada() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota")
        servidor.falla = { IllegalStateException("JSON cortado") }

        envio.empujarYa("u1").join()

        assertEquals(CausaEnEspera.SERVIDOR, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_SECCIONES).estado).causa)
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"))
    }

    @Test
    fun el_estado_guarda_el_dia_del_reloj_inyectado() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        servidor.falla = { TobbException(0, "red", "Sin conexión") }

        envio.empujarYa("u1").join()

        // `Escena` arma la cola con un reloj que arranca en noviembre de 2026.
        assertEquals("2026", db.estadosDe("u1").getValue("crear").desde.toString().take(4))
    }
}
