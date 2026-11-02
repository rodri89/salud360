package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.json
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.data.uno
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.database.createDatabase
import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Usuario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las licencias del panel de administración, contra una API de pediatría de verdad.
 *
 * La licencia es el permiso del médico para entrar a la historia clínica y vive en la base de
 * pediatría, que es donde se cobra. La prueba recorre el camino que usa el panel: leerlas, cambiarle
 * la fecha a una, volver a leerla para confirmar que quedó del otro lado, y dejarla como estaba.
 *
 * Necesita el token de un **administrador**: a un médico la API no le informa ninguna, a propósito.
 * Se saltea sola si no se lo pasan.
 *
 * Los de la spec 004 (el servidor decide la licencia) además mueven la licencia del **médico de prueba** con el token
 * de administrador y la leen con el del médico, así que piden también `-Psalud360.test.hc.token`. La restauran siempre
 * al terminar; aun así, solo contra la **copia local** de pediatría, nunca contra producción: evaluar una licencia
 * vencida hace que el propio servidor la deje inactiva, y un pedido cada ~1,1 s por el límite de 60 por minuto.
 *
 * ```
 * ./gradlew :core:data:jvmTest --rerun --tests '*HcLicenciasE2ETest*' \
 *   -Psalud360.test.hc.pediatria=http://localhost/HCDPediatria-salud360/public/index.php \
 *   -Psalud360.test.hc.token.admin=<token en claro de un administrador> \
 *   -Psalud360.test.hc.token=<token en claro del médico de prueba>
 * ```
 */
class HcLicenciasE2ETest {

    private val url = System.getProperty("salud360.test.hc.pediatria").orEmpty()
    private val token = System.getProperty("salud360.test.hc.token.admin").orEmpty()
    private val tokenMedico = System.getProperty("salud360.test.hc.token").orEmpty()

    @Test
    fun elPanelLeeYEscribeLasLicenciasDePediatria() {
        if (url.isBlank() || token.isBlank()) {
            println("HcLicenciasE2ETest: sin -Psalud360.test.hc.pediatria / .token.admin, no se corre.")
            return
        }
        val archivo = File.createTempFile("salud360-licencias", ".db").also { it.delete() }
        runBlocking {
            val db = createDatabase(DriverFactory(archivo.absolutePath))
            val api = HcApiClient(url, "pediatria").also { it.token = token }
            val backend = HcPediatriaBackend(db, api)

            val antes = backend.traerLicencias()
            assertTrue(antes.isNotEmpty(), "el administrador tiene que ver las licencias de pediatría")
            val original = antes.first()
            assertTrue(
                TobbIds.numero(original.medicoId) != null,
                "las licencias viajan con el número de médico de turnos, que es como la app nombra a los médicos",
            )

            try {
                val cambiada = original.copy(fechaExpiracion = VENCE, fechaAviso = AVISO)
                assertTrue(backend.guardarLicencia(cambiada), "pediatría tiene que aceptar el cambio")
                val despues = backend.traerLicencias().first { it.medicoId == original.medicoId }
                assertEquals(VENCE, despues.fechaExpiracion, "la fecha de vencimiento no quedó guardada")
                assertEquals(AVISO, despues.fechaAviso, "la fecha de aviso no quedó guardada")
                assertEquals(original.importe, despues.importe, "el importe no tenía que cambiar")

                // Y el camino completo del panel: el repositorio guarda en el dispositivo y en la
                // especialidad. El médico tiene que estar, porque es lo que dice a qué API mandarla.
                val ahora = ahoraMillis()
                db.authQueries.upsertMedico(
                    Medico(original.medicoId, "u-prueba", "Prueba", "Licencias", especialidadesHc = listOf("pediatria"))
                        .toRow(ahora),
                )
                val admin = AdminRepository(db, ApiClient("http://localhost:1"), mapOf("pediatria" to backend))
                admin.sincronizarLicencias()
                assertEquals(
                    emptyList(),
                    db.authQueries.licenciasDirty().lista { it.medico_id },
                    "la copia del dispositivo no tiene que quedar pendiente de enviar: el dueño del dato es pediatría",
                )
                assertTrue(admin.guardarLicencia(cambiada), "el panel tiene que poder guardar la licencia")
                assertEquals(
                    emptyList(),
                    db.authQueries.licenciasDirty().lista { it.medico_id },
                    "lo que llegó a pediatría no queda pendiente de enviar",
                )
                assertEquals(VENCE, admin.licencia(original.medicoId)?.fechaExpiracion)
            } finally {
                // La licencia decide quién entra: dejarla cambiada dejaría a un médico real afuera.
                backend.guardarLicencia(original)
            }
        }
    }

