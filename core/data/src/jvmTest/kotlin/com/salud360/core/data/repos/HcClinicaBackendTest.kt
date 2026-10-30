package com.salud360.core.data.repos

import app.cash.sqldelight.db.SqlDriver
import com.salud360.core.data.hcgeneral.CLAVE_OMITIDAS
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.ConsultasClinicaApi
import com.salud360.core.data.network.hc.OmitidasConsultasDto
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.data.network.hc.RespuestaListaConsultasDto
import com.salud360.core.data.repos.HcClinicaPruebas.FICHA
import com.salud360.core.data.repos.HcClinicaPruebas.MEDICO
import com.salud360.core.data.repos.HcClinicaPruebas.PACIENTE
import com.salud360.core.data.repos.HcClinicaPruebas.textos
import com.salud360.core.data.repos.HcClinicaPruebas.volcar
import com.salud360.core.data.repos.HcClinicaPruebas.volcarTodo
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.MedicoPreferencia
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `HcClinicaBackend.traerConsultas` (spec 002, E6.3) con una API falsa de [ConsultasClinicaApi] y una base SQLite de
 * verdad: pagina la lista, escribe las cabeceras con `dirty = 0` sin pisar lo del dispositivo (RF-48, RF-54), no pide nada
 * sin vínculo (RF-49), no repite antes de un minuto (RF-57) y no borra ni vacía nada (Q15). Datos inventados.
 */
class HcClinicaBackendTest {

    private class Pedido(val ficha: Long, val limite: Int, val desde: Int)

    /** La web: la lista de [cabeceras], de la más nueva a la más vieja, en páginas de lo que pida la app. */
    private class ApiFalsa(
        var cabeceras: List<ConsultaCabeceraDto> = emptyList(),
        var omitidas: OmitidasConsultasDto = OmitidasConsultasDto(),
    ) : ConsultasClinicaApi {
        val pedidos = mutableListOf<Pedido>()

        /** Para armar una respuesta a medida de cada pedido; por defecto, la lista cortada como el servidor. */
        var respuesta: ((Pedido) -> RespuestaListaConsultasDto)? = null

        /** Número de pedido (desde 1) → error que lanza en vez de contestar. */
        val fallas = mutableMapOf<Int, Throwable>()

        /** Si no es null, cada pedido espera a que se abra. */
        var compuerta: CompletableDeferred<Unit>? = null

        override suspend fun listar(pacienteWebId: Long, limite: Int, desde: Int): RespuestaListaConsultasDto {
            val pedido = Pedido(pacienteWebId, limite, desde)
            pedidos += pedido
            compuerta?.await()
            fallas[pedidos.size]?.let { throw it }
            respuesta?.let { return it(pedido) }
            val pagina = cabeceras.drop(desde).take(limite)
            return RespuestaListaConsultasDto(pagina, cabeceras.size, desde, if (desde + pagina.size < cabeceras.size) 1 else 0, omitidas)
        }

        override suspend fun consulta(id: Long): RespuestaConsultaDto = error("traerConsultas no pide una consulta (es de E6.4)")

        override suspend fun historia(pacienteWebId: Long): RespuestaHistoriaDto = error("traerConsultas no pide el historial (es de E6.4)")
    }

    private class Reloj(var millis: Long = 1_700_000_000_000L) {
        fun avanzar(ms: Long) { millis += ms }
    }

    private class Contexto(val backend: HcClinicaBackend, val db: Salud360Db, val driver: SqlDriver, val api: ApiFalsa, val reloj: Reloj)

    private fun conBackend(
        api: ApiFalsa = ApiFalsa(),
        vinculado: Boolean = true,
        maxPaginas: Int = HcClinicaBackend.MAX_PAGINAS,
        bloque: suspend Contexto.() -> Unit,
    ) = HcClinicaPruebas.conBase(vinculado) { db, driver ->
        val reloj = Reloj()
        val backend = HcClinicaBackend(db, api, HcClinicaPruebas.pacientes(db), { reloj.millis }, maxPaginas)
        Contexto(backend, db, driver, api, reloj).bloque()
    }

