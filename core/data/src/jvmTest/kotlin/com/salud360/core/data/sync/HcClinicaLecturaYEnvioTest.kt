package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.ConsultasClinicaApi
import com.salud360.core.data.network.hc.ExamenFisicoDto
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.SeccionesConsultaDto
import com.salud360.core.data.network.hc.TextoDto
import com.salud360.core.data.repos.HcClinicaBackend
import com.salud360.core.data.repos.HcClinicaPruebas
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.LoEnviadoGuardado
import com.salud360.core.model.Id
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.SeccionValor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T29 (arreglo de la carrera lectura/envío): la lectura de una propia ya enviada **espera su turno** (el mismo de la cola de envío)
 * para aplicar lo que trajo, y **no pisa** lo que un envío confirmó mientras su `GET` estaba en vuelo. El pedido de red va fuera del turno; solo
 * la aplicación va dentro. Determinista: el `GET` espera en una compuerta y el envío se detiene con `ServidorFalso.alRecibir`; sin tiempo real,
 * sin red y sin pedidos a nadie (API falsa y servidor falso con memoria).
 */
class HcClinicaLecturaYEnvioTest {
    private val dia = "2026-11-06"

    /** La API de lectura de siempre, pero `GET consultas/{id}` contesta con la respuesta de **antes** y la entrega cuando se abre la compuerta. */
    private class ApiConCompuerta(val base: HcClinicaPruebas.ApiFalsa, abierta: Boolean) : ConsultasClinicaApi by base {
        val llego = CompletableDeferred<Unit>()
        val abierta = CompletableDeferred<Unit>().also { if (abierta) it.complete(Unit) }

        override suspend fun consulta(id: Long): RespuestaConsultaDto {
            val respuesta = base.consulta(id) // ya armada: es lo que la web tenía al recibir el pedido
            llego.complete(Unit)
            abierta.await()
            return respuesta
        }
    }

    private fun cab(id: Long, estado: String = "ABIERTA") = ConsultaCabeceraDto(
        id = id, pacienteId = 5001, medicoId = 9, medicoNombre = "Médico Prueba Salud360", tipo = "control", tipoNumero = 1,
        fecha = dia, createdAt = "$dia 10:00:00", estado = estado, edadMostrar = "45 años", propia = true, updatedAt = "$dia 10:30:00",
    )

    private fun respuesta(nota: String? = null, habitos: String? = null) = RespuestaConsultaDto(
        consulta = cab(7001),
        secciones = SeccionesConsultaDto(nota = nota?.let(::TextoDto), habitos = habitos?.let(::TextoDto)),
        examenFisico = ExamenFisicoDto(),
    )

    /** Una propia ya enviada (`u1`, id 7001 en la web) con la nota «A» limpia, que es también lo último que la web tiene. */
    private suspend fun Escena.propiaConNotaA(estado: EstadoConsulta = EstadoConsulta.ABIERTA, dirty: Boolean = false) {
        db.sembrarPropia("u1", remotoId = "7001", estado = estado, dirty = dirty)
        servidor.consultas[7001] = ServidorFalso.ConsultaWeb(7001, "c:u1", 5001, "control")
        db.historiaClinicaQueries.upsertSeccionValor(SeccionValor("u1", "nota", "texto", "A").toRow(50, dirty = false))
        LoEnviadoGuardado(db).guardar(PACIENTE, "u1", LoEnviado(valores = mapOf("nota/texto" to "A")))
    }

    private fun Escena.conApi(api: ConsultasClinicaApi) =
        HcClinicaBackend(db, api, pacientes, { 0L }, HcClinicaBackend.MAX_PAGINAS, servidor, turnos)

    private suspend fun Escena.texto(seccion: String): String? = db.historiaClinicaQueries.valoresDeConsulta("u1").lista { it }
        .singleOrNull { it.seccion == seccion && it.campo == "texto" }?.let { it.valor + "|" + it.dirty }

    @Test
    fun un_get_pedido_antes_de_que_el_envio_confirme_no_pisa_lo_que_el_envio_anoto() = conServidor {
        propiaConNotaA()
        val web = HcClinicaPruebas.ApiFalsa().apply { consultas[7001] = respuesta(nota = "A") }
        val puerta = ApiConCompuerta(web, abierta = false)
        val lectura = scope.launch { conApi(puerta).traerConsulta("u1", "7001") }
        puerta.llego.await() // el GET salió y la web contestó «A»; la respuesta todavía no llegó

        // Mientras tanto el médico escribe «B» y el envío lo confirma.
        db.escribirTexto("u1", "nota", "B", 200)
        envio.empujarYa("u1").join()
        assertEquals("B|0", texto("nota"))
        assertEquals("B", db.loEnviadoDe("u1").valores["nota/texto"])

        puerta.abierta.complete(Unit) // ahora llega la respuesta vieja
        lectura.join()

        assertEquals("B|0", texto("nota"), "lo confirmado por el envío no se pisa con el valor viejo")
        assertEquals("B", db.loEnviadoDe("u1").valores["nota/texto"], "y lo último que la web tiene sigue siendo «B»")
        assertEquals(listOf("secciones 7001 nota"), servidor.pedidos, "la lectura no escribió nada en la web")
    }

