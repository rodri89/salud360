package com.salud360.core.data.sync

import com.salud360.core.data.hcgeneral.PARTE_CREAR
import com.salud360.core.data.hcgeneral.PARTE_SECCIONES
import com.salud360.core.data.hcgeneral.SituacionDeEnvio
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.repos.EstadosDeEnvio
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 005, T24 (RF-31): [HcClinicaEnvio.contarPendientes] sobre una base SQLite de verdad. La cuenta en sí está probada, pura, en
 * `ContadorDeEnvioTest`; acá se prueba que la cola **lee bien la base**: solo las propias de la HC general, con todo lo sucio de
 * cada una (textos, examen, datos por paciente asociados, laboratorio, pendientes), los estados guardados, y que **no escribe nada**.
 */
class HcClinicaEnvioContadorTest {

    private val espera = EstadoEnvio.EnEspera(CausaEnEspera.SESION_VENCIDA, "La sesión venció")
    private val rechazo = EstadoEnvio.NoSePudoEnviar(CausaDeRechazo.DATOS_INVALIDOS, "La web no acepta ese dato")

    @Test
    fun cuenta_las_propias_con_cambios_y_no_el_historico_ni_lo_de_la_web() = conCola { db, envio, _ ->
        db.sembrarPropia("propia-1")
        db.sembrarTexto("propia-1")
        db.sembrarPropia("propia-2", paciente = "otro-paciente")
        db.historiaClinicaQueries.upsertExamenFisico(ExamenFisico("propia-2", peso = "70", talla = "170").toRow(130))
        db.sembrarHistorica("hist-1")
        db.sembrarTexto("hist-1")
        db.sembrarDeLaWeb("hcc-c7003")
        db.sembrarTexto("hcc-c7003")

        val r = envio.contarPendientes()

        assertEquals(setOf("propia-1", "propia-2"), r.consultas.map { it.consultaId }.toSet())
        assertEquals(2, r.total)
        assertEquals(2, r.faltaEnviar)
    }

    @Test
    fun una_propia_creada_y_sin_nada_sucio_no_cuenta() = conCola { db, envio, _ ->
        db.historiaClinicaQueries.upsertConsulta(
            Consulta("propia-1", PACIENTE, MEDICO, "clinica", "consulta", FECHA_DE_PRUEBA, EstadoConsulta.ABIERTA, remotoId = "7001", propia = true)
                .toRow(100, dirty = false),
        )

        assertEquals(0, envio.contarPendientes().total)
    }

    @Test
    fun una_consulta_de_otra_historia_clinica_no_se_cuenta() = conCola { db, envio, _ ->
        db.historiaClinicaQueries.upsertConsulta(
            Consulta("ped-1", PACIENTE, MEDICO, "pediatria", "control", FECHA_DE_PRUEBA, EstadoConsulta.ABIERTA, propia = true).toRow(100),
        )

        assertEquals(0, envio.contarPendientes().total, "de pediatría se ocupa HcApiSync")
    }

    @Test
    fun el_dato_por_paciente_cuenta_por_la_consulta_a_la_que_esta_asociado_y_solo_si_viaja() = conCola(SubentregaDeEscritura.E2) { db, envio, _ ->
        // Una propia ya creada, sin nada más sucio que lo que se siembra acá.
        db.historiaClinicaQueries.upsertConsulta(
            Consulta("propia-1", PACIENTE, MEDICO, "clinica", "consulta", FECHA_DE_PRUEBA, EstadoConsulta.ABIERTA, remotoId = "7001", propia = true)
                .toRow(100, dirty = false),
        )
        db.historiaClinicaQueries.upsertConsulta(
            Consulta("hist-1", PACIENTE, MEDICO, "clinica", "consulta", FECHA_DE_PRUEBA, EstadoConsulta.CERRADA).toRow(100, dirty = false),
        )
        val q = db.historiaClinicaQueries
        // Desactivado, tabaquismo y uno asociado al histórico o a ninguna consulta: no viajan, no cuentan (RF-9, RF-60, RF-68).
        q.upsertAntecedente(Antecedente("a1", PACIENTE, "clinica", "familiares", "hta", true, "x", "propia-1", activo = false).toRow(10))
        q.upsertAntecedente(Antecedente("a2", PACIENTE, "clinica", "familiares", "tabaquismo", true, "", "propia-1").toRow(11))
        q.upsertAntecedente(Antecedente("a3", PACIENTE, "clinica", "familiares", "acv", true, "", "hist-1").toRow(12))
        q.upsertAntecedente(Antecedente("a4", PACIENTE, "clinica", "familiares", "acv", true, "", null).toRow(13))
        assertEquals(0, envio.contarPendientes().total)

        // Uno activo, asociado a la propia: viaja y cuenta.
        q.upsertAntecedente(Antecedente("a5", PACIENTE, "clinica", "familiares", "hta", true, "padre", "propia-1").toRow(14))
        assertEquals(listOf("propia-1"), envio.contarPendientes().consultas.map { it.consultaId })
    }

