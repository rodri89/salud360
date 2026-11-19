package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.AusenciasEnLaWeb
import com.salud360.core.data.hcgeneral.LoEnviado
import com.salud360.core.data.hcgeneral.PARTE_EXAMEN
import com.salud360.core.data.hcgeneral.PARTE_SECCIONES
import com.salud360.core.data.hcgeneral.esDeLaWeb
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.EstudioDto
import com.salud360.core.data.network.hc.ExamenFisicoDto
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.SeccionesConsultaDto
import com.salud360.core.data.network.hc.TextoDto
import com.salud360.core.data.repos.HcClinicaPruebas.ApiFalsa
import com.salud360.core.data.repos.HcClinicaPruebas.Contexto
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.conBackend
import com.salud360.core.data.repos.HcClinicaPruebas.marcasVigentes
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.repos.HcClinicaPruebas.volcar
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodoSinMarcas
import com.salud360.core.data.sync.EscrituraContada
import com.salud360.core.data.sync.FECHA_DE_PRUEBA
import com.salud360.core.data.sync.sembrarPropia
import com.salud360.core.model.Id
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 005, T29 (RF-33, RF-62, RF-64, RF-65): la lectura de la HC general **refresca la consulta propia ya enviada** con lo que cambió
 * en la web, sin duplicarla y sin pisar lo que el dispositivo tiene sin enviar. Se empareja por `remoto_id` (una propia no se vuelve a
 * crear como `hcc-c<id>`); lo sucio manda (y la propia conserva `propia`); un vacío no pisa; lo que el médico descartó no se pisa; una
 * consulta cerrada o anulada no se reabre por una lectura vieja; y lo que la web ya no devuelve queda «Ya no figura en la web» sin
 * borrar nada. La lectura sigue siendo solo `GET` e idempotente. API falsa y base SQLite de verdad; datos inventados.
 */
class HcClinicaBackendPropiasTest {
    private val dia = "2026-11-06"
    private val otroMedico = "m-99000077"

    private fun cab(id: Long, estado: String = "ABIERTA", edad: String = "", propia: Boolean = true, medico: Long? = 9) = ConsultaCabeceraDto(
        id = id, pacienteId = 5001, medicoId = medico, medicoNombre = "Médico Prueba Salud360", tipo = "control", tipoNumero = 1,
        fecha = dia, createdAt = "$dia 10:00:00", estado = estado, edadMostrar = edad, propia = propia, updatedAt = "$dia 10:30:00",
    )

    private fun respuesta(
        id: Long = 7001,
        estado: String = "ABIERTA",
        nota: String? = null,
        habitos: String? = null,
        peso: String? = null,
    ) = RespuestaConsultaDto(
        consulta = cab(id, estado),
        secciones = SeccionesConsultaDto(nota = nota?.let(::TextoDto), habitos = habitos?.let(::TextoDto)),
        examenFisico = ExamenFisicoDto(peso = peso),
    )

    private suspend fun Contexto.traer() = backend.traerConsultas(PACIENTE, MEDICO, forzar = true)

    private suspend fun Contexto.filas() = db.historiaClinicaQueries.consultasDePacienteTodas(PACIENTE).lista { it.id }.sorted()

    private fun Contexto.fila(id: Id) = textos(
        driver, "SELECT estado||'|'||edad_mostrar||'|'||remoto_id||'|'||propia||'|'||dirty FROM consulta WHERE id = '$id'",
    ).singleOrNull()

    private fun Contexto.texto(consulta: Id, seccion: String) = textos(
        driver, "SELECT valor||'|'||dirty FROM seccion_valor WHERE consulta_id = '$consulta' AND seccion = '$seccion' AND campo = 'texto'",
    ).singleOrNull()

    private fun Contexto.examen(consulta: Id) = textos(driver, "SELECT peso||'|'||dirty FROM examen_fisico WHERE consulta_id = '$consulta'").singleOrNull()

    private suspend fun Contexto.loEnviado(consulta: Id) = LoEnviadoGuardado(db).deLaConsulta(PACIENTE, consulta)

