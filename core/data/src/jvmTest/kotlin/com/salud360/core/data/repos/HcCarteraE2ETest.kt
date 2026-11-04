package com.salud360.core.data.repos

import com.salud360.core.data.lista
import com.salud360.core.data.network.hc.HcApiClient
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.createDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La cartera del médico en la historia clínica llega al dispositivo.
 *
 * Es lo único que tiene el médico que **solo** usa historia clínica: en turnos no le dieron ningún
 * turno, así que de ahí no sale ningún paciente y la lista le quedaba vacía aunque del otro lado
 * tenga cientos.
 *
 * Solo lee, así que no ensucia nada del otro lado. Se saltea sola sin el token.
 *
 * ```
 * ./gradlew :core:data:jvmTest --rerun --tests '*HcCarteraE2ETest*' \
 *   -Psalud360.test.hc.pediatria=http://localhost/HCDPediatria-salud360/public/index.php \
 *   -Psalud360.test.hc.token=salud360-prueba-medico
 * ```
 */
class HcCarteraE2ETest {

    private val url = System.getProperty("salud360.test.hc.pediatria").orEmpty()
    private val token = System.getProperty("salud360.test.hc.token").orEmpty()

    @Test
    fun laCarteraDelMedicoLlegaYNoSeDuplicaAlRepetirla() {
        if (url.isBlank() || token.isBlank()) {
            println("HcCarteraE2ETest: sin -Psalud360.test.hc.pediatria / .token, no se corre.")
            return
        }
        val archivo = File.createTempFile("salud360-cartera", ".db").also { it.delete() }
        runBlocking {
            val db = createDatabase(DriverFactory(archivo.absolutePath))
            val backend = HcPediatriaBackend(db, HcApiClient(url, "pediatria").also { it.token = token })
            val medicoId = "tobb-m1"

            val traidos = backend.traerPacientes(medicoId)
            assertTrue(traidos > 0, "el médico de prueba tiene pacientes en pediatría")
            val enBase = db.pacientesQueries.pacientesDeMedico(medicoId).lista { it.id }
            assertEquals(traidos, enBase.size, "todos los que llegaron tienen que quedar vinculados al médico")

            // Más de una página: el que más pacientes tiene ronda los novecientos y se piden de a 300.
            assertTrue(traidos > HcPediatriaBackend.PAGINA_PACIENTES, "la prueba tiene sentido con más de una página")

            // Repetirlo es lo que pasa cada vez que se abre la pantalla: no puede duplicar a nadie.
            val segunda = backend.traerPacientes(medicoId)
            assertEquals(traidos, segunda)
            assertEquals(
                enBase.size,
                db.pacientesQueries.pacientesDeMedico(medicoId).lista { it.id }.size,
                "la segunda pasada no tiene que crear pacientes nuevos",
            )

            // Y de cada uno queda anotado su id de pediatría, para no volver a resolverlo por documento.
            val conIdRemoto = enBase.count { id ->
                db.pacientesQueries.extrasDePaciente(id, "pediatria").lista { it.clave }
                    .contains(HcPediatriaBackend.EXTRA_HC_PACIENTE_ID)
            }
            assertEquals(enBase.size, conIdRemoto)
        }
    }
}
