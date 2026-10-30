package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.RespuestasClinicaDeEjemplo
import com.salud360.core.data.hcgeneral.claveNoLeidas
import com.salud360.core.data.hcgeneral.valoresDeLaWeb
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.EjemplosConsultasClinica
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.repos.HcClinicaPruebas.ApiFalsa
import com.salud360.core.data.repos.HcClinicaPruebas.Contexto
import com.salud360.core.data.repos.HcClinicaPruebas.ERRORES
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.conBackend
import com.salud360.core.data.repos.HcClinicaPruebas.sembrar
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.repos.HcClinicaPruebas.volcar
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `HcClinicaBackend.traerConsulta` (spec 002, E6.4) con una API falsa y una base SQLite de verdad: un pedido por consulta
 * (RF-57), el contenido de la consulta a `seccion_valor` y `examen_fisico` siempre con `dirty = 0` (RF-53), lo sucio del
 * dispositivo no se toca (RF-54), un valor vacío o que no viene no pisa nada (RF-55), nada se borra (Q15) y `no_leidas`
 * queda guardado para avisar (RF-43). Datos inventados.
 */
class HcClinicaBackendConsultaTest {

    private val completa: RespuestaConsultaDto get() = RespuestasClinicaDeEjemplo.consulta()
    private val abierta: RespuestaConsultaDto get() = RespuestasClinicaDeEjemplo.consulta(EjemplosConsultasClinica.CONSULTA_ABIERTA)
    private val conPdf: RespuestaConsultaDto get() = RespuestasClinicaDeEjemplo.consulta(EjemplosConsultasClinica.CONSULTA_CON_PDF)

    private fun apiCon(vararg consultas: RespuestaConsultaDto) = ApiFalsa().apply { consultas.forEach { this.consultas[it.consulta.id] = it } }

    private suspend fun Contexto.traer(id: Long = 7003) = backend.traerConsulta("hcc-c$id", "$id")

    private fun Contexto.valores(consulta: Id = "hcc-c7003") = textos(
        driver, "SELECT seccion||'/'||campo||'='||valor FROM seccion_valor WHERE consulta_id = '$consulta' ORDER BY 1",
    )

    private fun Contexto.examen(consulta: Id = "hcc-c7003") = textos(
        driver,
        "SELECT peso||'|'||talla||'|'||tension_arterial||'|'||imc||'|'||nota||'|'||frecuencia_cardiaca||'|'||circunferencia_abdominal||'|'||dirty " +
            "FROM examen_fisico WHERE consulta_id = '$consulta'",
    )

    private fun Contexto.noLeidas(id: Long = 7003) = textos(
        driver, "SELECT valor||'|'||dirty FROM paciente_extra WHERE paciente_id = '$PACIENTE' AND clave = '${claveNoLeidas(id)}'",
    )

    // ---- Lo que guarda ----

    @Test
    fun una_consulta_de_la_web_llega_a_seccion_valor_y_examen_fisico_sin_dirty() = conBackend(apiCon(completa)) {
        db.sembrar("hcc-c7003")

        traer()

        val esperado = valoresDeLaWeb("hcc-c7003", completa.secciones).map { "${it.seccion}/${it.campo}=${it.valor}" }.sorted()
        assertEquals(esperado, valores())
        listOf(
            "motivo_consulta/texto=Prueba Salud360 motivo (a)",
            "habitos/texto=Prueba Salud360 hábitos (a): tabaco, alcohol, ejercicio",
            "alimentacion/hiposodica=1|Prueba Salud360 hiposódica (a)",
            "alimentacion/celiaco=1|Prueba Salud360 celíaco detalle (a)",
            "serologias/igm_hav=SI", "serologias/ags_hbv=NO", "serologias/hiv=NO",
            "child_pugh/ascitis=2", "child_pugh/bilirrubina=1", "child_pugh/albumina=3", "child_pugh/tp=2", "child_pugh/encefalopatia=1",
        ).forEach { assertTrue(it in valores(), it) }
        assertEquals(listOf("70.5|170|120/80|24.4|Prueba Salud360 examen físico (a)|||0"), examen())
        assertEquals(0, HcClinicaPruebas.filasSucias(driver), "nada de lo traído queda para enviar (RF-53)")
    }

