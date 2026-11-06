package com.salud360.core.data.repos

import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toRow
import com.salud360.core.database.DriverFactory
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005 RF-68 y RF-69 (T18, D26, D27) en la HC general:
 *
 * - un dato por paciente (antecedente, fila de registro) viaja solo si su `consulta_id` apunta a una consulta propia; los de una
 *   consulta del histórico, de la web o sin consulta no viajan;
 * - editar una casilla o fila del histórico dentro de una consulta propia la reasigna a esa consulta y la marca sucia, y viaja
 *   solo eso;
 * - el autoguardado (cada 600 ms) guarda todo lo que hay en pantalla: **solo un cambio real marca algo**. Lo mismo, o un vacío sobre
 *   nada, no escribe, no marca `dirty`, no mueve `updated_at` ni la consulta de origen; vaciar algo que tenía texto sí se escribe.
 *
 * Pediatría y las demás especialidades no cambian: el guardado idéntico las sigue marcando, como hasta ahora. Base SQLite real.
 */
class DatosPorPacienteTest {
    private val paciente = "p-99000001"
    private val medico = "m-99000001"
    private val hoy = LocalDate.parse("2026-11-06")
    private val antes = 1000L

    private val historica = "hist-1"
    private val propia = "propia-1"
    private val otraPropia = "propia-2"
    private val deLaWeb = "hcc-c9"

    private fun conBase(bloque: suspend (Salud360Db, HcRepository) -> Unit) {
        val archivo = File.createTempFile("salud360-datos-paciente", ".db").also { it.delete() }
        try {
            runBlocking {
                val driver = DriverFactory(archivo.absolutePath).createDriver()
                try {
                    val db = Salud360Db(driver)
                    db.sembrarConsultas()
                    bloque(db, HcRepository(db))
                } finally {
                    driver.close()
                }
            }
        } finally {
            archivo.delete()
        }
    }

    private suspend fun Salud360Db.consulta(id: Id, propia: Boolean, especialidad: String = "clinica", estado: EstadoConsulta = EstadoConsulta.ABIERTA) =
        historiaClinicaQueries.upsertConsulta(Consulta(id, paciente, medico, especialidad, "consulta", hoy, estado, propia = propia).toRow(antes, dirty = false))

    private suspend fun Salud360Db.sembrarConsultas() {
        consulta(historica, propia = false)
        consulta(propia, propia = true)
        consulta(otraPropia, propia = true)
        consulta(deLaWeb, propia = false)
        consulta("ped-1", propia = false, especialidad = "pediatria")
    }

    private suspend fun Salud360Db.antecedente(
        id: String, clave: String, consultaId: Id?, dirty: Boolean = true, flag: Boolean = true, detalle: String = "detalle de $clave",
        especialidad: String = "clinica", categoria: String = "familiares",
    ) = historiaClinicaQueries.upsertAntecedente(
        Antecedente(id, paciente, especialidad, categoria, clave, flag, detalle, consultaId).toRow(antes, dirty = dirty),
    )

    private suspend fun Salud360Db.registro(id: String, consultaId: Id?, dirty: Boolean = true, campos: Map<String, String> = mapOf("evaluacion" to "pedido $id")) =
        historiaClinicaQueries.upsertRegistro(RegistroClinico(id, paciente, consultaId, "clinica", "examen_complementario", hoy, campos).toRow(antes, dirty = dirty))

    private data class FilaAntecedente(val consulta: String?, val dirty: Long, val actualizada: Long, val flag: Long, val detalle: String)

    private suspend fun Salud360Db.fila(clave: String, especialidad: String = "clinica") =
        historiaClinicaQueries.antecedentesDePaciente(paciente, especialidad)
            .lista { it.clave to FilaAntecedente(it.consulta_id, it.dirty, it.updated_at, it.flag, it.detalle) }
            .toMap()[clave]

    private suspend fun HcRepository.claves(soloSucios: Boolean = false) =
        antecedentesDeConsultasPropias(paciente, "clinica", soloSucios).map { it.clave }.sorted()

    private suspend fun HcRepository.idsDeRegistros(soloSucios: Boolean = false) =
        registrosDeConsultasPropias(paciente, "clinica", soloSucios).map { it.id }.sorted()

    // ---------- Qué dato por paciente es propio ----------

