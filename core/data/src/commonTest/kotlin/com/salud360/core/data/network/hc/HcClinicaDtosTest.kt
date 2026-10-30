package com.salud360.core.data.network.hc

import com.salud360.core.data.network.TobbException
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las tres respuestas de lectura de hclinica (RF-38 a RF-41) leídas con ejemplos de JSON de
 * `md/API_SALUD360_CLINICA.md`, «Consultas (solo lectura)»: `hay_mas` como número, `null`, campos que faltan,
 * `omitidas`, `no_leidas` y claves de más. Todo inventado («Prueba Salud360»).
 *
 * Regla de lo que puede faltar (Q15, nunca borrar): lo que el servidor manda siempre y de lo que se saca «esto ya no
 * está en la web» —listas, mapas, `hay_mas`, `antecedentes_familiares`, `pendientes`— es obligatorio: si falta, la
 * respuesta es inesperada y no se aplica (una ausencia no puede leerse como «vacío»). Lo nuevo (`created_at`,
 * `antecedentes_familiares_perfil4`) y lo que solo avisa (`omitidas`, `no_leidas`) puede faltar.
 */
class HcClinicaDtosTest {
    private val cliente = HcApiClient("http://localhost", "clinica")
    private val api = ConsultasClinicaApiRemota(HcApiSoloLectura(cliente))

    private fun ok(texto: String): JsonObject = cliente.interpretar(200, texto)

    private fun lista(texto: String = EjemplosConsultasClinica.LISTA) = api.listaDe(ok(texto))

    private fun consulta(texto: String = EjemplosConsultasClinica.CONSULTA_COMPLETA) = api.consultaDe(ok(texto))

    private fun historia(texto: String = EjemplosConsultasClinica.HISTORIA) = api.historiaDe(ok(texto))

    private fun inesperada(bloque: () -> Unit) {
        val e = assertFailsWith<TobbException> { bloque() }
        assertEquals("respuesta", e.codigo)
    }

    // --- Lista ---

    @Test
    fun lista_del_md() {
        val l = lista()
        assertEquals(listOf(7005L, 7004L, 7003L), l.consultas.map { it.id })
        assertEquals(4, l.total)
        assertEquals(0, l.desde)
        assertEquals(OmitidasConsultasDto(foto = 1, otro = 1, sinFecha = 0), l.omitidas)

        assertEquals(
            ConsultaCabeceraDto(
                id = 7003, pacienteId = 5001, medicoId = 9, medicoNombre = "Médico Prueba Salud360",
                tipo = "control", tipoNumero = 1, fecha = "2026-03-10", createdAt = "2026-03-10 10:00:00",
                estado = "CERRADA", edadMostrar = "45 años", propia = true, updatedAt = "2026-03-10 10:30:00",
            ),
            l.consultas[2],
        )
        val ajena = l.consultas[1]
        assertEquals("telemedicina", ajena.tipo)
        assertEquals(4, ajena.tipoNumero)
        assertEquals(14L, ajena.medicoId)
        assertFalse(ajena.propia)
        assertEquals("", ajena.edadMostrar)
        assertEquals("ABIERTA", l.consultas[0].estado)
    }

    @Test
    fun hay_mas_llega_como_numero() {
        assertEquals(1, lista().hayMas)
        assertTrue(lista().quedanMas)

        val sinMas = lista(EjemplosConsultasClinica.LISTA.replace(""""hay_mas": 1""", """"hay_mas": 0"""))
        assertEquals(0, sinMas.hayMas)
        assertFalse(sinMas.quedanMas)
    }

    @Test
    fun lista_vacia() {
        val l = lista("""{"ok": true, "consultas": [], "total": 0, "desde": 0, "hay_mas": 0,
            "omitidas": {"foto": 0, "otro": 0, "sin_fecha": 0}}""")
        assertTrue(l.consultas.isEmpty())
        assertEquals(0, l.total)
        assertFalse(l.quedanMas)
        assertEquals(OmitidasConsultasDto(0, 0, 0), l.omitidas)
    }

