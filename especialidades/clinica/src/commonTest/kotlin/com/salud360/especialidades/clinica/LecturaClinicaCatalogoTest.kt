package com.salud360.especialidades.clinica

import com.salud360.core.data.hcgeneral.CLAVE_NOTA
import com.salud360.core.data.hcgeneral.CLAVE_NOTA_PERFIL4
import com.salud360.core.data.hcgeneral.Desconocido
import com.salud360.core.data.hcgeneral.SECCION_DE_LA_NOTA_PERFIL4
import com.salud360.core.data.hcgeneral.antecedentesDeLaWeb
import com.salud360.core.data.hcgeneral.desconocidos
import com.salud360.core.data.hcgeneral.idConsultaWeb
import com.salud360.core.data.hcgeneral.laboratoriosDeLaWeb
import com.salud360.core.data.hcgeneral.motivosDeLaWeb
import com.salud360.core.data.hcgeneral.registrosDeLaWeb
import com.salud360.core.data.hcgeneral.valoresDeLaWeb
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.model.especialidad.TipoSeccion
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Los ids de sección, campo y analito que devuelve el servidor (ya con los nombres de la app: el mapa vive en el
 * servidor, Q12) existen en `catalogoHcGeneral`, y lo que el catálogo tiene sin equivalente en la web no se inventa
 * (spec 002, E6.2; RF-42). Respuestas de ejemplo inventadas con la forma del contrato; ver `LecturaClinica` en
 * `core/data`.
 */
class LecturaClinicaCatalogoTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val paciente = "hc-clinica-p5001"
    private val consultaId = idConsultaWeb(7003)

    private val camposCustom = mapOf("child_pugh" to parametrosChildPugh.map { it.clave }.toSet())

    private val consulta: RespuestaConsultaDto = json.decodeFromString(
        RespuestaConsultaDto.serializer(),
        """{"ok": true,
            "consulta": {"id": 7003, "paciente_id": 5001, "medico_id": 9, "medico_nombre": "Médico Prueba Salud360", "tipo": "control",
                         "tipo_numero": 1, "fecha": "2026-03-10", "created_at": "2026-03-10 10:00:00", "estado": "CERRADA",
                         "edad_mostrar": "45 años", "propia": true, "updated_at": "2026-03-10 10:30:00"},
            "secciones": {
              "motivo_consulta": {"texto": "Prueba Salud360 motivo"}, "datos_subjetivos": {"texto": "x"}, "datos_objetivos": {"texto": "x"},
              "conductas": {"texto": "x"}, "nota": {"texto": "x"}, "habitos": {"texto": "x"}, "diuresis_catarsis": {"texto": "x"},
              "somnia": {"texto": "x"},
              "alimentacion": {"hiposodica": "1|a", "diabetes": "1|b", "celiaco": "1|c", "otra": "0|d"},
              "serologias": {"igm_hav": "SI", "ags_hbv": "NO", "ig_anti_hcv": "SI", "fan": "NO", "anti_lkm": "SI", "igg_hav": "NO",
                             "ig_anti_core": "SI", "ig_anti_s_hbv": "NO", "ig_anti_hdv": "SI", "asma": "NO", "fibrotest": "SI",
                             "hiv": "NO", "ag_e": "SI", "anti_ag_e": "NO", "ig_anti_hev": "SI", "ama": "NO"},
              "child_pugh": {"ascitis": "2", "bilirrubina": "1", "albumina": "3", "tp": "2", "encefalopatia": "1"}},
            "examen_fisico": {"peso": "70.5", "talla": "170", "tension_arterial": "120/80", "imc": "24.4", "nota": "x"},
            "no_leidas": []}""",
    )

    private val historia: RespuestaHistoriaDto = json.decodeFromString(
        RespuestaHistoriaDto.serializer(),
        """{"ok": true, "paciente_id": 5001,
            "antecedentes_familiares": {"hta": {"flag": 1, "detalle": "x"}, "dbt_nir": {"flag": 1}, "dbt_ir": {"flag": 1}, "acv": {"flag": 1},
                "dsl": {"flag": 1}, "fa": {"flag": 1}, "mp": {"flag": 1}, "analfabeto": {"flag": 1}, "postrado": {"flag": 1},
                "af_ca_mama": {"flag": 1}, "ca_mama": {"flag": 1}, "institucionalizado": {"flag": 1}, "psicofarmacos": {"flag": 1},
                "dependiente_terceros": {"flag": 1}, "otro": {"flag": 1, "detalle": "x"}, "nota": "x"},
            "antecedentes_familiares_perfil4": {"nota": "x"},
            "laboratorios": {
              "laboratorio_general": [{"id": 703, "consulta_id": 7003, "fecha": "2026-03-10",
                  "valores": {"hto": "41", "hb": "13.9", "glucemia": "90", "alb_mina": "4.1", "f_sforo": "3.5", "concentraci_n": "90",
                              "_cido_rico": "5.2", "b12_homociste_na_f_lico": "300", "tgo": "20", "tgp": "21", "otro": "x"}}],
              "laboratorio_reducido": [{"id": 19, "consulta_id": 7003, "fecha": "2026-03-10",
                  "valores": {"hemograma": "normal", "glucemia": "97", "hba1c": "5", "otros": "x"}}],
              "peso": [{"id": 25, "consulta_id": 7003, "fecha": "2026-03-10", "valores": {"peso": "70.5"}}]},
            "estudios": {
              "eco_abdominal": [{"id": 1801, "consulta_id": 7003, "fecha": "2026-02-01", "detalle": "x"}],
              "veda": [{"id": 1797, "consulta_id": 7003, "fecha": "2026-02-02", "detalle": "x"}],
              "biopsia_hepatica": [{"id": 1797, "consulta_id": 7003, "fecha": "2026-02-03", "detalle": "x"}],
              "estudios_cardiologicos": [{"id": 1790, "consulta_id": 7003, "fecha": "2026-02-03", "detalle": "x"}],
              "tc_rmn": [{"id": 1797, "consulta_id": 7003, "fecha": "2026-02-04", "detalle": "x"}]},
            "motivos": [{"id": 1857, "consulta_id": 7003, "fecha": "2026-03-10", "texto": "Prueba Salud360 motivo"}],
            "examenes_complementarios": [{"id": 22, "consulta_id": 7003, "fecha": "2026-03-10", "solicito": "x", "respuesta": "y", "numero": 1}],
            "interconsultas": [{"id": 14, "consulta_id": 7003, "fecha": "2026-03-10", "especialista": "x", "solicito": "y", "respuesta": "z", "numero": 1}],
            "pendientes": {"id": 1744, "texto": "x"}, "omitidas": {"laboratorio_sin_fecha": 0}}""",
    )

    private val consultasLocales = setOf(consultaId)

    @Test
    fun cada_seccion_campo_analito_registro_y_antecedente_de_la_respuesta_existe_en_el_catalogo() {
        val d = desconocidos(
            catalogoHcGeneral,
            valores = valoresDeLaWeb(consultaId, consulta.secciones) + motivosDeLaWeb(historia, consultasLocales),
            laboratorios = laboratoriosDeLaWeb(paciente, historia.laboratorios, consultasLocales),
            registros = registrosDeLaWeb(paciente, historia, consultasLocales),
            antecedentes = antecedentesDeLaWeb(paciente, historia),
            camposCustom = camposCustom,
        )
        assertEquals(emptyList(), d, "lo que el servidor devuelve y el catálogo de la app no conoce")
    }

    @Test
    fun las_secciones_que_se_escriben_son_del_catalogo_y_del_tipo_que_se_espera() {
        val escritas = valoresDeLaWeb(consultaId, consulta.secciones).map { it.seccion }.toSet()
        val tipos = escritas.associateWith { catalogoHcGeneral.seccion(it)?.def?.tipo }
        assertEquals(
            mapOf(
                "motivo_consulta" to TipoSeccion.TEXTO, "datos_subjetivos" to TipoSeccion.TEXTO, "datos_objetivos" to TipoSeccion.TEXTO,
                "conductas" to TipoSeccion.TEXTO, "nota" to TipoSeccion.TEXTO, "habitos" to TipoSeccion.TEXTO,
                "diuresis_catarsis" to TipoSeccion.TEXTO, "somnia" to TipoSeccion.TEXTO, "alimentacion" to TipoSeccion.FORM,
                "serologias" to TipoSeccion.FORM, "child_pugh" to TipoSeccion.CUSTOM,
            ),
            tipos,
        )
    }

    @Test
    fun las_16_serologias_y_las_4_casillas_de_alimentacion_tienen_su_campo() {
        val serologias = catalogoHcGeneral.seccion("serologias")!!.def.campos.map { it.clave }.toSet()
        assertEquals(consulta.secciones.serologias.keys, serologias)
        val alimentacion = catalogoHcGeneral.seccion("alimentacion")!!.def.campos.map { it.clave }.toSet()
        assertEquals(consulta.secciones.alimentacion.keys, alimentacion)
        assertEquals(camposCustom.getValue("child_pugh"), consulta.secciones.childPugh.keys)
    }

    @Test
    fun los_analitos_desalineados_y_otros_existen_con_el_id_de_la_app() {
        val general = catalogoHcGeneral.seccion("laboratorio_general")!!.laboratorios.single().analitos.map { it.clave }.toSet()
        listOf("alb_mina", "f_sforo", "concentraci_n", "_cido_rico", "b12_homociste_na_f_lico", "otro").forEach {
            assertTrue(it in general, "el laboratorio general no tiene $it")
        }
        val reducido = catalogoHcGeneral.seccion("laboratorio_reducido")!!.laboratorios.single().analitos.map { it.clave }.toSet()
        assertTrue("otros" in reducido && "otro" !in reducido, "el reducido usa `otros`")
        assertEquals(setOf("peso"), catalogoHcGeneral.seccion("tabla_peso")!!.laboratorios.single().analitos.map { it.clave }.toSet())
    }

    @Test
    fun los_antecedentes_familiares_de_la_web_son_los_del_catalogo_mas_las_notas() {
        val catalogo = catalogoHcGeneral.seccion("antecedentes_familiares")!!.antecedentes.single { it.categoria == "familiares" }
            .items.map { it.clave }.toSet()
        val web = antecedentesDeLaWeb(paciente, historia).map { it.clave }.toSet()
        // `tabaquismo` está en el catálogo y la web no lo tiene (Q13): no se inventa. Las dos notas no son casillas.
        assertEquals(catalogo - "tabaquismo" + CLAVE_NOTA + CLAVE_NOTA_PERFIL4, web)
        assertTrue("tabaquismo" !in web)
    }

    @Test
    fun la_nota_del_perfil_4_no_es_una_casilla_de_antecedentes_familiares() {
        // El renderer de la sección recorre `items` del catálogo (AntecedentesSeccion): con la nota fuera de ellos nunca se
        // dibuja como una casilla más (E7.2, D2); se muestra aparte, con `notaPerfil4`.
        val familiares = catalogoHcGeneral.seccion("antecedentes_familiares")!!.antecedentes.single { it.categoria == "familiares" }
        assertTrue(familiares.items.none { it.clave == CLAVE_NOTA_PERFIL4 }, "nota_perfil4 no puede ser una casilla")
        assertTrue(familiares.items.none { it.clave == CLAVE_NOTA }, "la nota común tampoco")
        assertEquals("antecedentes_familiares", SECCION_DE_LA_NOTA_PERFIL4)
    }

    @Test
    fun cada_seccion_del_catalogo_tiene_una_decision_de_si_la_web_la_trae() {
        val porSecciones = setOf(
            "motivo_consulta", "datos_subjetivos", "datos_objetivos", "conductas", "nota", "habitos", "diuresis_catarsis", "somnia",
            "alimentacion", "serologias", "child_pugh",
        )
        val porTablasDelExamen = setOf("examen_fisico")
        val porPaciente = setOf(
            "antecedentes_familiares", "laboratorio_general", "laboratorio_reducido", "tabla_peso", "eco_abdominal", "veda",
            "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn", "examenes_complementarios", "interconsulta",
        )
        // Sin equivalente en esta entrega: `enfermedades` tiene su API (spec 003) y `documentos` se avisa con `no_leidas`.
        val sinEquivalente = setOf("enfermedades", "documentos")
        assertEquals(porSecciones + porTablasDelExamen + porPaciente + sinEquivalente, catalogoHcGeneral.ids.toSet())
        val escritas = valoresDeLaWeb(consultaId, consulta.secciones).map { it.seccion }.toSet()
        assertEquals(porSecciones, escritas)
        assertTrue(escritas.none { it in sinEquivalente })
        // Los registros y laboratorios que se escriben son tipos de esas secciones del catálogo.
        val tiposRegistro = catalogoHcGeneral.secciones.flatMap { it.registros }.map { it.tipo }.toSet()
        assertTrue(registrosDeLaWeb(paciente, historia, consultasLocales).all { it.tipo in tiposRegistro })
        val tiposLab = catalogoHcGeneral.secciones.flatMap { it.laboratorios }.map { it.tipo }.toSet()
        assertTrue(laboratoriosDeLaWeb(paciente, historia.laboratorios, consultasLocales).all { it.tipo in tiposLab })
    }

    @Test
    fun lo_que_no_esta_en_el_catalogo_se_reporta_sin_perderse() {
        val h = json.decodeFromString(
            RespuestaHistoriaDto.serializer(),
            """{"ok": true, "paciente_id": 5001, "antecedentes_familiares": {"vapeo": {"flag": 1}},
                "laboratorios": {"laboratorio_general": [{"id": 1, "fecha": "2026-03-10", "valores": {"hb": "13", "analito_nuevo": "7"}}],
                                 "laboratorio_reducido": [], "peso": []},
                "estudios": {"eco_abdominal": [], "veda": [], "biopsia_hepatica": [], "estudios_cardiologicos": [], "tc_rmn": []},
                "motivos": [], "examenes_complementarios": [], "interconsultas": [], "pendientes": null}""",
        )
        val labs = laboratoriosDeLaWeb(paciente, h.laboratorios, emptySet())
        val ants = antecedentesDeLaWeb(paciente, h)
        assertEquals(mapOf("hb" to "13", "analito_nuevo" to "7"), labs.single().valores, "no se pierde")
        assertEquals(listOf("vapeo"), ants.map { it.clave }, "no se pierde")
        assertEquals(
            listOf(Desconocido("analito", "laboratorio_general/analito_nuevo"), Desconocido("antecedente", "vapeo")),
            desconocidos(catalogoHcGeneral, laboratorios = labs, antecedentes = ants, camposCustom = camposCustom),
        )
    }
}
