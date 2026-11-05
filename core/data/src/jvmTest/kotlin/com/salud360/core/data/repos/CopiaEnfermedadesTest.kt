package com.salud360.core.data.repos

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.Enfermedad
import com.salud360.core.data.hcgeneral.EstadoAdministrar
import com.salud360.core.data.hcgeneral.EstadoSeccionEnfermedades
import com.salud360.core.data.hcgeneral.Marcable
import com.salud360.core.data.hcgeneral.MotivoEnfermedades
import com.salud360.core.data.hcgeneral.ResultadoCrear
import com.salud360.core.data.hcgeneral.motivoDeError
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.EnfermedadesApi
import com.salud360.core.data.network.hc.RespuestaCrear
import com.salud360.core.data.network.hc.RespuestaLista
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.auth.PerfilMedico
import com.salud360.core.model.auth.PerfilSecretaria
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.model.auth.Usuario
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Copia de las enfermedades en el dispositivo (RF-11, RF-30 a RF-34) sobre una base SQLite de verdad en un archivo
 * temporal, con una API falsa: sin red ni servidor. Los nombres de enfermedades son de prueba, nunca de la base real.
 */
class CopiaEnfermedadesTest {

    private val medico = "tobb-m1"
    private val otroMedico = "tobb-m2"
    private val nombreHc = "Clínica general"
    private val sinRed = TobbException(0, "red", "Sin conexión con la historia clínica de clinica")
    private val error500 = TobbException(500, null, "Error interno")

    private val seccionA = EstadoSeccionEnfermedades(
        listOf(Marcable(1, "Asma", true), Marcable(2, "Diabetes", false)),
        listOf(Enfermedad(3, "Hipertensión")),
    )
    private val seccionB = EstadoSeccionEnfermedades(listOf(Marcable(1, "Asma", false)), emptyList())
    private val seccionVacia = EstadoSeccionEnfermedades(emptyList(), emptyList())
    private val administrarA = EstadoAdministrar(listOf(Enfermedad(1, "Asma")), listOf(Enfermedad(2, "Diabetes")))
    private val administrarVacio = EstadoAdministrar(emptyList(), emptyList())

    /** API falsa: contesta lo que tenga cargado, cuando se abra la [compuerta] (si hay una), o lanza [fallo]. */
    private inner class ApiFalsa : EnfermedadesApi {
        var laSeccion = seccionA
        var laLista = administrarA
        var fallo: TobbException? = null
        var compuerta: CompletableDeferred<Unit>? = null
        var llamadas = 0

        private suspend fun <T> responder(valor: T): T {
            llamadas++
            compuerta?.await()
            fallo?.let { throw it }
            return valor
        }

        override suspend fun seccion(fichaId: Long) = responder(laSeccion)
        override suspend fun marcar(fichaId: Long, enfermedadId: Long, tiene: Boolean) = responder(laSeccion)
        override suspend fun administrar() = responder(laLista)
        override suspend fun enLista(enfermedadId: Long, enLista: Boolean, fichaId: Long?) =
            responder(RespuestaLista(laLista, fichaId?.let { laSeccion }))
        override suspend fun crear(nombre: String, fichaId: Long?) = responder(
            RespuestaCrear(
                laLista, ResultadoCrear(Enfermedad(9, nombre), false, false, false, fichaId != null, null), fichaId?.let { laSeccion },
            ),
        )
    }

