package com.salud360.core.data.repos

import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.TIPO_LABORATORIO_GENERAL
import com.salud360.core.data.hcgeneral.idCargaWeb
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.filasSucias
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.newId
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005, T83-a (RF-41, RF-42, RF-68, RF-69) en la HC general: guardar y borrar **cargas** (laboratorio general y peso) en
 * `HcRepository`, con la misma regla que los textos, las casillas y las filas de registro (T18):
 *
 * - el autoguardado guarda lo que hay en pantalla: **solo un cambio real escribe**; lo mismo no marca `dirty`, no mueve
 *   `updated_at` ni reasigna la consulta de origen;
 * - editar una carga del histórico dentro de una consulta propia la reasigna y la marca (viaja solo esa carga, RF-68); una que
 *   ya es de otra propia se queda donde está;
 * - vaciar a propósito una carga que la web ya tiene es un cambio y viaja vacía (RF-69), sin perder el `remoto_id`;
 * - borrar una carga que la web ya tiene deja la baja pendiente (`activo = 0, dirty = 1`); una que nunca se envió se retira del
 *   dispositivo sin dejar nada pendiente; la de la web (`hcc-l…`) no se toca (RF-62);
 * - la barrera de T36 (RF-25, D13) rechaza toda escritura sobre una consulta propia con el cierre ya enviado;
 * - pediatría y las demás historias clínicas siguen como hoy.
 *
 * Base SQLite real, datos inventados. El aviso a la cola desde la pantalla (`EnvioDeConsultas.avisarCambio`) no se prueba acá:
 * `ConsultaViewModel` no tiene tests.
 */
class HcRepositoryCargasTest {
    private val fecha = LocalDate.parse("2026-11-06")
    private val antes = 1000L

    private val propia = "propia-1"
    private val otraPropia = "propia-2"
    private val historica = "hist-1"
    private val deLaWeb = "hcc-c9"

    /** La base temporal de [HcClinicaPruebas.conBase] (con el paciente de prueba) y su driver, para mirar las filas crudas. */
    private class Base(val db: Salud360Db, val driver: SqlDriver, val r: HcRepository)

    private fun conBase(bloque: suspend Base.() -> Unit) =
        HcClinicaPruebas.conBase { db, driver -> Base(db, driver, HcRepository(db)).bloque() }

    private suspend fun Base.consulta(
        id: Id,
        propia: Boolean,
        especialidad: String = "clinica",
        estado: EstadoConsulta = EstadoConsulta.ABIERTA,
        remotoId: String = "",
        dirty: Boolean = false,
    ) = db.historiaClinicaQueries.upsertConsulta(
        Consulta(id, PACIENTE, MEDICO, especialidad, "consulta", fecha, estado, remotoId = remotoId, propia = propia).toRow(antes, dirty = dirty),
    )

    private suspend fun Base.carga(
        id: Id,
        consultaId: Id?,
        valores: Map<String, String> = mapOf("albumina" to "4"),
        activo: Boolean = true,
        remotoId: String = "",
        especialidad: String = "clinica",
        dirty: Boolean = false,
    ) = db.historiaClinicaQueries.upsertLaboratorio(
        Laboratorio(id, PACIENTE, consultaId, especialidad, TIPO_LABORATORIO_GENERAL, fecha, valores, activo, remotoId).toRow(antes, dirty = dirty),
    )

    /** La fila cruda de una carga, aunque esté retirada: `consulta|activo|dirty|deleted|remoto_id`. */
    private fun Base.fila(id: Id): String = textos(
        driver,
        "SELECT COALESCE(consulta_id, '') || '|' || activo || '|' || dirty || '|' || deleted || '|' || remoto_id FROM laboratorio WHERE id = '$id'",
    ).single()

    private fun Base.actualizada(id: Id): Long = textos(driver, "SELECT updated_at FROM laboratorio WHERE id = '$id'").single().toLong()

    // ---- Cambio real ----