    /**
     * El otro lado de la misma regla: al médico le tiene que llegar el aviso de que su licencia está
     * por vencer, con la fecha. La fecha vive en la base de pediatría, así que la prueba la mueve con
     * el token del administrador y la lee con el del médico, que es como pasa de verdad.
     */
    @Test
    fun elMedicoSeEnteraDeQueSuLicenciaEstaPorVencer() {
        if (url.isBlank() || token.isBlank() || tokenMedico.isBlank()) {
            println("HcLicenciasE2ETest: sin -Psalud360.test.hc.token (médico) además del de administrador, no se corre.")
            return
        }
        val archivo = File.createTempFile("salud360-aviso", ".db").also { it.delete() }
        runBlocking {
            val db = createDatabase(DriverFactory(archivo.absolutePath))
            val comoAdmin = HcPediatriaBackend(db, HcApiClient(url, "pediatria").also { it.token = token })
            val apiMedico = HcApiClient(url, "pediatria").also { it.token = tokenMedico }
            val comoMedico = HcPediatriaBackend(db, apiMedico)

            // De quién es el token del médico: la licencia que hay que mover es la suya y no otra.
            val perfil = apiMedico.leer(PerfilDePrueba.serializer(), apiMedico.get("auth/perfil"), "perfil")
            val medicoId = TobbIds.medico(perfil.medicoIdTobb)
            val original = assertNotNull(
                comoAdmin.traerLicencias().firstOrNull { it.medicoId == medicoId },
                "el médico del token tiene que tener licencia, si no no habría entrado",
            )

            val cerca = hoy().plus(DatePeriod(days = 10)).toString()
            val lejos = hoy().plus(DatePeriod(years = 2)).toString()
            try {
                // Dentro de la ventana de aviso: entra igual, pero se lo avisa.
                assertTrue(comoAdmin.guardarLicencia(original.copy(fechaExpiracion = cerca, fechaAviso = hoy().minus(DatePeriod(days = 1)).toString())))
                val aviso = comoMedico.avisoDeLicencia()
                assertEquals(cerca, aviso?.vence, "el médico tiene que recibir la fecha en la que vence")
                assertEquals("pediatria", aviso?.especialidad)

                // Fuera de la ventana no molesta.
                assertTrue(comoAdmin.guardarLicencia(original.copy(fechaExpiracion = lejos, fechaAviso = lejos)))
                assertNull(comoMedico.avisoDeLicencia(), "sin aviso pendiente no se le muestra nada")
            } finally {
                comoAdmin.guardarLicencia(original)
            }
        }
    }

    // ---- Spec 004: la licencia la decide el servidor ----

    /**
     * `licenciaDelServidor()` sigue a la licencia del médico en la base de pediatría: vigente con su fecha, vencida
     * (el propio servidor la deja marcada inactiva al detectarlo, como la web), dada de baja y de nuevo vigente.
     * No cubre al médico sin ninguna fila (`SinLicencia`): para eso habría que borrar la fila de prueba, y lo cubren
     * los tests con servidor falso (T3b a T5) y la prueba manual M9.
     */
    @Test
    fun elServidorDiceSiLaLicenciaDelMedicoEstaVigenteOVencida() {
        if (faltanPropiedades("elServidorDiceSiLaLicenciaDelMedicoEstaVigenteOVencida")) return
        conElMedicoDePrueba { banco ->
            val original = banco.original
            val lejos = hoy().plus(DatePeriod(years = 2)).toString()
            val ayer = hoy().minus(DatePeriod(days = 1)).toString()
            val haceDiez = hoy().minus(DatePeriod(days = 10)).toString()

            banco.fijar(original.copy(fechaExpiracion = lejos, fechaAviso = lejos, activo = true))
            assertEquals(EstadoLicenciaRemoto.Vigente(lejos, lejos, porVencer = false), banco.leer(), "licencia lejana")

            banco.fijar(original.copy(fechaExpiracion = ayer, fechaAviso = haceDiez, activo = true))
            assertEquals(EstadoLicenciaRemoto.Vencida, banco.leer(), "vencida ayer")
            assertEquals(false, banco.guardada().activo, "al detectar el vencimiento el servidor deja la licencia inactiva")

            banco.fijar(original.copy(fechaExpiracion = lejos, fechaAviso = lejos, activo = false))
            assertEquals(EstadoLicenciaRemoto.Vencida, banco.leer(), "dada de baja, aunque la fecha sea lejana")

            banco.fijar(original)
            val restaurada = banco.leer()
            assertEquals(EstadoLicenciaRemoto.Vigente(original.fechaExpiracion, original.fechaAviso, restaurada.porVencer()), restaurada, "restaurada")
        }
    }