    private fun conBase(bloque: suspend (Salud360Db, SqlDriver) -> Unit) {
        val archivo = File.createTempFile("salud360-enfermedades", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    bloque(Salud360Db(driver), driver)
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    /** La `espera` por defecto es larga a propósito: con la máquina cargada un pedido puede tardar segundos y no debe darse por sin conexión. */
    private fun repo(db: Salud360Db, api: EnfermedadesApi?, espera: Duration = 30.seconds) =
        EnfermedadesRepository(db, api, nombreHc, espera)

    // ---------- qué se guarda (RF-32, RF-33) ----------

    @Test
    fun guarda_administrar_y_seccion_por_medico_y_ficha() = conBase { db, driver ->
        val api = ApiFalsa()
        val repo = repo(db, api)
        assertEquals(CargaSeccion.Actual(seccionA), repo.abrirSeccion(medico, 10))
        api.laSeccion = seccionB
        repo.abrirSeccion(medico, 11)
        repo.abrirSeccion(otroMedico, 10)
        assertIs<ResultadoPedido.Ok<EstadoAdministrar>>(repo.esperar(repo.pedirAdministrar(medico)))

        assertEquals(seccionA, repo.copiaSeccion(medico, 10))
        assertEquals(seccionB, repo.copiaSeccion(medico, 11))
        assertEquals(seccionB, repo.copiaSeccion(otroMedico, 10))
        assertEquals(administrarA, repo.copiaAdministrar(medico))
        assertNull(repo.copiaAdministrar(otroMedico))
        assertNull(repo.copiaSeccion(medico, 12))
        assertEquals(4L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))
    }

    @Test
    fun marcar_agregar_y_crear_tambien_guardan_la_parte_que_traen() = conBase { db, _ ->
        val api = ApiFalsa()
        val repo = repo(db, api)
        repo.esperar(repo.pedirMarcar(medico, 10, 1, true))
        assertEquals(seccionA, repo.copiaSeccion(medico, 10))
        assertNull(repo.copiaAdministrar(medico))

        // Sin ficha la respuesta no trae sección: solo cambia administrar.
        api.laSeccion = seccionB
        repo.esperar(repo.pedirEnLista(medico, 2, true, null))
        assertEquals(administrarA, repo.copiaAdministrar(medico))
        assertEquals(seccionA, repo.copiaSeccion(medico, 10))

        // Con ficha trae las dos.
        api.laLista = administrarVacio
        repo.esperar(repo.pedirCrear(medico, "Prueba", 10))
        assertEquals(administrarVacio, repo.copiaAdministrar(medico))
        assertEquals(seccionB, repo.copiaSeccion(medico, 10))
    }

    @Test
    fun una_lista_vacia_reemplaza_la_copia() = conBase { db, _ ->
        val api = ApiFalsa()
        val repo = repo(db, api)
        repo.abrirSeccion(medico, 10)
        repo.esperar(repo.pedirAdministrar(medico))

        api.laSeccion = seccionVacia
        api.laLista = administrarVacio
        assertEquals(CargaSeccion.Actual(seccionVacia), repo.abrirSeccion(medico, 10))
        repo.esperar(repo.pedirAdministrar(medico))
        // Vacía no es lo mismo que nunca recibida.
        assertEquals(seccionVacia, repo.copiaSeccion(medico, 10))
        assertEquals(administrarVacio, repo.copiaAdministrar(medico))
    }

    @Test
    fun un_403_un_422_y_un_500_no_pisan_la_copia() = conBase { db, _ ->
        val api = ApiFalsa()
        val repo = repo(db, api)
        repo.abrirSeccion(medico, 10)
        repo.esperar(repo.pedirAdministrar(medico))
        api.laSeccion = seccionB
        api.laLista = administrarVacio

        listOf(
            TobbException(403, "licencia_vencida", "Licencia vencida"),
            TobbException(422, "no_en_tu_lista", "No está en tu lista"),
            TobbException(429, null, "Too Many Attempts."),
            error500,
        ).forEach { e ->
            api.fallo = e
            repo.abrirSeccion(medico, 10)
            assertIs<ResultadoPedido.Error>(repo.esperar(repo.pedirMarcar(medico, 10, 1, false)))
            repo.esperar(repo.pedirAdministrar(medico))
            repo.esperar(repo.pedirEnLista(medico, 1, false, 10))
            repo.esperar(repo.pedirCrear(medico, "Prueba", 10))
            assertEquals(seccionA, repo.copiaSeccion(medico, 10), "${e.status} ${e.codigo}")
            assertEquals(administrarA, repo.copiaAdministrar(medico), "${e.status} ${e.codigo}")
        }
    }

    @Test
    fun fuera_de_cartera_borra_solo_esa_ficha_y_no_muestra_copia() = conBase { db, _ ->
        val api = ApiFalsa()
        val repo = repo(db, api)
        repo.abrirSeccion(medico, 10)
        repo.abrirSeccion(medico, 11)
        repo.abrirSeccion(otroMedico, 10)
        repo.esperar(repo.pedirAdministrar(medico))

        api.fallo = TobbException(403, "fuera_de_cartera", "El paciente no está en tu cartera")
        assertEquals(CargaSeccion.Motivo(MotivoEnfermedades.FueraDeCartera), repo.abrirSeccion(medico, 10))
        assertNull(repo.copiaSeccion(medico, 10))
        assertEquals(seccionA, repo.copiaSeccion(medico, 11))
        assertEquals(seccionA, repo.copiaSeccion(otroMedico, 10))
        assertEquals(administrarA, repo.copiaAdministrar(medico))

        // También si el rechazo llega al marcar.
        assertIs<ResultadoPedido.Error>(repo.esperar(repo.pedirMarcar(medico, 11, 1, true)))
        assertNull(repo.copiaSeccion(medico, 11))
    }

    // ---------- sin conexión (RF-30, RF-31) ----------

    @Test
    fun sin_red_con_copia_es_solo_lectura_con_el_aviso() = conBase { db, _ ->
        val api = ApiFalsa()
        val repo = repo(db, api)
        repo.abrirSeccion(medico, 10)
        val esperado = CargaSeccion.SoloLectura(seccionA, "Sin conexión: las enfermedades se pueden ver pero no cambiar")

        api.fallo = sinRed
        assertEquals(esperado, repo.abrirSeccion(medico, 10))
        api.fallo = TobbException(503, "tobb_caido", "Turnos no responde")
        assertEquals(esperado, repo.abrirSeccion(medico, 10))
        // La copia sigue intacta.
        assertEquals(seccionA, repo.copiaSeccion(medico, 10))
    }

    @Test
    fun con_un_500_y_copia_muestra_la_copia_con_el_texto_de_la_web() = conBase { db, _ ->
        val api = ApiFalsa()
        val repo = repo(db, api)
        repo.abrirSeccion(medico, 10)

        api.fallo = error500
        assertEquals(CargaSeccion.SoloLectura(seccionA, "Historia clínica de Clínica general: Error interno"), repo.abrirSeccion(medico, 10))
        api.fallo = TobbException(503, "ocupado", "El servidor está ocupado")
        assertEquals(
            CargaSeccion.SoloLectura(seccionA, "Historia clínica de Clínica general: El servidor está ocupado"),
            repo.abrirSeccion(medico, 10),
        )
        api.fallo = TobbException(0, "respuesta", "No se pudo leer la respuesta")
        assertEquals(
            CargaSeccion.SoloLectura(seccionA, "Historia clínica de Clínica general: No se pudo leer la respuesta"),
            repo.abrirSeccion(medico, 10),
        )
    }

    @Test
    fun sin_red_y_sin_copia_pide_conexion_y_con_un_500_muestra_el_texto_de_la_web() = conBase { db, _ ->
        val api = ApiFalsa()
        val repo = repo(db, api)

        api.fallo = sinRed
        assertEquals(CargaSeccion.SinCopia("Hace falta conexión para ver las enfermedades"), repo.abrirSeccion(medico, 10))
        api.fallo = error500
        assertEquals(CargaSeccion.SinCopia("Historia clínica de Clínica general: Error interno"), repo.abrirSeccion(medico, 10))
        // Una copia de otra ficha o de otro médico no sirve.
        api.fallo = null
        repo.abrirSeccion(medico, 11)
        repo.abrirSeccion(otroMedico, 10)
        api.fallo = sinRed
        assertEquals(CargaSeccion.SinCopia("Hace falta conexión para ver las enfermedades"), repo.abrirSeccion(medico, 10))
    }

    @Test
    fun los_otros_motivos_no_muestran_la_copia() = conBase { db, _ ->
        val api = ApiFalsa()
        val repo = repo(db, api)
        repo.abrirSeccion(medico, 10)

        listOf(
            TobbException(401, "token", "Sesión vencida"),
            TobbException(403, "hc_no_habilitada", "No habilitada"),
            TobbException(403, "medico_no_vinculado", "No vinculado"),
            TobbException(403, "licencia_vencida", "Licencia vencida"),
            TobbException(404, "paciente_no_encontrado", "No existe"),
        ).forEach { e ->
            api.fallo = e
            assertEquals(CargaSeccion.Motivo(motivoDeError(e, nombreHc)), repo.abrirSeccion(medico, 10), e.codigo)
        }
        assertEquals(seccionA, repo.copiaSeccion(medico, 10))
    }

    @Test
    fun sin_api_es_como_sin_conexion() = conBase { db, _ ->
        val sinApi = repo(db, null)
        assertEquals(CargaSeccion.SinCopia("Hace falta conexión para ver las enfermedades"), sinApi.abrirSeccion(medico, 10))

        // Con algo guardado antes (con API) vale eso.
        repo(db, ApiFalsa()).abrirSeccion(medico, 10)
        assertEquals(
            CargaSeccion.SoloLectura(seccionA, "Sin conexión: las enfermedades se pueden ver pero no cambiar"),
            sinApi.abrirSeccion(medico, 10),
        )
        val e = (sinApi.esperar(sinApi.pedirAdministrar(medico)) as ResultadoPedido.Error).error
        assertEquals("red", e.codigo)
    }

    @Test
    fun una_respuesta_lenta_es_sin_conexion_pero_igual_actualiza_la_copia() = conBase { db, _ ->
        val api = ApiFalsa()
        // La copia inicial va con la espera larga; la corta (100 ms) solo para el pedido retenido, que no contesta hasta
        // que el test abre la compuerta: no depende de cuánto tarde la máquina.
        repo(db, api).abrirSeccion(medico, 10)
        val repo = repo(db, api, espera = 100.milliseconds)

        api.laSeccion = seccionB
        val compuerta = CompletableDeferred<Unit>().also { api.compuerta = it }
        val pedido = repo.pedirSeccion(medico, 10)
        assertEquals(ResultadoPedido.SinRespuesta, repo.esperar(pedido))
        // El pedido sigue en camino: la copia todavía es la vieja.
        assertEquals(seccionA, repo.copiaSeccion(medico, 10))
        assertEquals(
            CargaSeccion.SoloLectura(seccionA, "Sin conexión: las enfermedades se pueden ver pero no cambiar"),
            repo.abrirSeccion(medico, 10),
        )

        // La respuesta tardía llega (quien la pidió puede esperarla) y actualiza la copia.
        compuerta.complete(Unit)
        assertEquals(ResultadoPedido.Ok(seccionB), pedido.await())
        assertEquals(seccionB, repo.copiaSeccion(medico, 10))
    }

    // ---------- nunca dirty (RF-32) ----------

    @Test
    fun la_tabla_no_tiene_dirty_ni_deleted_y_guardar_no_toca_otras_tablas() = conBase { db, driver ->
        assertEquals(listOf("medico_id", "parte", "ficha_id", "datos_json", "recibida_en"), columnas(driver))

        val antes = consultar(driver, "SELECT (SELECT count(*) FROM seccion_valor) + (SELECT count(*) FROM paciente_extra)")
        val api = ApiFalsa()
        val repo = repo(db, api)
        repo.abrirSeccion(medico, 10)
        repo.esperar(repo.pedirMarcar(medico, 10, 1, true))
        repo.esperar(repo.pedirAdministrar(medico))
        repo.esperar(repo.pedirEnLista(medico, 1, true, 10))
        repo.esperar(repo.pedirCrear(medico, "Prueba", 10))
        assertEquals(antes, consultar(driver, "SELECT (SELECT count(*) FROM seccion_valor) + (SELECT count(*) FROM paciente_extra)"))
        assertEquals(2L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))
    }