    @Test
    fun la_lectura_que_llega_con_un_envio_en_curso_espera_su_turno_y_aplica_despues() = conServidor {
        propiaConNotaA(estado = EstadoConsulta.CERRADA, dirty = true) // el cierre está pendiente: el envío solo cierra
        val web = HcClinicaPruebas.ApiFalsa().apply { consultas[7001] = respuesta(nota = "A", habitos = "Hábitos de la web") }
        val enVuelo = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        servidor.alRecibir = { if (it == "cerrar") { enVuelo.complete(Unit); soltar.await() } }
        val enviando = envio.empujarYa("u1")
        enVuelo.await() // el servidor ya cerró y el envío tiene el turno

        val lectura = scope.launch { conApi(ApiConCompuerta(web, abierta = true)).traerConsulta("u1", "7001") }

        assertTrue(lectura.isActive, "la respuesta llegó pero la lectura espera el turno del envío")
        assertNull(texto("habitos"), "y todavía no escribió nada")
        soltar.complete(Unit)
        enviando.join()
        lectura.join()
        assertEquals("Hábitos de la web|0", texto("habitos"), "después del envío, aplica lo que trajo")
        assertEquals(listOf("cerrar 7001 c:u1-cierre"), servidor.pedidos)
    }

    @Test
    fun sin_carrera_la_lectura_se_comporta_como_antes() = conServidor {
        propiaConNotaA()
        val web = HcClinicaPruebas.ApiFalsa().apply { consultas[7001] = respuesta(nota = "Cambiada en la web", habitos = "Hábitos de la web") }

        conApi(ApiConCompuerta(web, abierta = true)).traerConsulta("u1", "7001")

        assertEquals("Cambiada en la web|0", texto("nota"))
        assertEquals("Hábitos de la web|0", texto("habitos"))
        assertEquals("Cambiada en la web", db.loEnviadoDe("u1").valores["nota/texto"])
        assertEquals(emptyList(), servidor.pedidos)
    }

    @Test
    fun un_envio_que_falla_en_el_medio_no_cancela_el_refresco() = conServidor {
        propiaConNotaA()
        val web = HcClinicaPruebas.ApiFalsa().apply { consultas[7001] = respuesta(nota = "A", habitos = "Hábitos de la web") }
        val puerta = ApiConCompuerta(web, abierta = false)
        val lectura = scope.launch { conApi(puerta).traerConsulta("u1", "7001") }
        puerta.llego.await()
        db.escribirTexto("u1", "nota", "B", 200)
        servidor.falla = { com.salud360.core.data.network.TobbException(500, "error", "caído") }
        envio.empujarYa("u1").join() // no confirmó nada: «B» sigue pendiente

        puerta.abierta.complete(Unit)
        lectura.join()

        assertEquals("B|1", texto("nota"), "lo pendiente no se pisa")
        assertEquals("A", db.loEnviadoDe("u1").valores["nota/texto"])
        assertEquals("Hábitos de la web|0", texto("habitos"), "y lo demás se refresca igual: el envío no cambió lo que la web tiene")
    }

    @Test
    fun la_lectura_con_el_turno_no_se_traba_con_otra_lectura_ni_con_el_envio() = conServidor {
        propiaConNotaA()
        val web = HcClinicaPruebas.ApiFalsa(listOf(cab(7001))).apply { consultas[7001] = respuesta(nota = "A", habitos = "Hábitos de la web") }
        val compuertaDeLista = CompletableDeferred<Unit>()
        web.compuerta = compuertaDeLista
        val puerta = ApiConCompuerta(web, abierta = false)
        val backend = conApi(puerta)
        // 1) El GET de la consulta ya salió y espera su respuesta (no tiene el candado de la lectura ni el turno).
        val unaConsulta = scope.launch { backend.traerConsulta("u1", "7001") }
        puerta.llego.await()
        // 2) Una lista toma el candado de la lectura y queda esperando a la web.
        val lista = scope.launch { backend.traerConsultas(PACIENTE, MEDICO, forzar = true) }
        // 3) La respuesta de la consulta llega: toma el turno y espera el candado que tiene la lista.
        puerta.abierta.complete(Unit)
        // 4) Un envío de la misma consulta pide el turno y espera a que la lectura lo suelte.
        db.escribirTexto("u1", "nota", "B", 200)
        val enviando = envio.empujarYa("u1")
        assertTrue(unaConsulta.isActive && lista.isActive && enviando.isActive, "los tres esperan en cadena")

        compuertaDeLista.complete(Unit) // la lista termina, suelta el candado y todo avanza
        withTimeout(10_000) { lista.join(); unaConsulta.join(); enviando.join() }

        assertEquals("B|0", texto("nota"), "el envío salió y la lectura, que corrió antes, no lo pisa")
        assertEquals(listOf("secciones 7001 nota"), servidor.pedidos)
    }
}