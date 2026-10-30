package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.CLAVE_OMITIDAS_LABORATORIO
import com.salud360.core.data.hcgeneral.RespuestasClinicaDeEjemplo
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.OmitidasConsultasDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.data.repos.HcClinicaPruebas.ApiFalsa
import com.salud360.core.data.repos.HcClinicaPruebas.Contexto
import com.salud360.core.data.repos.HcClinicaPruebas.ERRORES
import com.salud360.core.data.repos.HcClinicaPruebas.FICHA
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.TABLAS
import com.salud360.core.data.repos.HcClinicaPruebas.conBackend
import com.salud360.core.data.repos.HcClinicaPruebas.historiaVacia
import com.salud360.core.data.repos.HcClinicaPruebas.marcasVigentes
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.repos.HcClinicaPruebas.volcar
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodoSinMarcas
import com.salud360.core.data.uno
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `HcClinicaBackend.traerConsultas` con el historial por paciente (spec 002, E6.4): `GET pacientes/{id}/historia` se pide
 * una vez por apertura de la historia, detrás de la lista (RF-57), y se aplica por paciente y fecha: antecedentes
 * familiares, cargas de laboratorio y peso, estudios, exámenes, interconsultas, motivos de las consultas que ya están y el
 * pendiente. Todo con `dirty = 0`, lo del dispositivo manda, un valor vacío no pisa y nada se borra (Q15). La lista y el
 * historial son pedidos y transacciones independientes. Datos inventados.
 */
class HcClinicaBackendHistoriaTest {

    private val lista: List<ConsultaCabeceraDto> get() = RespuestasClinicaDeEjemplo.lista().consultas // 7005, 7004, 7003
    private val completa: RespuestaHistoriaDto get() = RespuestasClinicaDeEjemplo.historia()

    /** Un historial a medida: todo vacío salvo lo que se pase (cada parámetro es el JSON de esa clave). */
    private fun historia(
        antecedentes: String = "null",
        perfil4: String? = "null",
        generales: String = "[]",
        reducidos: String = "[]",
        pesos: String = "[]",
        estudios: String = """{"eco_abdominal": [], "veda": [], "biopsia_hepatica": [], "estudios_cardiologicos": [], "tc_rmn": []}""",
        motivos: String = "[]",
        examenes: String = "[]",
        interconsultas: String = "[]",
        pendientes: String = "null",
        sinFecha: Int = 0,
        ficha: Long = FICHA,
    ): RespuestaHistoriaDto = RespuestasClinicaDeEjemplo.historia(
        """{"ok": true, "paciente_id": $ficha, "antecedentes_familiares": $antecedentes,
            ${perfil4?.let { """"antecedentes_familiares_perfil4": $it,""" }.orEmpty()}
            "laboratorios": {"laboratorio_general": $generales, "laboratorio_reducido": $reducidos, "peso": $pesos},
            "estudios": $estudios, "motivos": $motivos, "examenes_complementarios": $examenes, "interconsultas": $interconsultas,
            "pendientes": $pendientes, "omitidas": {"laboratorio_sin_fecha": $sinFecha}}""",
    )

    private fun conLaWeb(
        historial: RespuestaHistoriaDto = completa,
        cabeceras: List<ConsultaCabeceraDto> = lista,
        bloque: suspend Contexto.() -> Unit,
    ) = conBackend(ApiFalsa(cabeceras).apply { this.historial = historial }, bloque = bloque)

    private suspend fun Contexto.traer(forzar: Boolean = true) = backend.traerConsultas(PACIENTE, MEDICO, forzar)

    private fun Contexto.antecedentes() = textos(
        driver,
        "SELECT id||'|'||categoria||'|'||clave||'|'||flag||'|'||detalle||'|'||activo||'|'||ifnull(consulta_id,'-')||'|'||dirty FROM antecedente ORDER BY id",
    )

    private fun Contexto.ids(tabla: String) = textos(driver, "SELECT id FROM $tabla ORDER BY id")

    private fun Contexto.motivos() = textos(
        driver, "SELECT consulta_id||'='||valor||'|'||dirty FROM seccion_valor WHERE seccion = 'motivo_consulta' ORDER BY consulta_id",
    )

    private fun Contexto.pendientes() = textos(
        driver, "SELECT id||'|'||medico_id||'|'||texto||'|'||ifnull(consulta_id,'-')||'|'||resuelto||'|'||dirty FROM pendiente ORDER BY id",
    )

    private fun Contexto.sinFecha() = textos(driver, "SELECT valor||'|'||dirty FROM paciente_extra WHERE clave = '$CLAVE_OMITIDAS_LABORATORIO'")

