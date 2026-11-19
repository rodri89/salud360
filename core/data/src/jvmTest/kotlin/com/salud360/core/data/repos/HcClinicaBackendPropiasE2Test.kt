package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.hc.AntecedentesFamiliaresDto
import com.salud360.core.data.network.hc.CasillaAntecedenteDto
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.EstudioDto
import com.salud360.core.data.network.hc.ExamenFisicoDto
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.SeccionesConsultaDto
import com.salud360.core.data.repos.HcClinicaPruebas.ApiFalsa
import com.salud360.core.data.repos.HcClinicaPruebas.Contexto
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.conBackend
import com.salud360.core.data.repos.HcClinicaPruebas.marcasVigentes
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.data.sync.sembrarPropia
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 005 RF-33, RF-34, RF-68 (T65-a): la lectura de la HC general refresca lo de E2 de una consulta **propia ya enviada** sin duplicar y deja
 * al día lo último que la web tiene (`LoEnviado`): los **formularios** (con la regla de la L: lo pendiente y lo que el médico descartó no se pisa),
 * los **antecedentes** (la historia y lo que la app envió se emparejan por categoría + clave; lo del dispositivo manda) y los **estudios** (se
 * emparejan por tipo + `remoto_id`, nunca nace una `hcc-r…` aparte y una fila propia no se marca «Ya no figura en la web»). Los exámenes
 * complementarios y las interconsultas son de T65-b. API falsa y base SQLite de verdad; datos inventados.
 */
class HcClinicaBackendPropiasE2Test {
    private val dia = "2026-11-06"

    private fun cab(id: Long = 7001, estado: String = "ABIERTA") = ConsultaCabeceraDto(
        id = id, pacienteId = 5001, medicoId = 9, medicoNombre = "Médico Prueba Salud360", tipo = "control", tipoNumero = 1,
        fecha = dia, createdAt = "$dia 10:00:00", estado = estado, edadMostrar = "", propia = true, updatedAt = "$dia 10:30:00",
    )

    private fun conConsulta(secciones: SeccionesConsultaDto) = RespuestaConsultaDto(consulta = cab(), secciones = secciones, examenFisico = ExamenFisicoDto())

    private suspend fun Contexto.traer() = backend.traerConsultas(PACIENTE, MEDICO, forzar = true)

    /** `valor|dirty` de una fila de `seccion_valor` de [consulta], o `null` si no está. */
    private fun Contexto.valor(consulta: Id, seccion: String, campo: String) = textos(
        driver,
        "SELECT valor||'|'||dirty FROM seccion_valor WHERE consulta_id = '$consulta' AND seccion = '$seccion' AND campo = '$campo'",
    ).singleOrNull()

    private suspend fun Contexto.sembrarFormulario(consulta: Id = "u-1", seccion: String, campo: String, valor: String, dirty: Boolean = false, actualizada: Long = 50) =
        db.historiaClinicaQueries.upsertSeccionValor(SeccionValor(consulta, seccion, campo, valor).toRow(actualizada, dirty = dirty))

    private suspend fun Contexto.sembrarLoEnviado(vararg formularios: Pair<String, String>) =
        LoEnviadoGuardado(db).guardar(PACIENTE, "u-1", LoEnviado(formularios = formularios.toMap()))

    private suspend fun Contexto.loEnviado(consulta: Id = "u-1") = LoEnviadoGuardado(db).deLaConsulta(PACIENTE, consulta)

    private suspend fun Contexto.registros() = db.historiaClinicaQueries.registrosDePacienteTodos(PACIENTE, "clinica").lista { it.toModel() }

    private suspend fun Contexto.antecedentes() = db.historiaClinicaQueries.antecedentesDeCategoria(PACIENTE, "clinica", "familiares").lista { it.toModel() }

    private suspend fun Contexto.sembrarEstudio(id: Id, tipo: String = "veda", detalle: String, remotoId: String, dirty: Boolean = false, fecha: LocalDate = LocalDate.parse("2026-10-01")) =
        db.historiaClinicaQueries.upsertRegistro(
            RegistroClinico(id, PACIENTE, "u-1", "clinica", tipo, fecha, mapOf("detalle" to detalle), remotoId = remotoId).toRow(10, dirty = dirty),
        )

    // ---------- Formularios ----------