    @Test
    fun un_antecedente_es_propio_solo_si_su_consulta_es_propia() = conBase { db, r ->
        db.antecedente("a-h", "hta", historica)
        db.antecedente("a-p", "acv", propia)
        db.antecedente("a-p2", "dsl", otraPropia)
        db.antecedente("a-w", "fa", deLaWeb)
        db.antecedente("a-n", "mp", null)
        db.antecedente("a-x", "otro", "consulta-que-no-existe")

        assertEquals(listOf("acv", "dsl"), r.claves())
    }

    @Test
    fun una_fila_de_registro_es_propia_solo_si_su_consulta_es_propia() = conBase { db, r ->
        db.registro("r-h", historica)
        db.registro("r-p", propia)
        db.registro("r-w", deLaWeb)
        db.registro("r-n", null)

        assertEquals(listOf("r-p"), r.idsDeRegistros())
    }

    @Test
    fun de_los_datos_de_consultas_propias_solo_viaja_lo_sucio_y_lo_activo() = conBase { db, r ->
        db.antecedente("a-p-limpio", "acv", propia, dirty = false)
        db.antecedente("a-p-sucio", "dsl", propia, dirty = true)
        db.registro("r-p-limpio", propia, dirty = false)
        db.registro("r-p-sucio", propia, dirty = true)
        // Un antecedente desactivado (los que dejó la migración de hepatología) nunca cuenta (RF-9).
        db.historiaClinicaQueries.upsertAntecedente(
            Antecedente("a-p-inactivo", paciente, "clinica", "familiares", "fa", true, "x", propia, activo = false).toRow(antes, dirty = true),
        )

        assertEquals(listOf("acv", "dsl"), r.claves())
        assertEquals(listOf("dsl"), r.claves(soloSucios = true))
        assertEquals(listOf("r-p-limpio", "r-p-sucio"), r.idsDeRegistros())
        assertEquals(listOf("r-p-sucio"), r.idsDeRegistros(soloSucios = true))
    }

    @Test
    fun los_datos_de_otro_paciente_o_de_otra_hc_no_se_mezclan() = conBase { db, r ->
        db.antecedente("a-p", "acv", propia)
        db.antecedente("a-ped", "asma", "ped-1", especialidad = "pediatria", categoria = "personales")
        assertEquals(listOf("acv"), r.claves())
        assertEquals(emptyList(), r.antecedentesDeConsultasPropias("p-otro", "clinica"))
        assertEquals(emptyList(), r.antecedentesDeConsultasPropias(paciente, "pediatria"))
    }

    // ---------- Editar el histórico dentro de una propia ----------

    @Test
    fun editar_una_casilla_del_historico_dentro_de_una_propia_la_reasigna_la_marca_sucia_y_viaja_solo_eso() = conBase { db, r ->
        db.antecedente("a-h", "hta", historica, dirty = true)
        db.antecedente("a-h2", "dsl", historica, dirty = true)
        db.antecedente("a-n", "dbt_ir", null, dirty = true)
        db.antecedente("a-p", "acv", propia, dirty = false)
        assertEquals(listOf("acv"), r.claves())

        assertTrue(r.guardarAntecedente(paciente, "clinica", "familiares", "hta", true, "madre", propia))

        val hta = db.fila("hta")!!
        assertEquals(propia, hta.consulta, "pasó a la consulta propia")
        assertEquals(1L, hta.dirty)
        assertEquals("madre", hta.detalle)
        assertTrue(hta.actualizada > antes)
        assertEquals(listOf("acv", "hta"), r.claves())
        assertEquals(listOf("hta"), r.claves(soloSucios = true), "viaja solo lo que se tocó")
        // Lo demás del histórico no se mueve ni cambia.
        assertEquals(FilaAntecedente(historica, 1, antes, 1, "detalle de dsl"), db.fila("dsl"))
        assertEquals(FilaAntecedente(null, 1, antes, 1, "detalle de dbt_ir"), db.fila("dbt_ir"))
        assertEquals(FilaAntecedente(propia, 0, antes, 1, "detalle de acv"), db.fila("acv"))
    }

    @Test
    fun guardar_lo_mismo_desde_la_propia_no_reasigna_ni_marca_nada() = conBase { db, r ->
        db.antecedente("a-h", "hta", historica, dirty = false)
        val filaAntes = db.fila("hta")

        // El autoguardado vuelve a guardar lo que ya estaba.
        assertTrue(r.guardarAntecedente(paciente, "clinica", "familiares", "hta", true, "detalle de hta", propia))

        assertEquals(filaAntes, db.fila("hta"))
        assertEquals(emptyList(), r.claves())
        assertEquals(emptyList(), r.claves(soloSucios = true))
    }