    private suspend fun Contexto.carga(id: Id) = db.historiaClinicaQueries.laboratorioPorId(id).uno { it.toModel() }

    private suspend fun Contexto.registro(id: Id) = db.historiaClinicaQueries.registroPorId(id).uno { it.toModel() }

    // ---- Cuántos pedidos hace (RF-57, RF-58) ----

    @Test
    fun abrir_la_historia_de_un_paciente_son_la_lista_y_el_historial_en_ese_orden() = conLaWeb {
        traer()

        assertEquals(1, api.pedidos.size)
        assertEquals(listOf(FICHA), api.pedidosHistoria)
        assertEquals(0, api.pedidosConsulta.size)
        assertEquals(2, api.totalDePedidos, "2 pedidos al abrir la historia (RF-57)")
        assertEquals(listOf("lista", "historia"), api.orden, "el historial va después: las consultas ya están cuando se le asocian las cargas")
    }

    @Test
    fun una_lista_de_cuatro_paginas_son_cuatro_pedidos_mas_uno_de_historial() = conLaWeb(cabeceras = muchas(160)) {
        traer()

        assertEquals(listOf(0, 50, 100, 150), api.pedidos.map { it.desde })
        assertEquals(1, api.pedidosHistoria.size, "el historial es un solo pedido por apertura, sean cuantas sean las páginas")
        assertEquals(5, api.totalDePedidos)
        assertEquals(listOf("lista", "lista", "lista", "lista", "historia"), api.orden)
    }

    @Test
    fun un_paciente_sin_consultas_igual_pide_su_historial() = conLaWeb(cabeceras = emptyList()) {
        traer()

        assertEquals(1, api.pedidos.size)
        assertEquals(1, api.pedidosHistoria.size)
    }

    @Test
    fun sin_ficha_no_se_pide_ni_la_lista_ni_el_historial() {
        conBackend(ApiFalsa(lista).apply { historial = completa }, vinculado = false) {
            val antes = volcarTodo(driver)

            assertEquals(emptyList(), traer())
            assertEquals(emptyList(), traer(forzar = false))

            assertEquals(0, api.totalDePedidos, "RF-49: ni consultas ni historial ni pacientes/resolver")
            assertEquals(antes, volcarTodo(driver))
        }
    }

    @Test
    fun antes_de_un_minuto_no_repite_ni_la_lista_ni_el_historial() = conLaWeb {
        traer(forzar = false)
        assertEquals(2, api.totalDePedidos)

        reloj.avanzar(59_999)
        traer(forzar = false)
        assertEquals(2, api.totalDePedidos, "antes de 60 s no hay pedidos")

        reloj.avanzar(1)
        traer(forzar = false)
        assertEquals(4, api.totalDePedidos, "a los 60 s vuelve a pedir las dos cosas")
    }

    @Test
    fun actualizar_fuerza_la_lista_y_el_historial() = conLaWeb {
        traer(forzar = false)

        reloj.avanzar(1_000)
        traer(forzar = true)

        assertEquals(2, api.pedidos.size)
        assertEquals(2, api.pedidosHistoria.size)
    }

    @Test
    fun el_minuto_del_historial_se_cuenta_por_paciente_y_por_medico() = conLaWeb {
        traer(forzar = false)

        backend.traerConsultas(PACIENTE, "m-99000010", false)

        assertEquals(2, api.pedidosHistoria.size, "otro médico del mismo dispositivo no se saltea la lectura")
    }

    // ---- Qué guarda ----

    @Test
    fun todo_lo_del_historial_llega_a_su_tabla_con_dirty_cero() = conLaWeb {
        traer()

        assertEquals(
            listOf(
                "hcc-a5001-dbt_ir|familiares|dbt_ir|1||1|-|0",
                "hcc-a5001-hta|familiares|hta|1|Prueba Salud360 hta detalle|1|-|0",
                "hcc-a5001-nota|familiares|nota|1|Prueba Salud360 nota antecedentes|1|-|0",
                "hcc-a5001-nota_perfil4|familiares|nota_perfil4|1|Prueba Salud360 nota del perfil 4|1|-|0",
                "hcc-a5001-otro|familiares|otro|1|Prueba Salud360 otro af|1|-|0",
            ),
            antecedentes(),
        )
        assertEquals(listOf("hcc-llaboratorio_general-703", "hcc-llaboratorio_reducido-19", "hcc-lpeso-25"), ids("laboratorio"))
        assertEquals(
            listOf(
                "hcc-reco_abdominal-1801", "hcc-reco_abdominal-1802", "hcc-rbiopsia_hepatica-1797", "hcc-rexamen_complementario-22",
                "hcc-rexamen_complementario-23", "hcc-rinterconsulta-14", "hcc-rtc_rmn-1797", "hcc-rveda-1797",
            ).sorted(),
            ids("registro_clinico"),
            "ocho filas: el 1797 de la veda, de la biopsia y de la TC/RMN son tres ids distintos",
        )
        assertEquals(
            listOf("hcc-c7003=Prueba Salud360 motivo (a)|0", "hcc-c7005=Prueba Salud360 motivo (c)|0"),
            motivos(),
        )
        assertEquals(listOf("hcc-p1744|$MEDICO|Prueba Salud360 pendiente|-|0|0"), pendientes())
        assertEquals(0, HcClinicaPruebas.filasSucias(driver), "nada de lo traído queda para enviar (RF-53)")
    }