    private suspend fun Contexto.sembrarLoEnviado(consulta: Id, valores: Map<String, String> = emptyMap(), examen: Map<String, String> = emptyMap()) =
        LoEnviadoGuardado(db).guardar(PACIENTE, consulta, LoEnviado(valores, examen))

    private suspend fun Contexto.sembrarTexto(consulta: Id, seccion: String, texto: String, dirty: Boolean = false, actualizada: Long = 50) =
        db.historiaClinicaQueries.upsertSeccionValor(SeccionValor(consulta, seccion, "texto", texto).toRow(actualizada, dirty = dirty))

    // ---- Se ve una sola vez ----

    @Test
    fun una_propia_ya_enviada_se_ve_una_sola_vez_la_lista_no_crea_otra_hcc() = conBackend(ApiFalsa(listOf(cab(7001)))) {
        db.sembrarPropia("u-1", remotoId = "7001", edad = "", dirty = false)

        traer()
        traer()

        assertEquals(listOf("u-1"), filas(), "ni una fila hcc-c7001 más")
        assertTrue(fila("u-1")!!.endsWith("|7001|1|0"), "sigue siendo propia, con su id de la web y sin pendiente: ${fila("u-1")}")
    }

    @Test
    fun una_propia_que_otro_dispositivo_creo_se_ve_como_de_la_web_y_solo_para_leer() = conBackend(ApiFalsa(listOf(cab(7005, propia = true)))) {
        traer()

        assertEquals(listOf("hcc-c7005"), filas())
        val consulta = db.historiaClinicaQueries.consultaPorId("hcc-c7005").lista { it.toModel() }.single()
        assertTrue(consulta.esDeLaWeb(), "RF-64/65: es una consulta de la web para esta app")
        assertFalse(consulta.propia, "y no es propia: no se edita ni se cierra desde acá")
        assertEquals("7005", consulta.remotoId)
    }

    @Test
    fun si_la_propia_se_vincula_despues_de_que_la_lectura_trajo_su_copia_la_copia_se_retira_y_no_se_pierde_nada() = conBackend(ApiFalsa(listOf(cab(7001)))) {
        // La web creó la consulta, el dispositivo todavía no anotó su id (corte antes de anotar, T25) y la lectura la trajo como de la web.
        db.sembrarPropia("u-1", remotoId = "", dirty = true)
        traer()
        assertEquals(listOf("hcc-c7001", "u-1"), filas())
        sembrarTexto("hcc-c7001", "nota", "Lo que trajo la web")
        // Ahora el reenvío anota el id de la web en la propia (la clave se repite y la web contesta la misma consulta).
        db.historiaClinicaQueries.guardarRemotoId("7001", "u-1")

        traer()

        assertEquals(listOf("u-1"), filas(), "se ve una sola vez: la propia")
        assertEquals("Lo que trajo la web|0", texto("hcc-c7001", "nota"), "lo que había traído no se borra, solo deja de mostrarse la consulta")
        val antes = volcarTodo(driver)
        reloj.avanzar(120_000)
        traer()
        assertEquals(antes, volcarTodo(driver), "y repetir no cambia nada")
    }

    // ---- La cabecera ----

    @Test
    fun la_lista_refresca_la_cabecera_de_la_propia_con_lo_que_cambio_en_la_web_y_conserva_propia() =
        conBackend(ApiFalsa(listOf(cab(7001, estado = "CERRADA", edad = "47 años")))) {
            db.sembrarPropia("u-1", remotoId = "7001", edad = "", dirty = false)

            traer()

            assertEquals("CERRADA|47 años|7001|1|0", fila("u-1"), "cerrada desde la web, con su edad, sin dirty y todavía propia")
            assertEquals(listOf("u-1"), filas())
        }

    @Test
    fun una_edad_vacia_de_la_web_no_pisa_la_que_tenia_la_propia() = conBackend(ApiFalsa(listOf(cab(7001, edad = "")))) {
        db.sembrarPropia("u-1", remotoId = "7001", edad = "45 años", dirty = false)

        traer()

        assertEquals("ABIERTA|45 años|7001|1|0", fila("u-1"))
    }

