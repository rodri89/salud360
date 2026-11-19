package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.RespuestasClinicaDeEjemplo
import com.salud360.core.data.hcgeneral.clavesDeLaConsulta
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.CargaLaboratorioDto
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.EjemplosConsultasClinica
import com.salud360.core.data.network.hc.EstudiosDto
import com.salud360.core.data.network.hc.LaboratoriosDto
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.data.network.hc.RespuestaListaConsultasDto
import com.salud360.core.data.repos.HcClinicaPruebas.ApiFalsa
import com.salud360.core.data.repos.HcClinicaPruebas.Contexto
import com.salud360.core.data.repos.HcClinicaPruebas.ERRORES
import com.salud360.core.data.repos.HcClinicaPruebas.FICHA
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.TABLAS
import com.salud360.core.data.repos.HcClinicaPruebas.conBackend
import com.salud360.core.data.repos.HcClinicaPruebas.marcasVigentes
import com.salud360.core.data.repos.HcClinicaPruebas.sembrar
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.repos.HcClinicaPruebas.volcar
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodoSinMarcas
import com.salud360.core.data.uno
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val DIA = 86_400_000L
private const val OTRO = "p-99000101"
private const val OTRA_FICHA = 5002L

/** Lo que el historial completo de los ejemplos trae por paciente y fecha: todo esto se marca si la web deja de mandarlo. */
private val DEL_HISTORIAL = listOf(
    "hcc-a5001-dbt_ir", "hcc-a5001-hta", "hcc-a5001-nota", "hcc-a5001-otro",
    "hcc-llaboratorio_general-703", "hcc-llaboratorio_reducido-19", "hcc-lpeso-25",
    "hcc-rbiopsia_hepatica-1797", "hcc-reco_abdominal-1801", "hcc-reco_abdominal-1802", "hcc-rexamen_complementario-22",
    "hcc-rexamen_complementario-23", "hcc-rinterconsulta-14", "hcc-rtc_rmn-1797", "hcc-rveda-1797",
    "hcc-p1744",
    "hcc-c7003:motivo_consulta:texto", "hcc-c7005:motivo_consulta:texto",
)

/**
 * Las marcas «Ya no figura en la web» (spec 002, E6.4b, Q15 = nunca borrar, RF-63) con una API falsa y una base SQLite de
 * verdad. Lo que la web deja de mandar queda en el dispositivo tal cual y solo recibe una marca `hcc_ausente_<clave>` en
 * `paciente_extra` (con la fecha, `dirty = 0`), escrita en la misma transacción que el resto de esa respuesta; lo que
 * vuelve a venir pierde la marca; una respuesta con error, incompleta o de otra ficha no marca nada; lo del dispositivo
 * (sucio o creado acá) y lo de otro paciente no se marca nunca. Datos inventados (documentos 99000100 y 99000101).
 */
class HcClinicaBackendAusentesTest {

    private val cabeceras: List<ConsultaCabeceraDto> get() = RespuestasClinicaDeEjemplo.lista().consultas // 7005, 7004, 7003
    private val historiaCompleta: RespuestaHistoriaDto get() = RespuestasClinicaDeEjemplo.historia()
    private val consultaA: RespuestaConsultaDto get() = RespuestasClinicaDeEjemplo.consulta()
    private val consultaB: RespuestaConsultaDto
        get() = RespuestasClinicaDeEjemplo.consulta(
            """{"ok": true, "consulta": ${EjemplosConsultasClinica.CABECERA_B},
                "secciones": {"nota": {"texto": "Prueba Salud360 nota (b)"}}, "examen_fisico": {"peso": "80"}, "no_leidas": []}""",
        )
    private val consultaC: RespuestaConsultaDto get() = RespuestasClinicaDeEjemplo.consulta(EjemplosConsultasClinica.CONSULTA_ABIERTA)

    private val noEncontrada = TobbException(404, "consulta_no_encontrada", "Consulta no encontrada.")

    private fun laWeb() = ApiFalsa(cabeceras).apply {
        historial = historiaCompleta
        consultas[7003] = consultaA
        consultas[7004] = consultaB
        consultas[7005] = consultaC
    }

    private fun conLaWeb(bloque: suspend Contexto.() -> Unit) = conBackend(laWeb(), bloque = bloque)

    private fun muchas(n: Int, desdeId: Long = 9001): List<ConsultaCabeceraDto> = (0 until n).map { i ->
        val dia = java.time.LocalDate.parse("2026-02-28").minusDays(i.toLong()).toString()
        ConsultaCabeceraDto(
            id = desdeId + i, pacienteId = FICHA, medicoId = 9, medicoNombre = "Médico Prueba Salud360", tipo = "control", tipoNumero = 1,
            fecha = dia, createdAt = "$dia 10:00:00", estado = "CERRADA", edadMostrar = "", propia = true, updatedAt = null,
        )
    }

    /** La historia sin ninguna de las cosas que trae el ejemplo (con las mismas claves: la web las manda siempre). La cuenta de omitidas queda. */
    private fun RespuestaHistoriaDto.sinNada() = copy(
        antecedentesFamiliares = null, antecedentesFamiliaresPerfil4 = null,
        laboratorios = LaboratoriosDto(emptyList(), emptyList(), emptyList()),
        estudios = EstudiosDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        motivos = emptyList(), examenesComplementarios = emptyList(), interconsultas = emptyList(), pendientes = null,
    )

    private fun consultaVacia(cabecera: String) = RespuestasClinicaDeEjemplo.consulta(
        """{"ok": true, "consulta": $cabecera, "secciones": {}, "examen_fisico": {}, "no_leidas": []}""",
    )

    private suspend fun Contexto.traer() = backend.traerConsultas(PACIENTE, MEDICO, true)

    private suspend fun Contexto.abrir(id: Long) = backend.traerConsulta("hcc-c$id", "$id")

    /** Lo que hace la app al abrir la historia y cada consulta: la lista, el historial y el contenido de las tres. */
    private suspend fun Contexto.traerTodo() {
        traer()
        abrir(7003)
        abrir(7004)
        abrir(7005)
    }

    private fun Contexto.marcas() = marcasVigentes(driver)