    @Test
    fun laboratorio_y_pendientes_de_la_propia_cuentan_solo_cuando_la_version_los_envia() {
        fun conLaboratorioYPendiente(habilitada: SubentregaDeEscritura, esperado: Int) = conCola(habilitada) { db, envio, _ ->
            db.historiaClinicaQueries.upsertConsulta(
                Consulta("propia-1", PACIENTE, MEDICO, "clinica", "consulta", FECHA_DE_PRUEBA, EstadoConsulta.ABIERTA, remotoId = "7001", propia = true)
                    .toRow(100, dirty = false),
            )
            db.historiaClinicaQueries.upsertLaboratorio(
                Laboratorio("l1", PACIENTE, "propia-1", "clinica", "laboratorio_general", FECHA_DE_PRUEBA, mapOf("hb" to "13")).toRow(20),
            )
            db.historiaClinicaQueries.upsertPendiente(Pendiente("pe1", PACIENTE, MEDICO, "pedir estudio", "propia-1").toRow(21))

            assertEquals(esperado, envio.contarPendientes().total, "habilitada=$habilitada")
        }

        conLaboratorioYPendiente(SubentregaDeEscritura.E1, 0)
        conLaboratorioYPendiente(SubentregaDeEscritura.E3, 1)
    }

    @Test
    fun lee_los_estados_guardados_y_separa_en_espera_de_no_se_pudo_enviar() = conCola { db, envio, _ ->
        db.sembrarPropia("falta", paciente = "p-falta")
        db.sembrarPropia("espera", paciente = "p-espera")
        db.sembrarPropia("rechazo", paciente = "p-rechazo")
        listOf("falta", "espera", "rechazo").forEach { db.sembrarTexto(it) }
        val estados = EstadosDeEnvio(db)
        estados.guardar("p-espera", "espera", PARTE_CREAR, espera, FECHA_DE_PRUEBA)
        estados.guardar("p-rechazo", "rechazo", PARTE_SECCIONES, rechazo, FECHA_DE_PRUEBA)

        val r = envio.contarPendientes()

        assertEquals(3, r.total)
        assertEquals(1, r.faltaEnviar)
        assertEquals(1, r.enEspera)
        assertEquals(1, r.noSePudoEnviar)
        assertEquals(SituacionDeEnvio.EN_ESPERA, r.consultas.single { it.consultaId == "espera" }.situacion)
        assertEquals(SituacionDeEnvio.NO_SE_PUDO_ENVIAR, r.consultas.single { it.consultaId == "rechazo" }.situacion)
    }

    @Test
    fun contar_no_escribe_nada_ni_programa_nada() = conCola { db, envio, reloj ->
        db.sembrarPropia("propia-1")
        db.sembrarTexto("propia-1")
        val antes = filas(db)

        repeat(3) { envio.contarPendientes() }

        assertEquals(antes, filas(db))
        assertEquals(0, reloj.esperando)
        assertEquals(emptyList(), envio.eventos)
    }

    private suspend fun filas(db: Salud360Db): List<List<Any>> = listOf(
        db.historiaClinicaQueries.consultasDirty().lista { it },
        db.historiaClinicaQueries.seccionValoresDirty().lista { it },
        db.pacientesQueries.extrasDePaciente(PACIENTE, "clinica").lista { it },
    )
}