    @Test
    fun created_at_puede_faltar_en_un_servidor_anterior() {
        val sin = EjemplosConsultasClinica.LISTA.replace(Regex(""""created_at": "[^"]*",\s*"""), "")
        assertFalse("created_at" in sin)
        val l = lista(sin)
        assertEquals(3, l.consultas.size)
        assertTrue(l.consultas.all { it.createdAt == null })
        // La fecha sigue estando.
        assertEquals("2026-09-01", l.consultas[0].fecha)
    }

    @Test
    fun opcionales_ausentes_o_nulos_de_la_cabecera() {
        val l = lista(
            """{"ok": true, "consultas": [
                {"id": 7010, "paciente_id": 5001, "medico_id": null, "medico_nombre": null, "fecha": "2026-01-02", "estado": "CERRADA",
                 "propia": false},
                {"id": 7011, "paciente_id": 5001, "fecha": "2026-01-03", "estado": "ABIERTA", "propia": true}],
              "total": 2, "desde": 0, "hay_mas": 0, "omitidas": {"foto": 0, "otro": 0, "sin_fecha": 0}}""",
        )
        for (c in l.consultas) {
            assertNull(c.medicoId)
            assertNull(c.medicoNombre)
            assertNull(c.tipo)
            assertNull(c.tipoNumero)
            assertNull(c.createdAt)
            assertNull(c.updatedAt)
            assertEquals("", c.edadMostrar)
        }
    }

    @Test
    fun omitidas_con_claves_que_faltan_o_sobran() {
        val l = lista(EjemplosConsultasClinica.LISTA.replace(""""omitidas": {"foto": 1, "otro": 1, "sin_fecha": 0}""", """"omitidas": {"foto": 2, "baja": 5}"""))
        assertEquals(OmitidasConsultasDto(foto = 2, otro = 0, sinFecha = 0), l.omitidas)

        // Sin `omitidas` (solo avisa): cero.
        val sin = EjemplosConsultasClinica.LISTA.replace(Regex(""",\s*"omitidas": \{[^}]*\}"""), "")
        assertFalse("omitidas" in sin)
        assertEquals(OmitidasConsultasDto(0, 0, 0), lista(sin).omitidas)
    }

    @Test
    fun ids_como_entero_largo() {
        val l = lista(EjemplosConsultasClinica.LISTA.replace(""""id": 7005""", """"id": 3000000005"""))
        assertEquals(3_000_000_005L, l.consultas[0].id)
    }

    @Test
    fun lista_ignora_las_claves_de_mas() {
        val conDeMas = EjemplosConsultasClinica.LISTA
            .replace(""""total": 4""", """"total": 4, "futuro": {"a": 1}, "edad_meses": 0""")
            .replace(""""propia": true, "updated_at": "2026-09-01 09:20:00"""", """"propia": true, "updated_at": "2026-09-01 09:20:00", "edad_meses": 540, "otra": [1, 2]""")
        assertEquals(lista(), lista(conDeMas))
    }

