package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.TIPO_LABORATORIO_GENERAL
import com.salud360.core.data.hcgeneral.TIPO_PESO
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.CargaLaboratorioDto
import com.salud360.core.data.network.hc.LaboratoriosDto
import com.salud360.core.data.repos.HcClinicaPruebas.ApiFalsa
import com.salud360.core.data.repos.HcClinicaPruebas.Contexto
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.conBackend
import com.salud360.core.data.repos.HcClinicaPruebas.marcasVigentes
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.sync.sembrarPropia
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Laboratorio
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005 RF-33, RF-34, RF-41 (T86-a): la lectura de la HC general empareja las **cargas** de laboratorio general y peso que esta app ya
 * envió con las que trae `historia` (`hcc-l<tipo>-<id>`) por `remoto_id`, sin duplicar y respetando lo pendiente; una carga propia dada de
 * baja no reaparece y una editada en la web se refresca (RF-34). Los pendientes (el texto único) son T86-b. API falsa y base SQLite de
 * verdad; datos inventados.
 */
class HcClinicaBackendPropiasE3Test {
    private val dia = "2026-11-12"

    private suspend fun Contexto.traer() = backend.traerConsultas(PACIENTE, MEDICO, forzar = true)

    private fun laboratorios(
        generales: List<CargaLaboratorioDto> = emptyList(),
        pesos: List<CargaLaboratorioDto> = emptyList(),
    ) = LaboratoriosDto(laboratorioGeneral = generales, laboratorioReducido = emptyList(), peso = pesos)

    /** El historial de la ficha de prueba con las [cargas] que diga la web (todo lo demás vacío). */
    private fun historia(cargas: LaboratoriosDto) = HcClinicaPruebas.historiaVacia().copy(laboratorios = cargas)

    /** Una carga propia (del dispositivo) tal como la deja el repositorio y el envío: `remoto_id` una vez enviada, `dirty` si espera. */
    private suspend fun Contexto.sembrarCarga(
        id: Id,
        tipo: String = TIPO_LABORATORIO_GENERAL,
        valores: Map<String, String>,
        remotoId: String = "",
        consultaId: Id? = null,
        activo: Boolean = true,
        dirty: Boolean = false,
        actualizada: Long = 60,
    ) = db.historiaClinicaQueries.upsertLaboratorio(
        Laboratorio(id, PACIENTE, consultaId, "clinica", tipo, LocalDate.parse(dia), valores, activo, remotoId).toRow(actualizada, dirty = dirty),
    )

    private suspend fun Contexto.cargas() = db.historiaClinicaQueries.laboratoriosDePacienteTodos(PACIENTE, "clinica").lista { it.toModel() }

    private fun Contexto.dirty(id: Id) = textos(driver, "SELECT dirty FROM laboratorio WHERE id = '$id'").single()

    // ---------- Una sola fila, emparejada por `remoto_id` ----------

    @Test
    fun una_carga_propia_enviada_y_la_de_la_historia_son_una_sola() {
        val web = historia(laboratorios(generales = listOf(CargaLaboratorioDto(901, 7001, dia, mapOf("glucemia" to "120", "creatinina" to "0.9")))))
        conBackend(ApiFalsa().apply { historial = web }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarCarga("l-1", valores = mapOf("glucemia" to "100"), remotoId = "901", consultaId = "u-1")

            traer()

            assertEquals(
                listOf("l-1"),
                cargas().map { it.id },
                "se empareja por tipo + remoto_id: no nace una hcc-llaboratorio_general-901 aparte",
            )
            val carga = cargas().single()
            assertEquals("120", carga.valores["glucemia"], "y se refresca con lo que cambió en la web")
            assertEquals("0.9", carga.valores["creatinina"], "lo que la web trae de nuevo entra")
            assertEquals("u-1", carga.consultaId, "sigue colgada de su consulta")
            assertEquals("901", carga.remotoId)
            assertTrue(carga.activo)
            assertEquals("0", dirty("l-1"), "lo traído de la web no queda pendiente de envío")
        }
    }

    @Test
    fun el_peso_propio_se_empareja_por_tipo_y_remoto_id() {
        val web = historia(laboratorios(pesos = listOf(CargaLaboratorioDto(25, 7001, dia, mapOf("peso" to "80")))))
        conBackend(ApiFalsa().apply { historial = web }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarCarga("p-1", tipo = TIPO_PESO, valores = mapOf("peso" to "78"), remotoId = "25", consultaId = "u-1")

            traer()

            assertEquals(listOf("p-1"), cargas().map { it.id }, "el peso se empareja igual que el laboratorio: no nace una hcc-lpeso-25")
            assertEquals("80", cargas().single().valores["peso"], "y toma la corrección de la web")
            assertEquals("0", dirty("p-1"))
        }
    }

