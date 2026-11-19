package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.CargaLaboratorioDto
import com.salud360.core.data.network.hc.CasillaAntecedenteDto
import com.salud360.core.data.network.hc.AntecedentesFamiliaresDto
import com.salud360.core.data.network.hc.ConsultaCabeceraDto
import com.salud360.core.data.network.hc.EjemplosConsultasClinica
import com.salud360.core.data.network.hc.EstudioDto
import com.salud360.core.data.network.hc.ExamenComplementarioDto
import com.salud360.core.data.network.hc.ExamenFisicoDto
import com.salud360.core.data.network.hc.HcPacienteFicha
import com.salud360.core.data.network.hc.NotaPerfil4Dto
import com.salud360.core.data.network.hc.PendienteDto
import com.salud360.core.data.network.hc.MotivoDto
import com.salud360.core.model.TobbIds
import com.salud360.core.model.especialidad.AnalitoDef
import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.CategoriaAntecedentes
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.ItemAntecedente
import com.salud360.core.model.especialidad.LaboratorioDef
import com.salud360.core.model.especialidad.RegistroDef
import com.salud360.core.model.especialidad.SeccionCatalogo
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.MedicoPreferencia
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.newId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ids locales y conversión pura de las respuestas de lectura de la HC general (spec 002, E6.2; RF-40 a RF-42, RF-52 a
 * RF-55). Todo con los ejemplos inventados de [EjemplosConsultasClinica]: ni red, ni base.
 */
class LecturaClinicaTest {
    private val ejemplos = RespuestasClinicaDeEjemplo
    private val paciente = "hc-clinica-p5001"
    private val medico = "tobb-m9"
    private val consultasLocales = setOf("hcc-c7003", "hcc-c7004", "hcc-c7005")

    private fun cabeceraA() = ejemplos.consulta().consulta
    private fun cabeceraB() = ejemplos.lista().consultas[1]

    // --- Ids ---

    @Test
    fun ids_del_plan() {
        assertEquals("hcc-", PREFIJO_ID_WEB)
        assertEquals("hcc-c7003", idConsultaWeb(7003))
        assertEquals("hcc-m14", idMedicoWeb(14))
        assertEquals("hcc-llaboratorio_general-703", idCargaWeb(TIPO_LABORATORIO_GENERAL, 703))
        assertEquals("hcc-lpeso-25", idCargaWeb(TIPO_PESO, 25))
        assertEquals("hcc-rveda-1797", idFilaWeb("veda", 1797))
        assertEquals("hcc-rinterconsulta-14", idFilaWeb(TIPO_INTERCONSULTA, 14))
        assertEquals("hcc-p1744", idPendienteWeb(1744))
        assertEquals("hcc-a5001-hta", idAntecedenteWeb(5001, "hta"))
    }

    @Test
    fun los_estudios_con_el_mismo_numero_no_chocan_entre_tablas() {
        // veda, biopsia y tc_rmn comparten el 1797 en el fixture del servidor.
        val ids = TIPOS_ESTUDIO_WEB.map { idFilaWeb(it, 1797) }
        assertEquals(TIPOS_ESTUDIO_WEB.size, ids.toSet().size)
        assertEquals(setOf("hcc-rveda-1797", "hcc-rbiopsia_hepatica-1797", "hcc-rtc_rmn-1797"), ids.filter { "veda" in it || "biopsia" in it || "tc_rmn" in it }.toSet())
        // Y entre una carga de laboratorio, un estudio y una consulta con el mismo número.
        assertEquals(3, setOf(idConsultaWeb(703), idCargaWeb(TIPO_LABORATORIO_GENERAL, 703), idPendienteWeb(703)).size)
    }

    @Test
    fun ningun_id_choca_entre_tipos_ni_entre_pacientes_ni_con_otras_historias() {
        val numeros = listOf(1L, 2L, 14L, 22L, 25L, 703L, 1744L, 1797L, 1801L, 5001L, 5002L, 99_999L, 2_147_483_647L, 9_000_000_000L)
        val tiposCarga = listOf(TIPO_LABORATORIO_GENERAL, TIPO_LABORATORIO_REDUCIDO, TIPO_PESO)
        val tiposFila = TIPOS_ESTUDIO_WEB + TIPO_EXAMEN_COMPLEMENTARIO + TIPO_INTERCONSULTA
        val claves = listOf("hta", "dbt_nir", "dbt_ir", "dependiente_terceros", "otro", CLAVE_NOTA, CLAVE_NOTA_PERFIL4)
        val ids = buildList {
            for (n in numeros) {
                add(idConsultaWeb(n)); add(idMedicoWeb(n)); add(idPendienteWeb(n))
                tiposCarga.forEach { add(idCargaWeb(it, n)) }
                tiposFila.forEach { add(idFilaWeb(it, n)) }
                claves.forEach { add(idAntecedenteWeb(n, it)) } // el paciente es el de la web: otro paciente, otro id
            }
        }
        assertEquals(ids.size, ids.toSet().size, "dos entradas distintas con el mismo id")
        assertTrue(ids.all { it.startsWith(PREFIJO_ID_WEB) })

        // Ni con los ids de pediatría, ni con los del paciente de clínica, ni con los de turnos, con los mismos números.
        val ajenos = numeros.flatMap { n -> listOf("hcp-c$n", "hc-clinica-p$n", TobbIds.paciente(n), TobbIds.medico(n), TobbIds.usuario(n)) }
        assertTrue(ids.none { it in ajenos })
        assertTrue(ids.none { it.startsWith("hcp-") || it.startsWith("hc-clinica-p") || it.startsWith(TobbIds.PREFIJO) })
        assertTrue(ajenos.none { it.startsWith(PREFIJO_ID_WEB) })
        assertEquals("hc-clinica-p5001", idLocalDeFicha("clinica", HcPacienteFicha(id = 5001)))
        assertFalse(idLocalDeFicha("clinica", HcPacienteFicha(id = 5001)).startsWith(PREFIJO_ID_WEB))
    }

    @Test
    fun un_id_generado_en_el_dispositivo_nunca_empieza_con_el_prefijo_de_la_web() {
        // Los ids locales son UUID (hexadecimal y guiones): la `h` de `hcc-` no puede aparecer.
        repeat(500) { assertFalse(newId().startsWith(PREFIJO_ID_WEB)) }
    }

    @Test
    fun los_ids_son_deterministas_traer_dos_veces_da_lo_mismo() {
        val a = ejemplos.historia()
        val b = ejemplos.historia()
        assertEquals(laboratoriosDeLaWeb(paciente, a.laboratorios, consultasLocales).map { it.id }, laboratoriosDeLaWeb(paciente, b.laboratorios, consultasLocales).map { it.id })
        assertEquals(registrosDeLaWeb(paciente, a, consultasLocales).map { it.id }, registrosDeLaWeb(paciente, b, consultasLocales).map { it.id })
        assertEquals(antecedentesDeLaWeb(paciente, a).map { it.id }, antecedentesDeLaWeb(paciente, b).map { it.id })
        assertEquals(consultaDeLaWeb(cabeceraA(), paciente, medico), consultaDeLaWeb(cabeceraA(), paciente, medico))
        // El id no depende de quién lo pide ni de dónde se guarda: solo de lo que la web mandó.
        assertEquals(
            antecedentesDeLaWeb(paciente, a).map { it.id },
            antecedentesDeLaWeb("tobb-p77", a).map { it.id },
        )
    }

