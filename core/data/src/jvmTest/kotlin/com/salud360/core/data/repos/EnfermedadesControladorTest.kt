package com.salud360.core.data.repos

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.AvisoEnfermedades
import com.salud360.core.data.hcgeneral.Enfermedad
import com.salud360.core.data.hcgeneral.EstadoAdministrar
import com.salud360.core.data.hcgeneral.EstadoSeccionEnfermedades
import com.salud360.core.data.hcgeneral.Marcable
import com.salud360.core.data.hcgeneral.MotivoEnfermedades
import com.salud360.core.data.hcgeneral.ResultadoCrear
import com.salud360.core.data.hcgeneral.TEXTO_DEMASIADOS_CAMBIOS
import com.salud360.core.data.hcgeneral.textoSinConfirmar
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.EnfermedadesApi
import com.salud360.core.data.network.hc.HcPacienteCandidato
import com.salud360.core.data.network.hc.HcPacienteFicha
import com.salud360.core.data.network.hc.HcPacienteRequest
import com.salud360.core.data.network.hc.HcResolverRespuesta
import com.salud360.core.data.network.hc.PacientesHcApi
import com.salud360.core.data.network.hc.PacientesHcApis
import com.salud360.core.data.network.hc.PaginaCartera
import com.salud360.core.data.network.hc.RespuestaCrear
import com.salud360.core.data.network.hc.RespuestaLista
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Controlador de la sección "Enfermedades" (spec 003, RF-8, RF-15, RF-21 a RF-28, RF-40, RF-41, RF-44 a RF-46, RF-48,
 * RF-58) sobre una base SQLite de verdad en un archivo temporal, con las APIs falsas de enfermedades y de pacientes:
 * sin red ni servidor. Los pedidos de la API falsa que se "retienen" se quedan esperando hasta que el test los libera
 * (así se prueban respuestas desordenadas, tardías y rechazos); la `espera` del repositorio es de ~100 ms. Los nombres
 * de enfermedades son de prueba, nunca de la base real.
 */
class EnfermedadesControladorTest {

    private val medico = "tobb-m1"
    private val paciente = "tobb-p55"
    private val nombreHc = "Clínica general"
    private val sinRed = TobbException(0, "red", "Sin conexión con la historia clínica de clinica")

    @Volatile
    private var editable = true

    private val inicial = EstadoSeccionEnfermedades(
        listOf(Marcable(1, "Asma", false), Marcable(2, "Diabetes", false), Marcable(3, "Hipertensión", false)),
        listOf(Enfermedad(7, "Rinitis")),
    )
    private val administrarA = EstadoAdministrar(
        listOf(Enfermedad(1, "Asma"), Enfermedad(2, "Diabetes"), Enfermedad(3, "Hipertensión")),
        listOf(Enfermedad(4, "Artrosis"), Enfermedad(5, "Bronquitis")),
    )

    /** Como [inicial] pero con las [marcadas] que el paciente tiene según la web. */
    private fun seccion(vararg marcadas: Long) =
        inicial.copy(marcables = inicial.marcables.map { it.copy(tiene = it.id in marcadas) })

    /** Un pedido de la API falsa: lo que se pidió y la respuesta, que el test libera a mano si el tipo está retenido. */
    private class Llamada(val tipo: String, val args: List<Any?>) {
        private val gate = CompletableDeferred<Any?>()
        suspend fun esperar(): Any? = gate.await()
        fun responder(valor: Any) = gate.complete(valor)
        fun fallar(e: TobbException) = gate.complete(e)
    }

    /**
     * API de enfermedades falsa: anota cada pedido; los de un tipo en [retenidos] esperan a que se los libere, el resto
     * contesta enseguida con lo cargado (o con [fallo]).
     */
    private class ApiFalsa(var seccion: EstadoSeccionEnfermedades, var lista: EstadoAdministrar) : EnfermedadesApi {
        val llamadas = CopyOnWriteArrayList<Llamada>()
        val retenidos: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var fallo: TobbException? = null
        @Volatile var respuestaLista: RespuestaLista? = null
        @Volatile var respuestaCrear: RespuestaCrear? = null

        fun de(tipo: String) = llamadas.filter { it.tipo == tipo }

