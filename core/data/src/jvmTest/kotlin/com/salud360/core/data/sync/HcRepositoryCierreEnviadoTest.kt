package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_CIERRE
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.repos.EstadosDeEnvio
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005, T36 (RF-25, D13; T28 decisión 4): una consulta propia **cerrada con el cierre ya enviado** (o con el cierre viajando) queda solo
 * para leer, y **ninguna escritura** del repositorio la toca: la segunda barrera, por si la pantalla se equivoca. Una cerrada con el cierre
 * todavía pendiente, o con el cierre descartado, sigue como antes (se puede reabrir, y el repositorio no se lo impide). Servidor falso con
 * memoria; sin red.
 */
class HcRepositoryCierreEnviadoTest {

    private suspend fun Escena.consulta(id: Id): Consulta = db.historiaClinicaQueries.consultaPorId(id).lista { it.toModel() }.single()

    /** Todas las escrituras que `ConsultaViewModel` hace sobre una consulta; cada una devuelve lo que contestó el repositorio. */
    private suspend fun Escena.intentarEscribir(id: Id): List<Boolean> {
        val c = consulta(id)
        return listOf(
            hc.guardarValor(id, "nota", "texto", "otro texto"),
            hc.guardarSeccion(id, "habitos", mapOf("texto" to "otro texto")),
            hc.guardarExamenFisico(ExamenFisico(id, peso = "80")),
            hc.guardarAntecedente(PACIENTE, "clinica", "familiares", "hta", true, "detalle", id),
            hc.guardarRegistro(RegistroClinico("r-nuevo", PACIENTE, id, "clinica", "veda", FECHA_DE_PRUEBA, mapOf("detalle" to "x")), enConsulta = id),
            hc.guardarConsulta(c.copy(edadMostrar = "otra edad")),
        )
    }

    @Test
    fun una_cerrada_con_el_cierre_enviado_no_acepta_ninguna_escritura() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.CERRADA, dirty = false)
        val antes = volcarTodo(driver)

        val resultados = intentarEscribir("u1")

        assertEquals(List(resultados.size) { false }, resultados, "todas se rechazan")
        assertEquals(antes, volcarTodo(driver), "y no cambia ni una fila")
        reloj.avanzar(3_600_000)
        assertEquals(emptyList(), servidor.pedidos)
    }

    @Test
    fun eliminar_un_registro_de_una_cerrada_con_el_cierre_enviado_tampoco() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", estado = EstadoConsulta.ABIERTA, dirty = false)
        assertTrue(hc.guardarRegistro(RegistroClinico("r1", PACIENTE, "u1", "clinica", "veda", FECHA_DE_PRUEBA, mapOf("detalle" to "x")), enConsulta = "u1"))
        db.historiaClinicaQueries.limpiarRegistro("r1")
        db.historiaClinicaQueries.upsertConsulta(consulta("u1").copy(estado = EstadoConsulta.CERRADA).toRow(200, dirty = false))
        val antes = volcarTodo(driver)

        assertFalse(hc.eliminarRegistro("r1"))

        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun una_cerrada_con_el_cierre_pendiente_o_descartado_sigue_aceptando_escrituras_como_antes() = conServidor {
        db.sembrarPropia("pendiente", estado = EstadoConsulta.CERRADA) // dirty: el cierre espera
        db.sembrarPropia("descartada", remotoId = "7002", estado = EstadoConsulta.CERRADA, dirty = false)
        EstadosDeEnvio(db).guardar(PACIENTE, "descartada", PARTE_CIERRE, EstadoEnvio.Descartado, FECHA_DE_PRUEBA)

        assertTrue(hc.guardarSeccion("pendiente", "nota", mapOf("texto" to "x")))
        assertTrue(hc.guardarValor("descartada", "nota", "texto", "x"))
        assertTrue(hc.guardarExamenFisico(ExamenFisico("pendiente", peso = "80")))
    }

    @Test
    fun una_abierta_ya_enviada_sigue_editable() = conServidor {
        db.sembrarPropia("u1", remotoId = "7001", dirty = false)

        assertEquals(List(6) { true }, intentarEscribir("u1").map { it })
    }

    @Test
    fun mientras_el_cierre_viaja_tampoco_se_escribe() = conServidor {
        db.sembrarPropia("u1")
        db.sembrarTexto("u1")
        val enVuelo = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        servidor.alRecibir = { if (it == "cerrar") { enVuelo.complete(Unit); soltar.await() } }
        assertTrue(hc.cerrarConsulta("u1"))
        enVuelo.await()

        assertFalse(hc.guardarSeccion("u1", "nota", mapOf("texto" to "tarde")), "el cierre es lo último que sale")
        assertEquals("texto inventado", db.historiaClinicaQueries.valoresDeConsulta("u1").lista { it.toModel() }.single { it.seccion == "nota" }.valor)
        soltar.complete(Unit)
    }

    @Test
    fun el_historico_y_las_demas_historias_no_cambian() = conServidor {
        db.sembrarHistorica("historica") // cerrada, sin remoto_id, no propia
        db.historiaClinicaQueries.upsertConsulta(Consulta("pedi-1", PACIENTE, "m-1", "pediatria", "control", FECHA_DE_PRUEBA, EstadoConsulta.CERRADA, remotoId = "88", propia = true).toRow(100, dirty = false))

        assertTrue(hc.guardarSeccion("historica", "nota", mapOf("texto" to "x")))
        assertTrue(hc.guardarSeccion("pedi-1", "nota", mapOf("texto" to "x")), "pediatría no está en la HC general")
    }
}