    @Test
    fun las_cargas_de_laboratorio_y_de_peso_van_por_paciente_y_fecha_con_su_consulta() = conLaWeb {
        traer()

        val general = carga("hcc-llaboratorio_general-703")!!
        assertEquals("hcc-c7003", general.consultaId)
        assertEquals("laboratorio_general", general.tipo)
        assertEquals(LocalDate.parse("2026-03-10"), general.fecha)
        assertEquals(
            mapOf("hto" to "41", "hb" to "13.9", "alb_mina" to "4.1", "f_sforo" to "3.5", "otro" to "Prueba Salud360 otro lab 2"),
            general.valores,
        )
        assertEquals(mapOf("peso" to "70.5"), carga("hcc-lpeso-25")!!.valores)
        assertEquals("laboratorio_reducido", carga("hcc-llaboratorio_reducido-19")!!.tipo)
        assertEquals(PACIENTE, general.pacienteId)
        assertEquals("clinica", general.especialidad)
    }

    @Test
    fun los_estudios_los_examenes_y_las_interconsultas_llevan_su_tipo_su_fecha_y_su_consulta() = conLaWeb {
        traer()

        val veda = registro("hcc-rveda-1797")!!
        assertEquals(mapOf("detalle" to "Prueba Salud360 videoendoscopia (a)"), veda.campos)
        assertEquals(LocalDate.parse("2026-02-02"), veda.fecha)
        assertEquals("hcc-c7003", veda.consultaId)
        assertEquals("1797", veda.remotoId)
        assertEquals("biopsia_hepatica", registro("hcc-rbiopsia_hepatica-1797")!!.tipo)
        assertEquals("tc_rmn", registro("hcc-rtc_rmn-1797")!!.tipo)
        assertEquals(
            mapOf("solicito" to "Prueba Salud360 examen solicitado 1", "respuesta" to "Prueba Salud360 examen respondido 1", "numero" to "1"),
            registro("hcc-rexamen_complementario-22")!!.campos,
        )
        assertEquals(
            mapOf("solicito" to "Prueba Salud360 examen solicitado 2", "numero" to "2"),
            registro("hcc-rexamen_complementario-23")!!.campos,
            "la respuesta que no hay no se inventa",
        )
        assertEquals(
            mapOf(
                "especialista" to "Prueba Salud360 especialista 1", "solicito" to "Prueba Salud360 interconsulta solicitada 1",
                "respuesta" to "Prueba Salud360 interconsulta respondida 1", "numero" to "1",
            ),
            registro("hcc-rinterconsulta-14")!!.campos,
        )
    }

    @Test
    fun un_estudio_de_una_consulta_que_no_esta_en_el_dispositivo_se_guarda_sin_consulta_ni_fecha_inventada() = conLaWeb {
        traer()

        val sinFecha = registro("hcc-reco_abdominal-1802")!!
        assertNull(sinFecha.consultaId, "la 7006 no está acá y no se crea")
        assertNull(sinFecha.fecha)
        assertEquals(mapOf("detalle" to "Prueba Salud360 ecografía abdominal (f) sin fecha"), sinFecha.campos)
    }

    @Test
    fun una_cardiologica_de_191_caracteres_entra_entera() {
        val detalle = "ñ".repeat(191)
        val h = historia(
            estudios = """{"eco_abdominal": [], "veda": [], "biopsia_hepatica": [], "tc_rmn": [],
                "estudios_cardiologicos": [{"id": 1805, "consulta_id": 7003, "fecha": "2026-02-05", "detalle": "$detalle"}]}""",
        )
        conLaWeb(h) {
            traer()

            assertEquals(detalle, registro("hcc-restudios_cardiologicos-1805")!!.campos["detalle"])
            assertEquals(191, registro("hcc-restudios_cardiologicos-1805")!!.campos.getValue("detalle").length)
        }
    }