    @Test
    fun guardar_lo_mismo_no_marca_ni_reasigna() = conBase {
        consulta(propia, propia = true)
        consulta(historica, propia = false)
        carga("l1", propia)
        carga("l-h", historica)

        val filaAntes = fila("l1")
        val historicaAntes = fila("l-h")

        assertTrue(r.guardarLaboratorio(r.laboratorio("l1")!!, enConsulta = propia))
        assertTrue(r.guardarLaboratorio(r.laboratorio("l-h")!!, enConsulta = propia), "una carga del histórico igual tampoco se reasigna")
        // Una columna vacía sobre una que no está tampoco es un cambio: un vacío sobre nada no es un dato.
        assertTrue(r.guardarLaboratorio(r.laboratorio("l1")!!.copy(valores = mapOf("albumina" to "4", "glucosa" to "")), enConsulta = propia))

        assertEquals(filaAntes, fila("l1"), "no se reescribe la consulta, ni el activo, ni la marca de envío")
        assertEquals(antes, actualizada("l1"), "ni se mueve updated_at")
        assertEquals(historicaAntes, fila("l-h"), "la del histórico sigue siendo del histórico")
        assertEquals(0, filasSucias(driver), "nada queda pendiente de envío")
    }

    @Test
    fun editar_una_carga_del_historico_en_una_propia_la_reasigna_y_viaja_solo_eso() = conBase {
        consulta(propia, propia = true)
        consulta(otraPropia, propia = true)
        consulta(historica, propia = false)
        carga("l-h", historica)
        carga("l-h2", historica)
        carga("l-p2", otraPropia)

        assertTrue(r.guardarLaboratorio(r.laboratorio("l-h")!!.copy(valores = mapOf("albumina" to "4", "glucosa" to "1")), enConsulta = propia))
        // Una carga que ya es de otra propia se queda donde está: viaja con su consulta, no con esta.
        assertTrue(r.guardarLaboratorio(r.laboratorio("l-p2")!!.copy(valores = mapOf("albumina" to "9")), enConsulta = propia))

        assertEquals(propia, r.laboratorio("l-h")!!.consultaId, "pasó a la consulta propia")
        assertEquals("$propia|1|1|0|", fila("l-h"))
        assertTrue(actualizada("l-h") > antes)
        assertEquals("$otraPropia|1|1|0|", fila("l-p2"))
        assertEquals("$historica|1|0|0|", fila("l-h2"), "la que no se tocó no se mueve ni se marca")
        assertEquals(2, filasSucias(driver), "viaja solo lo que se tocó")
    }

    @Test
    fun una_carga_propia_ya_enviada_que_se_vacia_a_proposito_viaja_vacia() = conBase {
        consulta(propia, propia = true, remotoId = "7001")
        carga("l1", propia, valores = mapOf("albumina" to "4", "glucosa" to "2"), remotoId = "91")

        // La pantalla arma la carga de cero (no conoce el id de la web): el repositorio lo conserva.
        assertTrue(r.guardarLaboratorio(Laboratorio("l1", PACIENTE, propia, "clinica", TIPO_LABORATORIO_GENERAL, fecha, emptyMap()), enConsulta = propia))

        assertEquals(emptyMap(), r.laboratorio("l1")!!.valores, "se guardó vacía, no como estaba")
        assertEquals("$propia|1|1|0|91", fila("l1"), "queda sucia y conserva el remoto_id: viaja como vaciado")
        assertEquals(1, filasSucias(driver))
    }

    @Test
    fun vaciar_una_columna_de_una_carga_es_un_cambio() = conBase {
        consulta(propia, propia = true)
        carga("l1", propia, valores = mapOf("albumina" to "4"))

        assertTrue(r.guardarLaboratorio(r.laboratorio("l1")!!.copy(valores = mapOf("albumina" to "")), enConsulta = propia))

        assertEquals(mapOf("albumina" to ""), r.laboratorio("l1")!!.valores)
        assertEquals("$propia|1|1|0|", fila("l1"))
    }

    // ---- Baja de la carga ----

    @Test
    fun una_carga_que_la_web_ya_tiene_se_da_de_baja_y_la_baja_viaja() = conBase {
        consulta(propia, propia = true, remotoId = "7001")
        carga("l1", propia, remotoId = "91")

        assertTrue(r.eliminarLaboratorio("l1"))

        assertEquals("$propia|0|1|0|91", fila("l1"), "activo = 0 y dirty = 1, con el remoto_id para dar la baja en la web")
        assertEquals(1, filasSucias(driver))
    }