    @Test
    fun una_propia_con_el_cierre_o_la_baja_pendientes_no_se_toca() = conBackend(ApiFalsa(listOf(cab(7001, estado = "ABIERTA", edad = "otra")))) {
        db.sembrarPropia("u-1", remotoId = "7001", estado = EstadoConsulta.CERRADA, edad = "45 años", dirty = true)
        val antes = volcar(driver, "consulta").filter { "'u-1'" in it }

        traer()

        assertEquals(antes, volcar(driver, "consulta").filter { "'u-1'" in it }, "lo pendiente manda hasta enviarse")
    }

    @Test
    fun una_propia_cerrada_con_el_cierre_enviado_no_se_reabre_por_una_lista_vieja() = conBackend(ApiFalsa(listOf(cab(7001, estado = "ABIERTA")))) {
        db.sembrarPropia("u-1", remotoId = "7001", estado = EstadoConsulta.CERRADA, dirty = false)

        traer()

        assertTrue(fila("u-1")!!.startsWith("CERRADA|"), "la web no reabre: una lista que llegó tarde no la vuelve a abierta (${fila("u-1")})")
    }

    @Test
    fun una_propia_anulada_no_se_toca_ni_se_reabre() = conBackend(ApiFalsa(listOf(cab(7001, estado = "ABIERTA")))) {
        db.sembrarPropia("u-1", remotoId = "7001", estado = EstadoConsulta.ANULADA, dirty = false)
        val antes = fila("u-1")

        traer()

        assertEquals(antes, fila("u-1"))
        assertFalse("hcc-c7001" in filas(), "y no se vuelve a crear como de la web")
    }

    @Test
    fun la_propia_de_otro_medico_no_se_toca_desde_esta_sesion() = conBackend(ApiFalsa(listOf(cab(7001, estado = "CERRADA", edad = "ajena")))) {
        db.sembrarPropia("u-1", medico = otroMedico, remotoId = "7001", edad = "45 años", dirty = false)
        val antes = fila("u-1")

        traer()

        assertEquals(antes, fila("u-1"), "lo del primer médico no se modifica con la sesión del segundo (RF-50)")
        assertFalse("hcc-c7001" in filas(), "y tampoco se vuelve a crear como de la web")
    }

    // ---- «Ya no figura en la web» ----

    @Test
    fun una_propia_enviada_que_la_lista_ya_no_devuelve_se_marca_ya_no_figura_sin_borrar_nada() = conBackend(ApiFalsa(listOf(cab(7002)))) {
        db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
        sembrarTexto("u-1", "nota", "Texto del médico")
        val antes = volcar(driver, "consulta").filter { "'u-1'" in it }

        traer()

        val marcas = marcasVigentes(driver)
        assertTrue(marcas.any { it.startsWith("hcc-c7001=") }, "la dieron de baja en la web: $marcas")
        assertTrue(AusenciasEnLaWeb(backend.marcasAusentes(PACIENTE)).consultaPropia("7001"), "la pantalla pregunta por el id de la web de la propia")
        assertFalse(AusenciasEnLaWeb(backend.marcasAusentes(PACIENTE)).consultaPropia("7002"))
        assertEquals(antes, volcar(driver, "consulta").filter { "'u-1'" in it }, "la fila no cambia")
        assertEquals("Texto del médico|0", texto("u-1", "nota"), "ni un dato local")
        assertFalse(marcas.any { "7002" in it }, "la que sí viene no se marca")

        // Cuando la web la vuelve a mandar, la marca se va.
        api.cabeceras = listOf(cab(7001), cab(7002))
        traer()
        assertFalse(marcasVigentes(driver).any { it.startsWith("hcc-c7001=") })
    }