    @Test
    fun un_solo_pedido_a_consultas_id_y_ninguno_mas() = conBackend(apiCon(completa)) {
        db.sembrar("hcc-c7003")

        traer()

        assertEquals(listOf(7003L), api.pedidosConsulta)
        assertEquals(0, api.pedidos.size, "ni la lista")
        assertEquals(0, api.pedidosHistoria.size, "ni el historial (son 2 pedidos al abrir la historia, 1 al abrir la consulta: RF-57)")
        assertEquals(listOf("consulta"), api.orden)
    }

    @Test
    fun abrir_la_misma_consulta_dos_veces_son_dos_pedidos_pero_no_duplica_ni_reescribe() = conBackend(apiCon(completa)) {
        db.sembrar("hcc-c7003")
        traer()
        val antes = volcarTodo(driver)
        reloj.avanzar(5_000)

        traer()

        assertEquals(2, api.pedidosConsulta.size, "no hay espera de un minuto para una consulta: el pedido es de quien la abre")
        assertEquals(antes, volcarTodo(driver), "ni filas nuevas ni updated_at distinto")
    }

    @Test
    fun solo_escribe_filas_de_esa_consulta() = conBackend(apiCon(completa)) {
        db.sembrar("hcc-c7003")
        db.sembrar("hcc-c7004")
        val q = db.historiaClinicaQueries
        q.upsertSeccionValor(SeccionValor("hcc-c7004", "nota", "texto", "de la 7004").toRow(10, dirty = false))
        val antesDeLaOtra = valores("hcc-c7004")

        traer()

        assertEquals(antesDeLaOtra, valores("hcc-c7004"))
        assertEquals(listOf("hcc-c7003", "hcc-c7004"), textos(driver, "SELECT DISTINCT consulta_id FROM seccion_valor ORDER BY 1"))
        assertEquals(listOf("hcc-c7003"), textos(driver, "SELECT consulta_id FROM examen_fisico"))
    }

    @Test
    fun los_textos_se_guardan_intactos() = conBackend(
        apiCon(RespuestasClinicaDeEjemplo.consultaConSecciones("""{"nota": {"texto": "  Año nuevo\nsegunda línea 😀  \n"}}""")),
    ) {
        db.sembrar("hcc-c7003")

        traer()

        assertEquals(listOf("  Año nuevo\nsegunda línea 😀  \n"), textos(driver, "SELECT valor FROM seccion_valor WHERE campo = 'texto' AND seccion = 'nota'"))
    }

    @Test
    fun el_imc_y_la_talla_en_cero_de_la_web_se_guardan_como_datos() = conBackend(apiCon(abierta)) {
        db.sembrar("hcc-c7005", estado = EstadoConsulta.ABIERTA)

        traer(7005)

        // D4: el servidor manda "0" y "0.0" cuando son datos (lo «sin cargar» es "0.00" y no llega).
        assertEquals(listOf("|0.0||0||||0"), examen("hcc-c7005"))
    }

    // ---- Una consulta de otro médico, abierta o cerrada, es solo para leer (RF-25, RF-45) ----

    @Test
    fun traer_la_consulta_nunca_cambia_la_consulta_ni_crea_otra() {
        val ajena = completa.copy(consulta = completa.consulta.copy(id = 7004, propia = false, medicoId = 14))
        conBackend(apiCon(completa, ajena, abierta)) {
            for ((id, estado) in listOf(7003L to EstadoConsulta.CERRADA, 7004L to EstadoConsulta.CERRADA, 7005L to EstadoConsulta.ABIERTA)) {
                db.sembrar("hcc-c$id", estado = estado, medico = if (id == 7004L) "hcc-m14" else HcClinicaPruebas.MEDICO)
            }
            val antes = volcar(driver, "consulta")

            traer(7003)
            traer(7004)
            traer(7005)

            assertEquals(antes, volcar(driver, "consulta"), "ni estado, ni médico, ni edad, ni updated_at; y ninguna fila más")
            assertEquals(0, textos(driver, "SELECT COUNT(*) FROM consulta WHERE dirty <> 0").single().toInt())
        }
    }

    // ---- Lo que no viene o viene vacío no pisa (RF-55, Q15) ----

