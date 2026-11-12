package com.salud360.core.data.repos

import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.ClinicaEscrituraApi
import com.salud360.core.data.network.hc.ClinicaEscrituraApiRemota
import com.salud360.core.data.network.hc.ConsultasClinicaApi
import com.salud360.core.data.network.hc.EjemplosConsultasClinica
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.network.hc.RespuestaAntecedentesDto
import com.salud360.core.data.network.hc.RespuestaBajaDeCargaDto
import com.salud360.core.data.network.hc.RespuestaBajaDto
import com.salud360.core.data.network.hc.RespuestaCargaDto
import com.salud360.core.data.network.hc.RespuestaCerrarDto
import com.salud360.core.data.network.hc.RespuestaCrearConsultaDto
import com.salud360.core.data.network.hc.RespuestaEscrituraDto
import com.salud360.core.data.network.hc.RespuestaRegistrosDto
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.sync.HcApiSync
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Spec 005, T23 (RF-1): `HcClinicaBackend` pasa de solo lectura a lectura y escritura. Declara que ya no es de solo lectura, que
 * envía por su cuenta y que no administra licencias, y **recibe** la API de escritura (T19). Todavía **no la usa**: el motor de envío
 * es `HcClinicaEnvio` (T24 y T25), así que la lectura sigue haciendo solo `GET` y el motor viejo (`HcApiSync`) no manda nada suyo.
 * Contra un servidor local de la JVM (`127.0.0.1`) que anota cada pedido; datos inventados; ningún servidor real.
 */
class HcClinicaBackendEscrituraTest {

    /** La API de escritura de mentira: anota cada llamada. Si alguien la usa en esta tarea, el test lo ve. */
    private class EscrituraFalsa : ClinicaEscrituraApi {
        val llamadas = mutableListOf<String>()

        override suspend fun crear(clave: String, dispositivo: String, pacienteWebId: Long, tipo: String, fecha: LocalDate, edadMostrar: String?): RespuestaCrearConsultaDto {
            llamadas += "crear"
            error("no se usa en T23")
        }

        override suspend fun secciones(consultaWebId: Long, textos: Map<String, String>): RespuestaEscrituraDto {
            llamadas += "secciones"
            error("no se usa en T23")
        }

        override suspend fun examen(consultaWebId: Long, campos: Map<String, String>): RespuestaEscrituraDto {
            llamadas += "examen"
            error("no se usa en T23")
        }

        override suspend fun formularios(consultaWebId: Long, valores: Map<String, Map<String, String>>): RespuestaEscrituraDto {
            llamadas += "formularios"
            error("no se usa en T23")
        }

        override suspend fun antecedentes(pacienteWebId: Long, consultaWebId: Long, antecedentes: List<Antecedente>, enviados: Map<String, Antecedente>): RespuestaAntecedentesDto {
            llamadas += "antecedentes"
            error("no se usa en T23")
        }

        override suspend fun registros(consultaWebId: Long, registros: List<RegistroClinico>, enviados: Map<Id, RegistroClinico>): RespuestaRegistrosDto {
            llamadas += "registros"
            error("no se usa en T23")
        }

        override suspend fun cerrar(consultaWebId: Long, clave: String, dispositivo: String, fecha: LocalDate, edadMostrar: String?): RespuestaCerrarDto {
            llamadas += "cerrar"
            error("no se usa en T23")
        }

        override suspend fun baja(consultaWebId: Long, clave: String, dispositivo: String): RespuestaBajaDto {
            llamadas += "baja"
            error("no se usa en T23")
        }

        override suspend fun laboratorio(pacienteWebId: Long, consultaWebId: Long, dispositivo: String, fecha: LocalDate, carga: Laboratorio, idWeb: Long?, confirmada: Laboratorio?): RespuestaCargaDto {
            llamadas += "laboratorio"
            error("no se usa en T23")
        }

        override suspend fun peso(pacienteWebId: Long, consultaWebId: Long, dispositivo: String, fecha: LocalDate, carga: Laboratorio, idWeb: Long?, confirmada: Laboratorio?): RespuestaCargaDto {
            llamadas += "peso"
            error("no se usa en T23")
        }

        override suspend fun bajaDeCarga(pacienteWebId: Long, cargaWebId: Long, consultaWebId: Long, clave: String, dispositivo: String, tipo: String): RespuestaBajaDeCargaDto {
            llamadas += "bajaDeCarga"
            error("no se usa en T23")
        }
    }

    private class Pedido(val metodo: String, val ruta: String)

    private val noEncontrada = """{"ok": false, "mensaje": "No existe.", "codigo": "consulta_no_encontrada"}"""