        @Suppress("UNCHECKED_CAST")
        private suspend fun <T> responder(tipo: String, args: List<Any?>, porDefecto: () -> T): T {
            val llamada = Llamada(tipo, args).also { llamadas += it }
            val r: Any? = if (tipo in retenidos) llamada.esperar() else fallo ?: porDefecto()
            if (r is TobbException) throw r
            return r as T
        }

        override suspend fun seccion(fichaId: Long) = responder("seccion", listOf(fichaId)) { seccion }
        override suspend fun marcar(fichaId: Long, enfermedadId: Long, tiene: Boolean) =
            responder("marcar", listOf(fichaId, enfermedadId, tiene)) { seccion }
        override suspend fun administrar() = responder("administrar", emptyList()) { lista }
        override suspend fun enLista(enfermedadId: Long, enLista: Boolean, fichaId: Long?) =
            responder("enLista", listOf(enfermedadId, enLista, fichaId)) { respuestaLista ?: error("sin respuesta cargada") }
        override suspend fun crear(nombre: String, fichaId: Long?) =
            responder("crear", listOf(nombre, fichaId)) { respuestaCrear ?: error("sin respuesta cargada") }
    }

    /** API de pacientes falsa: `resolver` contesta en orden lo que haya en [respuestas] (una respuesta o una excepción). */
    private class PacientesFalsa : PacientesHcApi {
        val respuestas = ArrayDeque<Any>()
        val resueltos = CopyOnWriteArrayList<Pair<HcPacienteRequest, Long?>>()

        override suspend fun pagina(desde: Int, limite: Int): PaginaCartera = error("la cartera no se usa")

        override suspend fun resolver(pedido: HcPacienteRequest, elegida: Long?): HcResolverRespuesta {
            resueltos += pedido to elegida
            return when (val r = respuestas.removeFirstOrNull() ?: error("no había respuesta para resolver")) {
                is Throwable -> throw r
                else -> r as HcResolverRespuesta
            }
        }
    }

    private fun ficha(id: Long) = HcResolverRespuesta(
        HcPacienteFicha(id = id, nombre = "SALUD360 $id", apellido = "PRUEBA", dni = "30111222"), vinculadoPor = "dni",
    )

    private inner class Contexto(
        val db: Salud360Db,
        val driver: SqlDriver,
        val api: ApiFalsa,
        val pacientes: PacientesFalsa,
        val repo: EnfermedadesRepository,
        val pacientesHc: PacientesHcRepository,
        val c: EnfermedadesControlador,
    ) {
        /** Espera (hasta 5 s) a que [condicion] dé algo distinto de null y lo devuelve. */
        suspend fun <T : Any> hasta(que: String, condicion: () -> T?): T {
            val limite = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < limite) {
                condicion()?.let { return it }
                delay(10)
            }
            fail("No se cumplió a tiempo: $que\n  sección: ${c.estado.value}\n  administrar: ${c.administrar.value}\n  llamadas: ${api.llamadas.map { it.tipo }}")
        }

        /** La llamada [n] (desde 1) de ese tipo, esperando a que llegue. */
        suspend fun llamada(tipo: String, n: Int = 1): Llamada = hasta("la llamada $n de $tipo") { api.de(tipo).getOrNull(n - 1) }

        /** La llamada de ese tipo cuyo argumento [indice] es [valor] (los pedidos salen de corrutinas distintas). */
        suspend fun llamadaCon(tipo: String, indice: Int, valor: Any): Llamada =
            hasta("la llamada de $tipo con $valor") { api.de(tipo).firstOrNull { it.args[indice] == valor } }

        suspend fun abierta() {
            c.abrir()
            hasta("la sección abierta") { c.estado.value.takeIf { !it.cargando } }
        }

        suspend fun conAdministrar() {
            c.abrirAdministrar()
            hasta("administrar") { c.administrar.value.estado }
        }

        fun contar(sql: String): Long = consultar(driver, sql)

        fun tiene(id: Long): Boolean = c.estado.value.vista!!.marcables.first { it.id == id }.tiene