    private suspend fun Contexto.sembrarLoPrevio() {
        val q = db.historiaClinicaQueries
        db.sembrar("hcc-c7003")
        q.upsertSeccionValor(SeccionValor("hcc-c7003", "motivo_consulta", "texto", "Prueba Salud360 motivo local").toRow(10, dirty = false))
        q.upsertSeccionValor(SeccionValor("hcc-c7003", "habitos", "texto", "Prueba Salud360 hábitos local").toRow(10, dirty = false))
        q.upsertSeccionValor(SeccionValor("hcc-c7003", "serologias", "hiv", "SI").toRow(10, dirty = false))
        q.upsertExamenFisico(
            ExamenFisico("hcc-c7003", peso = "65", talla = "160", tensionArterial = "110/70", imc = "25.4", nota = "nota local",
                frecuenciaCardiaca = "72", circunferenciaAbdominal = "95").toRow(10, dirty = false),
        )
        q.upsertAntecedente(Antecedente("hcc-a5001-hta", PACIENTE, "clinica", "familiares", "hta", true, "Prueba Salud360 hta", null).toRow(10, dirty = false))
        q.upsertRegistro(
            RegistroClinico("hcc-rveda-1797", PACIENTE, "hcc-c7003", "clinica", "veda", LocalDate.parse("2026-02-02"), mapOf("detalle" to "Prueba Salud360 veda"), true, "1797")
                .toRow(10, dirty = false),
        )
        q.upsertLaboratorio(
            Laboratorio("hcc-llaboratorio_general-703", PACIENTE, "hcc-c7003", "clinica", "laboratorio_general", LocalDate.parse("2026-03-10"), mapOf("hb" to "13.9"))
                .toRow(10, dirty = false),
        )
        q.upsertPendiente(Pendiente("hcc-p1744", PACIENTE, HcClinicaPruebas.MEDICO, "Prueba Salud360 pendiente", null, false).toRow(10, dirty = false))
    }

    @Test
    fun una_respuesta_mas_corta_o_vacia_no_borra_ni_vacia_nada() {
        val vacia = RespuestasClinicaDeEjemplo.consultaConSecciones("{}")
        val enBlanco = RespuestasClinicaDeEjemplo.consultaConSecciones(
            """{"motivo_consulta": {"texto": ""}, "habitos": {"texto": "   "}, "serologias": {"hiv": ""}, "alimentacion": {"hiposodica": "0|"}}""",
            """{"peso": "", "talla": " ", "nota": null}""",
        )
        for (respuesta in listOf(vacia, enBlanco)) {
            conBackend(apiCon(respuesta)) {
                sembrarLoPrevio()
                val antes = volcarTodo(driver)

                traer()

                assertEquals(antes, volcarTodo(driver), "seccion_valor, examen_fisico, registro_clinico, laboratorio, antecedente y pendiente fila por fila")
            }
        }
    }

    @Test
    fun un_valor_con_dato_pisa_pero_los_que_no_vienen_se_conservan() = conBackend(
        apiCon(RespuestasClinicaDeEjemplo.consultaConSecciones("""{"habitos": {"texto": "Prueba Salud360 hábitos de la web"}}""", """{"peso": "70.5"}""")),
    ) {
        sembrarLoPrevio()

        traer()

        assertEquals(
            listOf(
                "habitos/texto=Prueba Salud360 hábitos de la web", "motivo_consulta/texto=Prueba Salud360 motivo local", "serologias/hiv=SI",
            ),
            valores(),
        )
        // El peso es de la web; lo que ella no manda (talla, tensión, nota, y la frecuencia y la circunferencia que no tiene) queda.
        assertEquals(listOf("70.5|160|110/70|25.4|nota local|72|95|0"), examen())
    }

    @Test
    fun el_examen_conserva_la_frecuencia_cardiaca_y_la_circunferencia_abdominal_locales() = conBackend(apiCon(completa)) {
        sembrarLoPrevio()

        traer()

        assertEquals(listOf("70.5|170|120/80|24.4|Prueba Salud360 examen físico (a)|72|95|0"), examen())
    }