    private fun Contexto.claves() = marcas().map { it.substringBefore("=") }

    private fun Contexto.hoy(): String =
        kotlin.time.Instant.fromEpochMilliseconds(reloj.millis).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

    /** Las marcas esperadas, ordenadas como las lee [marcas], con la fecha de hoy (la del reloj falso). */
    private fun Contexto.conFecha(claves: Collection<String>) = claves.sorted().map { "$it=${hoy()}" }

    private fun Contexto.conFecha(vararg claves: String) = conFecha(claves.toList())

    private fun Contexto.ids(sql: String) = textos(driver, sql)

    // ------------------------------------------------------------------
    // La lista: una consulta que desaparece
    // ------------------------------------------------------------------

    @Test
    fun una_consulta_que_desaparece_de_la_lista_queda_en_el_dispositivo_y_se_marca() = conLaWeb {
        traerTodo()
        assertEquals(emptyList(), marcas())
        val antes = volcarTodoSinMarcas(driver)
        api.cabeceras = api.cabeceras.filter { it.id != 7004L } // la dieron de baja en la web: la lista ya no la trae
        reloj.avanzar(DIA)

        traer()

        assertEquals(conFecha("hcc-c7004"), marcas())
        assertEquals(antes, volcarTodoSinMarcas(driver), "la fila de consulta y todas sus hijas iguales antes y después")
        assertEquals("1", ids("SELECT COUNT(*) FROM consulta WHERE id = 'hcc-c7004'").single())
        assertEquals(listOf("nota/texto=Prueba Salud360 nota (b)"), ids("SELECT seccion||'/'||campo||'='||valor FROM seccion_valor WHERE consulta_id = 'hcc-c7004'"))
        assertEquals(listOf("80"), ids("SELECT peso FROM examen_fisico WHERE consulta_id = 'hcc-c7004'"))
        assertEquals(0, HcClinicaPruebas.filasSucias(driver), "la marca no es algo para enviar")
        assertEquals("0", ids("SELECT dirty FROM paciente_extra WHERE clave = 'hcc_ausente_hcc-c7004'").single())
        assertEquals("clinica", ids("SELECT especialidad FROM paciente_extra WHERE clave = 'hcc_ausente_hcc-c7004'").single())
    }

    @Test
    fun la_marca_lleva_la_fecha_en_que_se_noto_y_no_la_cambia_si_sigue_ausente() = conLaWeb {
        traerTodo()
        api.cabeceras = api.cabeceras.filter { it.id != 7004L }
        reloj.avanzar(DIA)
        val elPrimerDia = hoy()
        traer()
        assertEquals(listOf("hcc-c7004=$elPrimerDia"), marcas())

        reloj.avanzar(3 * DIA)
        traer()

        assertNotEquals(elPrimerDia, hoy())
        assertEquals(listOf("hcc-c7004=$elPrimerDia"), marcas(), "una marca ya puesta no se repite ni se renueva: la fecha es la del primer día")
    }

    @Test
    fun si_la_consulta_vuelve_a_venir_la_marca_se_retira_y_se_aplica_lo_de_la_web() = conLaWeb {
        traerTodo()
        val sinLaB = api.cabeceras.filter { it.id != 7004L }
        val laB = api.cabeceras.first { it.id == 7004L }
        api.cabeceras = sinLaB
        traer()
        assertEquals(listOf("hcc-c7004"), claves())

        api.cabeceras = sinLaB + laB.copy(edadMostrar = "50 años")
        reloj.avanzar(DIA)
        traer()

        assertEquals(emptyList(), marcas(), "vuelve a figurar en la web")
        assertEquals(listOf("50 años"), ids("SELECT edad_mostrar FROM consulta WHERE id = 'hcc-c7004'"), "y se aplica lo que dice la web")
        assertEquals(0, HcClinicaPruebas.filasSucias(driver))
    }

    @Test
    fun una_consulta_ya_marcada_que_se_vuelve_a_abrir_con_exito_pierde_la_marca() = conLaWeb {
        traerTodo()
        api.fallasConsulta[7004] = noEncontrada
        assertFailsWith<TobbException> { abrir(7004) }
        assertEquals(listOf("hcc-c7004"), claves())

        api.fallasConsulta.clear()
        abrir(7004)

        assertEquals(emptyList(), marcas())
    }

    // ------------------------------------------------------------------
    // La lista: cuándo NO marca
    // ------------------------------------------------------------------

    @Test
    fun una_lista_que_viene_a_mitad_no_marca_nada() = conLaWeb {
        val web = cabeceras.filter { it.id != 7004L } + muchas(57)
        api.cabeceras = cabeceras + muchas(57) // 60 consultas: dos páginas
        traerTodo()
        val antes = volcarTodo(driver)
        api.cabeceras = web
        api.fallas[api.pedidos.size + 2] = ERRORES.first { it.status == 500 } // la primera página sale bien, la segunda falla

        val e = assertFailsWith<TobbException> { traer() }

        assertEquals(500, e.status)
        assertEquals(emptyList(), marcas(), "una lista a medias no dice que la 7004 ya no está")
        assertEquals(antes, volcarTodo(driver), "y tampoco se escribe a medias")
    }

    @Test
    fun un_total_que_cambia_entre_paginas_no_marca_nada() = conLaWeb {
        traerTodo()
        val todas = cabeceras.filter { it.id != 7004L } + muchas(57)
        api.respuesta = { p ->
            val pagina = todas.drop(p.desde).take(p.limite)
            RespuestaListaConsultasDto(pagina, if (p.desde == 0) 59 else 58, p.desde, if (p.desde + pagina.size < 59) 1 else 0)
        }

        traer()

        assertEquals(emptyList(), marcas(), "la lista cambió mientras se leía: no se sabe qué falta")
        assertTrue(ids("SELECT COUNT(*) FROM consulta").single().toInt() > 3, "lo que vino sí se aplicó")
        assertEquals("1", ids("SELECT COUNT(*) FROM consulta WHERE id = 'hcc-c7004'").single())
    }