    @Test
    fun una_casilla_vacia_sobre_nada_no_crea_ninguna_fila() = conBase { db, r ->
        assertTrue(r.guardarAntecedente(paciente, "clinica", "familiares", "hta", false, "", propia))
        assertTrue(r.guardarAntecedente(paciente, "clinica", "familiares", "dsl", false, "   ", propia))

        assertEquals(emptyList(), db.historiaClinicaQueries.antecedentesDePaciente(paciente, "clinica").lista { it.clave })
    }

    @Test
    fun vaciar_a_proposito_una_casilla_de_la_propia_se_escribe_y_viaja() = conBase { db, r ->
        db.antecedente("a-p", "acv", propia, dirty = false)

        assertTrue(r.guardarAntecedente(paciente, "clinica", "familiares", "acv", false, "", propia))

        assertEquals(FilaAntecedente(propia, 1, db.fila("acv")!!.actualizada, 0, ""), db.fila("acv"))
        assertTrue(db.fila("acv")!!.actualizada > antes)
        assertEquals(listOf("acv"), r.claves(soloSucios = true))
    }

    @Test
    fun editar_una_fila_del_historico_o_sin_consulta_dentro_de_una_propia_la_reasigna_y_viaja_solo_eso() = conBase { db, r ->
        db.registro("r-h", historica, campos = mapOf("evaluacion" to "pedido r-h"))
        db.registro("r-h2", historica)
        db.registro("r-n", null)
        db.registro("r-p2", otraPropia, dirty = false)
        assertEquals(listOf("r-p2"), r.idsDeRegistros())

        assertTrue(r.guardarRegistro(r.registro("r-h")!!.copy(campos = mapOf("evaluacion" to "pedido r-h", "respuesta" to "normal")), enConsulta = propia))
        assertTrue(r.guardarRegistro(r.registro("r-n")!!.copy(campos = mapOf("evaluacion" to "cambiado")), enConsulta = propia))
        // La que ya es de otra propia se queda donde está: viaja con su consulta, no con esta.
        assertTrue(r.guardarRegistro(r.registro("r-p2")!!.copy(campos = mapOf("evaluacion" to "cambiado")), enConsulta = propia))

        assertEquals(propia, r.registro("r-h")!!.consultaId)
        assertEquals(propia, r.registro("r-n")!!.consultaId)
        assertEquals(otraPropia, r.registro("r-p2")!!.consultaId)
        assertEquals(historica, r.registro("r-h2")!!.consultaId, "la que no se tocó sigue en el histórico")
        assertEquals(listOf("r-h", "r-n", "r-p2"), r.idsDeRegistros())
        assertEquals(listOf("r-h", "r-n", "r-p2"), r.idsDeRegistros(soloSucios = true))
        assertFalse("r-h2" in r.idsDeRegistros())
    }

    @Test
    fun guardar_lo_mismo_una_fila_del_historico_no_la_reasigna_ni_la_marca() = conBase { db, r ->
        db.registro("r-h", historica, dirty = false)
        val registro = r.registro("r-h")!!
        val filaAntes = db.historiaClinicaQueries.registroPorId("r-h").lista { Triple(it.consulta_id, it.dirty, it.updated_at) }

        assertTrue(r.guardarRegistro(registro, enConsulta = propia))
        assertTrue(r.guardarRegistro(registro.copy(campos = registro.campos + ("respuesta" to "")), enConsulta = propia), "un campo vacío sobre nada tampoco")

        assertEquals(filaAntes, db.historiaClinicaQueries.registroPorId("r-h").lista { Triple(it.consulta_id, it.dirty, it.updated_at) })
        assertEquals(emptyList(), r.idsDeRegistros())
    }

    @Test
    fun sin_decir_en_que_consulta_se_edita_la_fila_conserva_la_suya() = conBase { db, r ->
        db.registro("r-h", historica)

        assertTrue(r.guardarRegistro(r.registro("r-h")!!.copy(campos = mapOf("evaluacion" to "cambiado"))))

        assertEquals(historica, r.registro("r-h")!!.consultaId)
        assertEquals(emptyList(), r.idsDeRegistros())
    }