    @Test
    fun los_motivos_solo_van_a_las_consultas_que_ya_estan_y_no_se_crea_ninguna() = conLaWeb(cabeceras = lista.filter { it.id == 7005L }) {
        traer()

        assertEquals(listOf("hcc-c7005=Prueba Salud360 motivo (c)|0"), motivos(), "el de la 7003 no tiene dónde mostrarse")
        assertEquals(listOf("hcc-c7005"), ids("consulta"))
        assertEquals(listOf("hcc-c7005"), textos(driver, "SELECT DISTINCT consulta_id FROM seccion_valor"))
    }

    @Test
    fun una_carga_cuya_consulta_no_esta_se_guarda_sin_consulta() = conLaWeb(cabeceras = lista.filter { it.id == 7005L }) {
        traer()

        assertNull(carga("hcc-llaboratorio_general-703")!!.consultaId)
        assertNull(registro("hcc-rveda-1797")!!.consultaId)
    }

    // ---- Antecedentes y la nota del perfil 4 ----

    @Test
    fun una_casilla_en_cero_sin_detalle_no_se_inventa_y_con_detalle_se_guarda_sin_marcar() = conLaWeb(
        historia(antecedentes = """{"hta": {"flag": 0}, "acv": {"flag": 0, "detalle": ""}, "dsl": {"flag": 0, "detalle": "Prueba Salud360 dsl"}, "fa": {"flag": 1}}"""),
    ) {
        traer()

        assertEquals(
            listOf(
                "hcc-a5001-dsl|familiares|dsl|0|Prueba Salud360 dsl|1|-|0",
                "hcc-a5001-fa|familiares|fa|1||1|-|0",
            ),
            antecedentes(),
        )
    }

    @Test
    fun la_nota_del_perfil_4_es_el_antecedente_nota_perfil4_aparte_de_la_nota() = conLaWeb(
        historia(antecedentes = """{"nota": "Prueba Salud360 nota"}""", perfil4 = """{"nota": "Prueba Salud360 perfil 4"}"""),
    ) {
        traer()

        assertEquals(
            listOf(
                "hcc-a5001-nota|familiares|nota|1|Prueba Salud360 nota|1|-|0",
                "hcc-a5001-nota_perfil4|familiares|nota_perfil4|1|Prueba Salud360 perfil 4|1|-|0",
            ),
            antecedentes(),
        )
    }

    @Test
    fun sin_nota_del_perfil_4_o_con_el_servidor_anterior_que_no_la_manda_no_se_escribe_nada() {
        for (perfil4 in listOf("null", """{"nota": "   "}""", null)) {
            conLaWeb(historia(perfil4 = perfil4)) {
                traer()

                assertEquals(emptyList(), antecedentes(), "perfil4=$perfil4")
            }
        }
    }

    // ---- D1: las cargas sin fecha ----

    @Test
    fun las_cargas_sin_fecha_no_llegan_y_se_cuentan_con_su_aviso() = conLaWeb(historia(sinFecha = 3)) {
        traer()

        assertEquals(listOf("3|0"), sinFecha())
        assertEquals(emptyList(), ids("laboratorio"))
    }

    @Test
    fun una_carga_que_llegara_sin_fecha_igual_no_se_guarda() = conLaWeb(
        historia(
            generales = """[{"id": 703, "consulta_id": 7003, "fecha": null, "valores": {"hb": "13.9"}},
                            {"id": 704, "consulta_id": 7003, "fecha": "2026-03-11", "valores": {"hb": "14"}}]""",
            pesos = """[{"id": 25, "consulta_id": 7003, "fecha": "1000-01-01", "valores": {"peso": "70.5"}}]""",
        ),
    ) {
        traer()

        assertEquals(listOf("hcc-llaboratorio_general-704"), ids("laboratorio"))
    }

    @Test
    fun la_cuenta_sin_fecha_en_cero_y_sin_fila_previa_no_escribe_nada_y_sigue_a_la_web_si_baja() = conLaWeb(historia(sinFecha = 0)) {
        traer()
        assertEquals(emptyList(), sinFecha())

        api.historial = historia(sinFecha = 2)
        traer()
        assertEquals(listOf("2|0"), sinFecha())

        api.historial = historia(sinFecha = 0)
        traer()
        assertEquals(listOf("0|0"), sinFecha(), "es una cuenta, no un dato clínico: sigue a la web también hacia cero")
    }

