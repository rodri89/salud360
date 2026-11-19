package com.salud360.core.data.repos

import com.salud360.core.data.lista
import com.salud360.core.data.network.ApiClient
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Licencias frente a un backend de solo lectura (spec 002, E5.3, RF-61): registrar el backend de la HC general no
 * puede cambiar nada para un médico que solo usa clínica —sigue sin destino y `guardarLicencia` sigue devolviendo
 * `true`— ni para pediatría, que sigue guardando allá. Sobre una base SQLite de verdad en un archivo temporal;
 * datos inventados.
 */
class AdminRepositoryLicenciasTest {

    /**
     * Backend falso. [guardaLicencias] imita a pediatría (guarda y contesta [respuesta]); con `false` deja el
     * `guardarLicencia` por defecto de la interfaz (`false`), como el de clínica. Anota cada llamada de licencias.
     */
    private class BackendFalso(
        override val especialidad: String,
        override val soloLectura: Boolean,
        val guardaLicencias: Boolean = false,
        var respuesta: Boolean = true,
        val licenciasDelServidor: List<Licencia> = emptyList(),
        override val administraLicencia: Boolean = true,
    ) : HcBackend {
        val llamadas = mutableListOf<String>()

        override suspend fun traerLicencias(): List<Licencia> {
            llamadas += "traerLicencias"
            return licenciasDelServidor
        }

        override suspend fun guardarLicencia(licencia: Licencia): Boolean {
            llamadas += "guardarLicencia"
            return if (guardaLicencias) respuesta else super.guardarLicencia(licencia)
        }

        override suspend fun asegurarConsulta(consulta: Consulta): String? = error("no es un envío")
        override suspend fun enviarSecciones(remotoId: String, secciones: Map<String, Map<String, String>>) = error("no es un envío")
        override suspend fun enviarExamen(remotoId: String, campos: Map<String, String>) = error("no es un envío")
        override suspend fun enviarRegistros(remotoId: String, registros: List<RegistroClinico>): Map<Id, String> = error("no es un envío")
        override suspend fun enviarEstado(consulta: Consulta, remotoId: String) = error("no es un envío")
        override suspend fun subirArchivo(remotoId: String, archivo: Archivo, bytes: ByteArray): String? = error("no es un envío")
        override suspend fun borrarArchivo(remotoId: String, archivo: Archivo) = error("no es un envío")
        override suspend fun bajarArchivo(remotoId: String, archivo: Archivo): ByteArray? = null
        override suspend fun traerConsultas(pacienteId: Id, medicoId: Id): List<Consulta> = emptyList()
        override suspend fun traerConsulta(consultaLocalId: Id, remotoId: String) = Unit
    }

