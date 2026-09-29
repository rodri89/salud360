package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.ApiClient
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.createDatabase
import com.salud360.core.model.TobbIds
import com.salud360.core.model.auth.Medico
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
 * ```
 * ./gradlew :core:data:jvmTest --rerun --tests '*HcLicenciasE2ETest*' \
 *   -Psalud360.test.hc.pediatria=http://localhost/HCDPediatria-salud360/public/index.php \
 *   -Psalud360.test.hc.token.admin=<token en claro de un administrador>
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

    /** Lo único que la prueba necesita del perfil de pediatría. */
    @Serializable
    private data class PerfilDePrueba(@SerialName("medico_id_tobb") val medicoIdTobb: Long = 0)

    private companion object {
        /** Lejos, para que se note si quedó, y sin bloquear a nadie mientras la prueba corre. */
        private const val VENCE = "2031-12-31"
        private const val AVISO = "2031-12-01"
    }
}