    @Test
    fun la_pagina_dos_sola_no_marca_nada() = conLaWeb {
        traerTodo()
        api.respuesta = { RespuestaListaConsultasDto(cabeceras.filter { it.id == 7005L }, 102, 50, 0) } // una página que no se pidió

        traer()

        assertEquals(emptyList(), marcas(), "la 7003 y la 7004 no vinieron en esta página, y eso no quiere decir que ya no estén")
    }

    @Test
    fun una_lista_que_pasa_del_tope_de_paginas_no_marca_nada() = conLaWeb {
        traerTodo()
        val antes = volcar(driver, "consulta")
        val pedidos = api.pedidos.size
        api.cabeceras = muchas(160) + cabeceras.filter { it.id != 7004L }
        val corto = HcClinicaBackend(db, api, HcClinicaPruebas.pacientes(db), { reloj.millis }, 2) // tope de 2 páginas = 100 consultas

        corto.traerConsultas(PACIENTE, MEDICO, true)

        assertEquals(2, api.pedidos.size - pedidos, "se pidieron solo las dos páginas del tope")
        assertEquals(emptyList(), marcas(), "ni la 7004 (que ya no está) ni la 7003 (más allá del tope) se marcan: no se sabe")
        assertTrue(antes.all { it in volcar(driver, "consulta") }, "lo que había sigue igual")
    }

    @Test
    fun una_lista_de_otra_ficha_no_marca_nada() = conLaWeb {
        traerTodo()
        val antes = volcarTodo(driver)
        api.cabeceras = cabeceras.map { it.copy(pacienteId = 9999) }.filter { it.id != 7004L }

        traer()

        assertEquals(emptyList(), marcas())
        assertEquals(antes, volcarTodo(driver), "ni se marca ni se escribe nada de lo que es de otra ficha")
    }

    @Test
    fun una_lista_con_error_no_marca_nada_y_no_pide_el_historial() = conLaWeb {
        traerTodo()
        api.cabeceras = emptyList()
        for (error in ERRORES) {
            val antes = volcarTodo(driver)
            val historiales = api.pedidosHistoria.size
            api.fallas[api.pedidos.size + 1] = error

            val e = assertFailsWith<TobbException> { traer() }

            assertEquals(error.status, e.status)
            assertEquals(antes, volcarTodo(driver), "${error.status} ${error.codigo}")
            assertEquals(emptyList(), marcas(), "${error.status} ${error.codigo}")
            assertEquals(historiales, api.pedidosHistoria.size)
        }
    }

    @Test
    fun una_lista_vacia_pero_completa_si_marca_todas_las_consultas_y_no_borra_ninguna() = conLaWeb {
        traerTodo()
        val antes = volcarTodoSinMarcas(driver)
        protegerTodo()
        api.cabeceras = emptyList()

        traer()

        assertEquals(conFecha("hcc-c7003", "hcc-c7004", "hcc-c7005"), marcas(), "total 0, hay_mas 0: la lista está completa")
        assertEquals(antes, volcarTodoSinMarcas(driver), "las tres siguen en el dispositivo con todas sus filas")
    }

    // ------------------------------------------------------------------
    // El contenido de una consulta (GET consultas/{id})
    // ------------------------------------------------------------------

    private val parcial: RespuestaConsultaDto
        get() = RespuestasClinicaDeEjemplo.consultaConSecciones(
            """{"motivo_consulta": {"texto": "Prueba Salud360 motivo (a)"}, "alimentacion": {"hiposodica": "1|Prueba Salud360 hiposódica (a)"},
                "serologias": {"igm_hav": "SI"}, "child_pugh": {"ascitis": "2"}}""",
            """{"talla": "170"}""",
        )

    @Test
    fun el_contenido_de_una_consulta_que_la_web_vacio_se_marca_campo_por_campo_y_queda_en_el_dispositivo() = conLaWeb {
        traerTodo()
        // Lo que la app tiene de ese examen y la web no conoce (frecuencia cardíaca): nunca se marca.
        val ef = db.historiaClinicaQueries.examenFisicoDeConsulta("hcc-c7003").uno { it.toModel() }!!
        db.historiaClinicaQueries.upsertExamenFisico(ef.copy(frecuenciaCardiaca = "72").toRow(50, dirty = false))
        val antes = volcarTodoSinMarcas(driver)
        api.consultas[7003] = parcial
        val esperadas = (clavesDeLaConsulta(consultaA) - clavesDeLaConsulta(parcial)).toList()

        abrir(7003)

        assertEquals(conFecha(esperadas), marcas())
        listOf(
            "hcc-c7003:habitos:texto", "hcc-c7003:nota:texto", "hcc-c7003:conductas:texto", "hcc-c7003:alimentacion:celiaco",
            "hcc-c7003:alimentacion:diabetes", "hcc-c7003:serologias:ags_hbv", "hcc-c7003:serologias:hiv", "hcc-c7003:child_pugh:bilirrubina",
            "hcc-c7003:examen_fisico:peso", "hcc-c7003:examen_fisico:tension_arterial", "hcc-c7003:examen_fisico:imc", "hcc-c7003:examen_fisico:nota",
        ).forEach { assertTrue(it in claves(), it) }
        listOf("hcc-c7003", "hcc-c7003:motivo_consulta:texto", "hcc-c7003:serologias:igm_hav", "hcc-c7003:child_pugh:ascitis", "hcc-c7003:examen_fisico:talla")
            .forEach { assertTrue(it !in claves(), "$it sigue viniendo") }
        assertTrue(claves().none { "frecuencia" in it || "abdominal" in it }, "lo que la web no conoce no se marca")
        assertTrue(claves().all { it.startsWith("hcc-c7003:") }, "solo el contenido de esa consulta")
        assertEquals(antes, volcarTodoSinMarcas(driver), "ningún valor se vació ni se borró")
        assertEquals(0, HcClinicaPruebas.filasSucias(driver))
    }

