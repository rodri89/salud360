package com.salud360.core.data.repos

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.PacientesHcApi
import com.salud360.core.data.network.hc.PacientesHcApis
import com.salud360.core.data.network.hc.HcPacienteRequest
import com.salud360.core.data.network.hc.HcResolverRespuesta
import com.salud360.core.data.network.hc.PaginaCartera
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Lo que comparten los tests de `HcClinicaBackend` (spec 002, E6.3): una base SQLite de verdad en un archivo temporal con
 * un paciente de prueba, volcados de tablas para comparar «antes y después» fila por fila y una API de pacientes que falla
 * si alguien la usa (la lectura nunca pide `pacientes/resolver`, RF-49). Datos inventados (documento 99000100).
 */
internal object HcClinicaPruebas {
    const val PACIENTE = "p-99000100"
    const val MEDICO = "m-99000009"

    /** Id de la ficha de la web del paciente de prueba. */
    const val FICHA = 5001L

    /** Las tablas de la historia clínica que la lectura no puede borrar ni vaciar (más `paciente_extra`). */
    val TABLAS = listOf(
        "consulta", "seccion_valor", "examen_fisico", "antecedente", "registro_clinico", "laboratorio", "pendiente",
        "archivo", "consulta_diagnostico", "medico_preferencia", "paciente_extra",
    )

    /** La API de pacientes de la HC general: si se la llama, el test falla (ni `resolver` ni la cartera). */
    object PacientesProhibida : PacientesHcApi {
        override suspend fun pagina(desde: Int, limite: Int): PaginaCartera = error("la lectura no pide la cartera")

        override suspend fun resolver(pedido: HcPacienteRequest, elegida: Long?): HcResolverRespuesta =
            error("la lectura no puede llamar a pacientes/resolver (RF-49)")
    }

    fun pacientes(db: Salud360Db) = PacientesHcRepository(db, PacientesHcApis(mapOf(CodigosHc.GENERAL to PacientesProhibida)), emptyMap())

    /** Base temporal con el paciente de prueba; [vinculado] le guarda la ficha de la web (sin `dirty`). */
    fun conBase(vinculado: Boolean = true, bloque: suspend (Salud360Db, SqlDriver) -> Unit) {
        val archivo = File.createTempFile("salud360-hcclinica", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    val db = Salud360Db(driver)
                    db.pacientesQueries.upsertPaciente(Paciente(PACIENTE, "99000100", "Salud360", "Prueba").toRow(0, dirty = false))
                    if (vinculado) {
                        db.pacientesQueries.upsertPacienteExtra(
                            PacienteExtra(PACIENTE, CodigosHc.GENERAL, PacientesHcRepository.EXTRA_HC_PACIENTE_ID, FICHA.toString())
                                .toRow(0, dirty = false),
                        )
                    }
                    bloque(db, driver)
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    fun textos(driver: SqlDriver, sql: String): List<String> =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(buildList { while (c.next().value) add(c.getString(0).orEmpty()) }) }, 0).value

    /** Todas las filas de [tabla], con todas sus columnas (`quote`), ordenadas: sirve para comparar antes y después. */
    fun volcar(driver: SqlDriver, tabla: String): List<String> {
        val columnas = textos(driver, "SELECT name FROM pragma_table_info('$tabla') ORDER BY cid")
        check(columnas.isNotEmpty()) { "la tabla $tabla no existe" }
        return textos(
            driver,
            "SELECT " + columnas.joinToString(" || '|' || ") { "quote($it)" } + " FROM $tabla ORDER BY " + columnas.joinToString(","),
        )
    }

    fun volcarTodo(driver: SqlDriver): Map<String, List<String>> = TABLAS.associateWith { volcar(driver, it) }
}