    @Test
    fun una_carga_que_nunca_se_envio_se_retira_sin_dejar_nada_pendiente() = conBase {
        consulta(propia, propia = true)
        carga("l1", propia, dirty = true) // recién cargada, todavía sin enviar

        assertTrue(r.eliminarLaboratorio("l1"))

        assertEquals("$propia|0|0|1|", fila("l1"), "se retira: ni pendiente de alta ni de baja")
        assertEquals(0, filasSucias(driver), "no queda nada por enviar")
        assertEquals(emptyList(), r.observarLaboratorios(PACIENTE, "clinica", TIPO_LABORATORIO_GENERAL).first())
    }

    @Test
    fun una_carga_de_la_web_no_se_toca() = conBase {
        consulta(deLaWeb, propia = false)
        val cargaWeb = idCargaWeb(TIPO_LABORATORIO_GENERAL, 703)
        carga(cargaWeb, deLaWeb)
        val antes = volcarTodo(driver)

        assertFalse(r.eliminarLaboratorio(cargaWeb))
        assertFalse(r.guardarLaboratorio(r.laboratorio(cargaWeb)!!.copy(valores = mapOf("albumina" to "9"))))
        assertFalse(r.guardarLaboratorio(Laboratorio(newId(), PACIENTE, deLaWeb, "clinica", TIPO_LABORATORIO_GENERAL, fecha, mapOf("albumina" to "3"))))

        assertEquals(antes, volcarTodo(driver), "ni una fila cambia")
        assertEquals(0, filasSucias(driver))
    }

    // ---- Barrera de T36 ----

    @Test
    fun la_barrera_de_t36_impide_escribir_cargas_en_una_consulta_cerrada_y_enviada() = conBase {
        consulta(propia, propia = true, estado = EstadoConsulta.CERRADA, remotoId = "7001")
        carga("l1", propia, remotoId = "91")
        val antes = volcarTodo(driver)

        assertFalse(
            r.guardarLaboratorio(
                Laboratorio(newId(), PACIENTE, propia, "clinica", TIPO_LABORATORIO_GENERAL, fecha, mapOf("albumina" to "3")),
                enConsulta = propia,
            ),
            "una carga nueva no se escribe",
        )
        assertFalse(r.guardarLaboratorio(r.laboratorio("l1")!!.copy(valores = mapOf("albumina" to "5")), enConsulta = propia))
        assertFalse(r.eliminarLaboratorio("l1"), "ni se da de baja")

        assertEquals(antes, volcarTodo(driver), "la base no cambia")
    }

    @Test
    fun una_consulta_abierta_o_con_el_cierre_pendiente_sigue_aceptando_cargas() = conBase {
        consulta(propia, propia = true, remotoId = "7001")
        consulta("pendiente", propia = true, estado = EstadoConsulta.CERRADA, remotoId = "7002", dirty = true)

        assertTrue(r.guardarLaboratorio(Laboratorio(newId(), PACIENTE, propia, "clinica", TIPO_LABORATORIO_GENERAL, fecha, mapOf("albumina" to "3")), enConsulta = propia))
        assertTrue(r.guardarLaboratorio(Laboratorio(newId(), PACIENTE, "pendiente", "clinica", TIPO_LABORATORIO_GENERAL, fecha, mapOf("albumina" to "3")), enConsulta = "pendiente"))
    }

    // ---- Pediatría y las demás no cambian ----

    @Test
    fun pediatria_sigue_escribiendo_en_cada_guardado_y_borrando_como_antes() = conBase {
        consulta("ped-1", propia = false, especialidad = "pediatria")
        carga("l-ped", "ped-1", especialidad = "pediatria")

        // Lo mismo se sigue marcando (el envío de pediatría lo necesita así).
        assertTrue(r.guardarLaboratorio(r.laboratorio("l-ped")!!, enConsulta = "ped-1"))
        assertEquals("ped-1|1|1|0|", fila("l-ped"))
        assertTrue(actualizada("l-ped") > antes)

        // Y una carga sin remoto_id se da de baja como siempre: activo = 0, sucia, sin retirarla.
        assertTrue(r.eliminarLaboratorio("l-ped"))
        assertEquals("ped-1|0|1|0|", fila("l-ped"))
    }
}