    @Test
    fun la_cuenta_de_la_lista_y_la_del_historial_no_se_pisan() = conBackend(
        ApiFalsa(lista, omitidas = OmitidasConsultasDto(foto = 1, otro = 2, sinFecha = 3))
            .apply { historial = historia(sinFecha = 5) },
    ) {
        traer()

        assertEquals(listOf("1|2|3|0"), textos(driver, "SELECT valor||'|'||dirty FROM paciente_extra WHERE clave = 'hcc_omitidas'"))
        assertEquals(listOf("5|0"), sinFecha())
    }

    // ---- Traer dos veces no duplica ----

    @Test
    fun traer_dos_veces_no_duplica_ni_reescribe() = conLaWeb {
        traer()
        val antes = volcarTodo(driver)
        reloj.avanzar(5_000)

        traer()

        assertEquals(antes, volcarTodo(driver), "ni filas nuevas ni updated_at distinto")
        assertEquals(5, antecedentes().size)
        assertEquals(8, ids("registro_clinico").size)
    }

    // ---- Lo del dispositivo manda (RF-48, RF-54) ----

    @Test
    fun lo_sucio_del_dispositivo_no_se_pisa_y_lo_demas_entra() = conLaWeb {
        val q = db.historiaClinicaQueries
        q.upsertAntecedente(Antecedente("hcc-a5001-hta", PACIENTE, "clinica", "familiares", "hta", false, "lo que escribió el médico", null).toRow(999, dirty = true))
        q.upsertLaboratorio(
            Laboratorio("hcc-llaboratorio_general-703", PACIENTE, "hcc-c7003", "clinica", "laboratorio_general", LocalDate.parse("2026-03-10"), mapOf("hb" to "9"))
                .toRow(999, dirty = true),
        )
        q.upsertRegistro(
            RegistroClinico("hcc-rveda-1797", PACIENTE, "hcc-c7003", "clinica", "veda", LocalDate.parse("2026-02-02"), mapOf("detalle" to "lo del médico"), true, "1797")
                .toRow(999, dirty = true),
        )
        q.upsertPendiente(Pendiente("hcc-p1744", PACIENTE, MEDICO, "lo del médico", null, true).toRow(999, dirty = true))
        q.upsertSeccionValor(SeccionValor("hcc-c7003", "motivo_consulta", "texto", "lo del médico").toRow(999, dirty = true))
        db.pacientesQueries.upsertPacienteExtra(PacienteExtra(PACIENTE, "clinica", CLAVE_OMITIDAS_LABORATORIO, "9").toRow(999, dirty = true))
        // Las filas sucias se reconocen por su `updated_at` (999), que ninguna otra tiene.
        val antes = TABLAS.associateWith { t -> volcar(driver, t).filter { "|999|" in it || it.endsWith("|999") } }
        assertEquals(6, antes.values.sumOf { it.size })

        traer()

        assertEquals(antes, TABLAS.associateWith { t -> volcar(driver, t).filter { "|999|" in it || it.endsWith("|999") } }, "las filas sucias, tal cual: valor, updated_at y dirty")
        assertEquals(1, antecedentes().count { it.startsWith("hcc-a5001-hta|") })
        assertEquals(listOf("lo que escribió el médico"), textos(driver, "SELECT detalle FROM antecedente WHERE id = 'hcc-a5001-hta'"))
        assertEquals(listOf("hcc-p1744|$MEDICO|lo del médico|-|1|1"), pendientes())
        assertEquals(listOf("9|1"), sinFecha())
        assertEquals(listOf("hcc-c7003=lo del médico|1", "hcc-c7005=Prueba Salud360 motivo (c)|0"), motivos())
        // Lo que no estaba sucio entra.
        assertEquals(4, antecedentes().count { it.endsWith("|0") })
        assertEquals(listOf("hcc-llaboratorio_reducido-19", "hcc-llaboratorio_general-703", "hcc-lpeso-25").sorted(), ids("laboratorio"))
    }

    @Test
    fun un_antecedente_del_dispositivo_de_la_misma_casilla_manda_y_no_se_duplica() = conLaWeb {
        val q = db.historiaClinicaQueries
        // Uno del dispositivo con otro id, sin enviar, y otro ya enviado (la HC de clínica no envía, pero 8.sqm deja filas así).
        q.upsertAntecedente(Antecedente("u-1", PACIENTE, "clinica", "familiares", "dbt_ir", false, "mío", null).toRow(50, dirty = true))
        q.upsertAntecedente(Antecedente("u-2", PACIENTE, "clinica", "familiares", "otro", true, "del dispositivo", null).toRow(50, dirty = false))

        traer()

        assertEquals(1, antecedentes().count { it.contains("|dbt_ir|") })
        assertEquals(1, antecedentes().count { it.contains("|otro|") })
        assertEquals(listOf("mío"), textos(driver, "SELECT detalle FROM antecedente WHERE id = 'u-1'"))
        assertEquals(listOf("del dispositivo"), textos(driver, "SELECT detalle FROM antecedente WHERE id = 'u-2'"))
        assertEquals(3, antecedentes().count { it.startsWith("hcc-a5001-") }, "hta, nota y nota_perfil4 sí entran")
    }