    private fun cab(
        id: Long,
        creada: String = "2026-03-10 10:00:00",
        propia: Boolean = true,
        medico: Long? = 9,
        nombre: String? = "Médico Prueba Salud360",
        estado: String = "CERRADA",
        edad: String = "",
        tipo: String? = "control",
        tipoNumero: Int? = 1,
        paciente: Long = FICHA,
    ) = ConsultaCabeceraDto(
        id = id, pacienteId = paciente, medicoId = medico, medicoNombre = nombre, tipo = tipo, tipoNumero = tipoNumero,
        fecha = creada.take(10), createdAt = creada, estado = estado, edadMostrar = edad, propia = propia, updatedAt = creada,
    )

    /** [n] consultas de a una por día, de la más nueva a la más vieja. */
    private fun muchas(n: Int, desde: Long = 8000): List<ConsultaCabeceraDto> =
        (0 until n).map { i -> cab(desde + n - i, creada = "${java.time.LocalDate.parse("2026-06-30").minusDays(i.toLong())} 10:00:00") }

    private val tres = listOf(
        cab(7005, "2026-09-01 09:00:00", estado = "ABIERTA"),
        cab(7004, "2026-04-14 15:30:00", propia = false, medico = 14, nombre = "Otro Médico Prueba Salud360", tipo = "telemedicina", tipoNumero = 4),
        cab(7003, "2026-03-10 10:00:00", edad = "45 años"),
    )

    private val hoy = LocalDate.parse("2026-10-30")

    private suspend fun Salud360Db.sembrar(
        id: Id,
        remotoId: String = id.removePrefix("hcc-c"),
        paciente: Id = PACIENTE,
        medico: Id = MEDICO,
        estado: EstadoConsulta = EstadoConsulta.CERRADA,
        edad: String = "",
        dirty: Boolean = false,
        actualizada: Long = 111,
    ) = historiaClinicaQueries.upsertConsulta(
        Consulta(id, paciente, medico, "clinica", "consulta", hoy, estado, edad, remotoId = remotoId).toRow(actualizada, dirty = dirty),
    )

    private suspend fun Contexto.filas() = db.historiaClinicaQueries.consultasDePacienteTodas(PACIENTE).lista { it }

    private suspend fun Contexto.traer(forzar: Boolean = true) = backend.traerConsultas(PACIENTE, MEDICO, forzar)

    // ---- Qué guarda ----

    @Test
    fun declara_su_especialidad_y_que_es_de_solo_lectura() = conBackend {
        assertEquals("clinica", backend.especialidad)
        assertTrue(backend.soloLectura)
    }

    @Test
    fun una_pagina_es_un_pedido_y_guarda_las_cabeceras_sin_dirty_con_su_remoto_id() = conBackend(ApiFalsa(tres)) {
        val traidas = traer()

        assertEquals(1, api.pedidos.size)
        assertEquals(listOf(FICHA), api.pedidos.map { it.ficha })
        assertEquals(listOf(50), api.pedidos.map { it.limite })
        assertEquals(listOf(0), api.pedidos.map { it.desde })
        assertEquals(
            listOf(
                "hcc-c7003|$PACIENTE|$MEDICO|clinica|control|2026-03-10|CERRADA|45 años|0|7003",
                "hcc-c7004|$PACIENTE|hcc-m14|clinica|telemedicina|2026-04-14|CERRADA||0|7004",
                "hcc-c7005|$PACIENTE|$MEDICO|clinica|control|2026-09-01|ABIERTA||0|7005",
            ),
            textos(
                driver,
                "SELECT id||'|'||paciente_id||'|'||medico_id||'|'||especialidad||'|'||tipo||'|'||fecha||'|'||estado||'|'||edad_mostrar||'|'||dirty||'|'||remoto_id FROM consulta ORDER BY id",
            ),
        )
        assertEquals(listOf("hcc-c7005", "hcc-c7004", "hcc-c7003"), traidas.map { it.id })
        assertEquals(0, filas().count { it.dirty != 0L })
    }

    @Test
    fun el_medico_ajeno_es_hcc_m_con_su_nombre_en_medico_preferencia_y_el_propio_el_de_la_sesion() = conBackend(ApiFalsa(tres)) {
        traer()

        // RF-25: la propia lleva el médico de la sesión; la de otro, un id que nunca coincide con el de un médico de turnos.
        assertEquals(setOf(MEDICO, "hcc-m14"), filas().map { it.medico_id }.toSet())
        assertEquals(
            listOf("hcc-m14|nombre|Otro Médico Prueba Salud360|0"),
            textos(driver, "SELECT medico_id||'|'||clave||'|'||valor||'|'||dirty FROM medico_preferencia"),
        )
    }

