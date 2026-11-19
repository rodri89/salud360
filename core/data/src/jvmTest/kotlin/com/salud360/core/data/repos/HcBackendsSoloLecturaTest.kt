package com.salud360.core.data.repos

import com.salud360.core.data.AppConfig
import com.salud360.core.data.dataModule
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Qué backends registra de verdad el módulo de datos y qué declaran de sí mismos (spec 002, E5.1, RF-47; spec 005, T22, RF-5 y
 * RF-66): `soloLectura`, `envioPropio` y `administraLicencia`. Los tres tienen un valor por defecto que no cambia a pediatría, así que
 * un backend nuevo podría enviar, o cobrar licencias, por descuido: este test lo impide, porque falla con cualquier código de
 * especialidad que no tenga decidida su terna acá.
 *
 * - Pediatría: envía su motor de siempre (`HcApiSync`) y administra su licencia: `false`, `false`, `true`.
 * - La HC general (`clinica`): lee y escribe (`soloLectura = false`, T23), envía por su cuenta (`HcClinicaEnvio`, spec 005) y su licencia
 *   la administra la web de hclinica hasta E5 (RF-66): `envioPropio = true` y `administraLicencia = false`.
 */
class HcBackendsSoloLecturaTest {

    private fun registrados(): Map<String, HcBackend> {
        val archivo = File.createTempFile("salud360-backends", ".db").also { it.delete() }
        return try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    // Una URL por cada HC con API; el cliente HTTP se arma pero nunca se llama.
                    val config = AppConfig(
                        entorno = "dev",
                        turnosUrl = "http://localhost:1",
                        hcUrls = mapOf("pediatria" to "http://localhost:1", CodigosHc.GENERAL to "http://localhost:1"),
                    )
                    val koin = koinApplication { modules(dataModule(Salud360Db(driver), config, emptyMap())) }.koin
                    koin.get<HcBackends>().porEspecialidad
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    private data class Terna(val soloLectura: Boolean, val envioPropio: Boolean, val administraLicencia: Boolean)

    @Test
    fun cada_backend_registrado_declara_soloLectura_envioPropio_y_administraLicencia() {
        val backends = registrados()
        assertTrue("pediatria" in backends, "pediatría tendría que estar registrada: ${backends.keys}")
        assertTrue(CodigosHc.GENERAL in backends, "la HC general tendría que estar registrada (E6.5): ${backends.keys}")
        for ((codigo, backend) in backends) {
            val esperada = when (codigo) {
                "pediatria" -> Terna(soloLectura = false, envioPropio = false, administraLicencia = true)
                // Spec 005: lee y escribe (T23), envía por su cuenta y no administra licencias desde la app (RF-5, RF-66).
                CodigosHc.GENERAL -> Terna(soloLectura = false, envioPropio = true, administraLicencia = false)
                else -> fail("el backend de '$codigo' no tiene decidido qué declara: agregarlo acá con su soloLectura, su envioPropio y su administraLicencia")
            }
            assertEquals(esperada, Terna(backend.soloLectura, backend.envioPropio, backend.administraLicencia), "lo que declara '$codigo'")
        }
    }

    /** Un backend que no declara nada: lo que vale por omisión, que es lo que tiene pediatría. */
    private class BackendMinimo : HcBackend {
        override val especialidad = "nueva"
        override suspend fun asegurarConsulta(consulta: Consulta): String? = null
        override suspend fun enviarSecciones(remotoId: String, secciones: Map<String, Map<String, String>>) = Unit
        override suspend fun enviarExamen(remotoId: String, campos: Map<String, String>) = Unit
        override suspend fun enviarRegistros(remotoId: String, registros: List<RegistroClinico>): Map<Id, String> = emptyMap()
        override suspend fun enviarEstado(consulta: Consulta, remotoId: String) = Unit
        override suspend fun subirArchivo(remotoId: String, archivo: Archivo, bytes: ByteArray): String? = null
        override suspend fun borrarArchivo(remotoId: String, archivo: Archivo) = Unit
        override suspend fun bajarArchivo(remotoId: String, archivo: Archivo): ByteArray? = null
        override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> = emptyList()
        override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) = Unit
    }

    @Test
    fun por_omision_un_backend_envia_con_el_motor_de_siempre_y_administra_su_licencia() {
        val b = BackendMinimo()

        assertFalse(b.soloLectura)
        assertFalse(b.envioPropio, "sin declararlo, el motor de siempre (HcApiSync) lo atiende: pediatría no cambia")
        assertTrue(b.administraLicencia, "sin declararlo, el panel de licencias le guarda las licencias: pediatría no cambia")
    }
}