    @Test
    fun una_carga_del_dispositivo_de_la_misma_fecha_con_otro_id_convive_con_la_de_la_web() = conLaWeb {
        db.historiaClinicaQueries.upsertLaboratorio(
            Laboratorio("u-lab", PACIENTE, null, "clinica", "laboratorio_general", LocalDate.parse("2026-03-10"), mapOf("hb" to "12")).toRow(50, dirty = true),
        )

        traer()

        assertEquals(listOf("hcc-llaboratorio_general-703", "hcc-llaboratorio_reducido-19", "hcc-lpeso-25", "u-lab"), ids("laboratorio"))
        assertEquals(mapOf("hb" to "12"), carga("u-lab")!!.valores)
    }

    // ---- Q15: nunca borrar, nunca vaciar ----

    @Test
    fun un_historial_vacio_no_borra_ni_vacia_nada_de_lo_traido() = conLaWeb {
        traer()
        val tablas = TABLAS - "paciente_extra"
        val antes = tablas.associateWith { volcar(driver, it) }

        api.historial = historiaVacia()
        traer()

        assertEquals(antes, tablas.associateWith { volcar(driver, it) }, "las seis tablas fila por fila")
        // Lo que la web dejó de mandar recibe una marca (E6.4b; el detalle, en HcClinicaBackendAusentesTest), y nada más.
        val marcadas = marcasVigentes(driver).map { it.substringBefore("=") }
        assertTrue(listOf("hcc-a5001-hta", "hcc-llaboratorio_general-703", "hcc-rveda-1797", "hcc-p1744").all { it in marcadas }, "$marcadas")
        assertTrue("hcc-a5001-nota_perfil4" !in marcadas, "la nota del perfil 4 no se marca nunca")
    }

    @Test
    fun un_historial_mas_corto_no_borra_lo_que_dejo_de_venir() = conLaWeb {
        traer()
        val antes = volcarTodoSinMarcas(driver)

        api.historial = historia(
            antecedentes = """{"hta": {"flag": 1, "detalle": "Prueba Salud360 hta detalle"}}""",
            generales = """[{"id": 703, "consulta_id": 7003, "fecha": "2026-03-10", "valores": {"hb": "13.9"}}]""",
            pendientes = """{"id": 1744, "texto": "Prueba Salud360 pendiente"}""",
            sinFecha = 1,
        )
        traer()

        assertEquals(antes, volcarTodoSinMarcas(driver), "la lista de la web es otra y la cuenta es la misma: las tablas quedan idénticas")
        val marcadas = marcasVigentes(driver).map { it.substringBefore("=") }
        assertTrue("hcc-llaboratorio_reducido-19" in marcadas && "hcc-lpeso-25" in marcadas && "hcc-rveda-1797" in marcadas, "$marcadas")
        assertTrue(listOf("hcc-llaboratorio_general-703", "hcc-a5001-hta", "hcc-p1744").none { it in marcadas }, "lo que sigue viniendo no se marca")
    }