    // ---------- Lo pendiente manda ----------

    @Test
    fun lo_sucio_de_una_carga_propia_manda_y_no_se_duplica() {
        val web = historia(laboratorios(generales = listOf(CargaLaboratorioDto(901, 7001, dia, mapOf("glucemia" to "120")))))
        conBackend(ApiFalsa().apply { historial = web }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarCarga("l-1", valores = mapOf("glucemia" to "100"), remotoId = "901", consultaId = "u-1", dirty = true, actualizada = 70)

            traer()

            assertEquals(listOf("l-1"), cargas().map { it.id })
            assertEquals("100", cargas().single().valores["glucemia"], "lo pendiente manda: no se pisa")
            assertEquals("1", dirty("l-1"), "y sigue pendiente de envío")
        }
    }

    // ---------- Una baja propia no reaparece ----------

    @Test
    fun una_carga_propia_dada_de_baja_no_reaparece() {
        val web = historia(laboratorios(generales = listOf(CargaLaboratorioDto(901, 7001, dia, mapOf("glucemia" to "120")))))
        conBackend(ApiFalsa().apply { historial = web }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            // La baja de T83-a: la web la tiene, así que queda `activo = 0, dirty = 1` hasta que viaje.
            sembrarCarga("l-1", valores = mapOf("glucemia" to "100"), remotoId = "901", consultaId = "u-1", activo = false, dirty = true)

            traer()

            assertEquals(listOf("l-1"), cargas().map { it.id }, "no vuelve como hcc-llaboratorio_general-901")
            assertFalse(cargas().single().activo, "ni se reactiva")
            assertEquals("1", dirty("l-1"), "ni se limpia sola: la baja la confirma el envío")
            assertEquals(emptyList(), marcasVigentes(driver).filter { it.startsWith("hcc-l") }, "una carga propia con remoto_id no se marca «Ya no figura en la web»")
        }
    }

    // ---------- Lo editado en la web se refresca (RF-34) ----------

    @Test
    fun una_carga_editada_en_la_web_se_refresca_si_no_hay_pendiente() {
        val web = historia(laboratorios(generales = listOf(CargaLaboratorioDto(901, 7001, dia, mapOf("glucemia" to "120", "hb" to "")))))
        conBackend(ApiFalsa().apply { historial = web }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarCarga("l-1", valores = mapOf("glucemia" to "100", "hb" to "13.9"), remotoId = "901", consultaId = "u-1")

            traer()

            val carga = cargas().single()
            assertEquals("120", carga.valores["glucemia"], "la corrección hecha en la web entra")
            assertEquals("13.9", carga.valores["hb"], "y un valor vacío de la web no pisa el que había")
            assertEquals("0", dirty("l-1"))
            assertEquals(LocalDate.parse(dia), carga.fecha)
        }
    }

    // ---------- La carga de la web de otra consulta no se mezcla ----------

    @Test
    fun una_carga_de_la_web_de_otra_consulta_no_se_mezcla_con_la_propia() {
        val web = historia(
            laboratorios(
                generales = listOf(
                    CargaLaboratorioDto(901, 7002, dia, mapOf("glucemia" to "120")),
                    CargaLaboratorioDto(902, 7002, dia, mapOf("glucemia" to "140")),
                ),
            ),
        )
        conBackend(ApiFalsa().apply { historial = web }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarCarga("l-1", valores = mapOf("glucemia" to "100"), remotoId = "901", consultaId = "u-1")

            traer()

            assertEquals(
                listOf("hcc-llaboratorio_general-902", "l-1"),
                cargas().map { it.id }.sorted(),
                "la carga 902 de la web es otra fila: no se mezcla con la propia",
            )
            val propia = cargas().single { it.id == "l-1" }
            assertEquals("120", propia.valores["glucemia"], "la propia se refresca con su remoto_id")
            assertEquals("u-1", propia.consultaId, "y no se la mueve a la consulta que diga la web")
            val ajena = cargas().single { it.id == "hcc-llaboratorio_general-902" }
            assertEquals("140", ajena.valores["glucemia"])
            assertEquals(null, ajena.consultaId, "la consulta 7002 no está en el dispositivo: se guarda sin consulta")
        }
    }
}
