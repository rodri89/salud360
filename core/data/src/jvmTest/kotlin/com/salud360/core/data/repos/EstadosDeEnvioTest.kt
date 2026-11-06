package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.EstadoGuardado
import com.salud360.core.data.hcgeneral.claveDeEstadoDeEnvio
import com.salud360.core.data.hcgeneral.extrasDeLectura
import com.salud360.core.data.lista
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.pacientes.PacienteExtra
import com.salud360.core.data.mappers.toRow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 005 RF-6, RF-30 (T20; plan, «Estados persistentes»): el estado de envío de cada consulta vive en `paciente_extra`, sin
 * columnas nuevas, con `dirty = 0` (es un dato local: el dueño del estado es este dispositivo y nunca sale hacia turnos) y
 * **sobrevive a cerrar y reabrir la app**. Sobre una base SQLite de verdad en un archivo temporal; datos inventados.
 */
class EstadosDeEnvioTest {
    private val paciente = "p-99000001"
    private val otroPaciente = "p-99000002"
    private val consulta = "3f2c9d1e-0a6b-4c11-9a77-5c1d2e3f4a5b"
    private val otraConsulta = "8b1a7c40-2d5e-4f33-8e90-1a2b3c4d5e6f"
    private val desde = LocalDate.parse("2026-11-06")