    // --- esDeLaWeb y esPropia ---

    @Test
    fun una_consulta_convertida_es_de_la_web_y_una_del_dispositivo_no() {
        val web = consultaDeLaWeb(cabeceraA(), paciente, medico)!!
        assertTrue(web.esDeLaWeb())
        val local = Consulta(newId(), paciente, medico, "clinica", "control", LocalDate(2026, 3, 10))
        assertFalse(local.esDeLaWeb())
        // Por prefijo aunque todavía no tenga remoto_id, y por remoto_id aunque el id sea otro.
        assertTrue(web.copy(remotoId = "").esDeLaWeb())
        assertTrue(local.copy(remotoId = "7003").esDeLaWeb())
    }

    @Test
    fun es_propia_solo_si_la_cargo_el_medico_de_la_sesion() {
        val propia = consultaDeLaWeb(cabeceraA(), paciente, medico)!!
        val ajena = consultaDeLaWeb(cabeceraB(), paciente, medico)!!
        assertTrue(propia.esPropia(medico))
        assertFalse(ajena.esPropia(medico))
        // Otro médico en el mismo dispositivo la ve distinto: lo deriva el medicoId, sin columna.
        assertFalse(propia.esPropia("tobb-m10"))
        assertFalse(ajena.esPropia(ajena.medicoId.removePrefix(PREFIJO_ID_WEB)))
    }

    // --- Consulta ---

    @Test
    fun consulta_propia() {
        val c = consultaDeLaWeb(cabeceraA(), paciente, medico)
        assertEquals(
            Consulta(
                id = "hcc-c7003", pacienteId = paciente, medicoId = medico, especialidad = "clinica", tipo = "control",
                fecha = LocalDate(2026, 3, 10), estado = EstadoConsulta.CERRADA, edadMostrar = "45 años",
                updatedAt = null, remotoId = "7003",
            ),
            c,
        )
        assertNull(nombreMedicoDeLaWeb(cabeceraA()), "la propia no guarda el nombre: es el de la sesión")
    }

    @Test
    fun consulta_ajena_guarda_el_medico_de_la_web_y_su_nombre() {
        val c = consultaDeLaWeb(cabeceraB(), paciente, medico)!!
        assertEquals("hcc-m14", c.medicoId)
        assertEquals("telemedicina", c.tipo)
        assertEquals(LocalDate(2026, 4, 14), c.fecha)
        assertEquals("", c.edadMostrar)
        assertEquals(MedicoPreferencia("hcc-m14", "nombre", "Otro Médico Prueba Salud360"), nombreMedicoDeLaWeb(cabeceraB()))
        assertNotEquals(medico, c.medicoId)
    }

    @Test
    fun consulta_abierta() {
        val c = consultaDeLaWeb(ejemplos.lista().consultas[0], paciente, medico)!!
        assertEquals("hcc-c7005", c.id)
        assertEquals(EstadoConsulta.ABIERTA, c.estado)
    }

    @Test
    fun una_ajena_sin_medico_no_se_inventa_pero_nunca_es_la_de_la_sesion() {
        val sinMedico = cabeceraB().copy(medicoId = null, medicoNombre = null)
        val c = consultaDeLaWeb(sinMedico, paciente, medico)!!
        assertEquals(MEDICO_WEB_DESCONOCIDO, c.medicoId)
        assertFalse(c.esPropia(medico))
        assertNull(nombreMedicoDeLaWeb(sinMedico))
    }

    @Test
    fun el_nombre_vacio_o_de_espacios_no_se_guarda() {
        assertNull(nombreMedicoDeLaWeb(cabeceraB().copy(medicoNombre = "")))
        assertNull(nombreMedicoDeLaWeb(cabeceraB().copy(medicoNombre = "   ")))
        assertEquals("Ñandú 🙂", nombreMedicoDeLaWeb(cabeceraB().copy(medicoNombre = "Ñandú 🙂"))!!.valor)
    }

    @Test
    fun la_fecha_sale_de_fecha_y_si_no_de_created_at() {
        assertEquals(LocalDate(2026, 3, 10), consultaDeLaWeb(cabeceraA().copy(fecha = "2026-03-10", createdAt = null), paciente, medico)!!.fecha)
        assertEquals(LocalDate(2026, 3, 11), consultaDeLaWeb(cabeceraA().copy(fecha = "", createdAt = "2026-03-11 23:59:59"), paciente, medico)!!.fecha)
        assertEquals(LocalDate(2026, 3, 11), consultaDeLaWeb(cabeceraA().copy(fecha = "basura", createdAt = "2026-03-11 08:00:00"), paciente, medico)!!.fecha)
        // Sin ninguna fecha útil no se inventa la de hoy: la consulta no se convierte.
        assertNull(consultaDeLaWeb(cabeceraA().copy(fecha = "", createdAt = null), paciente, medico))
        assertNull(consultaDeLaWeb(cabeceraA().copy(fecha = "0000-00-00", createdAt = "1000-01-01 00:00:00"), paciente, medico))
    }

    @Test
    fun el_tipo_sale_de_tipo_y_si_falta_del_numero() {
        assertEquals("control", consultaDeLaWeb(cabeceraA().copy(tipo = null, tipoNumero = 1), paciente, medico)!!.tipo)
        assertEquals("telemedicina", consultaDeLaWeb(cabeceraA().copy(tipo = null, tipoNumero = 4), paciente, medico)!!.tipo)
        assertNull(consultaDeLaWeb(cabeceraA().copy(tipo = null, tipoNumero = null), paciente, medico))
        assertNull(consultaDeLaWeb(cabeceraA().copy(tipo = null, tipoNumero = 2), paciente, medico))
        // Un tipo que esta versión no muestra no se convierte en otro.
        assertNull(consultaDeLaWeb(cabeceraA().copy(tipo = "foto", tipoNumero = 1), paciente, medico))
        assertNull(consultaDeLaWeb(cabeceraA().copy(tipo = "otro"), paciente, medico))
    }

    @Test
    fun un_estado_o_un_id_que_no_se_reconocen_no_se_convierten() {
        assertNull(consultaDeLaWeb(cabeceraA().copy(estado = "ANULADA"), paciente, medico))
        assertNull(consultaDeLaWeb(cabeceraA().copy(estado = ""), paciente, medico))
        assertNull(consultaDeLaWeb(cabeceraA().copy(id = 0), paciente, medico))
        assertNull(consultaDeLaWeb(cabeceraA().copy(id = -4), paciente, medico))
    }