    @Test
    fun los_formularios_de_una_propia_ya_enviada_se_refrescan_y_lo_confirmado_queda_al_dia() =
        conBackend(
            ApiFalsa(listOf(cab())).apply {
                consultas[7001] = conConsulta(
                    SeccionesConsultaDto(
                        alimentacion = mapOf("hiposodica" to "1|sin sal"), serologias = mapOf("hiv" to "SI"), childPugh = mapOf("tp" to "2"),
                    ),
                )
            },
        ) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarFormulario(seccion = "alimentacion", campo = "hiposodica", valor = "1|poca sal")
            sembrarFormulario(seccion = "serologias", campo = "hiv", valor = "NO")
            sembrarFormulario(seccion = "child_pugh", campo = "tp", valor = "1")
            sembrarLoEnviado("alimentacion/hiposodica" to "1|poca sal", "serologias/hiv" to "NO", "child_pugh/tp" to "1")

            traer()
            backend.traerConsulta("u-1", "7001")

            assertEquals("1|sin sal|0", valor("u-1", "alimentacion", "hiposodica"), "lo que cambió en la web se refresca sin pendiente")
            assertEquals("SI|0", valor("u-1", "serologias", "hiv"))
            assertEquals("2|0", valor("u-1", "child_pugh", "tp"))
            assertEquals(
                mapOf("alimentacion/hiposodica" to "1|sin sal", "serologias/hiv" to "SI", "child_pugh/tp" to "2"),
                loEnviado().formularios,
                "y lo que la web tiene queda anotado: así el próximo cambio se mide contra eso",
            )
        }

    @Test
    fun un_formulario_que_el_medico_descarto_no_se_pisa_y_lo_pendiente_tampoco() =
        conBackend(
            ApiFalsa(listOf(cab())).apply {
                consultas[7001] = conConsulta(
                    SeccionesConsultaDto(serologias = mapOf("hiv" to "NO"), childPugh = mapOf("tp" to "2"), alimentacion = mapOf("diabetes" to "1|dieta")),
                )
            },
        ) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            // El médico escribió «SI» y se descartó: la fila quedó limpia con un valor que la web no tiene.
            sembrarFormulario(seccion = "serologias", campo = "hiv", valor = "SI")
            // Y este sigue pendiente de envío.
            sembrarFormulario(seccion = "child_pugh", campo = "tp", valor = "3", dirty = true, actualizada = 70)
            sembrarLoEnviado("serologias/hiv" to "NO", "child_pugh/tp" to "1")

            traer()
            backend.traerConsulta("u-1", "7001")

            assertEquals("SI|0", valor("u-1", "serologias", "hiv"), "lo que el médico descartó se conserva (RF-64)")
            assertEquals("3|1", valor("u-1", "child_pugh", "tp"), "y lo pendiente manda: no se pisa")
            assertEquals("1|dieta|0", valor("u-1", "alimentacion", "diabetes"), "lo que acá no estaba se trae")
            assertEquals(
                mapOf("serologias/hiv" to "NO", "child_pugh/tp" to "2", "alimentacion/diabetes" to "1|dieta"),
                loEnviado().formularios,
                "lo anotado es lo que la web tiene (no lo que el médico descartó ni lo que tiene pendiente el dispositivo)",
            )
        }

    @Test
    fun lo_confirmado_de_los_formularios_se_anota_aunque_no_haya_nada_que_escribir_y_la_lectura_es_idempotente() =
        conBackend(
            ApiFalsa(listOf(cab())).apply {
                consultas[7001] = conConsulta(SeccionesConsultaDto(serologias = mapOf("hiv" to "NO")))
            },
        ) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarFormulario(seccion = "serologias", campo = "hiv", valor = "NO")

            traer()
            backend.traerConsulta("u-1", "7001")
            val primera = volcarTodo(driver)

            assertEquals(mapOf("serologias/hiv" to "NO"), loEnviado().formularios, "lo que la web tiene queda anotado desde la primera lectura")
            assertEquals("NO|0", valor("u-1", "serologias", "hiv"))

            reloj.avanzar(120_000)
            traer()
            backend.traerConsulta("u-1", "7001")

            assertEquals(primera, volcarTodo(driver), "ni filas nuevas, ni updated_at distinto, ni otra fila de lo enviado")
        }

    // ---------- Antecedentes ----------

    @Test
    fun los_antecedentes_de_la_historia_no_duplican_lo_que_la_app_envio_y_lo_del_dispositivo_manda() {
        val historia = HcClinicaPruebas.historiaVacia().let { h ->
            h.copy(
                antecedentesFamiliares = AntecedentesFamiliaresDto(
                    casillas = mapOf(
                        "hta" to CasillaAntecedenteDto(flag = 1, detalle = "madre"),
                        "acv" to CasillaAntecedenteDto(flag = 1, detalle = ""),
                        "dbt_ir" to CasillaAntecedenteDto(flag = 1, detalle = "abuelo"),
                    ),
                ),
            )
        }
        conBackend(ApiFalsa(listOf(cab())).apply { this.historial = historia }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            // Lo que la app cargó y envió: id local, con su consulta.
            db.historiaClinicaQueries.upsertAntecedente(
                Antecedente("a-hta", PACIENTE, "clinica", "familiares", "hta", true, "padre", "u-1").toRow(10, dirty = false),
            )
            // Y una casilla con cambios sin enviar: la web trae otra cosa para la misma clave.
            db.historiaClinicaQueries.upsertAntecedente(
                Antecedente("a-acv", PACIENTE, "clinica", "familiares", "acv", true, "lo del médico", "u-1").toRow(10, dirty = true),
            )

            traer()

            val filas = antecedentes()
            assertEquals(setOf("a-hta", "a-acv", "hcc-a5001-dbt_ir"), filas.map { it.id }.toSet(), "una fila por clave: ni se duplica ni se crea otra")
            assertEquals("padre", filas.single { it.clave == "hta" }.detalle, "lo que la app ya tenía manda")
            assertEquals("lo del médico", filas.single { it.clave == "acv" }.detalle, "y lo sucio también")
            assertEquals("abuelo", filas.single { it.clave == "dbt_ir" }.detalle, "lo que solo tiene la web se trae como siempre")
            assertEquals(1, filas.count { it.clave == "hta" })
            assertTrue(filas.single { it.id == "hcc-a5001-dbt_ir" }.consultaId == null, "los antecedentes son del paciente, sin consulta")
        }
    }

    // ---------- Estudios ----------

    @Test
    fun los_estudios_propios_se_emparejan_por_tipo_y_remoto_id_y_lo_confirmado_se_anota() {
        val historia = HcClinicaPruebas.historiaVacia().let { h ->
            h.copy(estudios = h.estudios.copy(veda = listOf(EstudioDto(901, 7001, dia, "detalle de la web"))))
        }
        conBackend(ApiFalsa(listOf(cab())).apply { this.historial = historia }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarEstudio("r-veda", detalle = "lo del médico", remotoId = "901")

            traer()

            val registros = registros()
            assertEquals(listOf("r-veda"), registros.map { it.id }, "se empareja por tipo + remoto_id: no nace una hcc-rveda-901 aparte")
            assertEquals("detalle de la web", registros.single().campos["detalle"], "y se refresca con lo que cambió en la web")
            assertEquals(LocalDate.parse(dia), registros.single().fecha)
            assertEquals(
                mapOf("901" to mapOf("fecha" to dia, "detalle" to "detalle de la web")),
                loEnviado().registros,
                "lo que la web tiene del estudio queda anotado para el envío siguiente",
            )
            assertEquals(0, textos(driver, "SELECT COUNT(*) FROM registro_clinico WHERE dirty <> 0").single().toInt())
        }
    }

    @Test
    fun un_estudio_propio_con_cambios_sin_enviar_no_se_pisa_y_lo_de_la_web_se_anota_igual() {
        val historia = HcClinicaPruebas.historiaVacia().let { h ->
            h.copy(estudios = h.estudios.copy(veda = listOf(EstudioDto(901, 7001, dia, "detalle de la web"))))
        }
        conBackend(ApiFalsa(listOf(cab())).apply { this.historial = historia }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarEstudio("r-veda", detalle = "lo que escribió el médico", remotoId = "901", dirty = true)

            traer()

            assertEquals("lo que escribió el médico", registros().single().campos["detalle"], "lo pendiente manda")
            assertEquals("1", textos(driver, "SELECT dirty FROM registro_clinico WHERE id = 'r-veda'").single())
            assertEquals(
                mapOf("901" to mapOf("fecha" to dia, "detalle" to "detalle de la web")),
                loEnviado().registros,
                "lo anotado es lo que la web tiene: el envío siguiente compara contra eso y manda el cambio",
            )
        }
    }

    @Test
    fun una_fila_propia_con_remoto_id_no_se_marca_ya_no_figura_aunque_la_historia_no_la_devuelva() {
        conBackend(ApiFalsa(listOf(cab())).apply { this.historial = HcClinicaPruebas.historiaVacia() }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarEstudio("r-veda", detalle = "lo del médico", remotoId = "901")

            traer()

            assertEquals(emptyList(), marcasVigentes(driver), "una fila con remoto_id no se marca: la ausencia se identifica por hcc-r…")
            assertEquals(listOf("r-veda"), registros().map { it.id }, "y la fila sigue en el dispositivo")
            assertEquals("lo del médico", registros().single().campos["detalle"])
        }
    }
}