    @Test
    fun un_valor_vacio_o_que_no_viene_no_pisa_el_que_habia_pero_uno_con_dato_si() = conLaWeb {
        traer()

        api.historial = historia(
            antecedentes = """{"hta": {"flag": 1, "detalle": ""}, "otro": {"flag": 1, "detalle": "Prueba Salud360 otro af NUEVO"}}""",
            generales = """[{"id": 703, "consulta_id": null, "fecha": "2026-03-10", "valores": {"hb": "", "hto": "44", "creatinina": "0.9"}}]""",
            estudios = """{"eco_abdominal": [{"id": 1801, "consulta_id": null, "fecha": null, "detalle": "Prueba Salud360 ecografía NUEVA"}],
                "veda": [], "biopsia_hepatica": [], "estudios_cardiologicos": [], "tc_rmn": []}""",
            motivos = """[{"id": 1857, "consulta_id": 7003, "fecha": "2026-03-10", "texto": "  "}]""",
            examenes = """[{"id": 22, "consulta_id": 7003, "fecha": "2026-03-10", "solicito": "", "respuesta": "Prueba Salud360 respuesta NUEVA"}]""",
        )
        traer()

        // Antecedente: el detalle vacío no borra el que había; el nuevo pisa.
        assertTrue("hcc-a5001-hta|familiares|hta|1|Prueba Salud360 hta detalle|1|-|0" in antecedentes())
        assertTrue("hcc-a5001-otro|familiares|otro|1|Prueba Salud360 otro af NUEVO|1|-|0" in antecedentes())
        // Carga: hb vacío no pisa, hto nuevo sí, creatinina se agrega y lo demás se conserva; la consulta no vuelve a null.
        val general = carga("hcc-llaboratorio_general-703")!!
        assertEquals("13.9", general.valores["hb"])
        assertEquals("44", general.valores["hto"])
        assertEquals("0.9", general.valores["creatinina"])
        assertEquals("4.1", general.valores["alb_mina"])
        assertEquals("hcc-c7003", general.consultaId)
        // Estudio: el nuevo detalle pisa; ni la consulta ni la fecha vuelven a null.
        val eco = registro("hcc-reco_abdominal-1801")!!
        assertEquals(mapOf("detalle" to "Prueba Salud360 ecografía NUEVA"), eco.campos)
        assertEquals("hcc-c7003", eco.consultaId)
        assertEquals(LocalDate.parse("2026-02-01"), eco.fecha)
        // Examen: el solicito vacío no pisa, la respuesta nueva sí, el número queda.
        assertEquals(
            mapOf("solicito" to "Prueba Salud360 examen solicitado 1", "respuesta" to "Prueba Salud360 respuesta NUEVA", "numero" to "1"),
            registro("hcc-rexamen_complementario-22")!!.campos,
        )
        // Motivo en blanco: no pisa.
        assertEquals("hcc-c7003=Prueba Salud360 motivo (a)|0", motivos().first())
    }

    @Test
    fun el_pendiente_que_deja_de_venir_o_viene_vacio_se_queda() = conLaWeb {
        traer()
        val antes = pendientes()

        api.historial = historia(pendientes = "null")
        traer()
        assertEquals(antes, pendientes())

        api.historial = historia(pendientes = """{"id": 1744, "texto": ""}""")
        traer()
        assertEquals(antes, pendientes())

        api.historial = historia(pendientes = """{"id": 1744, "texto": "Prueba Salud360 pendiente NUEVO"}""")
        traer()
        assertEquals(listOf("hcc-p1744|$MEDICO|Prueba Salud360 pendiente NUEVO|-|0|0"), pendientes())
    }

    @Test
    fun el_pendiente_es_del_medico_de_la_sesion_que_lo_trae() = conLaWeb {
        traer()

        backend.traerConsultas(PACIENTE, "m-99000010", true)

        assertEquals(listOf("hcc-p1744|m-99000010|Prueba Salud360 pendiente|-|0|0"), pendientes())
    }

    // ---- La lista y el historial son pedidos y transacciones independientes ----

    @Test
    fun si_el_historial_falla_la_lista_ya_aplicada_se_queda_y_el_error_sale_tal_cual() {
        for (error in ERRORES) {
            conLaWeb {
                api.fallaHistorial = error
                val antesDeTodo = volcarTodo(driver)

                val e = assertFailsWith<TobbException>("${error.status} ${error.codigo}") { traer() }

                assertSame(error, e)
                assertEquals(listOf("hcc-c7003", "hcc-c7004", "hcc-c7005"), ids("consulta"), "la lista quedó: ${error.status} ${error.codigo}")
                assertEquals(1, api.pedidos.size)
                assertEquals(1, api.pedidosHistoria.size, "sin reintento (RF-58): ${error.status} ${error.codigo}")
                val despues = volcarTodo(driver)
                for (t in TABLAS - "consulta" - "medico_preferencia") {
                    assertEquals(antesDeTodo.getValue(t), despues.getValue(t), "$t no cambia: ${error.status} ${error.codigo}")
                }
            }
        }
    }

    @Test
    fun tras_un_error_del_historial_el_proximo_pedido_pide_solo_el_historial() = conLaWeb {
        api.fallaHistorial = TobbException(429, null, "Too Many Attempts.")
        assertFailsWith<TobbException> { traer(forzar = false) }
        assertEquals(1, api.pedidos.size)
        assertEquals(1, api.pedidosHistoria.size)

        api.fallaHistorial = null
        reloj.avanzar(10_000)
        traer(forzar = false)

        assertEquals(1, api.pedidos.size, "la lista se leyó bien hace 10 s: no se repite antes del minuto")
        assertEquals(2, api.pedidosHistoria.size, "el historial no se leyó nunca: se pide")
        assertEquals(5, antecedentes().size)
        assertEquals(listOf("lista", "historia", "historia"), api.orden)
    }

