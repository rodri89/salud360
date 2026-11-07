package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.hcgeneral.PARTE_CREAR
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec 005, T25 (RF-15): un envío que se repite **no duplica nada** en la web. Si se pierde la respuesta de la creación (corte, sin
 * señal), el dispositivo no sabe que la consulta ya existe: el reenvío repite **la misma clave** (`c:<id local>`) y el servidor
 * contesta la misma consulta, sin una fila nueva. Lo mismo vale para el cierre y la baja (sus claves propias) y al reabrir la app.
 * Contra un servidor falso con memoria que, como el real, devuelve lo mismo para la misma clave.
 */
class HcClinicaEnvioRepetibleTest {

    @Test
    fun si_se_pierde_la_respuesta_de_la_creacion_el_reenvio_repite_la_misma_clave_y_queda_una_consulta() = conServidor {
        db.sembrarPropia("u1")
        servidor.respuestasPerdidas += "crear"

        envio.empujarYa("u1").join()

        // El servidor la creó, pero el dispositivo no se enteró: sigue sin id de la web, sucia y «En espera».
        assertEquals(1, servidor.cantidadDeConsultas)
        assertEquals("", db.consultaLocal("u1")!!.remotoId)
        assertTrue(db.consultaSucia("u1"))
        assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CREAR).estado).causa)

        servidor.respuestasPerdidas.clear()
        envio.empujarYa("u1").join()

        assertEquals(listOf("crear c:u1", "crear c:u1"), servidor.pedidos, "se repite con la misma clave")
        assertEquals(1, servidor.cantidadDeConsultas, "y sigue habiendo una sola consulta")
        assertEquals("7001", db.consultaLocal("u1")!!.remotoId, "el dispositivo se entera del id de siempre")
        assertFalse(db.consultaSucia("u1"))
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun la_clave_de_la_creacion_es_estable_entre_reintentos_y_entre_aperturas_de_la_app() = conServidor {
        db.sembrarPropia("u1")
        servidor.respuestasPerdidas += "crear"

        envio.empujarYa("u1").join()
        // Se cierra la app y se abre otra vez: otra cola, la misma base.
        servidor.respuestasPerdidas.clear()
        nuevoEnvio().empujarYa("u1").join()

        assertEquals(listOf("c:u1", "c:u1"), servidor.creaciones.map { it.clave })
        assertEquals(1, servidor.cantidadDeConsultas)
        assertEquals("7001", db.consultaLocal("u1")!!.remotoId)
    }

    @Test
    fun consultas_distintas_tienen_claves_distintas_y_no_se_pisan() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarPropia("u2")

        envio.empujarYa("u1").join()
        envio.empujarYa("u2").join()

        assertEquals(listOf("c:u1", "c:u2"), servidor.creaciones.map { it.clave })
        assertEquals(2, servidor.cantidadDeConsultas)
        assertEquals("7001", db.consultaLocal("u1")!!.remotoId)
        assertEquals("7002", db.consultaLocal("u2")!!.remotoId)
    }

    @Test
    fun si_se_pierde_la_respuesta_de_los_textos_el_reenvio_los_manda_otra_vez_sin_duplicar_nada() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.sembrarTexto("u1", "nota", "Una nota")
        servidor.respuestasPerdidas += "secciones"

        envio.empujarYa("u1").join()

        assertEquals(mapOf("nota" to "Una nota"), servidor.consultas.getValue(7001).secciones, "el servidor ya lo tiene")
        assertEquals(setOf("nota/texto"), db.textosSucios("u1"), "el dispositivo no lo sabe: sigue pendiente")

        servidor.respuestasPerdidas.clear()
        envio.empujarYa("u1").join()

        assertEquals(listOf("secciones 7001 nota", "secciones 7001 nota"), servidor.pedidos)
        assertEquals(mapOf("nota" to "Una nota"), servidor.consultas.getValue(7001).secciones)
        assertEquals(emptySet(), db.textosSucios("u1"))
    }

    @Test
    fun si_se_pierde_la_respuesta_del_cierre_el_reenvio_repite_la_clave_del_cierre_y_se_cierra_una_sola_vez() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.CERRADA)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        servidor.respuestasPerdidas += "cerrar"

        envio.empujarYa("u1").join()
        assertEquals(CausaEnEspera.SIN_CONEXION, assertIs<EstadoEnvio.EnEspera>(db.estadosDe("u1").getValue(PARTE_CIERRE).estado).causa)
        assertTrue(db.consultaSucia("u1"))

        servidor.respuestasPerdidas.clear()
        envio.empujarYa("u1").join()

        assertEquals(listOf("c:u1-cierre", "c:u1-cierre"), servidor.cierres.map { it.clave })
        assertEquals("c:u1-cierre", servidor.consultas.getValue(7001).claveDeCierre)
        assertEquals("CERRADA", servidor.consultas.getValue(7001).estado)
        assertFalse(db.consultaSucia("u1"))
        assertEquals(emptyMap(), db.estadosDe("u1"))
    }

    @Test
    fun las_claves_de_crear_cerrar_y_dar_de_baja_son_distintas_entre_si() = conServidor {
        db.sembrarPropia("a", estado = EstadoConsulta.CERRADA)
        db.sembrarPropia("b", remotoId = "7099", estado = EstadoConsulta.ANULADA)
        servidor.consultas[7099] = ServidorFalso.ConsultaWeb(7099, "c:b", 5001, "control")

        envio.empujarYa("a").join()
        envio.empujarYa("b").join()

        val claves = servidor.pedidos.map { it.substringAfterLast(' ') }
        assertEquals(listOf("c:a", "c:a-cierre", "c:b-baja"), claves)
        assertEquals(claves.size, claves.toSet().size)
    }
}