    @Test
    fun una_edad_vacia_de_la_web_no_pisa_la_que_la_consulta_ya_tenia() {
        val web = consultaDeLaWeb(cabeceraB(), paciente, medico)!!
        val local = web.copy(edadMostrar = "30 años")
        assertNull(fusionarConsulta(web, local), "la edad vacía no cambia nada: no hay qué escribir")
        val nueva = fusionarConsulta(web.copy(estado = EstadoConsulta.ABIERTA), local)!!
        assertEquals("30 años", nueva.edadMostrar)
        assertEquals(EstadoConsulta.ABIERTA, nueva.estado)
        // Una edad que sí viene reemplaza.
        assertEquals("31 años", fusionarConsulta(web.copy(edadMostrar = "31 años"), local)!!.edadMostrar)
    }

    @Test
    fun una_consulta_igual_a_la_que_ya_esta_no_se_vuelve_a_escribir() {
        val web = consultaDeLaWeb(cabeceraA(), paciente, medico)!!
        assertNull(fusionarConsulta(web, web))
        // El updatedAt del dispositivo no cuenta: no hay nada que escribir y la fila no parece editada.
        assertNull(fusionarConsulta(web, web.copy(updatedAt = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000))))
        assertEquals(web, fusionarConsulta(web, null))
    }

    // --- Orden ---

    @Test
    fun orden_por_created_at_y_despues_id_de_la_mas_reciente_a_la_mas_vieja() {
        val a = cabeceraA().copy(id = 1, fecha = "2026-03-10", createdAt = "2026-03-10 10:00:00")
        val b = cabeceraA().copy(id = 2, fecha = "2026-03-10", createdAt = "2026-03-10 18:30:00")
        val c = cabeceraA().copy(id = 3, fecha = "2026-03-10", createdAt = "2026-03-10 18:30:00")
        val d = cabeceraA().copy(id = 4, fecha = "2026-01-02", createdAt = "2026-01-02 09:00:00")
        assertEquals(listOf(3L, 2L, 1L, 4L), ordenarMasRecientesPrimero(listOf(d, a, c, b)).map { it.id })
    }

    @Test
    fun orden_sin_created_at_es_por_fecha_y_despues_id() {
        val a = cabeceraA().copy(id = 10, fecha = "2026-03-10", createdAt = null)
        val b = cabeceraA().copy(id = 11, fecha = "2026-03-10", createdAt = null)
        val c = cabeceraA().copy(id = 5, fecha = "2026-04-01", createdAt = null)
        assertEquals(listOf(5L, 11L, 10L), ordenarMasRecientesPrimero(listOf(a, c, b)).map { it.id })
        // El id compara como número, no como texto.
        val x = cabeceraA().copy(id = 9, createdAt = null)
        val y = cabeceraA().copy(id = 100, createdAt = null)
        assertEquals(listOf(100L, 9L), ordenarMasRecientesPrimero(listOf(x, y)).map { it.id })
    }

    // --- Secciones de la consulta ---

    @Test
    fun consulta_completa_cada_dato_en_su_seccion_y_campo() {
        val esperado = listOf(
            SeccionValor("hcc-c7003", "motivo_consulta", "texto", "Prueba Salud360 motivo (a)"),
            SeccionValor("hcc-c7003", "datos_subjetivos", "texto", "Prueba Salud360 datos subjetivos (a)"),
            SeccionValor("hcc-c7003", "datos_objetivos", "texto", "Prueba Salud360 datos objetivos (a)"),
            SeccionValor("hcc-c7003", "conductas", "texto", "Prueba Salud360 conductas (a)"),
            SeccionValor("hcc-c7003", "nota", "texto", "Prueba Salud360 nota (a)"),
            SeccionValor("hcc-c7003", "habitos", "texto", "Prueba Salud360 hábitos (a): tabaco, alcohol, ejercicio"),
            SeccionValor("hcc-c7003", "diuresis_catarsis", "texto", "Prueba Salud360 diuresis y catarsis (a)"),
            SeccionValor("hcc-c7003", "somnia", "texto", "Prueba Salud360 somnia (a)"),
            SeccionValor("hcc-c7003", "alimentacion", "hiposodica", "1|Prueba Salud360 hiposódica (a)"),
            SeccionValor("hcc-c7003", "alimentacion", "diabetes", "0|Prueba Salud360 sin diabetes"),
            SeccionValor("hcc-c7003", "alimentacion", "celiaco", "1|Prueba Salud360 celíaco detalle (a)"),
            SeccionValor("hcc-c7003", "serologias", "igm_hav", "SI"),
            SeccionValor("hcc-c7003", "serologias", "ags_hbv", "NO"),
            SeccionValor("hcc-c7003", "serologias", "hiv", "NO"),
            SeccionValor("hcc-c7003", "child_pugh", "ascitis", "2"),
            SeccionValor("hcc-c7003", "child_pugh", "bilirrubina", "1"),
            SeccionValor("hcc-c7003", "child_pugh", "albumina", "3"),
            SeccionValor("hcc-c7003", "child_pugh", "tp", "2"),
            SeccionValor("hcc-c7003", "child_pugh", "encefalopatia", "1"),
        )
        val real = valoresDeLaWeb("hcc-c7003", ejemplos.consulta().secciones)
        assertEquals(esperado.size, real.size)
        assertEquals(esperado.toSet(), real.toSet())
    }

    @Test
    fun una_consulta_con_poco_cargado_solo_escribe_lo_que_vino() {
        val c = ejemplos.consulta(EjemplosConsultasClinica.CONSULTA_ABIERTA)
        assertEquals(
            setOf(
                SeccionValor("hcc-c7005", "motivo_consulta", "texto", "Prueba Salud360 motivo (c)"),
                SeccionValor("hcc-c7005", "conductas", "texto", "Prueba Salud360 conductas (c)"),
                SeccionValor("hcc-c7005", "nota", "texto", "Prueba Salud360 nota (c) en curso"),
            ),
            valoresDeLaWeb("hcc-c7005", c.secciones).toSet(),
        )
        assertEquals(emptyList(), valoresDeLaWeb("hcc-c7005", ejemplos.consultaConSecciones("{}").secciones))
    }

    @Test
    fun las_serologias_son_si_o_no_y_nunca_otra_cosa() {
        val s = ejemplos.consultaConSecciones("""{"serologias": {"igm_hav": "SI", "ags_hbv": "NO", "fan": "1", "hiv": "", "asma": "si", "ama": "0", "ig_anti_hcv": "2"}}""").secciones
        assertEquals(
            setOf(SeccionValor("c", "serologias", "igm_hav", "SI"), SeccionValor("c", "serologias", "ags_hbv", "NO")),
            valoresDeLaWeb("c", s).toSet(),
        )
    }

    @Test
    fun child_pugh_son_los_puntos_de_1_a_3() {
        val s = ejemplos.consultaConSecciones("""{"child_pugh": {"ascitis": "1", "bilirrubina": "3", "albumina": "0", "tp": "4", "encefalopatia": ""}}""").secciones
        assertEquals(
            setOf(SeccionValor("c", "child_pugh", "ascitis", "1"), SeccionValor("c", "child_pugh", "bilirrubina", "3")),
            valoresDeLaWeb("c", s).toSet(),
        )
    }

    @Test
    fun la_alimentacion_conserva_el_formato_flag_barra_detalle() {
        val s = ejemplos.consultaConSecciones(
            """{"alimentacion": {"hiposodica": "1|sin sal", "diabetes": "0|", "celiaco": "1|", "otra": "0|solo vegetales", "x": ""}}""",
        ).secciones
        val v = valoresDeLaWeb("c", s).associate { it.campo to it.valor }
        assertEquals(mapOf("hiposodica" to "1|sin sal", "celiaco" to "1|", "otra" to "0|solo vegetales"), v)
    }

    @Test
    fun los_textos_no_se_recortan_ni_se_alteran() {
        val textos = listOf(
            "  con espacios al borde  ",
            "Ñandú, canción, ñoquis 🙂 y 👩‍⚕️",
            "primera línea\nsegunda línea\n\n  cuarta con sangría\r\nquinta",
            "0",
            "tab\tseparada y \"comillas\" y \\barra",
            "a".repeat(5000),
        )
        textos.forEach { texto ->
            val json = kotlinx.serialization.json.JsonPrimitive(texto).toString()
            val s = ejemplos.consultaConSecciones("""{"motivo_consulta": {"texto": $json}}""").secciones
            assertEquals(listOf(SeccionValor("c", "motivo_consulta", "texto", texto)), valoresDeLaWeb("c", s), texto.take(20))
        }
    }

    @Test
    fun un_texto_vacio_o_de_espacios_es_no_hay_dato_nunca_un_valor_vacio() {
        val s = ejemplos.consultaConSecciones(
            """{"motivo_consulta": {"texto": ""}, "nota": {"texto": "   "}, "conductas": {"texto": "\n\t "}, "habitos": {"texto": "x"}}""",
        ).secciones
        assertEquals(listOf(SeccionValor("c", "habitos", "texto", "x")), valoresDeLaWeb("c", s))
        // Ningún valor convertido es vacío, en ninguna respuesta de ejemplo.
        listOf(EjemplosConsultasClinica.CONSULTA_COMPLETA, EjemplosConsultasClinica.CONSULTA_ABIERTA, EjemplosConsultasClinica.CONSULTA_CON_PDF).forEach {
            assertTrue(valoresDeLaWeb("c", ejemplos.consulta(it).secciones).none { v -> v.valor.isBlank() })
        }
    }

    @Test
    fun solo_se_escriben_las_secciones_que_la_web_tiene_las_demas_no_se_inventan() {
        val escritas = valoresDeLaWeb("hcc-c7003", ejemplos.consulta().secciones).map { it.seccion }.toSet()
        assertEquals(
            setOf(
                "motivo_consulta", "datos_subjetivos", "datos_objetivos", "conductas", "nota", "habitos", "diuresis_catarsis",
                "somnia", "alimentacion", "serologias", "child_pugh",
            ),
            escritas,
        )
        // Sin equivalente en la web (se avisan con `no_leidas` o son de otra API): nunca aparecen.
        listOf("diagnosticos", "documentos", "audio", "fotos", "antecedentes_personales", "enfermedades", "examen_fisico").forEach {
            assertFalse(it in escritas, it)
        }
    }

    // --- Examen físico ---

    @Test
    fun examen_fisico_completo_sin_nada_local() {
        val dto = ejemplos.consulta().examenFisico
        assertEquals(
            ExamenFisico("hcc-c7003", peso = "70.5", talla = "170", tensionArterial = "120/80", imc = "24.4", nota = "Prueba Salud360 examen físico (a)"),
            fusionarExamenFisico("hcc-c7003", dto, null),
        )
    }

    @Test
    fun el_examen_conserva_lo_local_que_la_web_no_tiene_y_lo_que_no_vino() {
        val local = ExamenFisico("hcc-c7003", peso = "68", frecuenciaCardiaca = "72", circunferenciaAbdominal = "95", nota = "nota local")
        val nuevo = fusionarExamenFisico("hcc-c7003", ExamenFisicoDto(talla = "170", tensionArterial = "120/80"), local)!!
        assertEquals("72", nuevo.frecuenciaCardiaca)
        assertEquals("95", nuevo.circunferenciaAbdominal)
        assertEquals("68", nuevo.peso, "no vino: se conserva")
        assertEquals("nota local", nuevo.nota)
        assertEquals("170", nuevo.talla)
        assertEquals("120/80", nuevo.tensionArterial)
    }

    @Test
    fun un_campo_vacio_del_examen_nunca_pisa_el_cargado() {
        val local = ExamenFisico("c", peso = "68", talla = "170", imc = "23.5", tensionArterial = "110/70", nota = "ok")
        assertNull(fusionarExamenFisico("c", ExamenFisicoDto(peso = "", talla = "  ", tensionArterial = null, imc = "", nota = "\n"), local))
        assertNull(fusionarExamenFisico("c", ExamenFisicoDto(), local))
        assertNull(fusionarExamenFisico("c", ExamenFisicoDto(), null), "no hay examen: no hay nada que escribir")
    }

    @Test
    fun el_cero_del_examen_es_un_dato_y_no_se_filtra_en_la_app() {
        // El servidor ya decidió qué es «sin cargar» ("0.00"); un "0" o "0.0" que llega es un dato.
        val nuevo = fusionarExamenFisico("c", ExamenFisicoDto(talla = "0.0", imc = "0"), null)!!
        assertEquals("0.0", nuevo.talla)
        assertEquals("0", nuevo.imc)
        assertEquals("", nuevo.peso)
    }

    @Test
    fun un_examen_igual_al_local_no_se_reescribe() {
        val local = ExamenFisico("c", peso = "70.5", talla = "170")
        assertNull(fusionarExamenFisico("c", ExamenFisicoDto(peso = "70.5", talla = "170"), local))
    }

    @Test
    fun la_nota_del_examen_conserva_saltos_y_emoji() {
        val nota = "  línea 1\nlínea 2 🙂  "
        assertEquals(nota, fusionarExamenFisico("c", ExamenFisicoDto(nota = nota), null)!!.nota)
    }

    // --- Antecedentes familiares ---

    @Test
    fun antecedentes_familiares_cada_casilla_en_su_clave() {
        val a = antecedentesDeLaWeb(paciente, ejemplos.historia()).associateBy { it.clave }
        assertEquals(setOf("hta", "dbt_ir", "otro", "nota", "nota_perfil4"), a.keys)
        assertEquals(
            Antecedente("hcc-a5001-hta", paciente, "clinica", "familiares", "hta", true, "Prueba Salud360 hta detalle", null, true),
            a.getValue("hta"),
        )
        assertEquals(Antecedente("hcc-a5001-dbt_ir", paciente, "clinica", "familiares", "dbt_ir", true, "", null, true), a.getValue("dbt_ir"))
        assertEquals("Prueba Salud360 otro af", a.getValue("otro").detalle)
        // La nota libre: como la guarda la pantalla (clave `nota`, con la marca puesta y el texto en el detalle).
        assertEquals(Antecedente("hcc-a5001-nota", paciente, "clinica", "familiares", "nota", true, "Prueba Salud360 nota antecedentes", null, true), a.getValue("nota"))
        // La nota del perfil 4 queda aparte, distinta de la nota de las casillas.
        assertEquals(Antecedente("hcc-a5001-nota_perfil4", paciente, "clinica", "familiares", "nota_perfil4", true, "Prueba Salud360 nota del perfil 4", null, true), a.getValue("nota_perfil4"))
    }

    @Test
    fun el_antecedente_dependiente_de_terceros_va_en_su_clave() {
        val h = ejemplos.historia().copy(
            antecedentesFamiliares = AntecedentesFamiliaresDto(mapOf("dependiente_terceros" to CasillaAntecedenteDto(1)), null),
            antecedentesFamiliaresPerfil4 = null,
        )
        val a = antecedentesDeLaWeb(paciente, h)
        assertEquals(listOf("dependiente_terceros"), a.map { it.clave })
        assertEquals("hcc-a5001-dependiente_terceros", a.single().id)
    }

    @Test
    fun una_casilla_en_cero_sin_detalle_no_se_inventa() {
        val h = ejemplos.historia().copy(
            antecedentesFamiliares = AntecedentesFamiliaresDto(
                mapOf(
                    "hta" to CasillaAntecedenteDto(0),
                    "acv" to CasillaAntecedenteDto(0, ""),
                    "fa" to CasillaAntecedenteDto(0, "   "),
                    "mp" to CasillaAntecedenteDto(0, "tía"),
                    "dsl" to CasillaAntecedenteDto(1, null),
                ),
                nota = "   ",
            ),
            antecedentesFamiliaresPerfil4 = NotaPerfil4Dto(""),
        )
        val a = antecedentesDeLaWeb(paciente, h).associateBy { it.clave }
        assertEquals(setOf("mp", "dsl"), a.keys)
        assertFalse(a.getValue("mp").flag)
        assertEquals("tía", a.getValue("mp").detalle)
        assertTrue(a.getValue("dsl").flag)
    }

    @Test
    fun sin_antecedentes_no_hay_nada_que_escribir() {
        assertEquals(emptyList(), antecedentesDeLaWeb(paciente, ejemplos.historia(EjemplosConsultasClinica.HISTORIA_VACIA)))
    }

    @Test
    fun el_antecedente_de_la_web_no_pisa_uno_del_dispositivo() {
        val web = antecedentesDeLaWeb(paciente, ejemplos.historia()).first { it.clave == "hta" }
        val delDispositivo = web.copy(id = newId(), detalle = "lo escribí yo")
        // Sucio: manda el del dispositivo (RF-54).
        assertNull(fusionarAntecedente(web, listOf(delDispositivo), sucias = setOf(delDispositivo.id)))
        // Otra fila de la misma clave que no es la de la web: tampoco se duplica.
        assertNull(fusionarAntecedente(web, listOf(delDispositivo), sucias = emptySet()))
        // Una fila de otra clave u otro paciente no cuenta.
        assertEquals(web, fusionarAntecedente(web, listOf(delDispositivo.copy(clave = "acv"), delDispositivo.copy(pacienteId = "tobb-p9")), emptySet()))
        assertEquals(web, fusionarAntecedente(web, emptyList(), emptySet()))
    }

    @Test
    fun el_detalle_vacio_de_la_web_no_borra_el_detalle_ya_traido() {
        val web = antecedentesDeLaWeb(paciente, ejemplos.historia()).first { it.clave == "dbt_ir" }
        val yaTraido = web.copy(detalle = "padre")
        assertNull(fusionarAntecedente(web, listOf(yaTraido), emptySet()), "queda igual: nada que escribir")
        assertEquals("madre", fusionarAntecedente(web.copy(detalle = "madre"), listOf(yaTraido), emptySet())!!.detalle)
    }

    // --- Laboratorio y peso ---

    @Test
    fun cada_carga_en_su_tipo_con_los_valores_de_la_app() {
        val l = laboratoriosDeLaWeb(paciente, ejemplos.historia().laboratorios, consultasLocales).associateBy { it.id }
        assertEquals(setOf("hcc-llaboratorio_general-703", "hcc-llaboratorio_reducido-19", "hcc-lpeso-25"), l.keys)
        assertEquals(
            Laboratorio(
                "hcc-llaboratorio_general-703", paciente, "hcc-c7003", "clinica", "laboratorio_general", LocalDate(2026, 3, 10),
                mapOf("hto" to "41", "hb" to "13.9", "alb_mina" to "4.1", "f_sforo" to "3.5", "otro" to "Prueba Salud360 otro lab 2"),
                remotoId = "703",
            ),
            l.getValue("hcc-llaboratorio_general-703"),
        )
        assertEquals("laboratorio_reducido", l.getValue("hcc-llaboratorio_reducido-19").tipo)
        assertEquals(mapOf("peso" to "70.5"), l.getValue("hcc-lpeso-25").valores)
        // Spec 005, T86-a: cada carga de la web lleva el id de su fila para emparejarla con la que la app ya envió.
        assertEquals(listOf("703", "19", "25"), l.values.map { it.remotoId })
    }

    @Test
    fun los_cinco_analitos_desalineados_y_otros_van_cada_uno_en_su_fila() {
        val general = CargaLaboratorioDto(
            703, 7003, "2026-03-10",
            mapOf(
                "alb_mina" to "4.1", "f_sforo" to "3.5", "concentraci_n" to "90", "_cido_rico" to "5.2",
                "b12_homociste_na_f_lico" to "300", "otro" to "x",
            ),
        )
        val reducido = CargaLaboratorioDto(19, 7003, "2026-03-10", mapOf("otros" to "y", "glucemia" to "97"))
        val h = ejemplos.historia()
        val labs = laboratoriosDeLaWeb(paciente, h.laboratorios.copy(laboratorioGeneral = listOf(general), laboratorioReducido = listOf(reducido), peso = emptyList()), consultasLocales)
        // La conversión no re-mapea nombres: el servidor ya devuelve los ids de la app (Q12).
        assertEquals(general.valores, labs.first { it.tipo == "laboratorio_general" }.valores)
        assertEquals(reducido.valores, labs.first { it.tipo == "laboratorio_reducido" }.valores)
        assertEquals(setOf("laboratorio_general", "laboratorio_reducido"), labs.map { it.tipo }.toSet())
    }

    @Test
    fun una_carga_de_una_consulta_que_no_esta_local_queda_sin_consulta() {
        val sinLocales = laboratoriosDeLaWeb(paciente, ejemplos.historia().laboratorios, consultaDeOtro())
        assertTrue(sinLocales.isNotEmpty())
        assertTrue(sinLocales.all { it.consultaId == null }, "la carga es del paciente: se guarda igual, sin consulta")
        val con = laboratoriosDeLaWeb(paciente, ejemplos.historia().laboratorios, consultasLocales)
        assertTrue(con.all { it.consultaId == "hcc-c7003" })
        // consulta_id ausente en la respuesta.
        val sinConsulta = ejemplos.historia().laboratorios.let { it.copy(peso = listOf(it.peso.single().copy(consultaId = null))) }
        assertNull(laboratoriosDeLaWeb(paciente, sinConsulta, consultasLocales).first { it.tipo == "peso" }.consultaId)
    }

    private fun consultaDeOtro() = setOf("hcc-c1", "hcc-c2")

    @Test
    fun una_carga_sin_fecha_o_sin_valores_no_se_convierte() {
        val h = ejemplos.historia()
        val l = h.laboratorios
        // Las sin fecha no llegan (D1); si llegara una, no se le inventa la fecha de hoy.
        assertEquals(0, laboratoriosDeLaWeb(paciente, l.copy(laboratorioGeneral = listOf(l.laboratorioGeneral.single().copy(fecha = null)), laboratorioReducido = emptyList(), peso = emptyList()), consultasLocales).size)
        assertEquals(0, laboratoriosDeLaWeb(paciente, l.copy(laboratorioGeneral = listOf(l.laboratorioGeneral.single().copy(fecha = "2000-xx")), laboratorioReducido = emptyList(), peso = emptyList()), consultasLocales).size)
        assertEquals(0, laboratoriosDeLaWeb(paciente, l.copy(laboratorioGeneral = listOf(l.laboratorioGeneral.single().copy(valores = emptyMap())), laboratorioReducido = emptyList(), peso = emptyList()), consultasLocales).size)
        assertEquals(0, laboratoriosDeLaWeb(paciente, l.copy(laboratorioGeneral = listOf(l.laboratorioGeneral.single().copy(valores = mapOf("hb" to "", "hto" to "  "))), laboratorioReducido = emptyList(), peso = emptyList()), consultasLocales).size)
    }

    @Test
    fun los_valores_vacios_se_descartan_y_los_ceros_son_datos() {
        val carga = CargaLaboratorioDto(1, null, "2026-03-10", mapOf("hb" to "", "hto" to "0", "glucemia" to " 97 ", "otro" to "línea 1\nlínea 2 ñ 🙂"))
        val l = ejemplos.historia().laboratorios.copy(laboratorioGeneral = listOf(carga), laboratorioReducido = emptyList(), peso = emptyList())
        val valores = laboratoriosDeLaWeb(paciente, l, emptySet()).single().valores
        assertEquals(mapOf("hto" to "0", "glucemia" to " 97 ", "otro" to "línea 1\nlínea 2 ñ 🙂"), valores)
    }

    @Test
    fun una_carga_guardada_no_pierde_valores_ni_consulta_por_una_respuesta_mas_corta() {
        val web = Laboratorio("hcc-llaboratorio_general-703", paciente, null, "clinica", "laboratorio_general", LocalDate(2026, 3, 10), mapOf("hb" to "13.9"))
        val local = web.copy(consultaId = "hcc-c7003", valores = mapOf("hb" to "13", "hto" to "41"))
        val nueva = fusionarLaboratorio(web, local)!!
        assertEquals(mapOf("hb" to "13.9", "hto" to "41"), nueva.valores, "lo que vino pisa; lo que no vino se queda")
        assertEquals("hcc-c7003", nueva.consultaId, "la consulta que ya tenía no vuelve a null")
        assertNull(fusionarLaboratorio(web.copy(valores = mapOf("hb" to "13")), local))
        // La consulta que antes no estaba local y ahora sí se completa.
        assertEquals("hcc-c7003", fusionarLaboratorio(web.copy(consultaId = "hcc-c7003"), web)!!.consultaId)
        assertEquals(web, fusionarLaboratorio(web, null))
    }

    // --- Estudios, exámenes e interconsultas ---

    @Test
    fun estudios_cada_uno_con_el_id_de_su_tipo() {
        val r = registrosDeLaWeb(paciente, ejemplos.historia(), consultasLocales).associateBy { it.id }
        val veda = r.getValue("hcc-rveda-1797")
        val biopsia = r.getValue("hcc-rbiopsia_hepatica-1797")
        val tc = r.getValue("hcc-rtc_rmn-1797")
        assertEquals(setOf("veda", "biopsia_hepatica", "tc_rmn"), setOf(veda.tipo, biopsia.tipo, tc.tipo))
        assertEquals("Prueba Salud360 videoendoscopia (a)", veda.campos["detalle"])
        assertEquals("Prueba Salud360 biopsia hepática (a)", biopsia.campos["detalle"])
        assertEquals("Prueba Salud360 TC/RMN (a)", tc.campos["detalle"])
        assertEquals(
            RegistroClinico(
                "hcc-reco_abdominal-1801", paciente, "hcc-c7003", "clinica", "eco_abdominal", LocalDate(2026, 2, 1),
                mapOf("detalle" to "Prueba Salud360 ecografía abdominal (a)"), true, "1801",
            ),
            r.getValue("hcc-reco_abdominal-1801"),
        )
    }

    @Test
    fun un_estudio_sin_fecha_se_guarda_sin_fecha_y_el_de_una_consulta_ausente_sin_consulta() {
        val eco = registrosDeLaWeb(paciente, ejemplos.historia(), consultasLocales).first { it.id == "hcc-reco_abdominal-1802" }
        assertNull(eco.fecha)
        assertNull(eco.consultaId, "la 7006 no está local")
        assertEquals("Prueba Salud360 ecografía abdominal (f) sin fecha", eco.campos["detalle"])
    }

    @Test
    fun examenes_e_interconsultas_con_sus_campos_y_nunca_un_vacio_inventado() {
        val r = registrosDeLaWeb(paciente, ejemplos.historia(), consultasLocales).associateBy { it.id }
        val examen1 = r.getValue("hcc-rexamen_complementario-22")
        assertEquals("examen_complementario", examen1.tipo)
        assertEquals(
            mapOf("solicito" to "Prueba Salud360 examen solicitado 1", "respuesta" to "Prueba Salud360 examen respondido 1", "numero" to "1"),
            examen1.campos,
        )
        val examen2 = r.getValue("hcc-rexamen_complementario-23")
        assertFalse("respuesta" in examen2.campos, "sin respuesta no hay clave (nunca un \"\")")
        assertEquals("2", examen2.campos["numero"])
        val inter = r.getValue("hcc-rinterconsulta-14")
        assertEquals("interconsulta", inter.tipo)
        assertEquals("Prueba Salud360 especialista 1", inter.campos["especialista"])
        assertEquals("Prueba Salud360 interconsulta solicitada 1", inter.campos["solicito"])
        assertEquals("Prueba Salud360 interconsulta respondida 1", inter.campos["respuesta"])
        assertTrue(r.values.all { reg -> reg.campos.values.none { it.isBlank() } })
    }

    @Test
    fun un_registro_sin_texto_no_se_convierte() {
        val h = ejemplos.historia().copy(
            estudios = ejemplos.historia().estudios.copy(
                ecoAbdominal = listOf(EstudioDto(1, 7003, "2026-02-01", ""), EstudioDto(2, 7003, "2026-02-01", "   ")),
                veda = emptyList(), biopsiaHepatica = emptyList(), tcRmn = emptyList(),
            ),
            examenesComplementarios = listOf(ExamenComplementarioDto(5, 7003, "2026-03-10", "", "  ", 3)),
            interconsultas = emptyList(),
        )
        assertEquals(emptyList(), registrosDeLaWeb(paciente, h, consultasLocales))
    }

    @Test
    fun el_texto_de_un_estudio_no_se_recorta() {
        val detalle = "  hallazgo ñ\n\n  segunda línea 🙂  "
        val h = ejemplos.historia().let {
            it.copy(estudios = it.estudios.copy(ecoAbdominal = listOf(EstudioDto(1, 7003, "2026-02-01", detalle)), veda = emptyList(), biopsiaHepatica = emptyList(), tcRmn = emptyList()))
        }
        assertEquals(detalle, registrosDeLaWeb(paciente, h, consultasLocales).single { it.tipo == "eco_abdominal" }.campos["detalle"])
    }

    @Test
    fun un_registro_guardado_no_pierde_campos_ni_consulta_ni_fecha() {
        val web = RegistroClinico("hcc-rinterconsulta-14", paciente, null, "clinica", "interconsulta", null, mapOf("solicito" to "nuevo"), true, "14")
        val local = web.copy(consultaId = "hcc-c7003", fecha = LocalDate(2026, 3, 10), campos = mapOf("solicito" to "viejo", "respuesta" to "r"))
        val nuevo = fusionarRegistro(web, local)!!
        assertEquals(mapOf("solicito" to "nuevo", "respuesta" to "r"), nuevo.campos)
        assertEquals("hcc-c7003", nuevo.consultaId)
        assertEquals(LocalDate(2026, 3, 10), nuevo.fecha)
        assertNull(fusionarRegistro(web.copy(campos = mapOf("solicito" to "viejo")), local))
        assertEquals(web, fusionarRegistro(web, null))
    }

    // --- Motivos y pendientes ---

    @Test
    fun el_motivo_va_al_texto_de_su_consulta_si_esa_consulta_esta_local() {
        val m = motivosDeLaWeb(ejemplos.historia(), consultasLocales)
        assertEquals(
            setOf(
                SeccionValor("hcc-c7005", "motivo_consulta", "texto", "Prueba Salud360 motivo (c)"),
                SeccionValor("hcc-c7003", "motivo_consulta", "texto", "Prueba Salud360 motivo (a)"),
            ),
            m.toSet(),
        )
        // Sin la consulta local no hay dónde mostrarlo: no se crea una consulta ni se inventa una.
        assertEquals(
            listOf(SeccionValor("hcc-c7003", "motivo_consulta", "texto", "Prueba Salud360 motivo (a)")),
            motivosDeLaWeb(ejemplos.historia(), setOf("hcc-c7003")),
        )
        assertEquals(emptyList(), motivosDeLaWeb(ejemplos.historia(), emptySet()))
    }

    @Test
    fun un_motivo_vacio_o_sin_consulta_no_se_convierte_y_el_texto_queda_intacto() {
        val texto = " Ñandú\nsegunda 🙂 "
        val h = ejemplos.historia().copy(
            motivos = listOf(
                MotivoDto(1, 7003, "2026-03-10", texto),
                MotivoDto(2, 7004, "2026-03-11", ""),
                MotivoDto(3, 7005, "2026-03-12", "   "),
                MotivoDto(4, null, null, "sin consulta"),
            ),
        )
        assertEquals(listOf(SeccionValor("hcc-c7003", "motivo_consulta", "texto", texto)), motivosDeLaWeb(h, consultasLocales))
    }

    @Test
    fun el_pendiente_es_del_medico_de_la_sesion() {
        assertEquals(
            Pendiente("hcc-p1744", paciente, medico, "Prueba Salud360 pendiente", null, false),
            pendienteDeLaWeb(paciente, medico, ejemplos.historia()),
        )
        assertEquals("tobb-m77", pendienteDeLaWeb(paciente, "tobb-m77", ejemplos.historia())!!.medicoId)
        assertNull(pendienteDeLaWeb(paciente, medico, ejemplos.historia(EjemplosConsultasClinica.HISTORIA_VACIA)))
        assertNull(pendienteDeLaWeb(paciente, medico, ejemplos.historia().copy(pendientes = PendienteDto(1, "   "))))
        assertNull(pendienteDeLaWeb(paciente, medico, ejemplos.historia().copy(pendientes = PendienteDto(0, "x"))))
        assertEquals(" comprar ñ\n🙂 ", pendienteDeLaWeb(paciente, medico, ejemplos.historia().copy(pendientes = PendienteDto(2, " comprar ñ\n🙂 ")))!!.texto)
    }

    // --- Todo entra limpio ---

    @Test
    fun lo_traido_de_la_web_siempre_se_guarda_sin_pendiente_de_envio() {
        val ahora = 1_800_000_000_000L
        val c = consultaDeLaWeb(cabeceraA(), paciente, medico)!!
        val h = ejemplos.historia()
        val filas = listOf(
            c.filaDeLaWeb(ahora).let { Triple(it.dirty, it.deleted, it.updated_at) },
            valoresDeLaWeb(c.id, ejemplos.consulta().secciones).first().filaDeLaWeb(ahora).let { Triple(it.dirty, it.deleted, it.updated_at) },
            fusionarExamenFisico(c.id, ejemplos.consulta().examenFisico, null)!!.filaDeLaWeb(ahora).let { Triple(it.dirty, it.deleted, it.updated_at) },
            antecedentesDeLaWeb(paciente, h).first().filaDeLaWeb(ahora).let { Triple(it.dirty, it.deleted, it.updated_at) },
            laboratoriosDeLaWeb(paciente, h.laboratorios, consultasLocales).first().filaDeLaWeb(ahora).let { Triple(it.dirty, it.deleted, it.updated_at) },
            registrosDeLaWeb(paciente, h, consultasLocales).first().filaDeLaWeb(ahora).let { Triple(it.dirty, it.deleted, it.updated_at) },
            pendienteDeLaWeb(paciente, medico, h)!!.filaDeLaWeb(ahora).let { Triple(it.dirty, it.deleted, it.updated_at) },
            nombreMedicoDeLaWeb(cabeceraB())!!.filaDeLaWeb(ahora).let { Triple(it.dirty, it.deleted, it.updated_at) },
        )
        filas.forEach { assertEquals(Triple(0L, 0L, ahora), it) }
        assertEquals("7003", c.filaDeLaWeb(ahora).remoto_id)
        assertEquals("hcc-c7003", c.filaDeLaWeb(ahora).id)
        // Las marcas «Ya no figura en la web» también entran limpias (si no, el SyncEngine las empujaría).
        val marca = marcaAusente(paciente, "hcc-c7004", LocalDate(2026, 10, 30)).filaDeLaWeb(ahora)
        assertEquals(Triple(0L, 0L, ahora), Triple(marca.dirty, marca.deleted, marca.updated_at))
    }

    // --- Lo que el catálogo no conoce ---

    private val catalogo = catalogoMinimo()

    @Test
    fun con_la_respuesta_de_ejemplo_el_catalogo_conoce_todo() {
        val c = ejemplos.consulta()
        val h = ejemplos.historia()
        val desconocidos = desconocidos(
            catalogo,
            valores = valoresDeLaWeb("hcc-c7003", c.secciones) + motivosDeLaWeb(h, consultasLocales),
            laboratorios = laboratoriosDeLaWeb(paciente, h.laboratorios, consultasLocales),
            registros = registrosDeLaWeb(paciente, h, consultasLocales),
            antecedentes = antecedentesDeLaWeb(paciente, h),
            camposCustom = mapOf("child_pugh" to setOf("ascitis", "bilirrubina", "albumina", "tp", "encefalopatia")),
        )
        assertEquals(emptyList(), desconocidos)
    }

    @Test
    fun lo_que_el_catalogo_no_conoce_se_reporta_y_se_guarda_igual() {
        val h = ejemplos.historia()
        val l = h.laboratorios.copy(
            laboratorioGeneral = listOf(CargaLaboratorioDto(703, 7003, "2026-03-10", mapOf("hb" to "13", "analito_nuevo" to "7"))),
            laboratorioReducido = emptyList(),
            peso = listOf(CargaLaboratorioDto(25, 7003, "2026-03-10", mapOf("peso" to "70"))),
        )
        val labs = laboratoriosDeLaWeb(paciente, l, consultasLocales)
        assertEquals(mapOf("hb" to "13", "analito_nuevo" to "7"), labs.first { it.tipo == "laboratorio_general" }.valores, "no se pierde")

        val valores = listOf(
            SeccionValor("c", "seccion_nueva", "texto", "x"),
            SeccionValor("c", "alimentacion", "vegana", "1|"),
            SeccionValor("c", "motivo_consulta", "texto", "ok"),
            SeccionValor("c", "motivo_consulta", "otro_campo", "x"),
            SeccionValor("c", "child_pugh", "inventado", "1"),
        )
        val antecedentes = listOf(
            Antecedente("a", paciente, "clinica", "familiares", "hta", true),
            Antecedente("b", paciente, "clinica", "familiares", "clave_nueva", true),
            Antecedente("c", paciente, "clinica", "familiares", CLAVE_NOTA, true, "n"),
            Antecedente("d", paciente, "clinica", "familiares", CLAVE_NOTA_PERFIL4, true, "n"),
        )
        val registros = listOf(
            RegistroClinico("r1", paciente, null, "clinica", "tipo_nuevo", null, mapOf("detalle" to "x")),
            RegistroClinico("r2", paciente, null, "clinica", "veda", null, mapOf("detalle" to "x", "campo_nuevo" to "y", "numero" to "2")),
        )
        val d = desconocidos(
            catalogo, valores, labs, registros, antecedentes,
            camposCustom = mapOf("child_pugh" to setOf("ascitis")),
        )
        assertEquals(
            setOf(
                Desconocido("seccion", "seccion_nueva"),
                Desconocido("campo", "alimentacion/vegana"),
                Desconocido("campo", "motivo_consulta/otro_campo"),
                Desconocido("campo", "child_pugh/inventado"),
                Desconocido("analito", "laboratorio_general/analito_nuevo"),
                Desconocido("antecedente", "clave_nueva"),
                Desconocido("registro", "tipo_nuevo"),
                Desconocido("campo_de_registro", "veda/campo_nuevo"),
            ),
            d.toSet(),
        )
        assertEquals(d.size, d.toSet().size, "sin repetidos")
    }
}