        /** Un rato para comprobar que algo NO pasó. */
        suspend fun unRato() = delay(200)
    }

    private fun conControlador(
        vinculado: Boolean = true,
        espera: Duration = 5.seconds,
        bloque: suspend Contexto.() -> Unit,
    ) {
        editable = true
        val archivo = File.createTempFile("salud360-controlador", ".db").also { it.delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    val db = Salud360Db(driver)
                    db.pacientesQueries.upsertPaciente(
                        Paciente(paciente, "30.111.222", "Salud360", "Prueba", mail = "prueba@ejemplo.com").toRow(0, dirty = false),
                    )
                    if (vinculado) {
                        db.pacientesQueries.upsertPacienteExtra(
                            PacienteExtra(paciente, CodigosHc.GENERAL, "hc_paciente_id", "10").toRow(0, dirty = false),
                        )
                    }
                    val api = ApiFalsa(inicial, administrarA)
                    val pacientes = PacientesFalsa()
                    val repo = EnfermedadesRepository(db, api, nombreHc, espera, scope)
                    val pacientesHc = PacientesHcRepository(
                        db, PacientesHcApis(mapOf(CodigosHc.GENERAL to pacientes)), mapOf(CodigosHc.GENERAL to nombreHc),
                    )
                    val c = EnfermedadesControlador(repo, pacientesHc, scope, medico, paciente) { editable }
                    Contexto(db, driver, api, pacientes, repo, pacientesHc, c).bloque()
                } finally {
                    scope.cancel()
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    // ---------- abrir: vínculo y un solo pedido (RF-8, RF-9, RF-15) ----------

    @Test
    fun sin_vinculo_lo_resuelve_con_pacientes_y_lo_guarda() = conControlador(vinculado = false) {
        pacientes.respuestas += ficha(10)
        abierta()

        assertEquals(1, pacientes.resueltos.size)
        assertEquals(10L, pacientesHc.fichaConocida(paciente, CodigosHc.GENERAL))
        assertEquals(listOf<Any?>(10L), llamada("seccion").args)
        assertEquals(inicial, c.estado.value.vista)
        assertEquals(10L, c.estado.value.fichaId)
    }

    @Test
    fun con_vinculo_guardado_no_lo_pide() = conControlador {
        abierta()
        assertEquals(emptyList(), pacientes.resueltos.toList())
        assertEquals(listOf<Any?>(10L), llamada("seccion").args)
        assertEquals(inicial, c.estado.value.vista)
        assertTrue(c.estado.value.conConexion)
    }

    @Test
    fun abrir_hace_un_solo_pedido_y_reemplaza_la_copia() = conControlador {
        api.seccion = seccion(1)
        repo.abrirSeccion(medico, 10)
        assertEquals(seccion(1), repo.copiaSeccion(medico, 10))
        api.llamadas.clear()

        api.seccion = inicial
        c.abrir()
        c.abrir() // abrir dos veces no repite el pedido
        hasta("la sección abierta") { c.estado.value.takeIf { !it.cargando } }

        assertEquals(1, api.de("seccion").size)
        assertEquals(inicial, repo.copiaSeccion(medico, 10))
        assertEquals(inicial, c.estado.value.vista)
    }

    // ---------- elegir ficha (RF-9) ----------

    @Test
    fun elegir_ficha_guarda_el_vinculo_y_carga_la_seccion() = conControlador(vinculado = false) {
        val candidatas = listOf(HcPacienteCandidato(20, "A", "B", "30111222"), HcPacienteCandidato(21, "A", "B", "30111222"))
        pacientes.respuestas += TobbException(409, "paciente_ambiguo", "Varias fichas", candidatos = candidatas)
        abierta()

        assertEquals(MotivoEnfermedades.ElegirFicha(candidatas), c.estado.value.motivo)
        assertNull(c.estado.value.vista)
        assertEquals(0, api.llamadas.size)
        assertTrue(c.puedeAdministrar()) // RF-37: también con el motivo de elegir ficha
        assertEquals(null, pacientesHc.fichaConocida(paciente, CodigosHc.GENERAL))

        pacientes.respuestas += ficha(21)
        c.elegirFicha(21)
        hasta("la sección cargada") { c.estado.value.vista }

        assertEquals(21L, pacientes.resueltos[1].second)
        assertEquals(21L, pacientesHc.fichaConocida(paciente, CodigosHc.GENERAL))
        assertEquals(listOf<Any?>(21L), llamada("seccion").args)
        assertNull(c.estado.value.motivo)
        assertEquals(inicial, c.estado.value.vista)
    }

    // ---------- tocar (RF-21, RF-23, RF-25, RF-28, RF-36) ----------

    @Test
    fun tocar_manda_el_estado_elegido_sin_esperar_al_autoguardado() = conControlador {
        abierta()
        api.retenidos += "marcar"

        c.tocar(2, true)
        // Enseguida: lo elegido y "Guardando…" (RF-21).
        assertTrue(tiene(2))
        assertTrue(c.estado.value.marcado.guardando(2))

        assertEquals(listOf<Any?>(10L, 2L, true), llamada("marcar").args)
        assertEquals(0L, contar("SELECT count(*) FROM seccion_valor"))
        llamada("marcar").responder(seccion(2))

        hasta("el guardado") { c.estado.value.takeIf { it.marcado.guardado == 1 && !it.marcado.guardando(2) } }
        assertTrue(tiene(2))
        // Desmarcar manda el estado elegido, no "invertir".
        c.tocar(2, false)
        assertEquals(listOf<Any?>(10L, 2L, false), llamada("marcar", 2).args)
    }

    @Test
    fun dos_toques_respondidos_al_reves_la_vieja_no_pisa_pero_deja_su_casilla() = conControlador {
        abierta()
        api.retenidos += "marcar"

        c.tocar(1, true)
        c.tocar(2, true)
        val asma = llamadaCon("marcar", 1, 1L)
        val diabetes = llamadaCon("marcar", 1, 2L)

        // La web procesó primero Diabetes: su respuesta todavía no tiene Asma.
        diabetes.responder(seccion(2))
        hasta("lo de Diabetes") { c.estado.value.takeIf { !it.marcado.guardando(2) } }
        assertTrue(tiene(1)) // Asma sigue en camino: conserva lo elegido
        assertTrue(c.estado.value.marcado.guardando(1))

        // La de Asma llega después y es más vieja: no pisa el estado más nuevo (Diabetes sigue marcada).
        asma.responder(seccion(1))
        hasta("lo de Asma") { c.estado.value.takeIf { !it.marcado.guardando(1) } }
        assertTrue(tiene(1))
        assertTrue(tiene(2))
        assertFalse(tiene(3))
        assertEquals(2, c.estado.value.marcado.guardado)
    }

    @Test
    fun un_422_avisa_con_el_nombre_y_pide_el_estado() = conControlador {
        abierta()
        api.retenidos += "marcar"
        api.seccion = EstadoSeccionEnfermedades(inicial.marcables.drop(1), emptyList()) // el médico ya no tiene Asma

        c.tocar(1, true)
        llamada("marcar").fallar(TobbException(422, "no_en_tu_lista", "No está en tu lista"))

        hasta("el estado nuevo") { c.estado.value.vista?.takeIf { it.marcables.size == 2 } }
        assertEquals(2, api.de("seccion").size) // al abrir y el que se pidió después del rechazo
        assertEquals(AvisoEnfermedades.Rechazo("«Asma» ya no está en tu lista"), c.estado.value.marcado.aviso)
        assertFalse(c.estado.value.marcado.soloLectura)
    }

    @Test
    fun sin_respuesta_y_el_estado_tambien_sin_respuesta_queda_solo_lectura_con_la_copia() = conControlador(espera = 100.milliseconds) {
        // Solo acá la espera es corta. Un primer pedido (con la carga de clases) puede excederla bajo carga: se lo hace
        // antes, para que la copia exista y la apertura del controlador no dependa del reloj.
        repo.abrirSeccion(medico, 10)
        api.llamadas.clear()
        abierta()
        api.retenidos += listOf("marcar", "seccion")

        c.tocar(1, true)
        // A los ~100 ms el toque no contesta y se pide el estado; a los ~100 ms más tampoco (RF-28).
        hasta("solo lectura") { c.estado.value.takeIf { it.marcado.soloLectura } }

        val estado = c.estado.value
        assertEquals(AvisoEnfermedades.Rechazo(textoSinConfirmar("Asma")), estado.marcado.aviso)
        assertEquals("Sin conexión: las enfermedades se pueden ver pero no cambiar", estado.avisoSoloLectura)
        assertEquals(2, api.de("seccion").size)
        assertFalse(tiene(1)) // el último estado confirmado por la web: lo que dice la copia
        assertEquals(inicial, repo.copiaSeccion(medico, 10))
        assertFalse(estado.marcado.puedeTocar(1))
        assertFalse(estado.conConexion)
        assertFalse(c.puedeAdministrar())

        // Las respuestas tardías se aplican (RF-23) pero la sección sigue en solo lectura hasta "Reintentar".
        llamada("seccion", 2).responder(seccion(1))
        llamada("marcar").responder(seccion(1))
        hasta("lo tardío") { c.estado.value.takeIf { tiene(1) } }
        assertTrue(c.estado.value.marcado.soloLectura)
        assertEquals(seccion(1), repo.copiaSeccion(medico, 10))
    }

    @Test
    fun reintentar_vuelve_a_pedir_la_seccion_y_sale_del_solo_lectura() = conControlador {
        abierta()
        api.fallo = sinRed
        c.reintentar()
        hasta("solo lectura con la copia") { c.estado.value.takeIf { !it.cargando && it.marcado.soloLectura } }
        assertEquals(inicial, c.estado.value.vista)
        assertEquals("Sin conexión: las enfermedades se pueden ver pero no cambiar", c.estado.value.avisoSoloLectura)

        api.fallo = null
        api.seccion = seccion(3)
        c.reintentar()
        hasta("sección actual") { c.estado.value.takeIf { !it.cargando && !it.marcado.soloLectura } }
        assertEquals(seccion(3), c.estado.value.vista)
        assertNull(c.estado.value.avisoSoloLectura)
        assertTrue(c.estado.value.conConexion)
    }

    @Test
    fun un_403_fuera_de_cartera_al_pedir_el_estado_da_su_motivo_y_no_deja_la_casilla_trabada() = conControlador {
        abierta()
        api.retenidos += "marcar"
        c.tocar(1, true)
        val toque = llamada("marcar")
        api.fallo = TobbException(403, "fuera_de_cartera", "El paciente no está en tu cartera")
        toque.fallar(sinRed) // sin conexión: se pide el estado, y ese lo rechaza la web

        hasta("el motivo") { c.estado.value.motivo }
        assertEquals(MotivoEnfermedades.FueraDeCartera, c.estado.value.motivo)
        assertNull(c.estado.value.vista)
        assertTrue(c.estado.value.marcado.enCamino.isEmpty())
        assertNull(repo.copiaSeccion(medico, 10)) // RF-11
    }

    @Test
    fun una_respuesta_con_la_lista_cambiada_desde_otro_lado_se_muestra_entera() = conControlador {
        abierta()
        api.retenidos += "marcar"
        c.tocar(1, true)
        // Mientras tanto desde la web: Hipertensión salió de la lista y entró Gota, y hay otra del paciente.
        val entera = EstadoSeccionEnfermedades(
            listOf(Marcable(1, "Asma", true), Marcable(2, "Diabetes", false), Marcable(9, "Gota", false)),
            listOf(Enfermedad(3, "Hipertensión"), Enfermedad(7, "Rinitis")),
        )
        llamada("marcar").responder(entera)
        hasta("lo de la web") { c.estado.value.takeIf { it.marcado.guardado == 1 } }
        assertEquals(entera, c.estado.value.vista)

        // Lo mismo con un cambio de la lista: se muestra lo que trae la respuesta, no lo que se esperaba.
        conAdministrar()
        val otra = EstadoAdministrar(listOf(Enfermedad(9, "Gota"), Enfermedad(1, "Asma")), listOf(Enfermedad(8, "Hernia")))
        api.respuestaLista = RespuestaLista(otra, null)
        c.agregar(4)
        assertEquals(otra, hasta("lo de la web en administrar") { c.administrar.value.takeIf { it.estado == otra } }.estado)
    }

    @Test
    fun tras_marcar_no_queda_nada_de_enfermedades_en_seccion_valor_ni_en_paciente_extra() = conControlador {
        abierta()
        val extrasAntes = contar("SELECT count(*) FROM paciente_extra")
        api.retenidos += "marcar"
        c.tocar(2, true)
        llamada("marcar").responder(seccion(2))
        hasta("el guardado") { c.estado.value.takeIf { it.marcado.guardado == 1 } }

        assertEquals(0L, contar("SELECT count(*) FROM seccion_valor"))
        assertEquals(0L, contar("SELECT count(*) FROM seccion_valor WHERE seccion = 'enfermedades'"))
        assertEquals(extrasAntes, contar("SELECT count(*) FROM paciente_extra"))
        assertEquals(0L, contar("SELECT count(*) FROM paciente_extra WHERE dirty = 1"))
        assertEquals(1L, contar("SELECT count(*) FROM hc_enfermedades WHERE parte = 'seccion'"))
    }

    // ---------- administrar (RF-38, RF-40, RF-41, RF-44, RF-45, RF-46, RF-58) ----------

    @Test
    fun abrir_administrar_hace_un_pedido_y_guarda_la_copia() = conControlador {
        abierta()
        c.abrirAdministrar()
        hasta("administrar") { c.administrar.value.estado }

        assertEquals(1, api.de("administrar").size)
        assertEquals(administrarA, c.administrar.value.estado)
        assertEquals(administrarA, repo.copiaAdministrar(medico))
        assertFalse(c.administrar.value.cargando)
        assertNull(c.administrar.value.aviso)
        assertTrue(c.puedeAdministrar())
    }

    @Test
    fun abrir_administrar_sin_conexion_avisa_y_no_muestra_nada() = conControlador {
        abierta()
        api.fallo = sinRed
        c.abrirAdministrar()
        hasta("el aviso") { c.administrar.value.aviso }
        assertNull(c.administrar.value.estado)
        assertEquals("Historia clínica de Clínica general: sin conexión. Intentá de nuevo en un momento.", c.administrar.value.aviso)
    }

    @Test
    fun agregar_y_quitar_muestran_administrar_y_la_seccion_de_la_respuesta() = conControlador {
        abierta()
        conAdministrar()

        // RF-40: agregar Artrosis; la respuesta trae también la sección (con la nueva casilla, sin marcar).
        val conArtrosis = EstadoAdministrar(administrarA.lista + Enfermedad(4, "Artrosis"), listOf(Enfermedad(5, "Bronquitis")))
        api.respuestaLista = RespuestaLista(conArtrosis, inicial.copy(marcables = inicial.marcables + Marcable(4, "Artrosis", false)))
        c.agregar(4)
        hasta("agregada") { c.administrar.value.takeIf { it.estado == conArtrosis && !it.enCamino } }
        assertEquals(listOf<Any?>(4L, true, 10L), api.de("enLista")[0].args)
        assertEquals(Marcable(4, "Artrosis", false), c.estado.value.vista!!.marcables.last())

        // RF-41: quitar Asma; el paciente la tenía: pasa a "Otras" (la respuesta de la web trae eso).
        val sinAsma = EstadoAdministrar(conArtrosis.lista.drop(1), conArtrosis.catalogo + Enfermedad(1, "Asma"))
        api.respuestaLista = RespuestaLista(
            sinAsma,
            EstadoSeccionEnfermedades(inicial.marcables.drop(1), listOf(Enfermedad(1, "Asma"), Enfermedad(7, "Rinitis"))),
        )
        c.quitar(1)
        hasta("quitada") { c.administrar.value.takeIf { it.estado == sinAsma && !it.enCamino } }
        assertEquals(listOf<Any?>(1L, false, 10L), api.de("enLista")[1].args)
        assertTrue(Enfermedad(1, "Asma") in c.estado.value.vista!!.otras)
        assertTrue(c.estado.value.vista!!.marcables.none { it.id == 1L })
        assertEquals(2, c.estado.value.marcado.guardado)
    }

    @Test
    fun crear_manda_el_nombre_normalizado_y_la_ficha_y_la_grilla_la_muestra_marcada() = conControlador {
        abierta()
        conAdministrar()
        val nueva = Enfermedad(9, "Prueba Nueva")
        api.respuestaCrear = RespuestaCrear(
            EstadoAdministrar(administrarA.lista + nueva, administrarA.catalogo),
            ResultadoCrear(nueva, reutilizada = false, reactivada = false, yaEnLista = false, marcada = true, sinMarcarPor = null),
            inicial.copy(marcables = inicial.marcables + Marcable(9, "Prueba Nueva", true)),
        )
        c.buscar("Prueba   Nueva ")
        c.crear("Prueba   Nueva ")

        hasta("creada") { c.administrar.value.takeIf { it.estado?.lista?.contains(nueva) == true && !it.enCamino } }
        assertEquals(listOf<Any?>("Prueba Nueva", 10L), api.de("crear").single().args)
        assertEquals(Marcable(9, "Prueba Nueva", true), c.estado.value.vista!!.marcables.last())
        assertNull(c.administrar.value.aviso) // nueva y marcada: la grilla ya la muestra (E11)
        assertEquals(1, c.estado.value.marcado.guardado)
    }

    @Test
    fun crear_sin_vinculo_no_manda_la_ficha_y_avisa_por_que_no_la_marco() = conControlador(vinculado = false) {
        val candidatas = listOf(HcPacienteCandidato(20, "A", "B", "30111222"), HcPacienteCandidato(21, "A", "B", "30111222"))
        pacientes.respuestas += TobbException(409, "paciente_ambiguo", "Varias fichas", candidatos = candidatas)
        abierta()
        conAdministrar()
        val nueva = Enfermedad(9, "Prueba Nueva")
        api.respuestaCrear = RespuestaCrear(
            EstadoAdministrar(administrarA.lista + nueva, administrarA.catalogo),
            ResultadoCrear(nueva, reutilizada = false, reactivada = false, yaEnLista = false, marcada = false, sinMarcarPor = null),
            null,
        )
        c.crear("Prueba Nueva")

        val aviso = hasta("el aviso") { c.administrar.value.aviso }
        assertEquals(listOf<Any?>("Prueba Nueva", null), api.de("crear").single().args)
        assertEquals("Se agregó a tu lista; no se marcó al paciente porque el paciente no está vinculado a una ficha de la web", aviso)
    }

    @Test
    fun crear_con_la_consulta_no_editable_no_manda_la_ficha() = conControlador {
        abierta()
        conAdministrar()
        editable = false
        val nueva = Enfermedad(9, "Prueba Nueva")
        api.respuestaCrear = RespuestaCrear(
            EstadoAdministrar(administrarA.lista + nueva, administrarA.catalogo),
            ResultadoCrear(nueva, reutilizada = false, reactivada = false, yaEnLista = false, marcada = false, sinMarcarPor = null),
            null,
        )
        c.crear("Prueba Nueva")

        val aviso = hasta("el aviso") { c.administrar.value.aviso }
        assertEquals(listOf<Any?>("Prueba Nueva", null), api.de("crear").single().args)
        assertEquals("Se agregó a tu lista; no se marcó al paciente porque la consulta no se puede editar", aviso)
        assertFalse(c.puedeAdministrar())
    }

    @Test
    fun un_nombre_que_no_sirve_no_se_envia_y_muestra_su_texto() = conControlador {
        abierta()
        conAdministrar()
        c.crear("   ")
        assertEquals("El nombre está vacío", c.administrar.value.aviso)
        c.crear("Con\u200Binvisible")
        assertEquals("El nombre tiene caracteres que no se ven: escribilo de nuevo", c.administrar.value.aviso)
        c.crear("x".repeat(256))
        assertEquals("El nombre tiene más de 255 caracteres: acortalo", c.administrar.value.aviso)
        unRato()
        assertEquals(0, api.de("crear").size)
        assertFalse(c.administrar.value.enCamino)
    }

    @Test
    fun un_cambio_de_la_lista_por_vez() = conControlador(espera = 5.seconds) { // sin que se cumpla la espera durante el rato
        abierta()
        conAdministrar()
        api.retenidos += "enLista"

        c.agregar(4)
        assertTrue(c.administrar.value.enCamino)
        val primera = llamada("enLista")
        // Mientras hay uno en camino, ni agregar, ni quitar, ni crear (RF-45).
        c.agregar(5)
        c.quitar(1)
        c.crear("Prueba Nueva")
        unRato()
        assertEquals(1, api.de("enLista").size)
        assertEquals(0, api.de("crear").size)

        val conArtrosis = EstadoAdministrar(administrarA.lista + Enfermedad(4, "Artrosis"), listOf(Enfermedad(5, "Bronquitis")))
        primera.responder(RespuestaLista(conArtrosis, inicial))
        hasta("terminado") { c.administrar.value.takeIf { !it.enCamino && it.estado == conArtrosis } }

        // Ya libre, el siguiente sí sale.
        c.quitar(1)
        llamada("enLista", 2)
    }

    @Test
    fun un_rechazo_conserva_lo_escrito_y_sin_conexion_pide_administrar_de_nuevo() = conControlador {
        abierta()
        conAdministrar()
        c.buscar("Artro")
        api.retenidos += "enLista"

        // 429: el motivo, sin cambiar lo que se ve, y el buscador queda como estaba (RF-46).
        c.agregar(4)
        llamada("enLista").fallar(TobbException(429, null, "Too Many Attempts."))
        hasta("el aviso") { c.administrar.value.takeIf { it.aviso == TEXTO_DEMASIADOS_CAMBIOS && !it.enCamino } }
        assertEquals("Artro", c.administrar.value.buscado)
        assertEquals(administrarA, c.administrar.value.estado)
        assertEquals(1, api.de("administrar").size)

        // Sin conexión: vuelve a pedir administrar y muestra ese (RF-28); sigue el aviso de que no se pudo confirmar.
        val nuevo = EstadoAdministrar(administrarA.lista + Enfermedad(4, "Artrosis"), listOf(Enfermedad(5, "Bronquitis")))
        api.lista = nuevo
        c.agregar(4)
        llamada("enLista", 2).fallar(sinRed)
        hasta("administrar de nuevo") { c.administrar.value.takeIf { it.estado == nuevo && !it.enCamino } }
        assertEquals(2, api.de("administrar").size)
        assertEquals(textoSinConfirmar("Artrosis"), c.administrar.value.aviso)
        assertEquals("Artro", c.administrar.value.buscado)
    }

    @Test
    fun un_rechazo_de_la_lista_no_previsto_o_de_nombre_da_su_texto() = conControlador {
        abierta()
        conAdministrar()
        api.retenidos += "enLista"
        c.agregar(4)
        llamada("enLista").fallar(TobbException(422, "enfermedad_dada_de_baja", "Dada de baja"))
        hasta("baja") { c.administrar.value.takeIf { it.aviso == "«Artrosis» se dio de baja en el catálogo" && !it.enCamino } }

        c.agregar(4)
        llamada("enLista", 2).fallar(TobbException(500, null, "Error interno"))
        hasta("no previsto") { c.administrar.value.takeIf { it.aviso == "No se pudo guardar el cambio. Probá de nuevo." && !it.enCamino } }

        api.retenidos += "crear"
        c.crear("Prueba Nueva")
        llamada("crear").fallar(TobbException(422, "nombre_invalido", "El nombre está vacío"))
        val aviso = hasta("nombre") { c.administrar.value.takeIf { it.aviso == "El nombre está vacío" && !it.enCamino } }.aviso
        assertEquals("El nombre está vacío", aviso)
    }

    // ---------- cerrar sesión con un pedido en camino (E12) ----------

    @Test
    fun una_respuesta_lenta_despues_del_logout_no_reescribe_la_copia() = conControlador(espera = 5.seconds) {
        api.retenidos += listOf("seccion", "administrar", "marcar")
        c.abrir()
        c.abrirAdministrar()
        llamada("seccion")
        llamada("administrar")
        assertEquals(0L, contar("SELECT count(*) FROM hc_enfermedades"))

        // Cierra la sesión con los dos pedidos en camino; la web contesta después.
        AuthRepository(db, ApiClient("http://localhost"), alCerrarSesion = repo::cerrarSesion).logout()
        llamada("seccion").responder(inicial)
        llamada("administrar").responder(administrarA)
        hasta("los dos pedidos terminados") { c.estado.value.takeIf { !it.cargando && !c.administrar.value.cargando } }

        assertEquals(0L, contar("SELECT count(*) FROM hc_enfermedades"))
        assertNull(repo.copiaSeccion(medico, 10))
        assertNull(repo.copiaAdministrar(medico))

        // También un toque en camino al cerrar sesión.
        c.tocar(1, true)
        val toque = llamada("marcar")
        AuthRepository(db, ApiClient("http://localhost"), alCerrarSesion = repo::cerrarSesion).logout()
        toque.responder(seccion(1))
        hasta("el toque terminado") { c.estado.value.takeIf { it.marcado.guardado == 1 } }
        assertEquals(0L, contar("SELECT count(*) FROM hc_enfermedades"))

        // Lo que se pide después de la sesión nueva sí se guarda: cerrar sesión no deja al repositorio sin copia.
        api.retenidos.clear()
        assertNotNull(repo.esperar(repo.pedirAdministrar(medico)) as? ResultadoPedido.Ok)
        assertEquals(administrarA, repo.copiaAdministrar(medico))
    }

    private fun consultar(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0).value
}