    private fun conBase(bloque: suspend (Salud360Db) -> Unit) {
        val archivo = File.createTempFile("salud360-admin", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    bloque(Salud360Db(driver))
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    private fun repo(db: Salud360Db, vararg backends: BackendFalso) =
        AdminRepository(db, ApiClient("http://localhost"), backends.associateBy { it.especialidad })

    private val licencia = Licencia("m-99000001", fechaExpiracion = "2027-01-31", fechaAviso = "2027-01-01", importe = 1000.0)

    private suspend fun AdminRepository.conMedico(hcs: List<String>) {
        guardarMedico(Medico("m-99000001", "u-99000001", "Prueba", "Inventada", especialidadesHc = hcs))
    }

    /** La licencia local con todas sus columnas (`dirty` incluido). */
    private suspend fun filasDeLicencia(db: Salud360Db) = db.authQueries.licencias().lista { it }

    private fun clinicaSoloLectura() = BackendFalso("clinica", soloLectura = true)

    /**
     * La HC general con escritura (spec 005, T23): ya no es de solo lectura, así que lo único que la deja afuera del panel es que no
     * administra licencias (RF-66). Con el `guardarLicencia` por defecto (`false`), como el de `HcClinicaBackend`.
     */
    private fun clinicaConEscritura() = BackendFalso("clinica", soloLectura = false, administraLicencia = false)
    private fun pediatria(respuesta: Boolean = true) = BackendFalso("pediatria", soloLectura = false, guardaLicencias = true, respuesta = respuesta)

    @Test
    fun medico_solo_de_clinica_guarda_y_devuelve_true_sin_llamar_al_backend_de_solo_lectura() = conBase { db ->
        val clinica = clinicaSoloLectura()
        val r = repo(db, clinica)
        r.conMedico(listOf("clinica"))

        assertTrue(r.guardarLicencia(licencia), "sin destino que la cobre sigue siendo true, como antes de registrar clínica")

        assertEquals(emptyList(), clinica.llamadas)
        assertEquals(licencia, r.licencia("m-99000001"))
    }

    @Test
    fun registrar_clinica_de_solo_lectura_no_cambia_nada_frente_a_no_registrarla() = conBase { db ->
        // Lo que hace hoy el código (sin backend de clínica) es la vara: mismo resultado y misma fila, `dirty` incluido.
        val sin = repo(db)
        sin.conMedico(listOf("clinica"))
        val resultadoSin = sin.guardarLicencia(licencia)
        val filasSin = filasDeLicencia(db)

        val con = repo(db, clinicaSoloLectura())
        val resultadoCon = con.guardarLicencia(licencia)

        assertTrue(resultadoSin)
        assertEquals(resultadoSin, resultadoCon)
        assertEquals(1, filasSin.size)
        // `updated_at` es de reloj, no se compara; las demás columnas (dirty = 1: no hay nadie que la limpie) sí.
        assertEquals(filasSin.single().copy(updated_at = 0), filasDeLicencia(db).single().copy(updated_at = 0))
    }

    @Test
    fun pediatria_sigue_recibiendo_la_licencia_y_la_deja_limpia() = conBase { db ->
        val pediatria = pediatria()
        val clinica = clinicaSoloLectura()
        val r = repo(db, pediatria, clinica)
        r.conMedico(listOf("pediatria"))

        assertTrue(r.guardarLicencia(licencia))

        assertEquals(listOf("guardarLicencia"), pediatria.llamadas)
        assertEquals(emptyList(), clinica.llamadas)
        assertEquals(0L, filasDeLicencia(db).single().dirty)
    }

    @Test
    fun pediatria_que_no_la_acepta_devuelve_false_y_la_deja_sucia() = conBase { db ->
        val pediatria = pediatria(respuesta = false)
        val r = repo(db, pediatria, clinicaSoloLectura())
        r.conMedico(listOf("pediatria"))

        assertFalse(r.guardarLicencia(licencia))

        assertEquals(listOf("guardarLicencia"), pediatria.llamadas)
        assertEquals(1L, filasDeLicencia(db).single().dirty)
    }

    @Test
    fun medico_de_pediatria_y_clinica_manda_la_respuesta_de_pediatria_y_clinica_no_cuenta() = conBase { db ->
        // Antes de registrar clínica el destino era solo pediatría; con clínica de solo lectura registrada, también.
        val pediatriaRechaza = pediatria(respuesta = false)
        val clinica = clinicaSoloLectura()
        val r = repo(db, pediatriaRechaza, clinica)
        r.conMedico(listOf("pediatria", "clinica"))

        assertFalse(r.guardarLicencia(licencia), "la respuesta es la de pediatría, no la 'false' por defecto de clínica")

        assertEquals(emptyList(), clinica.llamadas)
        assertEquals(1L, filasDeLicencia(db).single().dirty)
    }

    // ---- la HC general con escritura no administra licencias desde la app (spec 005, RF-66) ----

    @Test
    fun medico_solo_de_clinica_con_escritura_guarda_y_devuelve_true_sin_llamar_al_backend() = conBase { db ->
        val clinica = clinicaConEscritura()
        val r = repo(db, clinica)
        r.conMedico(listOf("clinica"))

        assertTrue(r.guardarLicencia(licencia), "sin destino que la cobre sigue siendo true, como antes de que la HC general escriba")

        assertEquals(emptyList(), clinica.llamadas, "cero llamadas al backend de la HC general")
        assertEquals(licencia, r.licencia("m-99000001"))
    }

    @Test
    fun la_licencia_local_queda_igual_con_la_hc_general_escribiendo_que_sin_registrarla() = conBase { db ->
        val sin = repo(db)
        sin.conMedico(listOf("clinica"))
        val resultadoSin = sin.guardarLicencia(licencia)
        val filasSin = filasDeLicencia(db)

        val con = repo(db, clinicaConEscritura())
        val resultadoCon = con.guardarLicencia(licencia)

        assertTrue(resultadoSin)
        assertEquals(resultadoSin, resultadoCon)
        assertEquals(filasSin.single().copy(updated_at = 0), filasDeLicencia(db).single().copy(updated_at = 0), "misma fila, `dirty` incluido")
    }

    @Test
    fun pediatria_sigue_igual_con_la_hc_general_escribiendo() = conBase { db ->
        val pediatria = pediatria()
        val clinica = clinicaConEscritura()
        val r = repo(db, pediatria, clinica)
        r.conMedico(listOf("pediatria"))

        assertTrue(r.guardarLicencia(licencia))

        assertEquals(listOf("guardarLicencia"), pediatria.llamadas)
        assertEquals(emptyList(), clinica.llamadas)
        assertEquals(0L, filasDeLicencia(db).single().dirty)
    }

    @Test
    fun medico_de_pediatria_y_clinica_con_escritura_manda_la_respuesta_de_pediatria() = conBase { db ->
        val pediatriaRechaza = pediatria(respuesta = false)
        val clinica = clinicaConEscritura()
        val r = repo(db, pediatriaRechaza, clinica)
        r.conMedico(listOf("pediatria", "clinica"))

        assertFalse(r.guardarLicencia(licencia), "la respuesta es la de pediatría, no la 'false' por defecto de la HC general")

        assertEquals(emptyList(), clinica.llamadas)
        assertEquals(listOf("guardarLicencia"), pediatriaRechaza.llamadas)
        assertEquals(1L, filasDeLicencia(db).single().dirty)
    }

    @Test
    fun sincronizarLicencias_no_le_pregunta_nada_a_la_hc_general_con_escritura() = conBase { db ->
        val deClinica = Licencia("m-99000002", "2027-02-28", "2027-02-01")
        val clinica = BackendFalso("clinica", soloLectura = false, licenciasDelServidor = listOf(deClinica), administraLicencia = false)
        val pediatria = BackendFalso("pediatria", soloLectura = false, guardaLicencias = true, licenciasDelServidor = listOf(licencia))
        val r = repo(db, clinica, pediatria)

        r.sincronizarLicencias()

        assertEquals(emptyList(), clinica.llamadas)
        assertEquals(listOf("traerLicencias"), pediatria.llamadas)
        assertEquals(listOf(licencia.medicoId), filasDeLicencia(db).map { it.medico_id })
    }

    @Test
    fun un_backend_que_administra_su_licencia_y_escribe_si_es_destino() = conBase { db ->
        // Es lo que habilita E5: una HC que escribe y además administra su licencia entra al panel como pediatría.
        val clinicaE5 = BackendFalso("clinica", soloLectura = false, guardaLicencias = true, administraLicencia = true)
        val r = repo(db, clinicaE5)
        r.conMedico(listOf("clinica"))

        assertTrue(r.guardarLicencia(licencia))

        assertEquals(listOf("guardarLicencia"), clinicaE5.llamadas)
    }

    @Test
    fun sincronizarLicencias_no_le_pregunta_nada_al_backend_de_solo_lectura() = conBase { db ->
        val deClinica = Licencia("m-99000002", "2027-02-28", "2027-02-01")
        val clinica = BackendFalso("clinica", soloLectura = true, licenciasDelServidor = listOf(deClinica))
        val pediatria = BackendFalso("pediatria", soloLectura = false, guardaLicencias = true, licenciasDelServidor = listOf(licencia))
        val r = repo(db, clinica, pediatria)

        r.sincronizarLicencias()

        assertEquals(emptyList(), clinica.llamadas)
        assertEquals(listOf("traerLicencias"), pediatria.llamadas)
        assertEquals(listOf(licencia.medicoId), filasDeLicencia(db).map { it.medico_id })
    }
}