    @Test
    fun no_se_marca_una_propia_sin_enviar_anulada_o_con_cambios_pendientes() = conBackend(ApiFalsa(listOf(cab(7009)))) {
        db.sembrarPropia("sin-enviar", remotoId = "", dirty = true)
        db.sembrarPropia("anulada", remotoId = "7001", estado = EstadoConsulta.ANULADA, dirty = false)
        db.sembrarPropia("pendiente", remotoId = "7002", estado = EstadoConsulta.CERRADA, dirty = true)

        traer()

        assertEquals(emptyList(), marcasVigentes(driver), "no se marca lo que no se mandó, lo que se dio de baja a propósito ni lo pendiente")
    }

    @Test
    fun el_404_de_la_consulta_marca_a_la_propia_y_el_error_sale_igual() = conBackend(ApiFalsa()) {
        db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
        sembrarTexto("u-1", "nota", "Texto del médico")
        api.fallasConsulta[7001] = TobbException(404, "consulta_no_encontrada", "Consulta no encontrada.")

        val error = runCatching { backend.traerConsulta("u-1", "7001") }.exceptionOrNull()

        assertTrue(error is TobbException && error.status == 404)
        assertTrue(marcasVigentes(driver).any { it.startsWith("hcc-c7001=") })
        assertEquals("Texto del médico|0", texto("u-1", "nota"))
    }

    // ---- El contenido ----

    @Test
    fun el_contenido_de_la_propia_se_refresca_con_lo_que_cambio_en_la_web_salvo_lo_pendiente() =
        conBackend(ApiFalsa().apply { consultas[7001] = respuesta(nota = "Nota cambiada en la web", habitos = "Hábitos de la web", peso = "80") }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            sembrarTexto("u-1", "nota", "Nota vieja")
            sembrarTexto("u-1", "habitos", "Hábitos del médico, sin enviar", dirty = true)
            sembrarLoEnviado("u-1", valores = mapOf("nota/texto" to "Nota vieja"))
            db.historiaClinicaQueries.upsertExamenFisico(com.salud360.core.model.hc.ExamenFisico("u-1", peso = "70").toRow(60, dirty = false))
            sembrarLoEnviado("u-1", valores = mapOf("nota/texto" to "Nota vieja"), examen = mapOf("peso" to "70"))

            backend.traerConsulta("u-1", "7001")

            assertEquals("Nota cambiada en la web|0", texto("u-1", "nota"), "lo que la web cambió y no está pendiente se actualiza")
            assertEquals("Hábitos del médico, sin enviar|1", texto("u-1", "habitos"), "lo sucio no se pisa")
            assertEquals("80|0", examen("u-1"))
            assertEquals(listOf(7001L), api.pedidosConsulta, "un pedido, de lectura")
            // Lo que la web tiene queda anotado, así lo que el médico escriba después se compara contra eso (RF-69).
            val lo = loEnviado("u-1")
            assertEquals("Nota cambiada en la web", lo.valores["nota/texto"])
            assertEquals("Hábitos de la web", lo.valores["habitos/texto"])
            assertEquals("80", lo.examen["peso"])
            assertEquals(listOf("u-1"), filas())
        }

    @Test
    fun una_propia_con_el_cierre_pendiente_igual_refresca_su_contenido() = conBackend(ApiFalsa().apply { consultas[7001] = respuesta(nota = "Nota de la web") }) {
        db.sembrarPropia("u-1", remotoId = "7001", estado = EstadoConsulta.CERRADA, dirty = true)
        sembrarTexto("u-1", "nota", "Nota vieja")
        sembrarLoEnviado("u-1", valores = mapOf("nota/texto" to "Nota vieja"))

        backend.traerConsulta("u-1", "7001")

        assertEquals("Nota de la web|0", texto("u-1", "nota"))
        assertTrue(fila("u-1")!!.endsWith("|1|1"), "y la fila sigue con su cierre pendiente: ${fila("u-1")}")
    }

    @Test
    fun un_vacio_de_la_web_no_pisa_lo_que_la_propia_tenia() = conBackend(ApiFalsa().apply { consultas[7001] = respuesta(nota = "", habitos = null, peso = null) }) {
        db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
        sembrarTexto("u-1", "nota", "Nota del médico")
        sembrarTexto("u-1", "habitos", "Hábitos del médico")
        sembrarLoEnviado("u-1", valores = mapOf("nota/texto" to "Nota del médico", "habitos/texto" to "Hábitos del médico"))
        val antes = volcar(driver, "seccion_valor")

        backend.traerConsulta("u-1", "7001")

        assertEquals(antes, volcar(driver, "seccion_valor"), "nada se vacía por una respuesta con menos")
    }