    @Test
    fun una_consulta_que_dejo_de_tener_un_dato_en_la_web_lo_conserva_y_no_pone_marcas() = conBackend(apiCon(completa)) {
        sembrarLoPrevio()
        traer()
        val conTodo = volcarTodo(driver)

        // La web vació todo: lo ya traído queda (la marca «Ya no figura en la web» es de E6.4b).
        api.consultas[7003] = RespuestasClinicaDeEjemplo.consultaConSecciones("{}")
        traer()

        assertEquals(conTodo, volcarTodo(driver))
        assertEquals(emptyList(), textos(driver, "SELECT clave FROM paciente_extra WHERE clave LIKE 'hcc_ausente_%'"))
    }

    // ---- Lo del dispositivo manda (RF-48, RF-54) ----

    @Test
    fun una_clave_sucia_no_se_toca_y_las_demas_entran() = conBackend(apiCon(completa)) {
        db.sembrar("hcc-c7003")
        val q = db.historiaClinicaQueries
        q.upsertSeccionValor(SeccionValor("hcc-c7003", "motivo_consulta", "texto", "lo que escribió el médico").toRow(999, dirty = true))
        val antes = volcar(driver, "seccion_valor").filter { "'motivo_consulta'" in it }

        traer()

        assertEquals(antes, volcar(driver, "seccion_valor").filter { "'motivo_consulta'" in it }, "ni valor, ni updated_at, y sigue sucia")
        assertTrue("habitos/texto=Prueba Salud360 hábitos (a): tabaco, alcohol, ejercicio" in valores(), "las otras claves entran")
        assertEquals(listOf("70.5|170|120/80|24.4|Prueba Salud360 examen físico (a)|||0"), examen())
    }

    @Test
    fun un_examen_fisico_sucio_no_se_toca() = conBackend(apiCon(completa)) {
        db.sembrar("hcc-c7003")
        db.historiaClinicaQueries.upsertExamenFisico(ExamenFisico("hcc-c7003", peso = "99", frecuenciaCardiaca = "60").toRow(999, dirty = true))
        val antes = volcar(driver, "examen_fisico")

        traer()

        assertEquals(antes, volcar(driver, "examen_fisico"))
        assertTrue(valores().isNotEmpty(), "las secciones, que no están sucias, entran")
    }

