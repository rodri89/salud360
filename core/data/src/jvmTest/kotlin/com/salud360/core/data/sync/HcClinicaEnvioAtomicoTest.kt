package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.network.TobbException
import com.salud360.core.model.hc.EstadoConsulta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005, T25 (RF-61): un corte entre «el servidor confirmó» y «el dispositivo lo anotó» no deja nada a medias ni duplicado. Cada
 * paso confirmado se anota en **una sola transacción** (el id de la web, la limpieza de lo enviado, lo que la web confirmó y el estado):
 * o queda todo anotado o no queda nada. El corte se simula con un `TRIGGER` de la base de prueba que aborta una de las escrituras a
 * mitad de la anotación (equivale a que la app se muera ahí). Como no quedó anotado, el reenvío repite lo mismo y el servidor, que
 * recuerda las claves, contesta lo mismo sin duplicar.
 */
class HcClinicaEnvioAtomicoTest {

    /** Aborta al dejar la fila de una consulta sin pendiente (`dirty = 0`): la última escritura de la anotación de la creación y del cierre. */
    private fun Escena.cortarAlLimpiarLaConsulta() {
        driver.execute(
            null,
            "CREATE TRIGGER corte BEFORE UPDATE OF dirty ON consulta WHEN NEW.dirty = 0 BEGIN SELECT RAISE(ABORT, 'corte simulado'); END",
            0,
        ).value
    }

    /** Aborta al guardar lo que la web confirmó (`hce_enviado_…`): a mitad de la anotación de los textos y del examen. */
    private fun Escena.cortarAlGuardarLoConfirmado() {
        driver.execute(
            null,
            "CREATE TRIGGER corte BEFORE INSERT ON paciente_extra WHEN NEW.clave LIKE 'hce_enviado_%' BEGIN SELECT RAISE(ABORT, 'corte simulado'); END",
            0,
        ).value
    }

    private fun Escena.repararElCorte() {
        driver.execute(null, "DROP TRIGGER corte", 0).value
    }

    @Test
    fun un_corte_al_anotar_la_creacion_no_deja_el_id_sin_limpiar_ni_lo_limpio_sin_id() = conServidor {
        db.sembrarPropia("u1")
        cortarAlLimpiarLaConsulta()

        envio.empujarYa("u1").join()

        // El servidor la creó…
        assertEquals(1, servidor.cantidadDeConsultas)
        // …y el dispositivo quedó exactamente como estaba: ni el id de la web ni la limpieza (una sola transacción).
        assertEquals("", db.consultaLocal("u1")!!.remotoId, "el id de la web no quedó guardado a medias")
        assertTrue(db.consultaSucia("u1"))
        assertEquals(emptyMap(), db.estadosDe("u1"), "y un corte local no se toma por un error de la web")

        // Se arregla el corte y se reenvía: misma clave, misma consulta, sin duplicar.
        repararElCorte()
        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "crear c:u1"), servidor.pedidos)
        assertEquals(1, servidor.cantidadDeConsultas, "nada duplicado")
        assertEquals("7001", db.consultaLocal("u1")!!.remotoId)
        assertFalse(db.consultaSucia("u1"), "ni nada pendiente")
    }

    @Test
    fun un_corte_al_anotar_los_textos_los_deja_todos_pendientes_y_el_reenvio_no_duplica() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Una nota")
        db.sembrarTexto("u1", "habitos", "Unos hábitos")
        cortarAlGuardarLoConfirmado()

        envio.empujarYa("u1").join()

        assertEquals(mapOf("nota" to "Una nota", "habitos" to "Unos hábitos"), servidor.consultas.getValue(7001).secciones)
        assertEquals(setOf("nota/texto", "habitos/texto"), db.textosSucios("u1"), "ninguno quedó marcado como enviado: o todos o ninguno")
        assertEquals(LoEnviado(), db.loEnviadoDe("u1"), "ni anotado lo que la web confirmó")

        repararElCorte()
        envio.empujarYa("u1").join()

        assertEquals(listOf("secciones 7001 habitos,nota", "secciones 7001 habitos,nota"), servidor.pedidos)
        assertEquals(2, servidor.textosEnviados.size)
        assertEquals(servidor.textosEnviados[0], servidor.textosEnviados[1], "el reenvío es idéntico")
        assertEquals(mapOf("nota" to "Una nota", "habitos" to "Unos hábitos"), servidor.consultas.getValue(7001).secciones, "sin duplicar nada")
        assertEquals(emptySet(), db.textosSucios("u1"))
        assertEquals(
            mapOf("nota/texto" to "Una nota", "habitos/texto" to "Unos hábitos"),
            db.loEnviadoDe("u1").valores,
            "y ahora sí queda anotado lo que la web confirmó",
        )
    }

    @Test
    fun un_corte_al_anotar_el_examen_lo_deja_pendiente_y_sin_anotar() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarExamen("u1", peso = "70", talla = "1.70")
        cortarAlGuardarLoConfirmado()

        envio.empujarYa("u1").join()

        assertTrue(db.examenSucio("u1"))
        assertEquals(emptyMap(), db.loEnviadoDe("u1").examen)

        repararElCorte()
        envio.empujarYa("u1").join()

        assertEquals(listOf("examen 7001 peso,talla", "examen 7001 peso,talla"), servidor.pedidos)
        assertEquals(mapOf("peso" to "70", "talla" to "1.70"), servidor.consultas.getValue(7001).examen)
        assertFalse(db.examenSucio("u1"))
        assertEquals(mapOf("peso" to "70", "talla" to "1.70"), db.loEnviadoDe("u1").examen)
    }

    @Test
    fun un_corte_al_anotar_el_cierre_deja_la_fila_sucia_y_el_reenvio_repite_la_clave_del_cierre() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.CERRADA)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        cortarAlLimpiarLaConsulta()

        envio.empujarYa("u1").join()

        assertEquals("CERRADA", servidor.consultas.getValue(7001).estado)
        assertTrue(db.consultaSucia("u1"), "el dispositivo no se enteró de que se cerró")

        repararElCorte()
        envio.empujarYa("u1").join()

        assertEquals(listOf("c:u1-cierre", "c:u1-cierre"), servidor.cierres.map { it.clave })
        assertEquals("c:u1-cierre", servidor.consultas.getValue(7001).claveDeCierre, "se cerró una sola vez")
        assertFalse(db.consultaSucia("u1"))
    }

    @Test
    fun un_corte_al_anotar_la_baja_deja_la_fila_sucia_y_la_baja_se_repite_sin_duplicar() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.ANULADA)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        cortarAlLimpiarLaConsulta()

        envio.empujarYa("u1").join()

        assertTrue(servidor.consultas.getValue(7001).baja)
        assertTrue(db.consultaSucia("u1"))

        repararElCorte()
        envio.empujarYa("u1").join()

        assertEquals(listOf("baja 7001 c:u1-baja", "baja 7001 c:u1-baja"), servidor.pedidos)
        assertFalse(db.consultaSucia("u1"))
    }

    @Test
    fun una_consulta_cerrada_no_pierde_su_pendiente_de_cierre_al_anotar_la_creacion() = conServidor {
        db.sembrarPropia("u1", estado = EstadoConsulta.CERRADA)
        servidor.falla = { if (it == "cerrar") TobbException(0, "red", "Sin conexión") else null }

        envio.empujarYa("u1").join()

        // La creación quedó anotada (con su id) y, en la misma transacción, la fila NO se limpió porque falta el cierre.
        assertEquals("7001", db.consultaLocal("u1")!!.remotoId)
        assertTrue(db.consultaSucia("u1"))
    }
}
