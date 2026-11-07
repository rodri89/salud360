package com.salud360.core.data.sync

import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec 005, T24 (RF-7, RF-1, D2): **nada sale solo** fuera de las consultas propias. La cola no programa ni manda nada del histórico
 * del dispositivo, de lo que trajo la web ni de otra historia clínica, ni por el autoguardado ([HcClinicaEnvio.marcarSucia]) ni a
 * pedido ([HcClinicaEnvio.empujarYa]); y lo que no es propio queda en la base exactamente como estaba. Reloj falso; sin red.
 */
class HcClinicaEnvioHistoricoTest {

    @Test
    fun el_historico_del_dispositivo_no_entra_en_la_cola() = conCola { db, envio, reloj ->
        db.sembrarHistorica("hist-1")
        db.sembrarTexto("hist-1")
        val antes = filasSucias(db)

        envio.marcarSucia("hist-1")
        envio.marcarSucia("hist-1", esperaMs = 0)
        envio.empujarYa("hist-1").join()
        reloj.avanzar(3_600_000)

        assertEquals(emptyList(), envio.eventos)
        assertEquals(0, reloj.esperando, "ni siquiera programa una espera")
        assertEquals(antes, filasSucias(db), "lo sucio queda como estaba")
    }

    @Test
    fun lo_que_trajo_la_web_no_entra_en_la_cola() = conCola { db, envio, reloj ->
        db.sembrarDeLaWeb("hcc-c7003")

        envio.marcarSucia("hcc-c7003")
        envio.empujarYa("hcc-c7003").join()
        reloj.avanzar(3_600_000)

        assertEquals(emptyList(), envio.eventos)
        assertEquals(0, reloj.esperando)
    }

    @Test
    fun una_consulta_de_otra_historia_clinica_o_que_no_existe_tampoco() = conCola { db, envio, reloj ->
        // `propia` solo significa algo en la HC general: una de pediatría no la usa la cola aunque la traiga marcada.
        db.historiaClinicaQueries.upsertConsulta(
            Consulta("ped-1", PACIENTE, MEDICO, "pediatria", "control", FECHA_DE_PRUEBA, EstadoConsulta.ABIERTA, propia = true).toRow(100),
        )

        envio.marcarSucia("ped-1")
        envio.marcarSucia("no-existe")
        envio.empujarYa("ped-1").join()
        envio.empujarYa("no-existe").join()
        reloj.avanzar(3_600_000)

        assertEquals(emptyList(), envio.eventos)
        assertEquals(0, reloj.esperando)
    }

    @Test
    fun la_propia_si_y_el_resto_no_en_el_mismo_momento() = conCola { db, envio, reloj ->
        db.sembrarPropia("propia-1")
        db.sembrarHistorica("hist-1")
        db.sembrarDeLaWeb("hcc-c7003")

        listOf("propia-1", "hist-1", "hcc-c7003").forEach { envio.marcarSucia(it) }
        assertEquals(1, reloj.esperando, "solo la propia queda esperando")
        reloj.avanzar(4_000)

        assertEquals(listOf("inicio propia-1"), envio.envios)
    }

    /** Todas las filas sucias de la consulta y de sus textos, con todas sus columnas: si la cola toca algo, cambia. */
    private suspend fun filasSucias(db: Salud360Db): List<List<Any>> =
        listOf(db.historiaClinicaQueries.consultasDirty().lista { it }, db.historiaClinicaQueries.seccionValoresDirty().lista { it })
}
