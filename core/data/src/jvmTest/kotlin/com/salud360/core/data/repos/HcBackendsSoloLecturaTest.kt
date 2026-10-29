package com.salud360.core.data.repos

import com.salud360.core.data.AppConfig
import com.salud360.core.data.dataModule
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.especialidad.CodigosHc
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Qué backends registra de verdad el módulo de datos y con qué `soloLectura` (spec 002, E5.1, RF-47). `soloLectura`
 * vale `false` si un backend no lo declara (para no cambiar a pediatría), así que un backend nuevo podría enviar por
 * descuido: este test lo impide, porque falla con cualquier código de especialidad que no tenga decidido su valor acá.
 *
 * Al registrar el backend de la HC general (E6.5) ya tiene que estar con `true`: el caso `clinica` de abajo lo exige
 * (hoy no está registrado, y está bien: sus consultas todavía no viajan).
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

    @Test
    fun cada_backend_registrado_tiene_decidido_si_envia() {
        val backends = registrados()
        assertTrue("pediatria" in backends, "pediatría tendría que estar registrada: ${backends.keys}")
        for ((codigo, backend) in backends) {
            val soloLectura = when (codigo) {
                "pediatria" -> false
                // E6.5: la HC general se registra de solo lectura (lo exige este test).
                CodigosHc.GENERAL -> true
                else -> fail("el backend de '$codigo' no tiene decidido si envía: agregarlo acá con su valor de soloLectura")
            }
            assertEquals(soloLectura, backend.soloLectura, "soloLectura de '$codigo'")
        }
    }
}