    @Test
    fun una_consulta_con_cambios_sin_enviar_no_se_pide_ni_se_toca() = conBackend(apiCon(completa)) {
        db.sembrar("hcc-c7003", estado = EstadoConsulta.ABIERTA, dirty = true, actualizada = 999)
        val antes = volcarTodo(driver)

        traer()

        assertEquals(0, api.totalDePedidos, "es del dispositivo: no se gasta un pedido en lo que no se va a aplicar")
        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun una_consulta_creada_en_el_dispositivo_no_se_pide_ni_se_toca() = conBackend(apiCon(completa)) {
        db.sembrar("0b1c6e56-aaaa-4bbb-8ccc-000000000001", remotoId = "", estado = EstadoConsulta.ABIERTA, dirty = true)
        val antes = volcarTodo(driver)

        backend.traerConsulta("0b1c6e56-aaaa-4bbb-8ccc-000000000001", "7003")

        assertEquals(0, api.totalDePedidos)
        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun una_del_dispositivo_con_el_mismo_remoto_id_no_recibe_lo_de_la_web() = conBackend(apiCon(completa)) {
        db.sembrar("u-7003", remotoId = "7003")
        val antes = volcarTodo(driver)

        backend.traerConsulta("u-7003", "7003")

        assertEquals(0, api.totalDePedidos)
        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun el_id_local_que_no_es_el_de_la_web_no_se_aplica() = conBackend(apiCon(completa)) {
        db.sembrar("hcc-c7003")
        val antes = volcarTodo(driver)

        backend.traerConsulta("hcc-c7003", "7004")
        backend.traerConsulta("hcc-c7003", "")
        backend.traerConsulta("hcc-c7003", "siete mil")
        backend.traerConsulta("hcc-c7003", "0")

        assertEquals(0, api.totalDePedidos)
        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun una_consulta_que_no_esta_en_el_dispositivo_no_se_pide() = conBackend(apiCon(completa)) {
        traer()

        assertEquals(0, api.totalDePedidos, "los valores cuelgan de una consulta que tiene que estar: no se crea una")
        assertEquals(emptyList(), volcar(driver, "consulta"))
        assertEquals(emptyList(), volcar(driver, "seccion_valor"))
    }

    @Test
    fun sin_ficha_de_la_web_no_se_pide_nada() = conBackend(apiCon(completa), vinculado = false) {
        db.sembrar("hcc-c7003")
        val antes = volcarTodo(driver)

        traer() // `HcClinicaPruebas.PacientesProhibida` hace fallar el test si algo llama a `pacientes/resolver`.

        assertEquals(0, api.totalDePedidos, "RF-49")
        assertEquals(antes, volcarTodo(driver))
    }

    // ---- Una respuesta que no es de lo que se pidió no se aplica (RF-55) ----

    @Test
    fun la_respuesta_de_otra_consulta_o_de_otro_paciente_no_se_aplica() {
        val deOtraConsulta = completa.copy(consulta = completa.consulta.copy(id = 7004))
        val deOtroPaciente = completa.copy(consulta = completa.consulta.copy(pacienteId = 9999))
        for (respuesta in listOf(deOtraConsulta, deOtroPaciente)) {
            conBackend(ApiFalsa().apply { consultas[7003] = respuesta }) {
                sembrarLoPrevio()
                val antes = volcarTodo(driver)

                val e = assertFailsWith<TobbException> { traer() }

                assertEquals("respuesta", e.codigo)
                assertEquals(1, api.pedidosConsulta.size)
                assertEquals(antes, volcarTodo(driver))
            }
        }
    }

    @Test
    fun la_consulta_de_un_paciente_con_otra_ficha_no_se_aplica() = conBackend(apiCon(completa)) {
        db.pacientesQueries.upsertPacienteExtra(
            PacienteExtra("p-otro", "clinica", PacientesHcRepository.EXTRA_HC_PACIENTE_ID, "5002").toRow(0, dirty = false),
        )
        db.sembrar("hcc-c7003", paciente = "p-otro")
        val antes = volcarTodo(driver)

        assertFailsWith<TobbException> { traer() }

        assertEquals(antes, volcarTodo(driver), "la ficha 5001 de la respuesta no es la 5002 del paciente que tiene esa consulta")
    }

    // ---- Errores (RF-56): nada nuevo, nada roto, sin reintento ----

    @Test
    fun cada_error_deja_todo_como_estaba_con_un_solo_pedido_y_lo_propaga_tal_cual() {
        for (error in ERRORES) {
            conBackend(apiCon(completa)) {
                sembrarLoPrevio()
                db.pacientesQueries.upsertPacienteExtra(PacienteExtra(PACIENTE, "clinica", claveNoLeidas(7003), "documentos").toRow(10, dirty = false))
                val antes = volcarTodo(driver)
                api.fallasConsulta[7003] = error

                val e = assertFailsWith<TobbException>("${error.status} ${error.codigo}") { traer() }

                assertSame(error, e)
                assertEquals(1, api.totalDePedidos, "sin reintento (RF-58): ${error.status} ${error.codigo}")
                assertEquals(antes, volcarTodo(driver), "lo ya traído se queda y no entra nada: ${error.status} ${error.codigo}")
            }
        }
    }

    @Test
    fun la_consulta_que_la_web_ya_no_tiene_se_queda_entera_sin_marcas_hasta_la_e6_4b() = conBackend(apiCon(completa)) {
        sembrarLoPrevio()
        val antes = volcarTodo(driver)
        api.fallasConsulta[7003] = TobbException(404, "consulta_no_encontrada", "Consulta no encontrada.")

        val e = assertFailsWith<TobbException> { traer() }

        assertEquals("consulta_no_encontrada", e.codigo, "quien llama puede distinguirla (E6.4b la marca y E7.3 la muestra)")
        assertEquals(antes, volcarTodo(driver))
        assertEquals(emptyList(), textos(driver, "SELECT clave FROM paciente_extra WHERE clave LIKE 'hcc_ausente_%'"))
    }

    @Test
    fun otro_error_que_no_es_de_la_api_tambien_sale_sin_escribir() = conBackend(apiCon(completa)) {
        sembrarLoPrevio()
        val antes = volcarTodo(driver)
        val roto = IllegalStateException("json roto")
        api.fallasConsulta[7003] = roto

        assertSame(roto, assertFailsWith<IllegalStateException> { traer() })

        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun si_falla_al_escribir_no_queda_media_consulta() = conBackend(apiCon(conPdf.copy(examenFisico = completa.examenFisico))) {
        sembrarLoPrevio()
        val antes = volcarTodo(driver)
        // Las secciones se escriben antes que el examen físico: la base rechaza el examen y las secciones tienen que deshacerse.
        driver.execute(null, "CREATE TRIGGER prueba_falla BEFORE INSERT ON examen_fisico BEGIN SELECT RAISE(ABORT, 'prueba'); END", 0).await()

        assertFailsWith<Exception> { traer() }

        driver.execute(null, "DROP TRIGGER prueba_falla", 0).await()
        assertEquals(antes, volcarTodo(driver), "ni secciones nuevas ni el aviso de no_leidas")
    }

    // ---- Lo que la consulta tiene y la L no muestra (RF-43) ----

    @Test
    fun no_leidas_se_guarda_en_paciente_extra_sin_dirty() = conBackend(apiCon(conPdf)) {
        db.sembrar("hcc-c7003")

        traer()

        assertEquals(listOf("documentos|0"), noLeidas())
        assertEquals(
            listOf("clinica|hcc_no_leidas_7003|documentos"),
            textos(driver, "SELECT especialidad||'|'||clave||'|'||valor FROM paciente_extra WHERE clave LIKE 'hcc_no_leidas_%'"),
        )
    }

    @Test
    fun varias_no_leidas_van_en_el_orden_del_servidor_y_las_que_la_app_no_conoce_tambien() = conBackend(
        apiCon(completa.copy(noLeidas = listOf("documentos", "audio", "fotos", "antecedentes_personales", "algo_nuevo"))),
    ) {
        db.sembrar("hcc-c7003")

        traer()

        assertEquals(listOf("documentos,audio,fotos,antecedentes_personales,algo_nuevo|0"), noLeidas())
    }

    @Test
    fun sin_no_leidas_y_sin_fila_previa_no_escribe_nada() = conBackend(apiCon(completa)) {
        db.sembrar("hcc-c7003")

        traer()

        assertEquals(emptyList(), noLeidas())
    }

    @Test
    fun el_aviso_sigue_a_la_web_porque_es_una_cuenta_y_no_un_dato_clinico() = conBackend(apiCon(conPdf)) {
        db.sembrar("hcc-c7003")
        traer()

        api.consultas[7003] = completa.copy(noLeidas = listOf("documentos", "audio"))
        traer()
        assertEquals(listOf("documentos,audio|0"), noLeidas())

        api.consultas[7003] = completa // la web ya no tiene nada de eso
        traer()
        assertEquals(listOf("|0"), noLeidas(), "queda la fila, sin ids")
    }

    @Test
    fun el_aviso_de_cada_consulta_es_aparte() = conBackend(apiCon(conPdf, abierta.copy(noLeidas = listOf("audio")))) {
        db.sembrar("hcc-c7003")
        db.sembrar("hcc-c7005", estado = EstadoConsulta.ABIERTA)

        traer(7003)
        traer(7005)

        assertEquals(listOf("documentos|0"), noLeidas(7003))
        assertEquals(listOf("audio|0"), noLeidas(7005))
    }

    @Test
    fun un_aviso_con_cambios_sin_enviar_no_se_toca() = conBackend(apiCon(conPdf)) {
        db.sembrar("hcc-c7003")
        db.pacientesQueries.upsertPacienteExtra(PacienteExtra(PACIENTE, "clinica", claveNoLeidas(7003), "lo del dispositivo").toRow(7, dirty = true))
        val antes = volcar(driver, "paciente_extra").filter { "hcc_no_leidas_7003" in it }

        traer()

        assertEquals(antes, volcar(driver, "paciente_extra").filter { "hcc_no_leidas_7003" in it })
    }

    @Test
    fun el_vinculo_de_la_ficha_queda_como_estaba() = conBackend(apiCon(conPdf)) {
        db.sembrar("hcc-c7003")
        val antes = volcar(driver, "paciente_extra").filter { "hc_paciente_id" in it }
        assertEquals(1, antes.size)

        traer()

        assertEquals(antes, volcar(driver, "paciente_extra").filter { "hc_paciente_id" in it })
    }
}
