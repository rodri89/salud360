package com.salud360.core.data.repos

import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.AppConfig
import com.salud360.core.data.dataModule
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ClinicaEscrituraApiRemota
import com.salud360.core.data.network.hc.EjemplosConsultasClinica
import com.salud360.core.data.network.hc.HcApiClients
import com.salud360.core.data.repos.HcClinicaPruebas.FICHA
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.repos.HcClinicaPruebas.volcar
import com.salud360.core.data.sync.HcApiSync
import com.salud360.core.data.sync.HcClinicaEnvio
import com.salud360.core.data.sync.sembrarPropia
import com.salud360.core.data.sync.sembrarTexto
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.auth.PerfilMedico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * La HC general registrada de verdad en el módulo Koin de datos (spec 002, E6.5, RF-38, RF-47, RF-56, RF-60): con el
 * `dataModule` real y un servidor local de la JVM (`127.0.0.1`, sin dependencias nuevas) que anota método, ruta y
 * `Authorization` de cada pedido, se comprueba que `clinica` es un backend de lectura y escritura (spec 005, T23: `soloLectura = false`, `envioPropio = true`), que el flujo de lectura de
 * `HcRepository` (sin pantalla) solo hace `GET` de `consultas`, `consultas/{id}` y `pacientes/{id}/historia`, y que el
 * motor de envío no manda nada de clínica con la base sucia.
 *
 * Datos inventados (documento 99000100); el servidor falso contesta como hclinica (ver [EjemplosConsultasClinica]).
 */
class DataModuleClinicaTest {

    private class Pedido(val metodo: String, val ruta: String, val query: String, val cuerpo: ByteArray, val autorizacion: String?) {
        override fun toString() = "$metodo $ruta${if (query.isEmpty()) "" else "?$query"}"
    }

    private class Entorno(
        val koin: Koin,
        val db: Salud360Db,
        val driver: SqlDriver,
        val archivos: ArchivoStore,
        val pedidos: List<Pedido>,
    ) {
        val hc get() = koin.get<HcRepository>()
        val backends get() = koin.get<HcBackends>().porEspecialidad
        fun rutas() = pedidos.map { it.toString() }
    }

    private val sinVinculo = "p-99000102"

    private fun cabecera(id: Long, dia: String) =
        """{"id": $id, "paciente_id": 5001, "medico_id": 9, "medico_nombre": "Médico Prueba Salud360", "tipo": "control", "tipo_numero": 1,
            "fecha": "$dia", "created_at": "$dia 10:00:00", "estado": "CERRADA", "edad_mostrar": "", "propia": true, "updated_at": "$dia 10:30:00"}"""

    private val cabeceras = listOf(cabecera(9003, "2026-06-30"), cabecera(9002, "2026-06-29"), cabecera(9001, "2026-06-28"))

    private fun lista(query: String): String {
        val params = query.split("&").filter { it.isNotEmpty() }.associate { it.substringBefore("=") to it.substringAfter("=", "") }
        val limite = params["limite"]?.toIntOrNull() ?: 50
        val desde = params["desde"]?.toIntOrNull() ?: 0
        val pagina = cabeceras.drop(desde).take(limite)
        val hayMas = if (desde + pagina.size < cabeceras.size) 1 else 0
        return """{"ok": true, "consultas": [${pagina.joinToString(",")}], "total": ${cabeceras.size}, "desde": $desde, "hay_mas": $hayMas,
            "omitidas": {"foto": 0, "otro": 0, "sin_fecha": 0}}"""
    }

    /** hclinica de mentira: la lista, el historial de la ficha 5001 y la consulta 9003; lo demás es 404. */
    private fun responder(ruta: String, query: String): Pair<Int, String> = when (ruta) {
        "consultas" -> 200 to lista(query)
        "pacientes/5001/historia" -> 200 to EjemplosConsultasClinica.HISTORIA
        "consultas/9003" -> 200 to EjemplosConsultasClinica.CONSULTA_COMPLETA.replace("7003", "9003")
        else -> 404 to """{"ok": false, "mensaje": "No existe.", "codigo": "no_existe"}"""
    }