    @Test
    fun lo_que_el_medico_descarto_no_se_pisa_con_lo_de_la_web() = conBackend(ApiFalsa().apply { consultas[7001] = respuesta(nota = "Uno", peso = "70") }) {
        db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
        // Se envió «Uno», el médico escribió «Dos», la web lo rechazó y lo descartó: «Dos» sigue solo en el dispositivo (T27).
        sembrarTexto("u-1", "nota", "Dos")
        sembrarLoEnviado("u-1", valores = mapOf("nota/texto" to "Uno"), examen = mapOf("peso" to "70"))
        EstadosDeEnvio(db).guardar(PACIENTE, "u-1", PARTE_SECCIONES, EstadoEnvio.Descartado, FECHA_DE_PRUEBA)
        db.historiaClinicaQueries.upsertExamenFisico(com.salud360.core.model.hc.ExamenFisico("u-1", peso = "75").toRow(60, dirty = false))
        EstadosDeEnvio(db).guardar(PACIENTE, "u-1", PARTE_EXAMEN, EstadoEnvio.Descartado, FECHA_DE_PRUEBA)

        backend.traerConsulta("u-1", "7001")

        assertEquals("Dos|0", texto("u-1", "nota"), "lo descartado se conserva")
        assertEquals("75|0", examen("u-1"))
        assertEquals("Uno", loEnviado("u-1").valores["nota/texto"], "y lo que la web tiene sigue siendo lo que tiene")
    }

    @Test
    fun el_contenido_no_se_pide_si_no_corresponde() = conBackend(ApiFalsa().apply { consultas[7001] = respuesta(nota = "x") }) {
        db.sembrarPropia("sin-enviar", remotoId = "", dirty = true)
        db.sembrarPropia("anulada", remotoId = "7001", estado = EstadoConsulta.ANULADA, dirty = false)
        db.sembrarPropia("otro-id", remotoId = "7002", dirty = false)
        val antes = volcarTodo(driver)

        backend.traerConsulta("sin-enviar", "7001")
        backend.traerConsulta("sin-enviar", "")
        backend.traerConsulta("anulada", "7001")
        backend.traerConsulta("otro-id", "7001") // el remoto_id local no coincide con el que se pide
        backend.traerConsulta("no-existe", "7001")

        assertEquals(0, api.totalDePedidos)
        assertEquals(antes, volcarTodo(driver))
    }

    // ---- Las filas de la consulta (empareja por remoto_id) ----

    @Test
    fun las_filas_de_la_web_de_una_propia_se_asocian_a_ella_y_las_que_ya_tiene_se_emparejan_por_remoto_id_sin_duplicar() {
        val historia = HcClinicaPruebas.historiaVacia().let { h ->
            h.copy(
                estudios = h.estudios.copy(
                    veda = listOf(EstudioDto(901, 7001, dia, "detalle de la web"), EstudioDto(902, 7001, dia, "otro estudio")),
                ),
            )
        }
        conBackend(ApiFalsa(listOf(cab(7001))).apply { this.historial = historia }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            // Un estudio que esta app ya envió: tiene su id local y el id de la web.
            db.historiaClinicaQueries.upsertRegistro(
                RegistroClinico("r-1", PACIENTE, "u-1", "clinica", "veda", FECHA_DE_PRUEBA, mapOf("detalle" to "detalle local"), remotoId = "901")
                    .toRow(10, dirty = false),
            )

            traer()

            val registros = db.historiaClinicaQueries.registrosDePacienteTodos(PACIENTE, "clinica").lista { it.toModel() }.associateBy { it.id }
            assertEquals(setOf("r-1", "hcc-rveda-902"), registros.keys, "el 901 es el r-1: no se crea otro")
            assertEquals("detalle de la web", registros.getValue("r-1").campos["detalle"], "y se refresca con lo que cambió en la web")
            assertEquals("u-1", registros.getValue("hcc-rveda-902").consultaId, "el que solo tiene la web cuelga de la propia, no queda suelto")
            assertEquals(0, textos(driver, "SELECT COUNT(*) FROM registro_clinico WHERE dirty <> 0").single().toInt())
        }
    }