    // ---------- cerrar sesión y cambiar de médico (RF-34) ----------

    private suspend fun conCopiaDeDos(db: Salud360Db) {
        val repo = repo(db, ApiFalsa())
        repo.abrirSeccion(medico, 10)
        repo.esperar(repo.pedirAdministrar(medico))
        repo.abrirSeccion(otroMedico, 10)
        repo.esperar(repo.pedirAdministrar(otroMedico))
    }

    private fun unaSesionDeMedico(id: String) = Sesion(
        Usuario("u-$id", "$id@prueba.test", "Prueba", "Médico", Rol.MEDICO), "", medico = PerfilMedico(id, listOf("clinica"), false),
    )

    @Test
    fun logout_borra_toda_la_copia() = conBase { db, driver ->
        conCopiaDeDos(db)
        assertEquals(4L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))
        // Como en `DataModule`: el borrado lo hace `cerrarSesion`, dentro de la guardia (E12), no `logout` por su cuenta.
        AuthRepository(db, ApiClient("http://localhost"), alCerrarSesion = repo(db, ApiFalsa())::cerrarSesion).logout()
        assertEquals(0L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))
    }

    @Test
    fun cerrar_sesion_borra_toda_la_copia_y_lo_que_estaba_en_camino_no_la_reescribe() = conBase { db, driver ->
        conCopiaDeDos(db)
        val api = ApiFalsa()
        val repo = repo(db, api)
        val compuerta = CompletableDeferred<Unit>().also { api.compuerta = it }
        val enCamino = repo.pedirAdministrar(medico) // salió antes de cerrar sesión y contesta después

        repo.cerrarSesion()
        assertEquals(0L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))

        compuerta.complete(Unit)
        assertEquals(ResultadoPedido.Ok(administrarA), enCamino.await()) // el pedido igual termina
        assertEquals(0L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))

        // Lo que sale con la sesión nueva sí se guarda: cerrar sesión no inutiliza al repositorio.
        repo.esperar(repo.pedirAdministrar(medico))
        assertEquals(administrarA, repo.copiaAdministrar(medico))
        assertEquals(1L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))
    }

    @Test
    fun guardar_la_sesion_borra_la_copia_de_los_demas_medicos() = conBase { db, driver ->
        // Es la línea de `guardarSesion` que `login`, `restaurar` y `refrescarPerfil` comparten (RF-34): sin ella, un
        // médico que entra vería la copia del anterior.
        conCopiaDeDos(db)
        AuthRepository(db, ApiClient("http://localhost")).guardarSesion(unaSesionDeMedico(medico))
        assertEquals(2L, consultar(driver, "SELECT count(*) FROM hc_enfermedades WHERE medico_id = '$medico'"))
        assertEquals(0L, consultar(driver, "SELECT count(*) FROM hc_enfermedades WHERE medico_id <> '$medico'"))

        AuthRepository(db, ApiClient("http://localhost")).guardarSesion(Sesion(Usuario("u-a", "a@prueba.test", "Prueba", "Admin", Rol.ADMIN), ""))
        assertEquals(0L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))
    }

    @Test
    fun entrar_con_otro_medico_borra_lo_de_los_demas_y_conserva_lo_propio() = conBase { db, driver ->
        conCopiaDeDos(db)
        AuthRepository(db, ApiClient("http://localhost")).borrarCopiaEnfermedadesAlEntrar(unaSesionDeMedico(medico))
        assertEquals(2L, consultar(driver, "SELECT count(*) FROM hc_enfermedades WHERE medico_id = '$medico'"))
        assertEquals(0L, consultar(driver, "SELECT count(*) FROM hc_enfermedades WHERE medico_id <> '$medico'"))

        // Volver a entrar el mismo médico no pierde nada.
        AuthRepository(db, ApiClient("http://localhost")).borrarCopiaEnfermedadesAlEntrar(unaSesionDeMedico(medico))
        assertEquals(2L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))

        // Un médico sin copia previa deja la base vacía.
        AuthRepository(db, ApiClient("http://localhost")).borrarCopiaEnfermedadesAlEntrar(unaSesionDeMedico("tobb-m3"))
        assertEquals(0L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))
    }

    @Test
    fun el_administrador_y_la_secretaria_borran_todo() = conBase { db, driver ->
        val auth = AuthRepository(db, ApiClient("http://localhost"))
        conCopiaDeDos(db)
        auth.borrarCopiaEnfermedadesAlEntrar(Sesion(Usuario("u-a", "a@prueba.test", "Prueba", "Admin", Rol.ADMIN), ""))
        assertEquals(0L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))

        conCopiaDeDos(db)
        val secretaria = Sesion(
            Usuario("u-s", "s@prueba.test", "Prueba", "Secretaria", Rol.SECRETARIA), "",
            secretaria = PerfilSecretaria("s1", listOf("c1"), listOf(medico)),
        )
        auth.borrarCopiaEnfermedadesAlEntrar(secretaria)
        assertEquals(0L, consultar(driver, "SELECT count(*) FROM hc_enfermedades"))
    }

    // ---------- migración 7.sqm (v7 -> v8) ----------

    @Test
    fun el_esquema_es_de_la_version_8_y_migrate_7_a_8_crea_la_tabla() {
        // El esquema actual es el de la versión 10 (8.sqm solo mueve datos y 9.sqm agrega la marca de consulta propia); acá se prueba la 7.sqm.
        assertEquals(10L, Salud360Db.Schema.version)
        val archivo = File.createTempFile("salud360-migracion", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                val fresca = definicion(driver)
                assertEquals(listOf("medico_id", "parte", "ficha_id"), fresca.filter { it.clave > 0 }.sortedBy { it.clave }.map { it.nombre })
                driver.execute(null, "DROP TABLE hc_enfermedades", 0)
                Salud360Db.Schema.synchronous().migrate(driver, 7, 8)
                assertEquals(listOf("medico_id", "parte", "ficha_id", "datos_json", "recibida_en"), columnas(driver))
                // `7.sqm` y `HcEnfermedades.sq` dan la misma tabla: nombres, tipos, obligatoriedad, defecto y clave primaria.
                assertEquals(fresca, definicion(driver))
                val repo = repo(Salud360Db(driver), ApiFalsa())
                repo.abrirSeccion(medico, 10)
                assertEquals(seccionA, repo.copiaSeccion(medico, 10))
                driver.close()
            }
        } finally {
            archivo.delete()
        }
    }

    @Test
    fun al_abrir_una_base_de_la_version_7_con_datos_el_driver_la_migra_y_conserva_todo() {
        val archivo = File.createTempFile("salud360-migracion", ".db").also { it.delete() }
        try {
            runBlocking {
                // Base como la dejó la versión anterior de la app: sin la tabla y con user_version 7, y con datos.
                DriverFactory(archivo.absolutePath).createDriver().apply {
                    execute(null, "DROP TABLE hc_enfermedades", 0)
                    // Sin la columna de la `9.sqm`, que el driver agrega al migrar.
                    execute(null, "ALTER TABLE consulta DROP COLUMN propia", 0)
                    execute(null, "PRAGMA user_version = 7", 0)
                    execute(null, "INSERT INTO hc_config(medico_id, hc_codigo, config_json, recibida_en) VALUES ('$medico', 'general', 'null', 5)", 0)
                    execute(null, "INSERT INTO sync_estado(tabla, ultima_version, ultima_sync) VALUES ('paciente', 3, 0)", 0)
                    close()
                }
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                val db = Salud360Db(driver)
                assertEquals(10L, consultar(driver, "PRAGMA user_version"))
                // Lo que ya había en la base sigue ahí.
                assertEquals(1L, consultar(driver, "SELECT count(*) FROM hc_config WHERE medico_id = '$medico' AND recibida_en = 5"))
                assertEquals(3L, consultar(driver, "SELECT ultima_version FROM sync_estado WHERE tabla = 'paciente'"))
                assertEquals(listOf("medico_id", "parte", "ficha_id", "datos_json", "recibida_en"), columnas(driver))
                assertEquals(definicionDeUnaBaseNueva(), definicion(driver))
                val repo = repo(db, ApiFalsa())
                repo.abrirSeccion(medico, 10)
                assertEquals(seccionA, repo.copiaSeccion(medico, 10))
                driver.close()
            }
        } finally {
            archivo.delete()
        }
    }

    private fun columnas(driver: SqlDriver): List<String> = definicion(driver).map { it.nombre }

    /** Una columna de `PRAGMA table_info`: para comparar la tabla migrada con la creada de cero. */
    private data class Columna(val nombre: String, val tipo: String, val obligatoria: Boolean, val porDefecto: String?, val clave: Int)

    private fun definicion(driver: SqlDriver): List<Columna> =
        driver.executeQuery(null, "PRAGMA table_info(hc_enfermedades)", { c ->
            QueryResult.Value(
                buildList {
                    while (c.next().value) add(Columna(c.getString(1)!!, c.getString(2)!!, c.getLong(3) == 1L, c.getString(4), c.getLong(5)!!.toInt()))
                },
            )
        }, 0).value

    private suspend fun definicionDeUnaBaseNueva(): List<Columna> {
        val archivo = File.createTempFile("salud360-fresca", ".db").also { it.delete() }
        try {
            val driver = DriverFactory(archivo.absolutePath).createDriver()
            try {
                return definicion(driver)
            } finally {
                driver.close()
            }
        } finally {
            archivo.delete()
        }
    }

    private fun consultar(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) }, 0).value
}