    /**
     * Koin con el `dataModule` real sobre una base SQLite temporal con dos pacientes (uno vinculado con la ficha 5001 y
     * otro sin vínculo). [hcUrls] dice qué HC tienen URL: `clinica` apunta al servidor local; pediatría, a un puerto sin
     * nadie (si algo la llama, falla). El cliente de clínica ya tiene token, como después del login.
     */
    private fun conEntorno(
        clinica: String? = "servidor",
        pediatria: Boolean = true,
        bloque: suspend Entorno.() -> Unit,
    ) {
        val pedidos = CopyOnWriteArrayList<Pedido>()
        val servidor = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        servidor.createContext("/") { x ->
            pedidos.add(Pedido(x.requestMethod, x.requestURI.path, x.requestURI.rawQuery ?: "", x.requestBody.readBytes(), x.requestHeaders.getFirst("Authorization")))
            val (estado, cuerpo) = responder(x.requestURI.path.removePrefix("/api/salud360/"), x.requestURI.rawQuery ?: "")
            val bytes = cuerpo.toByteArray(Charsets.UTF_8)
            x.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            x.sendResponseHeaders(estado, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }
        servidor.start()
        val archivo = File.createTempFile("salud360-datamodule", ".db").also { it.delete() }
        val adjuntos = File.createTempFile("salud360-datamodule", "").also { it.delete(); it.mkdirs() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    val db = Salud360Db(driver)
                    db.pacientesQueries.upsertPaciente(Paciente(PACIENTE, "99000100", "Salud360", "Prueba").toRow(0, dirty = false))
                    db.pacientesQueries.upsertPaciente(Paciente(sinVinculo, "99000102", "Salud360", "SinVinculo").toRow(0, dirty = false))
                    db.pacientesQueries.upsertPacienteExtra(
                        PacienteExtra(PACIENTE, CodigosHc.GENERAL, PacientesHcRepository.EXTRA_HC_PACIENTE_ID, FICHA.toString()).toRow(0, dirty = false),
                    )
                    val urls = buildMap {
                        if (pediatria) put("pediatria", "http://127.0.0.1:1")
                        when (clinica) {
                            "servidor" -> put(CodigosHc.GENERAL, "http://127.0.0.1:${servidor.address.port}")
                            null -> Unit
                            else -> put(CodigosHc.GENERAL, clinica)
                        }
                    }
                    val config = AppConfig(entorno = "dev", turnosUrl = "http://127.0.0.1:1", hcUrls = urls)
                    val archivos = ArchivoStore(adjuntos.absolutePath)
                    val koin = koinApplication {
                        modules(
                            dataModule(db, config, mapOf("pediatria" to "Pediatría", CodigosHc.GENERAL to "Clínica general")),
                            module { single { archivos } },
                        )
                    }.koin
                    koin.get<HcApiClients>().porEspecialidad[CodigosHc.GENERAL]?.token = "token-de-prueba-1"
                    Entorno(koin, db, driver, archivos, pedidos).bloque()
                } finally {
                    driver.close()
                }
            }
        } finally {
            servidor.stop(0)
            archivo.delete()
            adjuntos.deleteRecursively()
        }
    }

    /** Las filas `dirty` de las tablas de la HC, enteras: lo que el dispositivo todavía no mandó y no se puede tocar. */
    private fun sucias(driver: SqlDriver): Map<String, List<String>> = HcClinicaPruebas.TABLAS.associateWith { tabla ->
        val columnas = textos(driver, "SELECT name FROM pragma_table_info('$tabla') ORDER BY cid")
        textos(
            driver,
            "SELECT " + columnas.joinToString(" || '|' || ") { "quote($it)" } + " FROM $tabla WHERE dirty <> 0 ORDER BY " + columnas.joinToString(","),
        )
    }

    /** Escribe en el dispositivo, por el camino de la app, una consulta local de clínica con de todo sin enviar. */
    private suspend fun Entorno.escribirLocal(): Consulta {
        val local = hc.abrirConsulta(PACIENTE, MEDICO, CodigosHc.GENERAL, "consulta", LocalDate.parse("2026-10-30"), "")
        assertTrue(hc.guardarValor(local.id, "motivo_consulta", "texto", "Prueba Salud360 local"))
        assertTrue(hc.guardarExamenFisico(ExamenFisico(local.id, peso = "70")))
        assertTrue(hc.guardarAntecedente(PACIENTE, CodigosHc.GENERAL, "personales", "hta", true, "local", local.id))
        assertTrue(hc.guardarRegistro(RegistroClinico("r-local", PACIENTE, local.id, CodigosHc.GENERAL, "veda", campos = mapOf("detalle" to "local"))))
        return local
    }

    private fun Pedido.assertLecturaPermitida() {
        assertEquals("GET", metodo, "solo se lee: $this")
        assertEquals(0, cuerpo.size, "un GET con cuerpo: $this")
        assertTrue(
            ruta in setOf("/api/salud360/consultas", "/api/salud360/consultas/9003", "/api/salud360/pacientes/5001/historia"),
            "ruta que la lectura no usa: $this",
        )
    }

    // ---- Qué queda registrado ----

    @Test
    fun clinica_queda_registrada_con_lectura_y_escritura_y_pediatria_sigue_igual() = conEntorno {
        assertEquals(setOf("pediatria", CodigosHc.GENERAL), backends.keys)
        val clinica = assertNotNull(backends[CodigosHc.GENERAL])
        assertIs<HcClinicaBackend>(clinica)
        assertFalse(clinica.soloLectura, "T23: ya no es de solo lectura")
        assertTrue(clinica.envioPropio, "envía por su cuenta: el motor viejo la ignora")
        assertFalse(clinica.administraLicencia, "su licencia la administra la web")
        assertIs<ClinicaEscrituraApiRemota>(clinica.escritura, "trae el cliente completo")
        val pediatria = assertNotNull(backends["pediatria"])
        assertIs<HcPediatriaBackend>(pediatria)
        assertFalse(pediatria.soloLectura)

        assertTrue(hc.tieneApi(CodigosHc.GENERAL))
        assertFalse(hc.esSoloLectura(CodigosHc.GENERAL))
        assertTrue(hc.leeDeLaWeb(CodigosHc.GENERAL), "su historia se sigue leyendo de la web")
        assertTrue(hc.puedeActualizar(CodigosHc.GENERAL))
        assertTrue(hc.tieneApi("pediatria"))
        assertFalse(hc.esSoloLectura("pediatria"))
        assertFalse(hc.leeDeLaWeb("pediatria"))
        assertFalse(hc.puedeActualizar("pediatria"), "«Actualizar» es solo de la HC general")
        assertEquals(setOf("pediatria", CodigosHc.GENERAL), hc.especialidadesConApi)
        assertEquals(emptyList(), pedidos, "registrar no pide nada")
    }

    @Test
    fun sin_url_de_la_hc_general_no_se_registra_y_queda_como_siempre() = conEntorno(clinica = null) {
        assertEquals(setOf("pediatria"), backends.keys)
        assertFalse(hc.tieneApi(CodigosHc.GENERAL))
        assertFalse(hc.esSoloLectura(CodigosHc.GENERAL))
        assertFalse(hc.leeDeLaWeb(CodigosHc.GENERAL))
        assertFalse(hc.puedeActualizar(CodigosHc.GENERAL))
        assertEquals(setOf("pediatria"), hc.especialidadesConApi)
        assertNull(hc.traerConsultasRemotas(PACIENTE, MEDICO, CodigosHc.GENERAL))
        assertNull(hc.actualizarConsultasRemotas(PACIENTE, MEDICO, CodigosHc.GENERAL))
        hc.traerConsultaRemota(Consulta("hcc-c9003", PACIENTE, MEDICO, CodigosHc.GENERAL, "control", LocalDate.parse("2026-06-30"), EstadoConsulta.CERRADA, remotoId = "9003"))
        assertEquals(emptyList(), pedidos)
    }

    @Test
    fun con_la_url_en_blanco_tampoco_se_registra() = conEntorno(clinica = "   ") {
        assertEquals(setOf("pediatria"), backends.keys)
        assertFalse(hc.tieneApi(CodigosHc.GENERAL))
    }

    @Test
    fun usa_el_cliente_de_la_hc_general_que_comparten_el_login_y_el_resto() = conEntorno {
        koin.get<HcApiClients>().porEspecialidad[CodigosHc.GENERAL]!!.token = "token-del-login"

        hc.traerConsultasRemotas(PACIENTE, MEDICO, CodigosHc.GENERAL)

        assertTrue(pedidos.isNotEmpty())
        assertEquals(setOf("Bearer token-del-login"), pedidos.map { it.autorizacion }.toSet(), "el cliente al que `AuthRepository` le pone el token")
    }

    @Test
    fun el_backend_trae_el_cliente_completo_con_el_token_del_login_y_la_lectura_sigue_siendo_solo_get() = conEntorno {
        val clinica = backends.getValue(CodigosHc.GENERAL) as HcClinicaBackend

        // Un único pedido al servidor falso local alcanza para ver que la escritura usa el cliente completo (DELETE con cuerpo y token).
        assertFailsWith<TobbException> { clinica.escritura.baja(9003, "c:prueba-baja", "disp-prueba") }

        val p = pedidos.single()
        assertEquals("DELETE", p.metodo)
        assertEquals("/api/salud360/consultas/9003", p.ruta)
        assertTrue(p.cuerpo.isNotEmpty(), "el DELETE lleva su cuerpo")
        assertEquals("Bearer token-de-prueba-1", p.autorizacion, "el mismo cliente (y token) que el resto de la HC general")

        // Y leer, después, sigue siendo solo GET: la escritura no se mezcla con la lectura.
        hc.traerConsultasRemotas(PACIENTE, MEDICO, CodigosHc.GENERAL)
        assertEquals(emptyList(), pedidos.drop(1).filter { it.metodo != "GET" }.map { it.toString() })
    }

    // ---- El flujo de lectura de HcRepository, sin pantalla ----

    @Test
    fun flujo_completo_de_lectura_solo_get_de_las_tres_rutas_y_nada_queda_sucio() = conEntorno {
        val local = escribirLocal()
        val sucioAntes = sucias(driver)
        assertTrue(sucioAntes.values.sumOf { it.size } >= 4, "la consulta local tiene que estar sin enviar: $sucioAntes")

        // Abrir la historia: la lista (50 por pedido) y el historial.
        val traidas = assertNotNull(hc.traerConsultasRemotas(PACIENTE, MEDICO, CodigosHc.GENERAL))
        assertEquals(listOf("hcc-c9001", "hcc-c9002", "hcc-c9003"), traidas.map { it.id }.sorted())
        assertEquals(listOf("GET /api/salud360/consultas", "GET /api/salud360/pacientes/5001/historia"), pedidos.map { "${it.metodo} ${it.ruta}" })
        assertEquals(setOf("paciente_id=5001", "limite=50", "desde=0"), pedidos[0].query.split("&").toSet())

        // Abrirla otra vez enseguida no repite nada (RF-57).
        hc.traerConsultasRemotas(PACIENTE, MEDICO, CodigosHc.GENERAL)
        assertEquals(2, pedidos.size)

        // Abrir una consulta: un solo pedido.
        hc.traerConsultaRemota(traidas.first { it.remotoId == "9003" })
        assertEquals(3, pedidos.size)
        assertEquals("GET /api/salud360/consultas/9003", "${pedidos.last().metodo} ${pedidos.last().ruta}")
        assertEquals(
            "Prueba Salud360 motivo (a)",
            textos(driver, "SELECT valor FROM seccion_valor WHERE consulta_id = 'hcc-c9003' AND seccion = 'motivo_consulta'").single(),
        )

        // «Actualizar» se salta la espera: la lista y el historial otra vez.
        val actualizadas = assertNotNull(hc.actualizarConsultasRemotas(PACIENTE, MEDICO, CodigosHc.GENERAL))
        assertEquals(3, actualizadas.size)
        assertEquals(5, pedidos.size)
        assertEquals(listOf("GET /api/salud360/consultas", "GET /api/salud360/pacientes/5001/historia"), pedidos.takeLast(2).map { "${it.metodo} ${it.ruta}" })

        // Nunca un método que escribe, nunca una ruta de la que no se lee, y todos con el token del login.
        pedidos.forEach { it.assertLecturaPermitida() }
        assertEquals(emptyList(), pedidos.filter { it.metodo in setOf("POST", "PUT", "DELETE", "PATCH") })
        assertEquals(setOf("Bearer token-de-prueba-1"), pedidos.map { it.autorizacion }.toSet())

        // Lo traído entra limpio y lo del dispositivo queda exactamente como estaba.
        assertEquals(sucioAntes, sucias(driver), "lo `dirty` del dispositivo no se toca y la lectura no deja nada nuevo `dirty`")
        assertEquals(1, textos(driver, "SELECT COUNT(*) FROM consulta WHERE dirty <> 0").single().toInt())
        assertEquals(local.id, textos(driver, "SELECT id FROM consulta WHERE dirty <> 0").single())
        assertEquals(4, textos(driver, "SELECT COUNT(*) FROM consulta").single().toInt(), "la local y las tres de la web")
    }

    @Test
    fun un_paciente_sin_vinculo_no_pide_nada_ni_llama_a_resolver() = conEntorno {
        val deLaWeb = Consulta("hcc-c9003", sinVinculo, MEDICO, CodigosHc.GENERAL, "control", LocalDate.parse("2026-06-30"), EstadoConsulta.CERRADA, remotoId = "9003")
        val antes = HcClinicaPruebas.TABLAS.associateWith { volcar(driver, it) }

        assertEquals(emptyList(), hc.traerConsultasRemotas(sinVinculo, MEDICO, CodigosHc.GENERAL))
        assertEquals(emptyList(), hc.actualizarConsultasRemotas(sinVinculo, MEDICO, CodigosHc.GENERAL))
        hc.traerConsultaRemota(deLaWeb)

        assertEquals(emptyList(), pedidos.map { it.toString() }, "RF-49: ni consultas, ni historial, ni pacientes/resolver")
        assertEquals(antes, HcClinicaPruebas.TABLAS.associateWith { volcar(driver, it) })
    }

    // ---- El motor de envío no manda nada de clínica ----

    @Test
    fun el_motor_de_envio_no_manda_nada_de_clinica_con_la_base_sucia() = conEntorno {
        val local = escribirLocal()
        // Una consulta de la web vieja y sucia (como las de hepatología que dejó `8.sqm`) también.
        db.historiaClinicaQueries.upsertConsulta(
            Consulta("h-vieja", PACIENTE, MEDICO, CodigosHc.GENERAL, "consulta", LocalDate.parse("2025-01-10"), EstadoConsulta.CERRADA).toRow(1, dirty = true),
        )
        val sucioAntes = sucias(driver)
        val pendientesAntes = sucioAntes.values.sumOf { it.size }
        assertTrue(pendientesAntes > 0)

        // Un motor armado con los backends REALES del módulo, con su propio scope para poder esperarlo.
        val job = SupervisorJob()
        val propio = HcApiSync(db, backends, archivos, CoroutineScope(job + Dispatchers.Unconfined))
        propio.empujarTodo()
        propio.empujarYa(local.id)
        propio.empujarYa("h-vieja")
        propio.marcarSucia(local.id, esperaMs = 0)
        propio.marcarSucia(local.id)
        propio.marcarSucia("h-vieja", esperaMs = 0)
        assertEquals(0, job.children.count { it.isActive }, "nada programado ni reintentos")

        // El que arma Koin (con el scope de la app): se lo llama igual y se le da tiempo de sobra a que mande algo.
        val deLaApp = koin.get<HcApiSync>()
        assertTrue(deLaApp.activo(CodigosHc.GENERAL))
        deLaApp.empujarTodo()
        deLaApp.empujarYa(local.id)
        deLaApp.marcarSucia(local.id, esperaMs = 0)
        deLaApp.marcarSucia("h-vieja", esperaMs = 0)
        delay(1_500)

        assertEquals(emptyList(), pedidos.map { it.toString() }, "cero pedidos de envío (ni a consultas, ni a pacientes/resolver)")
        assertEquals(sucioAntes, sucias(driver), "la base queda idéntica y sigue sucia")
        assertEquals(pendientesAntes, sucias(driver).values.sumOf { it.size })

        // Después de eso, leer sigue siendo solo `GET` y lo sucio sigue intacto.
        hc.traerConsultasRemotas(PACIENTE, MEDICO, CodigosHc.GENERAL)
        hc.traerConsultaRemota(Consulta("hcc-c9003", PACIENTE, MEDICO, CodigosHc.GENERAL, "control", LocalDate.parse("2026-06-30"), EstadoConsulta.CERRADA, remotoId = "9003"))
        propio.empujarTodo()
        assertEquals(3, pedidos.size)
        pedidos.forEach { it.assertLecturaPermitida() }
        assertEquals(sucioAntes, sucias(driver))
        job.cancel()
    }

    /** Inicia la sesión del médico de prueba en el `AuthRepository` del módulo (sin red: guarda la sesión como después del login). */
    private suspend fun Entorno.iniciarSesionComoMedico() {
        val usuario = Usuario("u-medico", "medico@salud360.test", "Médico", "Prueba", Rol.MEDICO)
        koin.get<AuthRepository>().guardarSesion(Sesion(usuario, "token-de-prueba-1", PerfilMedico(MEDICO, listOf(CodigosHc.GENERAL), tieneTurnos = false)))
    }

    // ---- La cola de envío de la HC general (spec 005, T24 a T26) ----

    @Test
    fun la_cola_de_envio_queda_registrada_una_sola_vez_y_envia_por_el_cliente_completo_de_la_hc_general() = conEntorno {
        val envio = koin.get<HcClinicaEnvio>()
        assertSame(envio, koin.get<HcClinicaEnvio>(), "una sola cola para toda la app")
        db.sembrarPropia("propia-1", paciente = PACIENTE, medico = MEDICO)
        db.sembrarTexto("propia-1")

        // Sin sesión de médico (RF-50) la cola no manda nada: no hay token de nadie con el que mandarlo.
        envio.empujarYa("propia-1").join()
        assertEquals(emptyList(), pedidos.map { it.toString() }, "sin sesión no sale nada")
        assertEquals(emptyMap(), EstadosDeEnvio(db).deLaConsulta(PACIENTE, "propia-1"), "y no se marca nada")

        // Entra el médico dueño de la consulta. La cola real, con la API de escritura del backend de la HC general: lo primero que sale es la
        // creación. El servidor falso de este test contesta la lista de consultas a cualquier pedido a `consultas` (no sabe crear): lo que se
        // mira es por dónde salió el pedido y qué quedó en el dispositivo.
        iniciarSesionComoMedico()
        envio.empujarYa("propia-1").join()

        val pedido = pedidos.single()
        assertEquals("POST", pedido.metodo)
        assertEquals("/api/salud360/consultas", pedido.ruta)
        assertEquals("Bearer token-de-prueba-1", pedido.autorizacion, "el mismo cliente (y token) que el resto de la HC general")
        val cuerpo = pedido.cuerpo.decodeToString()
        assertTrue("\"clave\":\"c:propia-1\"" in cuerpo, cuerpo)
        assertTrue("\"paciente_id\":\"5001\"" in cuerpo, "la ficha de la web del paciente: $cuerpo")
        assertTrue("\"dispositivo\":\"" in cuerpo && "\"dispositivo\":\"\"" !in cuerpo, "con el id de este dispositivo: $cuerpo")

        // La web contestó algo que no es una creación: «En espera» (se reintenta sola, T27) y todo sigue en el dispositivo, sin perderse nada.
        val estadoDeCrear = EstadosDeEnvio(db).deLaConsulta(PACIENTE, "propia-1").getValue("crear").estado
        assertEquals(CausaEnEspera.SERVIDOR, assertIs<EstadoEnvio.EnEspera>(estadoDeCrear, estadoDeCrear.toString()).causa)
        assertEquals("1", textos(driver, "SELECT COUNT(*) FROM seccion_valor WHERE dirty <> 0").single())
        assertEquals("", textos(driver, "SELECT remoto_id FROM consulta WHERE id = 'propia-1'").single())
        assertEquals(1, envio.contarPendientes().enEspera)
    }

    @Test
    fun la_cola_de_envio_solo_toma_lo_del_medico_de_la_sesion_aunque_haya_otras_consultas_propias() = conEntorno {
        val envio = koin.get<HcClinicaEnvio>()
        db.sembrarPropia("de-otro", paciente = PACIENTE, medico = "m-otro-medico")
        db.sembrarTexto("de-otro")
        iniciarSesionComoMedico()

        envio.empujarYa("de-otro").join()

        assertEquals(emptyList(), pedidos.map { it.toString() }, "la consulta de otro médico no sale con la sesión de éste")
        assertEquals(0, envio.contarPendientes().total)
    }
    // ---- Licencias ----

    /** Lo que deja guardar la licencia de un médico solo de clínica: el resultado, la fila (con `dirty`) y los pedidos. */
    private fun licenciaDeUnMedicoSoloDeClinica(clinica: String?): Triple<Boolean, List<String>, List<String>> {
        var salida: Triple<Boolean, List<String>, List<String>>? = null
        conEntorno(clinica = clinica, pediatria = false) {
            val admin = koin.get<AdminRepository>()
            admin.guardarMedico(Medico("m-99000001", "u-99000001", "Prueba", "Inventada", especialidadesHc = listOf(CodigosHc.GENERAL)))
            admin.sincronizarLicencias()
            val guardada = admin.guardarLicencia(Licencia("m-99000001", "2027-01-01", "2026-12-01", 0.0, true))
            assertEquals(emptyList(), hc.avisosDeLicencia(), "el aviso de licencia de la HC general no existe")
            salida = Triple(guardada, textos(driver, "SELECT medico_id || '|' || fecha_expiracion || '|' || dirty FROM licencia"), pedidos.map { it.toString() })
        }
        return salida!!
    }

    @Test
    fun la_licencia_de_un_medico_solo_de_clinica_sigue_como_antes() {
        val sin = licenciaDeUnMedicoSoloDeClinica(clinica = null)
        val con = licenciaDeUnMedicoSoloDeClinica(clinica = "servidor")

        assertTrue(con.first, "sin destino que la cobre: como antes, true")
        assertEquals(sin, con, "registrar clínica no cambia el resultado ni la fila de la licencia")
        assertEquals(emptyList(), con.third, "ni traer ni guardar licencias pega a la HC general")
    }
}