    @Test
    fun un_registro_propio_con_cambios_sin_enviar_no_se_pisa() {
        val historia = HcClinicaPruebas.historiaVacia().let { h ->
            h.copy(estudios = h.estudios.copy(veda = listOf(EstudioDto(901, 7001, dia, "detalle de la web"))))
        }
        conBackend(ApiFalsa(listOf(cab(7001))).apply { this.historial = historia }) {
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)
            db.historiaClinicaQueries.upsertRegistro(
                RegistroClinico("r-1", PACIENTE, "u-1", "clinica", "veda", FECHA_DE_PRUEBA, mapOf("detalle" to "lo que escribió el médico"), remotoId = "901")
                    .toRow(10, dirty = true),
            )

            traer()

            assertEquals("1", textos(driver, "SELECT dirty FROM registro_clinico WHERE id = 'r-1'").single())
            assertTrue(textos(driver, "SELECT campos_json FROM registro_clinico WHERE id = 'r-1'").single().contains("lo que escribió el médico"))
            assertEquals(1, db.historiaClinicaQueries.registrosDePacienteTodos(PACIENTE, "clinica").lista { it.id }.size, "y no se crea otro")
        }
    }

    // ---- La lectura sigue siendo solo lectura, e idempotente ----

    @Test
    fun el_refresco_de_una_propia_solo_lee_nunca_escribe_en_la_web() {
        HcClinicaPruebas.conBase { db, _ ->
            val api = ApiFalsa(listOf(cab(7001, estado = "CERRADA", edad = "47 años"))).apply {
                consultas[7001] = respuesta(estado = "CERRADA", nota = "Nota de la web", peso = "80")
            }
            val escritura = EscrituraContada()
            val backend = HcClinicaBackend(db, api, HcClinicaPruebas.pacientes(db), { 1_700_000_000_000L }, HcClinicaBackend.MAX_PAGINAS, escritura)
            db.sembrarPropia("u-1", remotoId = "7001", dirty = false)

            backend.traerConsultas(PACIENTE, MEDICO, forzar = true)
            backend.traerConsulta("u-1", "7001")

            assertEquals(emptyList(), escritura.llamadas, "ni un POST, PUT ni DELETE")
            assertEquals(listOf("lista", "historia", "consulta"), api.orden, "solo los tres GET de la lectura")
        }
    }

    @Test
    fun correr_el_refresco_dos_veces_da_lo_mismo() = conBackend(
        ApiFalsa(listOf(cab(7001, estado = "CERRADA", edad = "47 años"), cab(7002))).apply {
            consultas[7001] = respuesta(estado = "CERRADA", nota = "Nota de la web", habitos = "Hábitos de la web", peso = "80")
        },
    ) {
        db.sembrarPropia("u-1", remotoId = "7001", edad = "", dirty = false)
        sembrarTexto("u-1", "nota", "Nota vieja")
        sembrarLoEnviado("u-1", valores = mapOf("nota/texto" to "Nota vieja"))

        traer()
        backend.traerConsulta("u-1", "7001")
        val primera = volcarTodo(driver)
        reloj.avanzar(120_000)
        traer()
        backend.traerConsulta("u-1", "7001")

        assertEquals(primera, volcarTodo(driver), "ni filas nuevas, ni updated_at distinto, ni marcas nuevas")
        assertEquals("Nota de la web|0", texto("u-1", "nota"))
        assertEquals(volcarTodoSinMarcas(driver), primera.mapValues { (_, f) -> f.filterNot { "'hcc_ausente_" in it } })
        assertNull(texto("hcc-c7001", "nota"))
    }
}