/** Un catálogo chico con las secciones, campos, analitos, registros y antecedentes que la web puede mandar. */
private fun catalogoMinimo(): CatalogoHc {
    fun texto(id: String) = SeccionCatalogo(SeccionDef(id, id, TipoSeccion.TEXTO))
    fun form(id: String, vararg campos: String) = SeccionCatalogo(SeccionDef(id, id, TipoSeccion.FORM, campos = campos.map { CampoDef(it, it) }))
    fun lab(tipo: String, vararg analitos: String) = SeccionCatalogo(
        SeccionDef(tipo, tipo, TipoSeccion.LABORATORIO, laboratorioTipo = tipo),
        laboratorios = listOf(LaboratorioDef(tipo, tipo, analitos.map { AnalitoDef(it, it) })),
    )
    fun registro(tipo: String, vararg campos: String) = SeccionCatalogo(
        SeccionDef(tipo, tipo, TipoSeccion.REGISTROS, registroTipo = tipo),
        registros = listOf(RegistroDef(tipo, tipo, tipo, campos.map { CampoDef(it, it) })),
    )
    return CatalogoHc(
        base = EspecialidadDefinition(codigo = "clinica", nombre = "Clínica", nombreLegacy = "hclinica", tiposConsulta = emptyList(), secciones = emptyList()),
        secciones = listOf(
            SeccionCatalogo(
                SeccionDef("antecedentes_familiares", "Antecedentes familiares", TipoSeccion.ANTECEDENTES, categoriaAntecedentes = "familiares"),
                antecedentes = listOf(CategoriaAntecedentes("familiares", "Antecedentes familiares", listOf("hta", "dbt_ir", "dependiente_terceros", "otro").map { ItemAntecedente(it, it) })),
            ),
            texto("motivo_consulta"), texto("datos_subjetivos"), texto("datos_objetivos"), texto("conductas"), texto("nota"),
            texto("habitos"), texto("diuresis_catarsis"), texto("somnia"),
            form("alimentacion", "hiposodica", "diabetes", "celiaco", "otra"),
            form("serologias", "igm_hav", "ags_hbv", "hiv"),
            SeccionCatalogo(SeccionDef("child_pugh", "Child-Pugh", TipoSeccion.CUSTOM, renderKey = "child_pugh")),
            lab("laboratorio_general", "hto", "hb", "alb_mina", "f_sforo", "otro"),
            lab("laboratorio_reducido", "hemograma", "glucemia", "otros"),
            lab("peso", "peso"),
            registro("eco_abdominal", "detalle"), registro("veda", "detalle"), registro("biopsia_hepatica", "detalle"),
            registro("estudios_cardiologicos", "detalle"), registro("tc_rmn", "detalle"),
            registro("examen_complementario", "solicito", "respuesta"),
            registro("interconsulta", "especialista", "solicito", "respuesta"),
        ),
    )
}