    @Test
    fun una_fila_nueva_de_la_propia_nace_asociada_a_ella() = conBase { _, r ->
        val nueva = RegistroClinico("r-nueva", paciente, propia, "clinica", "examen_complementario", hoy, mapOf("evaluacion" to "pedido"))
        assertTrue(r.guardarRegistro(nueva, enConsulta = propia))

        assertEquals(listOf("r-nueva"), r.idsDeRegistros(soloSucios = true))
    }

    // ---------- Cambio real en valores de sección y examen ----------

    private data class FilaValor(val valor: String, val dirty: Long, val actualizada: Long)

    private suspend fun Salud360Db.valor(consulta: Id, seccion: String, campo: String) =
        historiaClinicaQueries.valoresDeSeccion(consulta, seccion).lista { FilaValor(it.valor, it.dirty, it.updated_at) to it.campo }
            .firstOrNull { it.second == campo }?.first

    private suspend fun Salud360Db.sembrarValor(consulta: Id, seccion: String, campo: String, valor: String, dirty: Boolean = false) =
        historiaClinicaQueries.upsertSeccionValor(SeccionValor(consulta, seccion, campo, valor).toRow(antes, dirty = dirty))

    @Test
    fun guardar_el_mismo_valor_no_marca_ni_mueve_nada_en_clinica() = conBase { db, r ->
        for (consulta in listOf(propia, historica)) {
            db.sembrarValor(consulta, "motivo_consulta", "texto", "Control de rutina")

            assertTrue(r.guardarValor(consulta, "motivo_consulta", "texto", "Control de rutina"))
            assertTrue(r.guardarSeccion(consulta, "motivo_consulta", mapOf("texto" to "Control de rutina")))

            assertEquals(FilaValor("Control de rutina", 0, antes), db.valor(consulta, "motivo_consulta", "texto"), consulta)
        }
    }

    @Test
    fun un_valor_distinto_se_escribe_y_se_marca() = conBase { db, r ->
        db.sembrarValor(propia, "motivo_consulta", "texto", "Control")

        assertTrue(r.guardarValor(propia, "motivo_consulta", "texto", "Control de rutina"))

        val fila = db.valor(propia, "motivo_consulta", "texto")!!
        assertEquals("Control de rutina", fila.valor)
        assertEquals(1L, fila.dirty)
        assertTrue(fila.actualizada > antes)
    }

    @Test
    fun un_valor_vacio_sobre_nada_no_crea_ninguna_fila_y_sobre_texto_se_escribe_vacio() = conBase { db, r ->
        assertTrue(r.guardarValor(propia, "motivo_consulta", "texto", ""))
        assertTrue(r.guardarValor(propia, "conductas", "texto", "   "))
        assertEquals(emptyList(), db.historiaClinicaQueries.valoresDeConsulta(propia).lista { it.seccion })

        db.sembrarValor(propia, "nota", "texto", "algo")
        assertTrue(r.guardarValor(propia, "nota", "texto", ""))
        val fila = db.valor(propia, "nota", "texto")!!
        assertEquals("", fila.valor)
        assertEquals(1L, fila.dirty, "el vaciado a propósito queda marcado para enviarse")
        assertTrue(fila.actualizada > antes)
    }

    @Test
    fun de_una_seccion_solo_se_escriben_los_campos_que_cambiaron() = conBase { db, r ->
        db.sembrarValor(propia, "nota", "texto", "igual")
        db.sembrarValor(propia, "nota", "otro", "se vacía")

        assertTrue(r.guardarSeccion(propia, "nota", mapOf("texto" to "igual", "otro" to "", "nuevo" to "campo nuevo", "vacio" to "")))

        assertEquals(FilaValor("igual", 0, antes), db.valor(propia, "nota", "texto"))
        assertEquals("", db.valor(propia, "nota", "otro")!!.valor)
        assertEquals(1L, db.valor(propia, "nota", "otro")!!.dirty)
        assertEquals(1L, db.valor(propia, "nota", "nuevo")!!.dirty)
        assertEquals(null, db.valor(propia, "nota", "vacio"), "un vacío sobre nada no crea fila")
    }

    @Test
    fun una_consulta_de_la_web_sigue_sin_escribirse() = conBase { db, r ->
        assertFalse(r.guardarValor(deLaWeb, "nota", "texto", "x"))
        assertFalse(r.guardarSeccion(deLaWeb, "nota", mapOf("texto" to "x")))
        assertEquals(emptyList(), db.historiaClinicaQueries.valoresDeConsulta(deLaWeb).lista { it.seccion })
    }