    @Test
    fun un_texto_en_blanco_de_la_web_cuenta_como_que_ya_no_hay_texto() = conLaWeb {
        traerTodo()
        api.consultas[7003] = RespuestasClinicaDeEjemplo.consultaConSecciones(
            """{"motivo_consulta": {"texto": "Prueba Salud360 motivo (a)"}, "nota": {"texto": "   "}}""",
        )
        val nota = ids("SELECT valor FROM seccion_valor WHERE consulta_id = 'hcc-c7003' AND seccion = 'nota'").single()

        abrir(7003)

        assertTrue("hcc-c7003:nota:texto" in claves())
        assertEquals(nota, ids("SELECT valor FROM seccion_valor WHERE consulta_id = 'hcc-c7003' AND seccion = 'nota'").single(), "el texto de antes sigue ahí")
    }

    @Test
    fun si_el_contenido_vuelve_a_venir_la_marca_se_retira_y_se_aplica_el_valor_de_la_web() = conLaWeb {
        traerTodo()
        api.consultas[7003] = parcial
        abrir(7003)
        assertTrue("hcc-c7003:examen_fisico:peso" in claves())

        api.consultas[7003] = RespuestasClinicaDeEjemplo.consulta(EjemplosConsultasClinica.CONSULTA_COMPLETA.replace("\"peso\": \"70.5\"", "\"peso\": \"71.0\""))
        reloj.avanzar(DIA)
        abrir(7003)

        assertEquals(emptyList(), marcas())
        assertEquals(listOf("71.0"), ids("SELECT peso FROM examen_fisico WHERE consulta_id = 'hcc-c7003'"))
    }

    @Test
    fun abrir_una_consulta_no_marca_ni_desmarca_lo_de_otras_consultas_ni_lo_del_historial() = conLaWeb {
        traerTodo()
        api.cabeceras = api.cabeceras.filter { it.id != 7004L }
        api.historial = historiaCompleta.copy(pendientes = null, interconsultas = emptyList())
        traer()
        val antes = marcas()
        assertEquals(listOf("hcc-c7004", "hcc-p1744", "hcc-rinterconsulta-14"), claves())

        api.consultas[7003] = parcial
        abrir(7003)

        val nuevas = marcas().filterNot { it in antes }
        assertTrue(nuevas.isNotEmpty() && nuevas.all { it.startsWith("hcc-c7003:") })
        assertTrue(antes.all { it in marcas() }, "las de la lista y del historial siguen")
    }

    // ------------------------------------------------------------------
    // 404 consulta_no_encontrada
    // ------------------------------------------------------------------

    @Test
    fun un_404_consulta_no_encontrada_al_abrirla_marca_la_consulta_y_el_error_sale_igual() = conLaWeb {
        traerTodo()
        val antes = volcarTodoSinMarcas(driver)
        api.fallasConsulta[7003] = noEncontrada
        reloj.avanzar(DIA)

        val e = assertFailsWith<TobbException> { abrir(7003) }

        assertEquals(404, e.status)
        assertEquals("consulta_no_encontrada", e.codigo)
        assertEquals(conFecha("hcc-c7003"), marcas(), "la consulta, no su contenido")
        assertEquals(antes, volcarTodoSinMarcas(driver), "la consulta y todo su contenido siguen en el dispositivo")
        assertEquals(listOf(7003L, 7004L, 7005L, 7003L), api.pedidosConsulta, "un solo pedido para la que dio 404, sin reintento")
    }

    @Test
    fun cualquier_otro_error_al_abrir_una_consulta_no_marca_nada() = conLaWeb {
        traerTodo()
        for (error in ERRORES.filterNot { it.status == 404 && it.codigo == "consulta_no_encontrada" }) {
            val antes = volcarTodo(driver)
            api.fallasConsulta[7003] = error

            val e = assertFailsWith<TobbException> { abrir(7003) }

            assertEquals(error.status, e.status)
            assertEquals(antes, volcarTodo(driver), "${error.status} ${error.codigo}")
        }
        assertEquals(emptyList(), marcas())
    }