    private val espera = EstadoEnvio.EnEspera(CausaEnEspera.SIN_CONEXION, "Historia clínica de Clínica: sin conexión. Intentá de nuevo en un momento.")
    private val rechazo = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "Historia clínica de Clínica: la web no aceptó los datos. El dato «fecha» no es válido.")

    private fun conBase(bloque: suspend (Salud360Db, () -> Salud360Db) -> Unit) {
        val archivo = File.createTempFile("salud360-estados-envio", ".db").also { it.delete() }
        try {
            runBlocking {
                // `reabrir` cierra la base y la abre otra vez desde el archivo, como al cerrar y abrir la app.
                var driver = DriverFactory(archivo.absolutePath).createDriver()
                var db = Salud360Db(driver)
                val reabrir = {
                    driver.close()
                    driver = runBlocking { DriverFactory(archivo.absolutePath).createDriver() }
                    db = Salud360Db(driver)
                    db
                }
                try {
                    bloque(db, reabrir)
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    private suspend fun Salud360Db.extras(pacienteId: String = paciente) = pacientesQueries.extrasDePaciente(pacienteId, "clinica").lista { it }

    @Test
    fun el_estado_guardado_sobrevive_a_cerrar_y_reabrir_la_app() = conBase { db, reabrir ->
        EstadosDeEnvio(db).apply {
            guardar(paciente, consulta, "crear", espera, desde)
            guardar(paciente, consulta, "cierre", rechazo, desde)
            guardar(paciente, otraConsulta, "examen", EstadoEnvio.Descartado, desde)
        }

        val reabierta = EstadosDeEnvio(reabrir())

        val estados = reabierta.delPaciente(paciente)
        assertEquals(setOf(consulta, otraConsulta), estados.keys)
        assertEquals(mapOf("crear" to EstadoGuardado(espera, desde), "cierre" to EstadoGuardado(rechazo, desde)), estados.getValue(consulta))
        assertEquals(mapOf("examen" to EstadoGuardado(EstadoEnvio.Descartado, desde)), estados.getValue(otraConsulta))
        assertEquals(estados.getValue(consulta), reabierta.deLaConsulta(paciente, consulta))
    }

    @Test
    fun el_estado_nunca_queda_dirty_y_no_sale_hacia_turnos() = conBase { db, reabrir ->
        val estados = EstadosDeEnvio(db)
        estados.guardar(paciente, consulta, "crear", espera, desde)
        estados.guardar(paciente, consulta, "cierre", rechazo, desde)
        estados.guardar(paciente, consulta, "crear", rechazo, desde)
        estados.guardar(paciente, consulta, "examen", EstadoEnvio.Descartado, desde)
        estados.quitar(paciente, consulta, "examen")

        for (base in listOf(db, reabrir())) {
            assertTrue(base.extras().isNotEmpty())
            assertTrue(base.extras().all { it.dirty == 0L }, "todas con dirty = 0")
            assertEquals(emptyList(), base.pacientesQueries.pacienteExtrasDirty().lista { it.clave }, "nada para el motor de sincronización")
        }
    }

    @Test
    fun cambiar_el_estado_de_una_parte_reemplaza_la_fila_y_no_agrega_otra() = conBase { db, _ ->
        val estados = EstadosDeEnvio(db)
        estados.guardar(paciente, consulta, "crear", espera, desde)
        estados.guardar(paciente, consulta, "crear", rechazo, LocalDate.parse("2026-11-07"))

        assertEquals(1, db.extras().count { it.clave == claveDeEstadoDeEnvio(consulta, "crear") })
        assertEquals(mapOf("crear" to EstadoGuardado(rechazo, LocalDate.parse("2026-11-07"))), estados.deLaConsulta(paciente, consulta))
    }

    @Test
    fun pendiente_o_quitar_dan_de_baja_la_fila_sin_borrarla_y_vuelve_a_escribirse() = conBase { db, reabrir ->
        val estados = EstadosDeEnvio(db)
        estados.guardar(paciente, consulta, "crear", espera, desde)

        estados.guardar(paciente, consulta, "crear", EstadoEnvio.Pendiente, desde)

        assertEquals(emptyMap(), EstadosDeEnvio(reabrir()).deLaConsulta(paciente, consulta), "pendiente es la ausencia de un estado")
        // Nunca queda una fila sucia al dar de baja el estado.
        assertEquals(emptyList(), db.pacientesQueries.pacienteExtrasDirty().lista { it.clave })
        estados.guardar(paciente, consulta, "crear", espera, desde)
        assertEquals(mapOf("crear" to EstadoGuardado(espera, desde)), estados.deLaConsulta(paciente, consulta))
        // Quitar algo que no hay no escribe ni falla.
        estados.quitar(paciente, otraConsulta, "crear")
        assertEquals(emptyMap(), estados.deLaConsulta(paciente, otraConsulta))
    }

    @Test
    fun cada_paciente_tiene_los_suyos() = conBase { db, _ ->
        val estados = EstadosDeEnvio(db)
        estados.guardar(paciente, consulta, "crear", espera, desde)
        estados.guardar(otroPaciente, otraConsulta, "crear", rechazo, desde)

        assertEquals(setOf(consulta), estados.delPaciente(paciente).keys)
        assertEquals(setOf(otraConsulta), estados.delPaciente(otroPaciente).keys)
        assertEquals(emptyMap(), estados.delPaciente("p-sin-nada"))
    }

    @Test
    fun guardar_un_estado_no_toca_lo_que_la_lectura_dejo_en_paciente_extra() = conBase { db, reabrir ->
        db.pacientesQueries.upsertPacienteExtra(PacienteExtra(paciente, "clinica", "hcc_omitidas", "1|0|2").toRow(1000, dirty = false))
        db.pacientesQueries.upsertPacienteExtra(PacienteExtra(paciente, "clinica", "hcc_ausente_hcc-c7003", "2026-10-20").toRow(1000, dirty = false))
        db.pacientesQueries.upsertPacienteExtra(PacienteExtra(paciente, "pediatria", claveDeEstadoDeEnvio(consulta, "crear"), "espera|2026-11-06|SIN_CONEXION|x").toRow(1000, dirty = false))
        val antes = db.extras().map { it.toModelo() }.let(::extrasDeLectura)

        EstadosDeEnvio(db).guardar(paciente, consulta, "crear", espera, desde)
        val reabierta = reabrir()

        assertEquals(antes, reabierta.extras().map { it.toModelo() }.let(::extrasDeLectura), "lo de la historia de la web no cambia")
        assertEquals(1000L, reabierta.pacientesQueries.extrasDePaciente(paciente, "clinica").lista { it }.single { it.clave == "hcc_omitidas" }.updated_at)
        // El estado de otra historia clínica con la misma clave no se lee como de la HC general.
        assertEquals(mapOf(consulta to mapOf("crear" to EstadoGuardado(espera, desde))), EstadosDeEnvio(reabierta).delPaciente(paciente))
    }

    private fun com.salud360.core.database.Paciente_extra.toModelo() = PacienteExtra(paciente_id, especialidad, clave, valor)
}