    private fun cabecera(id: Long, dia: String) =
        """{"id": $id, "paciente_id": 5001, "medico_id": 9, "medico_nombre": "Médico Prueba Salud360", "tipo": "control", "tipo_numero": 1,
            "fecha": "$dia", "created_at": "$dia 10:00:00", "estado": "CERRADA", "edad_mostrar": "", "propia": true, "updated_at": "$dia 10:30:00"}"""

    private fun web(x: com.sun.net.httpserver.HttpExchange, pedidos: MutableList<Pedido>) {
        pedidos.add(Pedido(x.requestMethod, x.requestURI.path))
        val ruta = x.requestURI.path.removePrefix("/api/salud360/")
        val (estado, cuerpo) = when {
            ruta == "consultas" -> 200 to """{"ok": true, "consultas": [${cabecera(9003, "2026-06-30")}], "total": 1, "desde": 0, "hay_mas": 0,
                "omitidas": {"foto": 0, "otro": 0, "sin_fecha": 0}}"""
            ruta == "pacientes/5001/historia" -> 200 to EjemplosConsultasClinica.HISTORIA
            ruta == "consultas/9003" -> 200 to EjemplosConsultasClinica.CONSULTA_COMPLETA.replace("7003", "9003")
            else -> 404 to noEncontrada
        }
        val bytes = cuerpo.toByteArray(Charsets.UTF_8)
        x.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        x.sendResponseHeaders(estado, bytes.size.toLong())
        x.responseBody.use { it.write(bytes) }
    }

    /** Un servidor local que contesta como hclinica la lectura y anota cada pedido. */
    private fun conServidor(bloque: suspend (cliente: HcApiClient, pedidos: List<Pedido>) -> Unit) {
        val pedidos = CopyOnWriteArrayList<Pedido>()
        val servidor = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        servidor.createContext("/") { x -> web(x, pedidos) }
        servidor.start()
        try {
            val cliente = HcApiClient("http://127.0.0.1:${servidor.address.port}", "clinica").apply { token = "token-de-prueba-1" }
            kotlinx.coroutines.runBlocking { bloque(cliente, pedidos) }
        } finally {
            servidor.stop(0)
        }
    }

    // ---- Lo que declara ----

    @Test
    fun declara_que_ya_no_es_de_solo_lectura_que_envia_por_su_cuenta_y_que_no_administra_licencias() = conServidor { cliente, pedidos ->
        HcClinicaPruebas.conBase { db, _ ->
            val backend = HcClinicaBackend(db, cliente, HcClinicaPruebas.pacientes(db))

            assertFalse(backend.soloLectura)
            assertTrue(backend.envioPropio)
            assertFalse(backend.administraLicencia)
            assertEquals("clinica", backend.especialidad)
            assertEquals(emptyList(), pedidos.map { it.ruta }, "armarlo no pide nada")
        }
    }

    // ---- Lo que recibe ----

    @Test
    fun recibe_la_api_de_escritura_y_con_el_cliente_completo_arma_la_remota() = conServidor { cliente, _ ->
        HcClinicaPruebas.conBase { db, _ ->
            val escritura = EscrituraFalsa()
            val api = HcClinicaPruebas.ApiFalsa()
            val conFalsa = HcClinicaBackend(db, api, HcClinicaPruebas.pacientes(db), { 0L }, HcClinicaBackend.MAX_PAGINAS, escritura)
            val conCliente = HcClinicaBackend(db, cliente, HcClinicaPruebas.pacientes(db))

            assertSame(escritura, conFalsa.escritura, "recibe la API de escritura")
            assertIs<ClinicaEscrituraApiRemota>(conCliente.escritura, "con el cliente completo arma la remota")
        }
    }

    @Test
    fun el_constructor_publico_recibe_el_cliente_completo_y_el_primario_las_dos_apis() {
        val publicos = HcClinicaBackend::class.java.constructors
        assertTrue(publicos.any { c -> c.parameterTypes.any { it == HcApiClient::class.java } }, "el cliente completo, para la lectura y la escritura")
        // Sin el constructor sintético que Kotlin genera por los parámetros con valor por omisión (turnos).
        val primario = HcClinicaBackend::class.java.declaredConstructors.filter { !it.isSynthetic }.single { c ->
            c.parameterTypes.any { it == ConsultasClinicaApi::class.java } && c.parameterTypes.any { it == ClinicaEscrituraApi::class.java }
        }
        assertTrue(primario.parameterTypes.contains(ClinicaEscrituraApi::class.java))
    }