    @Test
    fun una_consulta_ajena_sin_medico_es_hcc_m0_y_sin_nombre_no_se_guarda_ninguno() = conBackend(
        ApiFalsa(listOf(cab(7006, propia = false, medico = null, nombre = null), cab(7007, propia = false, medico = 15, nombre = " "))),
    ) {
        traer()

        assertEquals(listOf("hcc-m0", "hcc-m15"), filas().map { it.medico_id }.sorted())
        assertEquals(emptyList(), volcar(driver, "medico_preferencia"))
    }

    @Test
    fun el_nombre_del_medico_ajeno_se_actualiza_pero_un_nombre_vacio_no_pisa() = conBackend(ApiFalsa(listOf(cab(7004, propia = false, medico = 14, nombre = "Nombre viejo")))) {
        traer()
        api.cabeceras = listOf(cab(7004, propia = false, medico = 14, nombre = "Nombre nuevo"))
        traer()
        assertEquals(listOf("Nombre nuevo"), textos(driver, "SELECT valor FROM medico_preferencia WHERE medico_id = 'hcc-m14'"))

        api.cabeceras = listOf(cab(7004, propia = false, medico = 14, nombre = null))
        traer()
        assertEquals(listOf("Nombre nuevo"), textos(driver, "SELECT valor FROM medico_preferencia WHERE medico_id = 'hcc-m14'"))
    }

    @Test
    fun un_nombre_de_medico_con_cambios_sin_enviar_no_se_toca() = conBackend(ApiFalsa(listOf(cab(7004, propia = false, medico = 14, nombre = "Nombre web")))) {
        db.historiaClinicaQueries.upsertPreferencia(MedicoPreferencia("hcc-m14", "nombre", "Nombre del dispositivo").toRow(5, dirty = true))
        val antes = volcar(driver, "medico_preferencia")

        traer()

        assertEquals(antes, volcar(driver, "medico_preferencia"))
    }

    @Test
    fun las_cabeceras_se_escriben_de_la_mas_vieja_a_la_mas_nueva_con_updated_at_creciente() = conBackend(
        ApiFalsa(
            // El mismo día: solo la hora de creación dice cuál es más nueva.
            listOf(cab(7012, "2026-05-02 11:00:00"), cab(7011, "2026-05-02 10:00:00"), cab(7010, "2026-05-02 09:00:00")),
        ),
    ) {
        traer()

        val porAntiguedad = textos(driver, "SELECT id FROM consulta ORDER BY updated_at ASC")
        assertEquals(listOf("hcc-c7010", "hcc-c7011", "hcc-c7012"), porAntiguedad)
        assertEquals(3, textos(driver, "SELECT DISTINCT updated_at FROM consulta").size, "updated_at estrictamente creciente")
        // Y la base las ofrece de la más nueva a la más vieja aunque la fecha sea la misma.
        assertEquals(listOf("hcc-c7012", "hcc-c7011", "hcc-c7010"), filas().map { it.id })
    }

    @Test
    fun una_consulta_sin_tipo_o_estado_conocido_no_se_escribe_y_las_demas_si() = conBackend(
        ApiFalsa(listOf(cab(7020, tipo = "otro", tipoNumero = 9), cab(7021, estado = "ELIMINADA"), cab(7022))),
    ) {
        traer()

        assertEquals(listOf("hcc-c7022"), filas().map { it.id })
    }

    @Test
    fun una_cabecera_de_otro_paciente_de_la_web_no_se_escribe() = conBackend(ApiFalsa(listOf(cab(7030, paciente = 9999), cab(7031)))) {
        traer()

        assertEquals(listOf("hcc-c7031"), filas().map { it.id })
    }

    @Test
    fun devuelve_las_consultas_de_la_web_del_paciente_y_no_las_del_dispositivo() = conBackend(ApiFalsa(tres)) {
        db.sembrar("u-1", remotoId = "", dirty = true)

        val traidas = traer()

        assertEquals(listOf("hcc-c7005", "hcc-c7004", "hcc-c7003"), traidas.map { it.id })
        assertEquals(listOf("7005", "7004", "7003"), traidas.map { it.remotoId })
    }