    /**
     * El caso reportado, de punta a punta: la copia del dispositivo dice «inactiva y vencida» y el servidor dice que la
     * licencia está vigente. Al recargar (sesión guardada, turnos sin señal) el médico entra y la copia queda al día.
     */
    @Test
    fun elMedicoConLaCopiaVencidaEntraSiElServidorDiceQueLaLicenciaEstaVigente() {
        if (faltanPropiedades("elMedicoConLaCopiaVencidaEntraSiElServidorDiceQueLaLicenciaEstaVigente")) return
        conElMedicoDePrueba { banco ->
            val lejos = hoy().plus(DatePeriod(years = 2)).toString()
            val ayer = hoy().minus(DatePeriod(days = 1)).toString()
            banco.fijar(banco.original.copy(fechaExpiracion = lejos, fechaAviso = lejos, activo = true))
            val app = appDelMedico(banco.medicoId, copia = Licencia(banco.medicoId, ayer, ayer, importe = 12.5, activo = false))

            val sesion = espaciado { app.repo.restaurar() }

            assertTrue(sesion != null, "con la licencia vigente en el servidor el médico entra aunque la copia diga vencida")
            assertTrue(app.repo.sesion.value != null, "la sesión quedó publicada")
            assertEquals(Licencia(banco.medicoId, lejos, lejos, importe = 12.5, activo = true), app.copiaLocal(), "la copia quedó al día y conservó el importe")
            assertEquals(emptyList(), app.dirty(), "la copia no queda pendiente de envío: el dueño es pediatría")
            assertTrue(app.sesionGuardada(), "la sesión quedó guardada")
        }
    }

    /** El inverso: la copia dice «vigente» y el servidor ya la dio por vencida. El médico no entra y no se publica nada. */
    @Test
    fun elMedicoConLaCopiaVigenteNoEntraSiElServidorDiceQueLaLicenciaEstaVencida() {
        if (faltanPropiedades("elMedicoConLaCopiaVigenteNoEntraSiElServidorDiceQueLaLicenciaEstaVencida")) return
        conElMedicoDePrueba { banco ->
            val lejos = hoy().plus(DatePeriod(years = 2)).toString()
            val ayer = hoy().minus(DatePeriod(days = 1)).toString()
            banco.fijar(banco.original.copy(fechaExpiracion = ayer, fechaAviso = ayer, activo = true))
            val app = appDelMedico(banco.medicoId, copia = Licencia(banco.medicoId, lejos, lejos, importe = 12.5, activo = true))
            val vistos = mutableListOf<Boolean>()
            val colector = launch(Dispatchers.Unconfined) { app.repo.sesion.collect { vistos += it != null } }

            val sesion = espaciado { app.repo.restaurar() }
            colector.cancel()

            assertTrue(sesion == null, "con la licencia vencida en el servidor el médico no entra aunque la copia diga vigente")
            assertEquals(listOf(false), vistos, "`sesion` nunca pasó por un valor no nulo")
            assertTrue(!app.sesionGuardada(), "no quedó sesión guardada")
            assertTrue(app.cliente.token == null, "el cliente de HC quedó sin token")
            assertEquals(Licencia(banco.medicoId, lejos, lejos, importe = 12.5, activo = false), app.copiaLocal(), "la copia quedó inactiva")
            assertEquals(emptyList(), app.dirty())
        }
    }

    /** Las tres propiedades hacen falta; si falta alguna la prueba se saltea (así el `jvmTest` de siempre no necesita servidor). */
    private fun faltanPropiedades(prueba: String): Boolean {
        if (url.isNotBlank() && token.isNotBlank() && tokenMedico.isNotBlank()) return false
        println("HcLicenciasE2ETest.$prueba: sin -Psalud360.test.hc.pediatria / .token.admin / .token (médico), no se corre.")
        return true
    }

    /** El servidor limita a 60 pedidos por minuto por IP: un pedido cada ~1,1 s. */
    private suspend fun <T> espaciado(pedido: suspend () -> T): T {
        delay(PAUSA_ENTRE_PEDIDOS_MS)
        return pedido()
    }

    private suspend fun baseTemporal(): Salud360Db {
        val archivo = File.createTempFile("salud360-licencias-e2e", ".db").also { it.delete(); it.deleteOnExit() }
        return createDatabase(DriverFactory(archivo.absolutePath))
    }

    private fun EstadoLicenciaRemoto.porVencer(): Boolean = (this as? EstadoLicenciaRemoto.Vigente)?.porVencer ?: false