    // ---- La lectura sigue haciendo solo GET y no toca la escritura ----

    @Test
    fun la_lectura_sigue_siendo_solo_get_y_nunca_usa_la_api_de_escritura() = conServidor { cliente, pedidos ->
        HcClinicaPruebas.conBase { db, _ ->
            val escritura = EscrituraFalsa()
            val lectura = com.salud360.core.data.network.hc.ConsultasClinicaApiRemota(com.salud360.core.data.network.hc.HcApiSoloLectura(cliente))
            val backend = HcClinicaBackend(db, lectura, HcClinicaPruebas.pacientes(db), { 0L }, HcClinicaBackend.MAX_PAGINAS, escritura)

            backend.traerConsultas(PACIENTE, MEDICO, true)
            backend.traerConsulta("hcc-c9003", "9003")

            assertEquals(
                listOf("GET /api/salud360/consultas", "GET /api/salud360/pacientes/5001/historia", "GET /api/salud360/consultas/9003"),
                pedidos.map { "${it.metodo} ${it.ruta}" },
            )
            assertEquals(emptyList(), escritura.llamadas, "todavía no la usa: el motor de envío es HcClinicaEnvio")
        }
    }

    @Test
    fun la_lectura_con_el_cliente_completo_tambien_es_solo_get() = conServidor { cliente, pedidos ->
        HcClinicaPruebas.conBase { db, _ ->
            val backend = HcClinicaBackend(db, cliente, HcClinicaPruebas.pacientes(db))

            backend.traerConsultas(PACIENTE, MEDICO, true)
            backend.traerConsulta("hcc-c9003", "9003")

            assertTrue(pedidos.isNotEmpty())
            assertEquals(emptyList(), pedidos.filter { it.metodo != "GET" }.map { "${it.metodo} ${it.ruta}" })
        }
    }

    @Test
    fun los_metodos_de_envio_de_hcbackend_siguen_sin_existir_y_no_tocan_ni_la_red_ni_la_escritura() = conServidor { cliente, pedidos ->
        HcClinicaPruebas.conBase { db, _ ->
            val escritura = EscrituraFalsa()
            val lectura = com.salud360.core.data.network.hc.ConsultasClinicaApiRemota(com.salud360.core.data.network.hc.HcApiSoloLectura(cliente))
            val backend = HcClinicaBackend(db, lectura, HcClinicaPruebas.pacientes(db), { 0L }, HcClinicaBackend.MAX_PAGINAS, escritura)
            val consulta = Consulta("u-1", PACIENTE, MEDICO, "clinica", "consulta", LocalDate.parse("2026-11-06"), EstadoConsulta.ABIERTA, propia = true)

            kotlin.test.assertFailsWith<UnsupportedOperationException> { backend.asegurarConsulta(consulta) }
            kotlin.test.assertFailsWith<UnsupportedOperationException> { backend.enviarSecciones("1", mapOf("nota" to mapOf("texto" to "x"))) }
            kotlin.test.assertFailsWith<UnsupportedOperationException> { backend.enviarEstado(consulta, "1") }

            assertEquals(emptyList(), pedidos.map { it.ruta })
            assertEquals(emptyList(), escritura.llamadas)
        }
    }

    // ---- El motor viejo no manda lo suyo ----

    @Test
    fun el_motor_viejo_no_manda_nada_de_la_hc_general_aunque_ya_no_sea_de_solo_lectura() = conServidor { cliente, pedidos ->
        HcClinicaPruebas.conBase { db, driver ->
            val backend = HcClinicaBackend(db, cliente, HcClinicaPruebas.pacientes(db))
            db.historiaClinicaQueries.upsertConsulta(
                Consulta("local-1", PACIENTE, MEDICO, "clinica", "consulta", LocalDate.parse("2026-11-06"), EstadoConsulta.ABIERTA, propia = true).toRow(100),
            )
            val antes = HcClinicaPruebas.volcarTodo(driver)
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.Unconfined)
            val sync = HcApiSync(db, mapOf("clinica" to backend), scope = scope)

            sync.empujarTodo()
            sync.empujarYa("local-1")
            sync.marcarSucia("local-1", esperaMs = 0)
            sync.marcarSucia("local-1")

            assertEquals(emptyList(), pedidos.map { it.ruta }, "ni un pedido")
            assertEquals(0, job.children.count { it.isActive })
            assertEquals(0, sync.pendientes.value, "lo cuenta su propio motor")
            assertEquals(antes, HcClinicaPruebas.volcarTodo(driver), "lo sucio queda como estaba")
            scope.cancel()
        }
    }
}