    @Test
    fun el_examen_fisico_igual_o_vacio_sobre_nada_no_se_escribe_y_un_cambio_si() = conBase { db, r ->
        fun filas() = db.historiaClinicaQueries.examenFisicoDeConsulta(propia)
        assertTrue(r.guardarExamenFisico(ExamenFisico(propia)))
        assertTrue(r.guardarExamenFisico(ExamenFisico(propia, peso = "0.00", talla = "0.00", tensionArterial = "0.00", imc = "0.00")))
        assertEquals(0, filas().lista { it.consulta_id }.size, "ni vacío ni 0.00 sobre nada crean la fila")

        assertTrue(r.guardarExamenFisico(ExamenFisico(propia, peso = "70", talla = "170")))
        val primera = filas().lista { it.dirty to it.updated_at }.single()
        assertEquals(1L, primera.first)

        // Repetir lo mismo (incluida una medida vacía escrita como 0.00) no toca la fila.
        db.historiaClinicaQueries.upsertExamenFisico(ExamenFisico(propia, peso = "70", talla = "170").toRow(antes, dirty = false))
        val limpia = filas().lista { it.dirty to it.updated_at }.single()
        assertEquals(0L to antes, limpia)
        assertTrue(r.guardarExamenFisico(ExamenFisico(propia, peso = "70", talla = "170", imc = "0.00")))
        assertEquals(limpia, filas().lista { it.dirty to it.updated_at }.single())

        // Un cambio real, y vaciar a propósito, sí.
        assertTrue(r.guardarExamenFisico(ExamenFisico(propia, peso = "71", talla = "170")))
        assertEquals(1L, filas().lista { it.dirty }.single())
        db.historiaClinicaQueries.upsertExamenFisico(ExamenFisico(propia, peso = "71", talla = "170").toRow(antes, dirty = false))
        assertTrue(r.guardarExamenFisico(ExamenFisico(propia, peso = "", talla = "170")))
        assertEquals(1L, filas().lista { it.dirty }.single())
        assertEquals("", filas().lista { it.peso }.single())
    }

    // ---------- Pediatría y las demás no cambian ----------

    @Test
    fun pediatria_sigue_marcando_en_cada_guardado_aunque_sea_lo_mismo() = conBase { db, r ->
        // Es lo que hacía todo antes de T18; el envío de pediatría lo necesita así.
        db.sembrarValor("ped-1", "nota", "texto", "igual")
        db.antecedente("a-ped", "asma", "ped-1", dirty = false, especialidad = "pediatria", categoria = "personales", detalle = "igual")
        db.historiaClinicaQueries.upsertRegistro(
            RegistroClinico("r-ped", paciente, "ped-1", "pediatria", "examen_complementario", hoy, mapOf("evaluacion" to "x")).toRow(antes, dirty = false),
        )

        assertTrue(r.guardarValor("ped-1", "nota", "texto", "igual"))
        assertTrue(r.guardarAntecedente(paciente, "pediatria", "personales", "asma", true, "igual", "ped-1"))
        assertTrue(r.guardarRegistro(r.registro("r-ped")!!, enConsulta = "ped-1"))
        // Y un vacío sobre nada se sigue escribiendo.
        assertTrue(r.guardarValor("ped-1", "nota", "vacio", ""))

        val valor = db.valor("ped-1", "nota", "texto")!!
        assertEquals(1L, valor.dirty)
        assertTrue(valor.actualizada > antes)
        assertEquals(1L, db.fila("asma", "pediatria")!!.dirty)
        assertEquals(1L, db.historiaClinicaQueries.registroPorId("r-ped").lista { it.dirty }.single())
        assertEquals(FilaValor("", 1, db.valor("ped-1", "nota", "vacio")!!.actualizada), db.valor("ped-1", "nota", "vacio"))
    }

    @Test
    fun una_consulta_sin_fila_se_sigue_escribiendo_como_antes() = conBase { db, r ->
        // Sin la consulta no se sabe de qué HC es: se guarda como siempre.
        assertTrue(r.guardarValor("no-existe", "nota", "texto", ""))
        assertEquals(1L, db.valor("no-existe", "nota", "texto")!!.dirty)
    }
}