    /**
     * La licencia del médico del token, en el servidor: el administrador la mueve y el médico la lee, que es como pasa de
     * verdad. [original] es como estaba, y se restaura siempre al terminar, pase lo que pase.
     */
    private inner class Banco(
        private val admin: HcPediatriaBackend,
        private val comoMedico: HcPediatriaBackend,
        val medicoId: Id,
        val original: Licencia,
    ) {
        suspend fun fijar(licencia: Licencia) = espaciado { assertTrue(admin.guardarLicencia(licencia), "pediatría tiene que aceptar el cambio de licencia") }
        suspend fun leer(): EstadoLicenciaRemoto = espaciado { comoMedico.licenciaDelServidor() }
        suspend fun guardada(): Licencia = espaciado { admin.traerLicencias().first { it.medicoId == medicoId } }
    }

    private fun conElMedicoDePrueba(bloque: suspend kotlinx.coroutines.CoroutineScope.(Banco) -> Unit) = runBlocking {
        val db = baseTemporal()
        val admin = HcPediatriaBackend(db, HcApiClient(url, "pediatria").also { it.token = token })
        val apiMedico = HcApiClient(url, "pediatria").also { it.token = tokenMedico }
        val perfil = espaciado { apiMedico.leer(PerfilDePrueba.serializer(), apiMedico.get("auth/perfil"), "perfil") }
        val medicoId = TobbIds.medico(perfil.medicoIdTobb)
        val original = assertNotNull(
            espaciado { admin.traerLicencias() }.firstOrNull { it.medicoId == medicoId },
            "el médico del token tiene que tener licencia, si no no habría entrado",
        )
        assertTrue(
            original.activo && original.fechaExpiracion > hoy().toString(),
            "la licencia de prueba tiene que estar vigente antes de empezar (un resto de otra corrida: restaurarla a mano)",
        )
        try {
            this.bloque(Banco(admin, HcPediatriaBackend(db, apiMedico), medicoId, original))
        } finally {
            // La licencia decide quién entra, y evaluarla vencida la deja inactiva en el servidor: se restaura siempre.
            espaciado { admin.guardarLicencia(original) }
        }
    }

    /** El dispositivo del médico: base temporal con su copia de la licencia y su sesión guardada, y turnos sin señal. */
    private inner class App(val db: Salud360Db, val repo: AuthRepository, val cliente: HcApiClient, private val medicoId: Id) {
        suspend fun copiaLocal(): Licencia? = db.authQueries.licenciaPorMedico(medicoId).uno { it.toModel() }
        suspend fun dirty(): List<Id> = db.authQueries.licenciasDirty().lista { it.medico_id }
        suspend fun sesionGuardada(): Boolean = db.authQueries.sesionActual().uno { it } != null
    }

    private suspend fun appDelMedico(medicoId: Id, copia: Licencia): App {
        val db = baseTemporal()
        val usuario = Usuario("u-licencias-e2e", "licencias-e2e@ejemplo.test", "Prueba", "Licencias", Rol.MEDICO)
        db.authQueries.upsertMedico(
            Medico(medicoId, usuario.id, "Prueba", "Licencias", especialidadesHc = listOf("pediatria"), tieneTurnos = true).toRow(1L, dirty = false),
        )
        db.authQueries.upsertLicencia(copia.toRow(1L, dirty = false))
        val cliente = HcApiClient(url, "pediatria")
        // Turnos y la API propia a un puerto cerrado: sin señal con turnos, y nada sale a producción.
        val repo = AuthRepository(
            db, ApiClient("http://127.0.0.1:1"), TurnosOnlineClient("http://127.0.0.1:1"),
            hcClients = mapOf("pediatria" to cliente),
            backendsHc = mapOf("pediatria" to HcPediatriaBackend(db, cliente)),
        )
        // La sesión que dejó un ingreso anterior, con el token del médico de prueba: es lo que `restaurar` lee al recargar.
        val sesion = resolverPerfil(db, usuario)!!.copy(token = tokenMedico)
        db.authQueries.guardarSesion(usuario.id, tokenMedico, json.encodeToString(sesion), 1L)
        return App(db, repo, cliente, medicoId)
    }

    /** Lo único que la prueba necesita del perfil de pediatría. */
    @Serializable
    private data class PerfilDePrueba(@SerialName("medico_id_tobb") val medicoIdTobb: Long = 0)

    private companion object {
        /** Lejos, para que se note si quedó, y sin bloquear a nadie mientras la prueba corre. */
        private const val VENCE = "2031-12-31"
        private const val AVISO = "2031-12-01"
        private const val PAUSA_ENTRE_PEDIDOS_MS = 1_100L
    }
}