    // ---- Paginado y cuántos pedidos hace (RF-57) ----

    @Test
    fun una_lista_de_cuatro_paginas_son_cuatro_pedidos_de_a_cincuenta() = conBackend(ApiFalsa(muchas(160))) {
        traer()

        assertEquals(4, api.pedidos.size)
        assertEquals(listOf(0, 50, 100, 150), api.pedidos.map { it.desde })
        assertEquals(listOf(50, 50, 50, 50), api.pedidos.map { it.limite })
        assertEquals(160, filas().size)
    }

    @Test
    fun cincuenta_justas_son_un_solo_pedido() = conBackend(ApiFalsa(muchas(50))) {
        traer()

        assertEquals(1, api.pedidos.size)
        assertEquals(50, filas().size)
    }

    @Test
    fun un_paciente_sin_consultas_es_un_pedido_y_no_escribe_nada() = conBackend(ApiFalsa(emptyList())) {
        val antes = volcarTodo(driver)

        assertEquals(emptyList(), traer())

        assertEquals(1, api.pedidos.size)
        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun el_tope_de_paginas_corta_sin_error_y_escribe_lo_que_llego() = conBackend(
        ApiFalsa(muchas(400)),
        maxPaginas = 3,
    ) {
        traer()

        assertEquals(3, api.pedidos.size)
        assertEquals(150, filas().size)
    }

    @Test
    fun el_tope_de_seguridad_es_razonable() {
        // 20 páginas de 50 = 1000 consultas de un mismo paciente, mucho más de lo que hay en la realidad.
        assertEquals(20, HcClinicaBackend.MAX_PAGINAS)
        assertEquals(50, HcClinicaBackend.LIMITE_POR_PAGINA)
    }

    @Test
    fun si_el_total_cambia_entre_paginas_se_deja_de_pedir_y_no_se_marca_nada() = conBackend(ApiFalsa(muchas(160))) {
        val base = muchas(160)
        api.respuesta = { p ->
            val pagina = base.drop(p.desde).take(p.limite)
            // En la segunda página alguien cargó otra consulta: el total pasa de 160 a 161.
            RespuestaListaConsultasDto(pagina, if (p.desde == 0) 160 else 161, p.desde, 1)
        }

        traer()

        assertEquals(listOf(0, 50), api.pedidos.map { it.desde }, "se corta apenas el total cambia")
        assertEquals(100, filas().size)
        assertEquals(emptyList(), textos(driver, "SELECT clave FROM paciente_extra WHERE clave LIKE 'hcc_ausente_%'"))
    }

    @Test
    fun una_pagina_vacia_con_hay_mas_no_hace_un_bucle() = conBackend {
        api.respuesta = { p -> RespuestaListaConsultasDto(if (p.desde == 0) listOf(cab(7040)) else emptyList(), 99, p.desde, 1) }

        traer()

        assertEquals(listOf(0, 1), api.pedidos.map { it.desde })
        assertEquals(listOf("hcc-c7040"), filas().map { it.id })
    }

    @Test
    fun una_pagina_que_no_empieza_donde_se_pidio_corta() = conBackend {
        api.respuesta = { p -> RespuestaListaConsultasDto(listOf(cab(7050 + p.desde.toLong())), 500, 0, 1) }

        traer()

        // La primera pagina dice que empieza en 0 (bien); la segunda también (mal: se pidió desde 1): se corta.
        assertEquals(2, api.pedidos.size)
    }

    // ---- Traer dos veces, debounce y «Actualizar» ----

    @Test
    fun traer_dos_veces_no_duplica_ni_cambia_nada() = conBackend(ApiFalsa(tres)) {
        traer()
        val antes = volcarTodo(driver)
        reloj.avanzar(5_000)

        traer()

        assertEquals(antes, volcarTodo(driver), "ni filas nuevas ni updated_at distinto")
        assertEquals(3, filas().size)
    }

    @Test
    fun la_segunda_llamada_antes_de_un_minuto_no_pide_nada() = conBackend(ApiFalsa(tres)) {
        val primera = traer(forzar = false)
        assertEquals(1, api.pedidos.size)

        reloj.avanzar(59_999)
        val segunda = traer(forzar = false)

        assertEquals(1, api.pedidos.size, "antes de 60 s no hay pedidos")
        assertEquals(primera.map { it.id }, segunda.map { it.id }, "devuelve lo que ya está en el dispositivo")

        reloj.avanzar(1)
        traer(forzar = false)
        assertEquals(2, api.pedidos.size, "a los 60 s vuelve a pedir")
    }

    @Test
    fun forzar_pide_otra_vez_aunque_no_haya_pasado_un_minuto() = conBackend(ApiFalsa(tres)) {
        traer(forzar = false)

        reloj.avanzar(1_000)
        traer(forzar = true)

        assertEquals(2, api.pedidos.size)
    }

    @Test
    fun el_metodo_de_la_interfaz_no_fuerza() = conBackend(ApiFalsa(tres)) {
        backend.traerConsultas(PACIENTE, MEDICO)
        backend.traerConsultas(PACIENTE, MEDICO)

        assertEquals(1, api.pedidos.size)
    }

    @Test
    fun el_minuto_se_cuenta_por_paciente_y_por_medico() = conBackend(ApiFalsa(tres)) {
        traer(forzar = false)

        backend.traerConsultas(PACIENTE, "m-99000010", false)
        assertEquals(2, api.pedidos.size, "otro médico del mismo dispositivo no se saltea la lectura")

        db.pacientesQueries.upsertPacienteExtra(
            PacienteExtra("p-otro", "clinica", PacientesHcRepository.EXTRA_HC_PACIENTE_ID, "5002").toRow(0, dirty = false),
        )
        api.cabeceras = emptyList()
        backend.traerConsultas("p-otro", MEDICO, false)
        assertEquals(3, api.pedidos.size, "otro paciente tampoco")
    }

    @Test
    fun un_error_no_arranca_el_minuto() = conBackend(ApiFalsa(tres)) {
        api.fallas[1] = TobbException(500, "consultas", "No se pudo leer la historia clínica.")
        assertFailsWith<TobbException> { traer(forzar = false) }

        traer(forzar = false)

        assertEquals(2, api.pedidos.size, "el reintento es manual pero nada lo frena después de un error")
        assertEquals(3, filas().size)
    }

    @Test
    fun dos_llamadas_a_la_vez_hacen_un_solo_pedido() = conBackend(ApiFalsa(tres)) {
        val compuerta = CompletableDeferred<Unit>()
        api.compuerta = compuerta

        coroutineScope {
            val a = async { backend.traerConsultas(PACIENTE, MEDICO, false) }
            val b = async { backend.traerConsultas(PACIENTE, MEDICO, false) }
            repeat(5) { yield() }
            compuerta.complete(Unit)
            awaitAll(a, b)
        }

        assertEquals(1, api.pedidos.size, "la segunda espera a la primera y ve que no pasó un minuto")
        assertEquals(3, filas().size)
    }

    // ---- Sin vínculo (RF-49) ----

    @Test
    fun un_paciente_sin_vinculo_no_pide_nada_ni_resuelve_ni_escribe() = conBackend(ApiFalsa(tres), vinculado = false) {
        val antes = volcarTodo(driver)

        // `HcClinicaPruebas.PacientesProhibida` hace fallar el test si algo llama a `pacientes/resolver`.
        assertEquals(emptyList(), traer())
        assertEquals(emptyList(), traer(forzar = false))

        assertEquals(0, api.pedidos.size)
        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun un_paciente_que_ni_esta_en_el_dispositivo_tampoco_pide_nada() = conBackend(ApiFalsa(tres)) {
        assertEquals(emptyList(), backend.traerConsultas("p-fantasma", MEDICO, true))

        assertEquals(0, api.pedidos.size)
    }

    @Test
    fun el_vinculo_de_otra_historia_no_cuenta() = conBackend(ApiFalsa(tres), vinculado = false) {
        db.pacientesQueries.upsertPacienteExtra(
            PacienteExtra(PACIENTE, "pediatria", PacientesHcRepository.EXTRA_HC_PACIENTE_ID, "77").toRow(0, dirty = false),
        )

        assertEquals(emptyList(), traer())
        assertEquals(0, api.pedidos.size)
    }

    // ---- Errores (RF-56): nada nuevo, nada roto, sin reintento ----

    private suspend fun Contexto.sembrarLoPrevio() {
        db.sembrar("hcc-c7003", edad = "45 años", actualizada = 222)
        db.sembrar("u-9", remotoId = "", estado = EstadoConsulta.ABIERTA, dirty = true, actualizada = 333)
    }

    @Test
    fun cada_error_deja_todo_como_estaba_con_un_solo_pedido_y_lo_propaga_tal_cual() {
        val errores = listOf(
            TobbException(401, "no_autorizado", "Sesión vencida"),
            TobbException(403, "fuera_de_cartera", "Ese paciente no está en tu cartera."),
            TobbException(404, "paciente_no_encontrado", "Paciente no encontrado."),
            TobbException(422, "datos_invalidos", "Falta el paciente o no es válido."),
            TobbException(429, null, "Too Many Attempts."),
            TobbException(500, "consultas", "No se pudo leer la historia clínica."),
            TobbException(0, "red", "Sin conexión con la historia clínica de clinica"),
            TobbException(200, "respuesta", "La respuesta no tiene la forma esperada"),
        )
        for (error in errores) {
            conBackend(ApiFalsa(tres)) {
                sembrarLoPrevio()
                val antes = volcarTodo(driver)
                api.fallas[1] = error

                val e = assertFailsWith<TobbException>("${error.status}") { traer() }

                assertSame(error, e)
                assertEquals(1, api.pedidos.size, "sin reintento (RF-58): ${error.status}")
                assertEquals(antes, volcarTodo(driver), "lo ya traído se queda y no entra nada nuevo: ${error.status}")
            }
        }
    }

    @Test
    fun un_error_a_mitad_de_la_lista_no_escribe_las_paginas_anteriores_y_conserva_lo_que_habia() = conBackend(ApiFalsa(muchas(160))) {
        sembrarLoPrevio()
        val antes = volcarTodo(driver)
        api.fallas[3] = TobbException(429, null, "Too Many Attempts.")

        assertFailsWith<TobbException> { traer() }

        assertEquals(3, api.pedidos.size, "las páginas 1 y 2 y el pedido que falló; ninguno más")
        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun otro_error_que_no_es_de_la_api_tambien_sale_sin_escribir() = conBackend(ApiFalsa(tres)) {
        sembrarLoPrevio()
        val antes = volcarTodo(driver)
        val roto = IllegalStateException("json roto")
        api.fallas[1] = roto

        assertSame(roto, assertFailsWith<IllegalStateException> { traer() })

        assertEquals(antes, volcarTodo(driver))
    }

    @Test
    fun si_falla_al_escribir_no_queda_media_lista() = conBackend(ApiFalsa(tres, OmitidasConsultasDto(foto = 1))) {
        sembrarLoPrevio()
        val antes = volcarTodo(driver)
        // La más nueva (7005) es la última que se escribe: la base la rechaza y las dos anteriores tienen que deshacerse.
        driver.execute(
            null, "CREATE TRIGGER prueba_falla BEFORE INSERT ON consulta WHEN NEW.id = 'hcc-c7005' BEGIN SELECT RAISE(ABORT, 'prueba'); END", 0,
        ).await()

        assertFailsWith<Exception> { traer() }

        driver.execute(null, "DROP TRIGGER prueba_falla", 0).await()
        assertEquals(antes, volcarTodo(driver), "ni la 7003, ni la 7004, ni el nombre del médico, ni omitidas")
    }

    // ---- Lo del dispositivo manda (RF-48, RF-54) ----

    /** La fila de `consulta` con ese id, con todas sus columnas. */
    private fun Contexto.fila(id: Id) = volcar(driver, "consulta").filter { it.startsWith("'$id'") }

    @Test
    fun una_consulta_de_la_web_con_cambios_sin_enviar_no_se_pisa() = conBackend(ApiFalsa(tres)) {
        db.sembrar("hcc-c7003", estado = EstadoConsulta.ABIERTA, edad = "lo que escribió el médico", dirty = true, actualizada = 999)
        val antes = fila("hcc-c7003")
        assertEquals(1, antes.size)

        traer()

        assertEquals(antes, fila("hcc-c7003"), "ni estado, ni edad, ni updated_at, y sigue sucia")
        assertEquals(3, filas().size, "las otras dos entran")
    }

    @Test
    fun las_consultas_creadas_en_el_dispositivo_no_se_tocan() = conBackend(ApiFalsa(tres)) {
        db.sembrar("0b1c6e56-aaaa-4bbb-8ccc-000000000001", remotoId = "", estado = EstadoConsulta.ABIERTA, dirty = true, actualizada = 400)
        db.sembrar("0b1c6e56-aaaa-4bbb-8ccc-000000000002", remotoId = "", estado = EstadoConsulta.CERRADA, dirty = true, actualizada = 401)
        val antes = volcar(driver, "consulta")

        traer()

        assertEquals(antes, volcar(driver, "consulta").filter { it.startsWith("'0b1c") })
        assertEquals(5, volcar(driver, "consulta").size)
    }

    @Test
    fun una_del_dispositivo_con_el_mismo_remoto_id_no_se_duplica_ni_se_pisa() = conBackend(ApiFalsa(tres)) {
        db.sembrar("u-7003", remotoId = "7003", estado = EstadoConsulta.ABIERTA, edad = "propia", dirty = false, actualizada = 400)
        val antes = fila("u-7003")

        traer()

        assertEquals(emptyList(), fila("hcc-c7003"), "no hay una segunda copia de la misma consulta")
        assertEquals(antes, fila("u-7003"))
    }

    @Test
    fun una_consulta_con_el_mismo_id_de_otro_paciente_del_dispositivo_no_se_mueve() = conBackend(ApiFalsa(tres)) {
        db.sembrar("hcc-c7003", paciente = "p-otro", edad = "de otro", actualizada = 400)
        val antes = fila("hcc-c7003")

        traer()

        assertEquals(antes, fila("hcc-c7003"))
    }

    @Test
    fun la_web_actualiza_lo_que_cambio_y_un_valor_vacio_no_pisa() = conBackend(ApiFalsa(listOf(cab(7003, estado = "CERRADA", edad = "")))) {
        db.sembrar("hcc-c7003", estado = EstadoConsulta.ABIERTA, edad = "45 años", dirty = false, actualizada = 400)

        traer()

        assertEquals(
            listOf("hcc-c7003|CERRADA|45 años|0|7003"),
            textos(driver, "SELECT id||'|'||estado||'|'||edad_mostrar||'|'||dirty||'|'||remoto_id FROM consulta WHERE id = 'hcc-c7003'"),
            "el estado de la web pisa; la edad vacía no",
        )

        api.cabeceras = listOf(cab(7003, estado = "CERRADA", edad = "46 años"))
        traer()
        assertEquals(listOf("46 años"), textos(driver, "SELECT edad_mostrar FROM consulta WHERE id = 'hcc-c7003'"), "una edad con valor sí pisa")
    }

    @Test
    fun una_consulta_de_otro_medico_sigue_siendo_de_otro_aunque_la_local_estuviera_como_propia() = conBackend(ApiFalsa(listOf(cab(7004, propia = false, medico = 14, nombre = "Otro")))) {
        db.sembrar("hcc-c7004", medico = MEDICO, dirty = false, actualizada = 400)

        traer()

        assertEquals(listOf("hcc-m14"), textos(driver, "SELECT medico_id FROM consulta WHERE id = 'hcc-c7004'"))
    }

    // ---- Q15: nunca borrar ----

    private suspend fun Contexto.sembrarHijas() {
        val q = db.historiaClinicaQueries
        q.upsertSeccionValor(SeccionValor("hcc-c7003", "motivo_consulta", "texto", "Prueba Salud360 motivo (a)").toRow(10, dirty = false))
        q.upsertExamenFisico(ExamenFisico("hcc-c7003", peso = "70.5", frecuenciaCardiaca = "72").toRow(10, dirty = false))
        q.upsertAntecedente(
            Antecedente("hcc-a5001-hta", PACIENTE, "clinica", "familiares", "hta", true, "Prueba Salud360 hta", null).toRow(10, dirty = false),
        )
        q.upsertRegistro(
            RegistroClinico("hcc-rveda-1797", PACIENTE, "hcc-c7003", "clinica", "veda", LocalDate.parse("2026-02-02"), mapOf("detalle" to "Prueba Salud360 veda"), true, "1797")
                .toRow(10, dirty = false),
        )
        q.upsertLaboratorio(
            Laboratorio("hcc-llaboratorio_general-703", PACIENTE, "hcc-c7003", "clinica", "laboratorio_general", LocalDate.parse("2026-03-10"), mapOf("hb" to "13.9"))
                .toRow(10, dirty = false),
        )
        q.upsertPendiente(Pendiente("hcc-p1744", PACIENTE, MEDICO, "Prueba Salud360 pendiente", null, false).toRow(10, dirty = false))
    }

    @Test
    fun una_lista_mas_corta_que_la_que_habia_no_borra_ni_marca_nada() = conBackend(ApiFalsa(tres)) {
        traer()
        sembrarHijas()
        val antes = volcarTodo(driver)
        assertEquals(3, antes.getValue("consulta").size)

        // Desaparecieron la 7003 y la 7004 de la web.
        api.cabeceras = listOf(tres.first())
        traer()
        assertEquals(antes, volcarTodo(driver), "conteo y contenido de consulta y de las hijas iguales; ninguna marca (eso es E6.4b)")

        // Y la lista vacía tampoco vacía nada.
        api.cabeceras = emptyList()
        traer()
        assertEquals(antes, volcarTodo(driver))
        assertEquals(3, filas().size)
    }

    @Test
    fun ninguna_lectura_toca_las_tablas_hijas() = conBackend(ApiFalsa(tres)) {
        sembrarHijas()
        val hijas = listOf("seccion_valor", "examen_fisico", "antecedente", "registro_clinico", "laboratorio", "pendiente", "archivo", "consulta_diagnostico")
        val antes = hijas.associateWith { volcar(driver, it) }

        traer()
        api.cabeceras = emptyList()
        traer()

        assertEquals(antes, hijas.associateWith { volcar(driver, it) })
    }

    // ---- Lo que la lista omite (RF-43) ----

    @Test
    fun omitidas_se_guarda_en_paciente_extra_sin_dirty() = conBackend(ApiFalsa(tres, OmitidasConsultasDto(foto = 1, otro = 2, sinFecha = 0))) {
        traer()

        assertEquals(
            listOf("clinica|hcc_omitidas|1|2|0|0"),
            textos(driver, "SELECT especialidad||'|'||clave||'|'||valor||'|'||dirty FROM paciente_extra WHERE clave = '$CLAVE_OMITIDAS'"),
        )
    }

    @Test
    fun omitidas_en_cero_sin_fila_previa_no_escribe_nada() = conBackend(ApiFalsa(tres)) {
        traer()

        assertEquals(emptyList(), textos(driver, "SELECT clave FROM paciente_extra WHERE clave = '$CLAVE_OMITIDAS'"))
    }

    @Test
    fun omitidas_sigue_a_la_web_si_baja_a_cero_porque_es_una_cuenta_y_no_un_dato_clinico() = conBackend(ApiFalsa(tres, OmitidasConsultasDto(foto = 1))) {
        traer()
        api.omitidas = OmitidasConsultasDto()

        traer()

        assertEquals(listOf("0|0|0"), textos(driver, "SELECT valor FROM paciente_extra WHERE clave = '$CLAVE_OMITIDAS'"))
    }

    @Test
    fun omitidas_con_cambios_sin_enviar_no_se_toca() = conBackend(ApiFalsa(tres, OmitidasConsultasDto(foto = 5))) {
        db.pacientesQueries.upsertPacienteExtra(
            PacienteExtra(PACIENTE, "clinica", CLAVE_OMITIDAS, "9|9|9").toRow(7, dirty = true),
        )

        traer()

        assertEquals(listOf("9|9|9|1"), textos(driver, "SELECT valor||'|'||dirty FROM paciente_extra WHERE clave = '$CLAVE_OMITIDAS'"))
    }

    @Test
    fun el_vinculo_de_la_ficha_queda_como_estaba() = conBackend(ApiFalsa(tres, OmitidasConsultasDto(foto = 1))) {
        val antes = volcar(driver, "paciente_extra").filter { "hc_paciente_id" in it }
        assertEquals(1, antes.size)

        traer()

        assertEquals(antes, volcar(driver, "paciente_extra").filter { "hc_paciente_id" in it })
    }
}