    @Test
    fun una_respuesta_de_otra_consulta_o_de_otra_ficha_no_marca_nada() = conLaWeb {
        traerTodo()
        val antes = volcarTodo(driver)
        api.consultas[7003] = consultaB // llega la 7004 cuando se pidió la 7003
        assertEquals("respuesta", assertFailsWith<TobbException> { abrir(7003) }.codigo)
        api.consultas[7003] = RespuestasClinicaDeEjemplo.consulta(EjemplosConsultasClinica.CONSULTA_COMPLETA.replace("\"paciente_id\": 5001", "\"paciente_id\": 9999"))
        assertEquals("respuesta", assertFailsWith<TobbException> { abrir(7003) }.codigo)

        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun una_consulta_que_no_se_pide_no_se_marca() = conLaWeb {
        traerTodo()
        db.sembrar("hcc-c7010", dirty = true)
        db.sembrar("u-1", remotoId = "7011")
        db.sembrar("hcc-c7020", paciente = OTRO_SIN_FICHA)
        val pedidos = api.totalDePedidos
        api.fallasConsulta[7010] = noEncontrada
        api.fallasConsulta[7011] = noEncontrada
        api.fallasConsulta[7020] = noEncontrada

        backend.traerConsulta("hcc-c7010", "7010") // sucia
        backend.traerConsulta("u-1", "7011") // creada en el dispositivo
        backend.traerConsulta("hcc-c7003", "7004") // el id remoto no es el de la consulta
        backend.traerConsulta("hcc-c9999", "9999") // no está en el dispositivo
        backend.traerConsulta("hcc-c7020", "7020") // de un paciente sin ficha en la web

        assertEquals(pedidos, api.totalDePedidos, "ni un pedido")
        assertEquals(emptyList(), marcas())
    }

    // ------------------------------------------------------------------
    // El historial del paciente
    // ------------------------------------------------------------------

    @Test
    fun todo_lo_que_el_historial_dejo_de_traer_se_marca_y_nada_se_borra() = conLaWeb {
        traerTodo()
        val antes = volcarTodoSinMarcas(driver)
        api.historial = historiaCompleta.sinNada()
        reloj.avanzar(DIA)

        traer()

        assertEquals(conFecha(DEL_HISTORIAL), marcas(), "la nota del perfil 4 no está: nunca se marca")
        assertEquals(antes, volcarTodoSinMarcas(driver), "cargas, estudios, exámenes, interconsulta, pendiente, antecedentes y motivos: iguales")
        assertEquals(0, HcClinicaPruebas.filasSucias(driver))
    }

    private fun RespuestaHistoriaDto.sinLaCasilla(clave: String): RespuestaHistoriaDto {
        val familiares = antecedentesFamiliares!!
        return copy(antecedentesFamiliares = familiares.copy(casillas = familiares.casillas - clave))
    }

    @Test
    fun cada_cosa_que_deja_de_venir_del_historial_se_marca_sola() {
        val cambios: List<Triple<String, (RespuestaHistoriaDto) -> RespuestaHistoriaDto, List<String>>> = listOf(
            Triple("carga de laboratorio general", { h -> h.copy(laboratorios = h.laboratorios.copy(laboratorioGeneral = emptyList())) }, listOf("hcc-llaboratorio_general-703")),
            Triple("carga de laboratorio reducido", { h -> h.copy(laboratorios = h.laboratorios.copy(laboratorioReducido = emptyList())) }, listOf("hcc-llaboratorio_reducido-19")),
            Triple("carga de peso", { h -> h.copy(laboratorios = h.laboratorios.copy(peso = emptyList())) }, listOf("hcc-lpeso-25")),
            Triple("estudio (el 1797 de la VEDA, no el de la biopsia ni el de la TC)", { h -> h.copy(estudios = h.estudios.copy(veda = emptyList())) }, listOf("hcc-rveda-1797")),
            Triple("una de dos ecografías", { h -> h.copy(estudios = h.estudios.copy(ecoAbdominal = h.estudios.ecoAbdominal.filter { it.id != 1801L })) }, listOf("hcc-reco_abdominal-1801")),
            Triple("examen complementario", { h -> h.copy(examenesComplementarios = h.examenesComplementarios.filter { it.id != 22L }) }, listOf("hcc-rexamen_complementario-22")),
            Triple("interconsulta", { h -> h.copy(interconsultas = emptyList()) }, listOf("hcc-rinterconsulta-14")),
            Triple("pendiente", { h -> h.copy(pendientes = null) }, listOf("hcc-p1744")),
            Triple("casilla de antecedente", { h -> h.sinLaCasilla("hta") }, listOf("hcc-a5001-hta")),
            Triple("nota de antecedentes", { h -> h.copy(antecedentesFamiliares = h.antecedentesFamiliares!!.copy(nota = null)) }, listOf("hcc-a5001-nota")),
            Triple("motivo de una consulta", { h -> h.copy(motivos = h.motivos.filter { it.consultaId != 7003L }) }, listOf("hcc-c7003:motivo_consulta:texto")),
            Triple("nota del perfil 4 (nunca se marca)", { h -> h.copy(antecedentesFamiliaresPerfil4 = null) }, emptyList()),
            Triple("nada (la misma historia)", { h -> h }, emptyList()),
        )
        for ((nombre, cambio, esperadas) in cambios) {
            conLaWeb {
                traerTodo()
                val antes = volcarTodoSinMarcas(driver)
                api.historial = cambio(historiaCompleta)
                reloj.avanzar(DIA)

                traer()

                assertEquals(conFecha(esperadas), marcas(), nombre)
                assertEquals(antes, volcarTodoSinMarcas(driver), "$nombre: las filas siguen")
            }
        }
    }

    @Test
    fun una_carga_que_perdio_la_fecha_en_la_web_se_marca_y_queda() = conLaWeb {
        traerTodo()
        val carga = ids("SELECT valores_json FROM laboratorio WHERE id = 'hcc-llaboratorio_general-703'").single()
        // La web ahora la guarda sin fecha: el servidor no la manda y la cuenta entre las omitidas.
        api.historial = historiaCompleta.let { h ->
            h.copy(laboratorios = h.laboratorios.copy(laboratorioGeneral = emptyList()), omitidas = h.omitidas.copy(laboratorioSinFecha = 2))
        }

        traer()

        assertEquals(listOf("hcc-llaboratorio_general-703"), claves())
        assertEquals(carga, ids("SELECT valores_json FROM laboratorio WHERE id = 'hcc-llaboratorio_general-703'").single(), "con todos sus valores")
        assertEquals(listOf("2|0"), ids("SELECT valor||'|'||dirty FROM paciente_extra WHERE clave = 'hcc_omitidas_laboratorio'"), "y el aviso de las omitidas")
    }

    @Test
    fun si_un_dato_del_historial_vuelve_la_marca_se_retira_y_se_aplica_lo_de_la_web() = conLaWeb {
        traerTodo()
        api.historial = historiaCompleta.copy(pendientes = null)
        traer()
        assertEquals(listOf("hcc-p1744"), claves())

        api.historial = historiaCompleta.copy(pendientes = historiaCompleta.pendientes!!.copy(texto = "Prueba Salud360 pendiente nuevo"))
        reloj.avanzar(DIA)
        traer()

        assertEquals(emptyList(), marcas())
        assertEquals(listOf("Prueba Salud360 pendiente nuevo"), ids("SELECT texto FROM pendiente WHERE id = 'hcc-p1744'"))
    }

    @Test
    fun un_historial_con_error_no_marca_nada_pero_la_lista_ya_aplicada_conserva_sus_marcas() = conLaWeb {
        traerTodo()
        api.cabeceras = api.cabeceras.filter { it.id != 7004L }
        for (error in ERRORES) {
            api.fallaHistorial = error
            reloj.avanzar(DIA)

            val e = assertFailsWith<TobbException> { traer() }

            assertEquals(error.status, e.status)
            assertEquals(listOf("hcc-c7004"), claves(), "${error.status} ${error.codigo}: solo la de la lista, nada del historial")
        }
    }

    @Test
    fun un_historial_de_otra_ficha_no_marca_nada() = conLaWeb {
        traerTodo()
        val antes = volcarTodo(driver)
        api.historial = historiaCompleta.sinNada().copy(pacienteId = 9999)

        val e = assertFailsWith<TobbException> { traer() }

        assertEquals("respuesta", e.codigo)
        assertEquals(antes, volcarTodo(driver))
        assertEquals(emptyList(), marcas())
    }

    // ------------------------------------------------------------------
    // Lo del dispositivo y lo de otro paciente nunca se marca
    // ------------------------------------------------------------------

    @Test
    fun lo_sucio_o_creado_en_el_dispositivo_nunca_se_marca() = conLaWeb {
        val q = db.historiaClinicaQueries
        db.sembrar("hcc-c7003")
        db.sembrar("hcc-c7012") // traída y limpia, y la lista ya no la trae: esta sí se marca
        db.sembrar("hcc-c7010", dirty = true)
        db.sembrar("u-1", remotoId = "7011")
        db.sembrar("u-2", remotoId = "")
        q.upsertSeccionValor(SeccionValor("hcc-c7003", "habitos", "texto", "traído").toRow(10, dirty = false))
        q.upsertSeccionValor(SeccionValor("hcc-c7003", "nota", "texto", "sin enviar").toRow(10, dirty = true))
        q.upsertSeccionValor(SeccionValor("u-2", "nota", "texto", "del dispositivo").toRow(10, dirty = true))
        q.upsertLaboratorio(Laboratorio("hcc-llaboratorio_general-900", PACIENTE, null, "clinica", "laboratorio_general", LocalDate.parse("2026-01-01"), mapOf("hb" to "1")).toRow(10, dirty = true))
        q.upsertLaboratorio(Laboratorio("lab-del-dispositivo", PACIENTE, null, "clinica", "laboratorio_general", LocalDate.parse("2026-01-02"), mapOf("hb" to "2")).toRow(10, dirty = true))
        q.upsertRegistro(RegistroClinico("hcc-rveda-901", PACIENTE, null, "clinica", "veda", null, mapOf("detalle" to "x"), remotoId = "901").toRow(10, dirty = true))
        q.upsertRegistro(RegistroClinico("reg-del-dispositivo", PACIENTE, null, "clinica", "veda", null, mapOf("detalle" to "y")).toRow(10, dirty = true))
        q.upsertPendiente(Pendiente("hcc-p1900", PACIENTE, MEDICO, "pendiente sin enviar").toRow(10, dirty = true))
        q.upsertPendiente(Pendiente("pend-del-dispositivo", PACIENTE, MEDICO, "propio").toRow(10, dirty = true))
        q.upsertAntecedente(Antecedente("hcc-a5001-hta", PACIENTE, "clinica", "familiares", "hta", true, "sin enviar").toRow(10, dirty = true))
        q.upsertAntecedente(Antecedente("ant-del-dispositivo", PACIENTE, "clinica", "familiares", "dbt_ir", true, "propio").toRow(10, dirty = true))
        api.cabeceras = cabeceras.filter { it.id == 7003L } // la 7003 sigue; la 7012, la sucia 7010 y las del dispositivo no figuran
        api.historial = historiaCompleta.sinNada()
        api.consultas[7003] = RespuestasClinicaDeEjemplo.consultaConSecciones("{}")
        // La lista actualiza la fecha de la 7003 y el historial deja el aviso de omitidas: ni la consulta ni `paciente_extra` entran en la comparación.
        val antes = volcarTodoSinMarcas(driver).filterKeys { it != "consulta" && it != "paciente_extra" }

        traer()
        abrir(7003)
        backend.traerConsulta("hcc-c7010", "7010")

        assertEquals(listOf(7003L), api.pedidosConsulta, "la sucia no se pide")
        assertEquals(listOf("hcc-c7003:habitos:texto", "hcc-c7012"), claves(), "solo lo traído y limpio; nada de lo sucio ni de lo creado acá")
        assertEquals(antes, volcarTodoSinMarcas(driver).filterKeys { it != "consulta" && it != "paciente_extra" })
        assertEquals(1, ids("SELECT COUNT(*) FROM consulta WHERE id IN ('hcc-c7010', 'u-1', 'u-2') AND dirty = 1").single().toInt())
    }

    @Test
    fun una_respuesta_de_un_paciente_no_marca_ni_desmarca_nada_del_otro() = conLaWeb {
        val otro = otroPaciente()
        val antesDelOtro = deOtro()
        traerTodo() // la historia de A también trae la carga 703, que en el dispositivo ya es de B: no se le mueve

        assertEquals(antesDelOtro, deOtro(), "leer a A no toca nada de B, ni siquiera la marca de B sobre la carga que A también trae")
        assertEquals(emptyList(), marcas())

        api.cabeceras = emptyList()
        api.historial = historiaCompleta.sinNada()
        traer()

        assertEquals(antesDelOtro, deOtro(), "ni marca de más ni marca de menos en B")
        assertEquals(otro, marcasVigentes(driver, OTRO))
        assertTrue("hcc-llaboratorio_general-703" !in claves() && claves().none { "8001" in it || "1800" in it || "1900" in it || "5002" in it })
        assertEquals(mapOf("hcc-llaboratorio_general-703" to "2020-01-01"), backend.marcasAusentes(OTRO))
        assertTrue(backend.marcasAusentes(PACIENTE).keys.containsAll(listOf("hcc-c7003", "hcc-p1744")))
        assertTrue(backend.marcasAusentes(PACIENTE).keys.none { it in backend.marcasAusentes(OTRO).keys })
    }

    @Test
    fun marcasAusentes_devuelve_solo_las_vigentes_del_paciente() = conLaWeb {
        traerTodo()
        assertEquals(emptyMap(), backend.marcasAusentes(PACIENTE))
        api.cabeceras = api.cabeceras.filter { it.id != 7004L }
        traer()
        assertEquals(mapOf("hcc-c7004" to hoy()), backend.marcasAusentes(PACIENTE))

        api.cabeceras = cabeceras
        reloj.avanzar(DIA)
        traer()

        assertEquals(emptyMap(), backend.marcasAusentes(PACIENTE), "la marca retirada no se lista")
        assertEquals(emptyMap(), backend.marcasAusentes(OTRO))
    }

    @Test
    fun sin_ficha_no_se_pide_ni_se_marca_nada() = conBackend(laWeb(), vinculado = false) {
        db.sembrar("hcc-c7003")
        val antes = volcarTodo(driver)

        assertEquals(emptyList(), backend.traerConsultas(PACIENTE, MEDICO, true))
        backend.traerConsulta("hcc-c7003", "7003")

        assertEquals(0, api.totalDePedidos)
        assertEquals(antes, volcarTodo(driver))
    }

    // ------------------------------------------------------------------
    // Todo o nada: las marcas van en la misma transacción
    // ------------------------------------------------------------------

    private suspend fun Contexto.conTrigger(sql: String, bloque: suspend () -> Unit) {
        driver.execute(null, sql, 0).await()
        try {
            bloque()
        } finally {
            driver.execute(null, "DROP TRIGGER prueba_falla", 0).await()
        }
    }

    @Test
    fun si_falla_una_escritura_de_la_lista_no_queda_ninguna_marca_y_si_falla_la_marca_no_queda_nada() = conLaWeb {
        traerTodo()
        api.cabeceras = cabeceras.filter { it.id != 7004L }.map { if (it.id == 7005L) it.copy(edadMostrar = "77 años") else it }
        val antes = volcarTodo(driver)

        // 1) La última consulta no se puede escribir: la marca de la 7004 ya estaba escrita y se deshace.
        conTrigger("CREATE TRIGGER prueba_falla BEFORE INSERT ON consulta WHEN NEW.id = 'hcc-c7005' BEGIN SELECT RAISE(ABORT, 'prueba'); END") {
            assertFails { traer() }
        }
        assertEquals(antes, volcarTodo(driver), "ni marca a medias ni consulta a medias")

        // 2) La marca no se puede escribir: tampoco queda la consulta que sí se podía escribir.
        conTrigger(
            "CREATE TRIGGER prueba_falla BEFORE INSERT ON paciente_extra WHEN NEW.clave LIKE 'hcc!_ausente!_%' ESCAPE '!' BEGIN SELECT RAISE(ABORT, 'prueba'); END",
        ) {
            assertFails { traer() }
        }
        assertEquals(antes, volcarTodo(driver))

        traer() // sin el problema entra todo junto
        assertEquals(listOf("hcc-c7004"), claves())
        assertEquals(listOf("77 años"), ids("SELECT edad_mostrar FROM consulta WHERE id = 'hcc-c7005'"))
    }

    @Test
    fun si_falla_una_escritura_del_historial_no_queda_ninguna_marca() = conLaWeb {
        traerTodo()
        api.historial = historiaCompleta.sinLaCasilla("hta").let { h ->
            h.copy(
                laboratorios = h.laboratorios.copy(
                    laboratorioGeneral = h.laboratorios.laboratorioGeneral + CargaLaboratorioDto(704, 7003, "2026-03-11", mapOf("hb" to "12")),
                ),
            )
        }
        val antes = volcarTodo(driver)

        conTrigger("CREATE TRIGGER prueba_falla BEFORE INSERT ON laboratorio BEGIN SELECT RAISE(ABORT, 'prueba'); END") {
            assertFails { traer() }
        }
        assertEquals(antes, volcarTodo(driver), "la marca de la casilla 'hta' tampoco quedó")

        traer()
        assertEquals(listOf("hcc-a5001-hta"), claves())
        assertEquals("1", ids("SELECT COUNT(*) FROM laboratorio WHERE id = 'hcc-llaboratorio_general-704'").single())
    }

    @Test
    fun si_falla_una_escritura_de_la_consulta_no_queda_ninguna_marca() = conLaWeb {
        traerTodo()
        val modificada = EjemplosConsultasClinica.CONSULTA_COMPLETA
            .replace("\"habitos\": {\"texto\": \"Prueba Salud360 hábitos (a): tabaco, alcohol, ejercicio\"},", "")
            .replace("\"peso\": \"70.5\"", "\"peso\": \"71.0\"")
        assertTrue("habitos" !in modificada && "71.0" in modificada, "el ejemplo cambió y el reemplazo no tomó")
        api.consultas[7003] = RespuestasClinicaDeEjemplo.consulta(modificada)
        val antes = volcarTodo(driver)

        conTrigger("CREATE TRIGGER prueba_falla BEFORE INSERT ON examen_fisico BEGIN SELECT RAISE(ABORT, 'prueba'); END") {
            assertFails { abrir(7003) }
        }
        assertEquals(antes, volcarTodo(driver))

        abrir(7003)
        assertEquals(listOf("hcc-c7003:habitos:texto"), claves())
        assertEquals(listOf("71.0"), ids("SELECT peso FROM examen_fisico WHERE consulta_id = 'hcc-c7003'"))
    }

    // ------------------------------------------------------------------
    // Nunca se borra, se vacía ni se oculta
    // ------------------------------------------------------------------

    /**
     * Triggers que abortan cualquier `DELETE`, y cualquier `INSERT` o `UPDATE` (también el de `INSERT OR REPLACE`, que corre
     * el `BEFORE INSERT`) en las tablas clínicas; en `paciente_extra` solo se admiten filas `hcc_ausente_…`. Si alguna rama de la
     * lectura borra, vacía o reescribe algo que no sea una marca, la lectura falla con el nombre de la tabla.
     */
    private suspend fun Contexto.protegerTodo() {
        for (tabla in TABLAS) {
            val soloMarcas = tabla == "paciente_extra"
            val nuevo = if (soloMarcas) "WHEN NEW.clave NOT LIKE 'hcc!_ausente!_%' ESCAPE '!'" else ""
            val viejo = if (soloMarcas) "WHEN OLD.clave NOT LIKE 'hcc!_ausente!_%' ESCAPE '!'" else ""
            driver.execute(null, "CREATE TRIGGER sin_escribir_$tabla BEFORE INSERT ON $tabla $nuevo BEGIN SELECT RAISE(ABORT, 'INSERT en $tabla'); END", 0).await()
            driver.execute(null, "CREATE TRIGGER sin_cambiar_$tabla BEFORE UPDATE ON $tabla $nuevo BEGIN SELECT RAISE(ABORT, 'UPDATE en $tabla'); END", 0).await()
            driver.execute(null, "CREATE TRIGGER sin_borrar_$tabla BEFORE DELETE ON $tabla $viejo BEGIN SELECT RAISE(ABORT, 'DELETE en $tabla'); END", 0).await()
        }
    }

    @Test
    fun ninguna_rama_de_la_lectura_borra_ni_vacia_nada_y_solo_cambian_las_marcas() = conLaWeb {
        traerTodo()
        val antes = volcarTodoSinMarcas(driver)
        protegerTodo()
        // La web lo vació todo salvo que las consultas siguen: dieron de baja la 7004 (ni en la lista ni al abrirla), el historial
        // quedó vacío (con la misma cuenta de omitidas) y la 7003 y la 7005 perdieron todo su contenido.
        api.cabeceras = api.cabeceras.filter { it.id != 7004L }
        api.historial = historiaCompleta.sinNada()
        api.consultas[7003] = consultaVacia(EjemplosConsultasClinica.CABECERA_A)
        api.consultas[7005] = consultaVacia(EjemplosConsultasClinica.CABECERA_C)
        api.fallasConsulta[7004] = noEncontrada
        reloj.avanzar(DIA)

        traer()
        abrir(7003)
        assertEquals(404, assertFailsWith<TobbException> { abrir(7004) }.status)
        abrir(7005)

        assertEquals(antes, volcarTodoSinMarcas(driver), "ni una fila borrada, vaciada o cambiada; solo filas hcc_ausente_ nuevas")
        val esperadas = listOf("hcc-c7004") + DEL_HISTORIAL + clavesDeLaConsulta(consultaA) + clavesDeLaConsulta(consultaC) -
            listOf("hcc-c7003", "hcc-c7005").toSet()
        assertEquals(conFecha(esperadas.toSet()), marcas())
        assertTrue("hcc-c7003:examen_fisico:peso" in claves() && "hcc-c7005:examen_fisico:imc" in claves())
        assertEquals(0, HcClinicaPruebas.filasSucias(driver))
    }

    @Test
    fun las_marcas_quitadas_tampoco_borran_filas_solo_las_dan_de_baja() = conLaWeb {
        traerTodo()
        api.historial = historiaCompleta.sinNada()
        traer()
        assertTrue(marcas().isNotEmpty())
        protegerTodo()

        api.historial = historiaCompleta // todo vuelve
        reloj.avanzar(DIA)
        traer()

        assertEquals(emptyList(), marcas())
        assertEquals(DEL_HISTORIAL.size, ids("SELECT COUNT(*) FROM paciente_extra WHERE clave LIKE 'hcc!_ausente!_%' ESCAPE '!' AND deleted = 1").single().toInt())
        assertEquals("0", ids("SELECT COUNT(*) FROM paciente_extra WHERE clave LIKE 'hcc!_ausente!_%' ESCAPE '!' AND dirty <> 0").single())
    }

    // ------------------------------------------------------------------
    // Idempotencia
    // ------------------------------------------------------------------

    @Test
    fun dos_corridas_iguales_dejan_la_misma_base() = conLaWeb {
        traerTodo()
        api.cabeceras = api.cabeceras.filter { it.id != 7004L }
        api.historial = historiaCompleta.copy(pendientes = null, interconsultas = emptyList())
        api.consultas[7003] = parcial
        api.fallasConsulta[7004] = noEncontrada
        suspend fun corrida() {
            traer()
            abrir(7003)
            assertFailsWith<TobbException> { abrir(7004) }
        }
        corrida()
        val primera = volcarTodo(driver)
        assertTrue(marcas().size > 5)

        reloj.avanzar(2 * DIA)
        corrida()

        assertEquals(primera, volcarTodo(driver), "mismas filas, mismas fechas y mismos updated_at")
    }

    // ------------------------------------------------------------------
    // Apoyo
    // ------------------------------------------------------------------

    private companion object {
        /** Un paciente local sin ficha en la web, para consultas que no se pueden pedir. */
        const val OTRO_SIN_FICHA = "p-99000199"
    }

    /** Un segundo paciente (ficha 5002) con filas ya traídas, una de ellas con el id de una carga que la historia de A también trae, y una marca. */
    private suspend fun Contexto.otroPaciente(): List<String> {
        val pq = db.pacientesQueries
        val q = db.historiaClinicaQueries
        pq.upsertPaciente(Paciente(OTRO, "99000101", "Salud360", "Prueba B").toRow(0, dirty = false))
        pq.upsertPacienteExtra(
            PacienteExtra(OTRO, CodigosHc.GENERAL, PacientesHcRepository.EXTRA_HC_PACIENTE_ID, OTRA_FICHA.toString()).toRow(0, dirty = false),
        )
        db.sembrar("hcc-c8001", paciente = OTRO)
        q.upsertSeccionValor(SeccionValor("hcc-c8001", "nota", "texto", "nota de B").toRow(10, dirty = false))
        q.upsertLaboratorio(
            Laboratorio("hcc-llaboratorio_general-703", OTRO, null, "clinica", "laboratorio_general", LocalDate.parse("2026-03-10"), mapOf("hb" to "13")).toRow(10, dirty = false),
        )
        q.upsertPendiente(Pendiente("hcc-p1800", OTRO, MEDICO, "pendiente de B").toRow(10, dirty = false))
        q.upsertAntecedente(Antecedente("hcc-a5002-hta", OTRO, "clinica", "familiares", "hta", true, "de B").toRow(10, dirty = false))
        q.upsertRegistro(RegistroClinico("hcc-rveda-1900", OTRO, null, "clinica", "veda", null, mapOf("detalle" to "de B"), remotoId = "1900").toRow(10, dirty = false))
        pq.upsertPacienteExtra(PacienteExtra(OTRO, CodigosHc.GENERAL, "hcc_ausente_hcc-llaboratorio_general-703", "2020-01-01").toRow(10, dirty = false))
        return marcasVigentes(driver, OTRO)
    }

    /** Todas las filas del segundo paciente, tabla por tabla (incluida su marca). */
    private fun Contexto.deOtro(): Map<String, List<String>> =
        listOf("consulta", "laboratorio", "pendiente", "antecedente", "registro_clinico", "paciente_extra").associateWith { tabla ->
            volcar(driver, tabla).filter { "'$OTRO'" in it }
        } + ("seccion_valor" to volcar(driver, "seccion_valor").filter { "'hcc-c8001'" in it })
}