    @Test
    fun si_la_lista_falla_el_historial_no_se_pide_ni_se_aplica() {
        for (error in ERRORES) {
            conLaWeb {
                val antes = volcarTodo(driver)
                api.fallas[1] = error

                val e = assertFailsWith<TobbException>("${error.status} ${error.codigo}") { traer() }

                assertSame(error, e)
                assertEquals(1, api.totalDePedidos, "un 429, un 401 o una caída de la lista valen para el historial: no se insiste (RF-57)")
                assertEquals(antes, volcarTodo(driver))
            }
        }
    }

    @Test
    fun tras_un_error_de_la_lista_el_proximo_pedido_pide_las_dos_cosas() = conLaWeb {
        api.fallas[1] = TobbException(500, "consultas", "No se pudo leer la historia clínica.")
        assertFailsWith<TobbException> { traer(forzar = false) }

        traer(forzar = false)

        assertEquals(2, api.pedidos.size)
        assertEquals(1, api.pedidosHistoria.size)
        assertEquals(3, ids("consulta").size)
        assertEquals(5, antecedentes().size)
    }

    @Test
    fun si_falla_al_escribir_el_historial_no_queda_a_medias_y_la_lista_se_queda() = conLaWeb {
        api.fallaHistorial = TobbException(500, "consultas", "x")
        assertFailsWith<TobbException> { traer() }
        val trasLaLista = volcarTodo(driver)
        api.fallaHistorial = null
        // Los antecedentes se escriben antes que las cargas: la base rechaza la primera carga y los antecedentes tienen que deshacerse.
        driver.execute(null, "CREATE TRIGGER prueba_falla BEFORE INSERT ON laboratorio BEGIN SELECT RAISE(ABORT, 'prueba'); END", 0).await()

        assertFailsWith<Exception> { traer() }

        driver.execute(null, "DROP TRIGGER prueba_falla", 0).await()
        assertEquals(trasLaLista, volcarTodo(driver), "ni antecedentes, ni cuenta, ni nada del historial; las 3 consultas siguen")
        assertEquals(3, ids("consulta").size)
    }

    @Test
    fun si_falla_al_escribir_la_lista_el_historial_no_se_pide() = conLaWeb {
        val antes = volcarTodo(driver)
        driver.execute(null, "CREATE TRIGGER prueba_falla BEFORE INSERT ON consulta WHEN NEW.id = 'hcc-c7005' BEGIN SELECT RAISE(ABORT, 'prueba'); END", 0).await()

        assertFailsWith<Exception> { traer() }

        driver.execute(null, "DROP TRIGGER prueba_falla", 0).await()
        assertEquals(0, api.pedidosHistoria.size)
        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun un_historial_de_otra_ficha_no_se_aplica() = conLaWeb(historia(antecedentes = """{"hta": {"flag": 1}}""", ficha = 9999)) {
        val e = assertFailsWith<TobbException> { traer() }

        assertEquals("respuesta", e.codigo)
        assertEquals(3, ids("consulta").size, "la lista, que era buena, se queda")
        assertEquals(emptyList(), antecedentes())
        assertEquals(1, api.pedidosHistoria.size)
    }

    @Test
    fun el_vinculo_de_la_ficha_queda_como_estaba() = conLaWeb {
        val antes = volcar(driver, "paciente_extra").filter { "hc_paciente_id" in it }
        assertEquals(1, antes.size)

        traer()

        assertEquals(antes, volcar(driver, "paciente_extra").filter { "hc_paciente_id" in it })
    }

    // ---- Auxiliares ----

    /** [n] consultas de a una por día, de la más nueva a la más vieja. */
    private fun muchas(n: Int, desde: Long = 8000): List<ConsultaCabeceraDto> = (0 until n).map { i ->
        val id = desde + n - i
        val dia = java.time.LocalDate.parse("2026-06-30").minusDays(i.toLong())
        ConsultaCabeceraDto(
            id = id, pacienteId = FICHA, medicoId = 9, medicoNombre = "Médico Prueba Salud360", tipo = "control", tipoNumero = 1,
            fecha = dia.toString(), createdAt = "$dia 10:00:00", estado = "CERRADA", edadMostrar = "", propia = true, updatedAt = "$dia 10:00:00",
        )
    }
}