    @Test
    fun lista_incompleta_es_respuesta_inesperada() {
        // Sin `hay_mas` la app no sabría si la lista llega hasta el final: marcaría de menos o de más (RF-63).
        inesperada { lista(EjemplosConsultasClinica.LISTA.replace(""", "hay_mas": 1""", "")) }
        inesperada { lista(EjemplosConsultasClinica.LISTA.replace(""""total": 4, """, "")) }
        inesperada { lista(EjemplosConsultasClinica.LISTA.replace(""""desde": 0, """, "")) }
        inesperada { lista("""{"ok": true, "total": 0, "desde": 0, "hay_mas": 0}""") }
        // Una cabecera sin lo imprescindible.
        inesperada { lista(EjemplosConsultasClinica.LISTA.replace(""""id": 7005, """, "")) }
        inesperada { lista(EjemplosConsultasClinica.LISTA.replace(""""estado": "ABIERTA",""", "")) }
        inesperada { lista(EjemplosConsultasClinica.LISTA.replace(""""fecha": "2026-09-01",""", "")) }
        inesperada { lista(EjemplosConsultasClinica.LISTA.replace(""""propia": true, "updated_at": "2026-09-01 09:20:00"""", """"updated_at": "2026-09-01 09:20:00"""")) }
        // Una lista que no es lista.
        inesperada { lista(EjemplosConsultasClinica.LISTA.replace(""""consultas": [""", """"consultas": 7, "x": [""")) }
    }

    // --- Una consulta ---

    @Test
    fun consulta_completa_del_md() {
        val c = consulta()
        assertEquals(7003L, c.consulta.id)
        assertEquals("CERRADA", c.consulta.estado)

        assertEquals(TextoDto("Prueba Salud360 motivo (a)"), c.secciones.motivoConsulta)
        assertEquals(TextoDto("Prueba Salud360 datos subjetivos (a)"), c.secciones.datosSubjetivos)
        assertEquals(TextoDto("Prueba Salud360 datos objetivos (a)"), c.secciones.datosObjetivos)
        assertEquals(TextoDto("Prueba Salud360 conductas (a)"), c.secciones.conductas)
        assertEquals(TextoDto("Prueba Salud360 nota (a)"), c.secciones.nota)
        assertEquals(TextoDto("Prueba Salud360 hábitos (a): tabaco, alcohol, ejercicio"), c.secciones.habitos)
        assertEquals(TextoDto("Prueba Salud360 diuresis y catarsis (a)"), c.secciones.diuresisCatarsis)
        assertEquals(TextoDto("Prueba Salud360 somnia (a)"), c.secciones.somnia)

        // Alimentación `"0|detalle"` / `"1|detalle"`, serologías `"SI"` / `"NO"` y Child-Pugh `"1"` a `"3"`: crudos.
        assertEquals(
            mapOf(
                "hiposodica" to "1|Prueba Salud360 hiposódica (a)",
                "diabetes" to "0|Prueba Salud360 sin diabetes",
                "celiaco" to "1|Prueba Salud360 celíaco detalle (a)",
            ),
            c.secciones.alimentacion,
        )
        assertEquals(mapOf("igm_hav" to "SI", "ags_hbv" to "NO", "hiv" to "NO"), c.secciones.serologias)
        assertEquals(
            mapOf("ascitis" to "2", "bilirrubina" to "1", "albumina" to "3", "tp" to "2", "encefalopatia" to "1"),
            c.secciones.childPugh,
        )

        assertEquals(
            ExamenFisicoDto(peso = "70.5", talla = "170", tensionArterial = "120/80", imc = "24.4", nota = "Prueba Salud360 examen físico (a)"),
            c.examenFisico,
        )
        assertEquals(emptyList(), c.noLeidas)
    }

    @Test
    fun consulta_abierta_con_poco_y_valores_sin_cargar_crudos() {
        val c = consulta(EjemplosConsultasClinica.CONSULTA_ABIERTA)
        assertEquals("ABIERTA", c.consulta.estado)
        assertEquals(TextoDto("Prueba Salud360 motivo (c)"), c.secciones.motivoConsulta)
        assertNull(c.secciones.datosSubjetivos)
        assertNull(c.secciones.habitos)
        assertTrue(c.secciones.alimentacion.isEmpty())
        assertTrue(c.secciones.serologias.isEmpty())
        assertTrue(c.secciones.childPugh.isEmpty())
        // El servidor manda `"0.0"` e `"0"` como datos: el DTO no los filtra.
        assertEquals(ExamenFisicoDto(talla = "0.0", imc = "0"), c.examenFisico)
        assertNull(c.examenFisico.peso)
    }

    @Test
    fun consulta_con_pdf_trae_el_aviso_y_el_examen_vacio() {
        val c = consulta(EjemplosConsultasClinica.CONSULTA_CON_PDF)
        assertEquals(listOf("documentos"), c.noLeidas)
        assertEquals(ExamenFisicoDto(), c.examenFisico)
        assertEquals(setOf("motivo_consulta"), c.secciones.textos().keys)
    }

    @Test
    fun secciones_y_examen_vacios_son_objetos_vacios() {
        val c = consulta(
            """{"ok": true, "consulta": ${EjemplosConsultasClinica.CABECERA_A}, "secciones": {}, "examen_fisico": {}, "no_leidas": []}""",
        )
        assertTrue(c.secciones.textos().isEmpty())
        assertTrue(c.secciones.alimentacion.isEmpty())
        assertEquals(ExamenFisicoDto(), c.examenFisico)
        assertEquals(emptyList(), c.noLeidas)
    }

    @Test
    fun textos_por_id_de_seccion_del_catalogo() {
        val textos = consulta().secciones.textos()
        assertEquals(
            mapOf(
                "motivo_consulta" to "Prueba Salud360 motivo (a)",
                "datos_subjetivos" to "Prueba Salud360 datos subjetivos (a)",
                "datos_objetivos" to "Prueba Salud360 datos objetivos (a)",
                "conductas" to "Prueba Salud360 conductas (a)",
                "nota" to "Prueba Salud360 nota (a)",
                "habitos" to "Prueba Salud360 hábitos (a): tabaco, alcohol, ejercicio",
                "diuresis_catarsis" to "Prueba Salud360 diuresis y catarsis (a)",
                "somnia" to "Prueba Salud360 somnia (a)",
            ),
            textos,
        )
    }

    @Test
    fun los_no_leidas_conocidos_y_los_nuevos_quedan_crudos() {
        val c = consulta(
            EjemplosConsultasClinica.CONSULTA_CON_PDF
                .replace("""["documentos"]""", """["documentos", "audio", "fotos", "antecedentes_personales", "algo_nuevo"]"""),
        )
        assertEquals(listOf("documentos", "audio", "fotos", "antecedentes_personales", "algo_nuevo"), c.noLeidas)
    }

    @Test
    fun consulta_sin_no_leidas_solo_pierde_el_aviso() {
        val c = consulta(EjemplosConsultasClinica.CONSULTA_CON_PDF.replace(""", "no_leidas": ["documentos"]""", ""))
        assertEquals(emptyList(), c.noLeidas)
    }

    @Test
    fun consulta_incompleta_es_respuesta_inesperada() {
        inesperada { consulta(EjemplosConsultasClinica.CONSULTA_CON_PDF.replace(""""examen_fisico": {}, """, "")) }
        inesperada { consulta(EjemplosConsultasClinica.CONSULTA_CON_PDF.replace(""""secciones": {"motivo_consulta": {"texto": "Prueba Salud360 motivo (f)"}},""", "")) }
        inesperada { consulta("""{"ok": true, "secciones": {}, "examen_fisico": {}, "no_leidas": []}""") }
        inesperada { consulta(EjemplosConsultasClinica.CONSULTA_CON_PDF.replace(""""texto": "Prueba Salud360 motivo (f)"""", """"otro": "x"""")) }
    }

    @Test
    fun consulta_ignora_las_claves_de_mas() {
        val conDeMas = EjemplosConsultasClinica.CONSULTA_COMPLETA
            .replace(""""somnia": {"texto": "Prueba Salud360 somnia (a)"},""", """"somnia": {"texto": "Prueba Salud360 somnia (a)", "autor": "x"}, "diagnosticos": {"texto": "no"},""")
            .replace(""""no_leidas": []""", """"no_leidas": [], "edad_meses": 0""")
            .replace(""""imc": "24.4",""", """"imc": "24.4", "frecuencia_cardiaca": "70",""")
        assertEquals(consulta(), consulta(conDeMas))
    }

    @Test
    fun los_valores_numericos_de_json_se_leen_como_cadenas() {
        // El servidor manda cadenas; si alguna vez mandara un número, no se rompe la respuesta entera.
        val c = consulta(
            EjemplosConsultasClinica.CONSULTA_COMPLETA
                .replace(""""peso": "70.5"""", """"peso": 70.5""")
                .replace(""""ascitis": "2"""", """"ascitis": 2"""),
        )
        assertEquals("70.5", c.examenFisico.peso)
        assertEquals("2", c.secciones.childPugh["ascitis"])
    }

    // --- Historial del paciente ---

    @Test
    fun historia_del_md() {
        val h = historia()
        assertEquals(5001L, h.pacienteId)

        val af = assertNotNull(h.antecedentesFamiliares)
        assertEquals(CasillaAntecedenteDto(flag = 1, detalle = "Prueba Salud360 hta detalle"), af.casillas["hta"])
        assertEquals(CasillaAntecedenteDto(flag = 1, detalle = null), af.casillas["dbt_ir"])
        assertEquals(CasillaAntecedenteDto(flag = 1, detalle = "Prueba Salud360 otro af"), af.casillas["otro"])
        assertEquals(setOf("hta", "dbt_ir", "otro"), af.casillas.keys)
        assertEquals("Prueba Salud360 nota antecedentes", af.nota)
        assertEquals(NotaPerfil4Dto("Prueba Salud360 nota del perfil 4"), h.antecedentesFamiliaresPerfil4)

        assertEquals(
            listOf(
                CargaLaboratorioDto(
                    id = 703, consultaId = 7003, fecha = "2026-03-10",
                    valores = mapOf("hto" to "41", "hb" to "13.9", "alb_mina" to "4.1", "f_sforo" to "3.5", "otro" to "Prueba Salud360 otro lab 2"),
                ),
            ),
            h.laboratorios.laboratorioGeneral,
        )
        assertEquals(
            listOf(
                CargaLaboratorioDto(
                    19, 7003, "2026-03-10",
                    mapOf("hemograma" to "normal", "glucemia" to "97", "otros" to "Prueba Salud360 otro reducido"),
                ),
            ),
            h.laboratorios.laboratorioReducido,
        )
        assertEquals(listOf(CargaLaboratorioDto(25, 7003, "2026-03-10", mapOf("peso" to "70.5"))), h.laboratorios.peso)

        assertEquals(
            listOf(
                EstudioDto(1801, 7003, "2026-02-01", "Prueba Salud360 ecografía abdominal (a)"),
                EstudioDto(1802, 7006, null, "Prueba Salud360 ecografía abdominal (f) sin fecha"),
            ),
            h.estudios.ecoAbdominal,
        )
        assertEquals(listOf(EstudioDto(1797, 7003, "2026-02-02", "Prueba Salud360 videoendoscopia (a)")), h.estudios.veda)
        assertEquals(listOf(EstudioDto(1797, 7003, "2026-02-03", "Prueba Salud360 biopsia hepática (a)")), h.estudios.biopsiaHepatica)
        assertEquals(emptyList(), h.estudios.estudiosCardiologicos)
        assertEquals(listOf(EstudioDto(1797, 7003, "2026-02-04", "Prueba Salud360 TC/RMN (a)")), h.estudios.tcRmn)

        assertEquals(
            listOf(
                MotivoDto(1859, 7005, "2026-09-01", "Prueba Salud360 motivo (c)"),
                MotivoDto(1857, 7003, "2026-03-10", "Prueba Salud360 motivo (a)"),
            ),
            h.motivos,
        )
        assertEquals(
            listOf(
                ExamenComplementarioDto(22, 7003, "2026-03-10", "Prueba Salud360 examen solicitado 1", "Prueba Salud360 examen respondido 1", 1),
                ExamenComplementarioDto(23, 7003, "2026-03-10", "Prueba Salud360 examen solicitado 2", null, 2),
            ),
            h.examenesComplementarios,
        )
        assertEquals(
            listOf(
                InterconsultaDto(
                    14, 7003, "2026-03-10", "Prueba Salud360 especialista 1",
                    "Prueba Salud360 interconsulta solicitada 1", "Prueba Salud360 interconsulta respondida 1", 1,
                ),
            ),
            h.interconsultas,
        )
        assertEquals(PendienteDto(1744, "Prueba Salud360 pendiente"), h.pendientes)
        assertEquals(OmitidasHistoriaDto(laboratorioSinFecha = 1), h.omitidas)
    }

    @Test
    fun los_ids_de_estudios_se_repiten_entre_tablas_y_quedan_crudos() {
        val e = historia().estudios
        assertEquals(1797L, e.veda.single().id)
        assertEquals(1797L, e.biopsiaHepatica.single().id)
        assertEquals(1797L, e.tcRmn.single().id)
    }

    @Test
    fun historia_vacia_con_null_y_listas_vacias() {
        val h = historia(EjemplosConsultasClinica.HISTORIA_VACIA)
        assertEquals(5002L, h.pacienteId)
        assertNull(h.antecedentesFamiliares)
        assertNull(h.antecedentesFamiliaresPerfil4)
        assertNull(h.pendientes)
        assertTrue(h.laboratorios.laboratorioGeneral.isEmpty())
        assertTrue(h.laboratorios.laboratorioReducido.isEmpty())
        assertTrue(h.laboratorios.peso.isEmpty())
        assertTrue(h.estudios.ecoAbdominal.isEmpty())
        assertTrue(h.estudios.veda.isEmpty())
        assertTrue(h.estudios.biopsiaHepatica.isEmpty())
        assertTrue(h.estudios.estudiosCardiologicos.isEmpty())
        assertTrue(h.estudios.tcRmn.isEmpty())
        assertTrue(h.motivos.isEmpty())
        assertTrue(h.examenesComplementarios.isEmpty())
        assertTrue(h.interconsultas.isEmpty())
        assertEquals(OmitidasHistoriaDto(0), h.omitidas)
    }

    @Test
    fun perfil4_puede_faltar_o_ser_null() {
        val sin = EjemplosConsultasClinica.HISTORIA.replace(""""antecedentes_familiares_perfil4": {"nota": "Prueba Salud360 nota del perfil 4"},""", "")
        assertFalse("perfil4" in sin)
        assertNull(historia(sin).antecedentesFamiliaresPerfil4)
        // Lo demás se lee igual.
        assertEquals(historia().copy(antecedentesFamiliaresPerfil4 = null), historia(sin))

        val nulo = EjemplosConsultasClinica.HISTORIA.replace("""{"nota": "Prueba Salud360 nota del perfil 4"}""", "null")
        assertNull(historia(nulo).antecedentesFamiliaresPerfil4)
    }

    @Test
    fun antecedentes_familiares_sin_nota_con_casillas_en_cero_y_claves_nuevas() {
        val h = historia(
            EjemplosConsultasClinica.HISTORIA.replace(
                Regex(""""antecedentes_familiares": \{.*?"nota": "Prueba Salud360 nota antecedentes"\}""", RegexOption.DOT_MATCHES_ALL),
                """"antecedentes_familiares": {"acv": {"flag": 0, "detalle": "Prueba Salud360 acv aclaración"},
                    "casilla_nueva": {"flag": 1, "otro": 5}, "dependiente_terceros": {"flag": 1}, "marca": 7}""",
            ),
        )
        val af = assertNotNull(h.antecedentesFamiliares)
        assertNull(af.nota)
        // El 0 con detalle es un dato (la web lo guarda así); una casilla que la app no conoce no rompe la lectura.
        assertEquals(CasillaAntecedenteDto(0, "Prueba Salud360 acv aclaración"), af.casillas["acv"])
        assertEquals(CasillaAntecedenteDto(1, null), af.casillas["casilla_nueva"])
        assertEquals(CasillaAntecedenteDto(1, null), af.casillas["dependiente_terceros"])
        // Un valor suelto que no es una casilla ni la nota se ignora.
        assertFalse("marca" in af.casillas)
    }

    @Test
    fun antecedentes_con_solo_nota() {
        val h = historia(
            EjemplosConsultasClinica.HISTORIA.replace(
                Regex(""""antecedentes_familiares": \{.*?"nota": "Prueba Salud360 nota antecedentes"\}""", RegexOption.DOT_MATCHES_ALL),
                """"antecedentes_familiares": {"nota": "Prueba Salud360 solo nota"}""",
            ),
        )
        val af = assertNotNull(h.antecedentesFamiliares)
        assertTrue(af.casillas.isEmpty())
        assertEquals("Prueba Salud360 solo nota", af.nota)
    }

    @Test
    fun cargas_de_laboratorio_con_consulta_o_fecha_nulas_y_valores_numericos() {
        val h = historia(
            EjemplosConsultasClinica.HISTORIA.replace(
                """{"id": 25, "consulta_id": 7003, "fecha": "2026-03-10", "valores": {"peso": "70.5"}}""",
                """{"id": 26, "consulta_id": null, "fecha": null, "valores": {"peso": 71}}""",
            ),
        )
        assertEquals(listOf(CargaLaboratorioDto(26, null, null, mapOf("peso" to "71"))), h.laboratorios.peso)
    }

    @Test
    fun estudio_largo_entra_entero() {
        // El servidor ya corta en 191 en cardiológicos; el DTO no recorta nada.
        val largo = "Prueba Salud360 " + "x".repeat(175)
        assertEquals(191, largo.length)
        val h = historia(
            EjemplosConsultasClinica.HISTORIA.replace(""""estudios_cardiologicos": []""", """"estudios_cardiologicos": [{"id": 733, "consulta_id": 7003, "fecha": "2026-02-20", "detalle": "$largo"}]"""),
        )
        assertEquals(largo, h.estudios.estudiosCardiologicos.single().detalle)
    }

    @Test
    fun examen_o_interconsulta_sin_opcionales() {
        val h = historia(
            EjemplosConsultasClinica.HISTORIA
                .replace(Regex("""\{"id": 23,.*?"numero": 2\}""", RegexOption.DOT_MATCHES_ALL), """{"id": 24}""")
                .replace(Regex(""""especialista".*?"numero": 1""", RegexOption.DOT_MATCHES_ALL), """"numero": 1"""),
        )
        assertEquals(ExamenComplementarioDto(24, null, null, null, null, null), h.examenesComplementarios[1])
        assertEquals(InterconsultaDto(14, 7003, "2026-03-10", null, null, null, 1), h.interconsultas.single())
    }

    @Test
    fun omitidas_de_la_historia_pueden_faltar() {
        val sin = EjemplosConsultasClinica.HISTORIA.replace(Regex(""",\s*"omitidas": \{[^}]*\}"""), "")
        assertFalse("omitidas" in sin)
        assertEquals(OmitidasHistoriaDto(0), historia(sin).omitidas)
    }

    @Test
    fun historia_ignora_las_claves_de_mas() {
        val conDeMas = EjemplosConsultasClinica.HISTORIA
            .replace(""""paciente_id": 5001,""", """"paciente_id": 5001, "version": 3,""")
            .replace(""""pendientes": {"id": 1744, "texto": "Prueba Salud360 pendiente"}""", """"pendientes": {"id": 1744, "texto": "Prueba Salud360 pendiente", "fecha": "2026-01-01"}, "diagnosticos": []""")
            .replace(""""estudios_cardiologicos": [],""", """"estudios_cardiologicos": [], "nuevo_estudio": [],""")
        assertEquals(historia(), historia(conDeMas))
    }

    @Test
    fun historia_incompleta_es_respuesta_inesperada() {
        // Una ausencia no puede leerse como «la web ya no lo tiene» (RF-63): `null` sí, pero explícito.
        val vacia = EjemplosConsultasClinica.HISTORIA_VACIA
        inesperada { historia(vacia.replace(""""antecedentes_familiares": null,""", "")) }
        inesperada { historia(vacia.replace(""""pendientes": null, """, "")) }
        inesperada { historia(vacia.replace(""""motivos": [], """, "")) }
        inesperada { historia(vacia.replace(""""examenes_complementarios": [], """, "")) }
        inesperada { historia(vacia.replace(""""interconsultas": [],""", "")) }
        inesperada { historia(vacia.replace(""""laboratorios": {"laboratorio_general": [], "laboratorio_reducido": [], "peso": []},""", "")) }
        inesperada { historia(vacia.replace(""""peso": []""", """"x": []""")) }
        inesperada { historia(vacia.replace(""", "tc_rmn": []""", "")) }
        inesperada { historia(vacia.replace(""""estudios": {"eco_abdominal": [], "veda": [], "biopsia_hepatica": [], "estudios_cardiologicos": [], "tc_rmn": []},""", "")) }
        inesperada { historia(vacia.replace(""""paciente_id": 5002,""", "")) }
        // Y una carga o un estudio sin lo imprescindible.
        inesperada { historia(EjemplosConsultasClinica.HISTORIA.replace(""""valores": {"peso": "70.5"}""", """"x": 1""")) }
        inesperada { historia(EjemplosConsultasClinica.HISTORIA.replace(""", "detalle": "Prueba Salud360 videoendoscopia (a)"""", "")) }
        inesperada { historia(EjemplosConsultasClinica.HISTORIA.replace(""""texto": "Prueba Salud360 pendiente"""", """"x": "y"""")) }
    }

    // --- Errores del contrato (E4.2) ---

    private fun fallo(status: Int, cuerpo: String): TobbException =
        assertFailsWith<TobbException> { cliente.interpretar(status, cuerpo) }

    @Test
    fun los_errores_de_las_tres_rutas_llegan_con_status_y_codigo() {
        for ((status, codigo) in listOf(
            403 to "fuera_de_cartera", 403 to "sin_permiso", 404 to "paciente_no_encontrado", 404 to "consulta_no_encontrada",
            422 to "datos_invalidos", 500 to "consultas",
            403 to "hc_no_habilitada", 403 to "medico_no_vinculado", 403 to "licencia_vencida", 401 to "sin_token", 503 to "tobb_caido",
        )) {
            val e = fallo(status, """{"ok": false, "mensaje": "Texto de la web.", "codigo": "$codigo"}""")
            assertEquals(status, e.status)
            assertEquals(codigo, e.codigo)
            assertEquals("Texto de la web.", e.message)
        }
    }

    @Test
    fun el_429_de_laravel_llega_sin_codigo_y_con_su_status() {
        // Sin `ok` ni `codigo`: la app lo reconoce por el estado (RF-56); no se reintenta sola (RF-57).
        val e = fallo(429, """{"message": "Too Many Attempts."}""")
        assertEquals(429, e.status)
        assertNull(e.codigo)
        assertEquals("Too Many Attempts.", e.message)

        // Y con un cuerpo que no es JSON (el 429 de un proxy): sigue el status, sin código.
        val html = fallo(429, "<html>Too Many Requests</html>")
        assertEquals(429, html.status)
        assertNull(html.codigo)
    }

    @Test
    fun un_cuerpo_cortado_o_que_no_es_json_es_error_y_no_una_lista_vacia() {
        val e = fallo(200, """{"ok": true, "consultas": [{"id": 7003, "paci""")
        assertEquals(200, e.status)
        assertNull(e.codigo)
    }
}
