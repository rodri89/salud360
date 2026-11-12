package com.salud360.especialidades.clinica

import com.salud360.core.data.hcgeneral.AVISO_NO_SE_ENVIA
import com.salud360.core.data.hcgeneral.CAMPOS_DEL_EXAMEN_QUE_LA_WEB_NO_GUARDA
import com.salud360.core.data.hcgeneral.CLAVE_NOTA
import com.salud360.core.data.hcgeneral.CLAVE_NOTA_PERFIL4
import com.salud360.core.data.hcgeneral.Desconocido
import com.salud360.core.data.hcgeneral.EnvioDeSeccion
import com.salud360.core.data.hcgeneral.ModoCampoExamen
import com.salud360.core.data.hcgeneral.SECCIONES_QUE_LA_WEB_NO_GUARDA
import com.salud360.core.data.hcgeneral.SECCION_DE_LA_NOTA_PERFIL4
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura
import com.salud360.core.data.hcgeneral.antecedentesDeLaWeb
import com.salud360.core.data.hcgeneral.avisoDeCampoDelExamen
import com.salud360.core.data.hcgeneral.avisoDeSeccion
import com.salud360.core.data.hcgeneral.campoDelExamenSeEnvia
import com.salud360.core.data.hcgeneral.casillaSeEnvia
import com.salud360.core.data.hcgeneral.desconocidos
import com.salud360.core.data.hcgeneral.envioDeSeccion
import com.salud360.core.data.hcgeneral.idConsultaWeb
import com.salud360.core.data.hcgeneral.laboratoriosDeLaWeb
import com.salud360.core.data.hcgeneral.modoCampoExamen
import com.salud360.core.data.hcgeneral.motivosDeLaWeb
import com.salud360.core.data.hcgeneral.registrosDeLaWeb
import com.salud360.core.data.hcgeneral.seccionSeOfrece
import com.salud360.core.data.hcgeneral.seccionesDe
import com.salud360.core.data.hcgeneral.seccionesQueSeOfrecen
import com.salud360.core.data.hcgeneral.valoresDeLaWeb
import com.salud360.core.data.network.hc.RespuestaConsultaDto
import com.salud360.core.data.network.hc.RespuestaHistoriaDto
import com.salud360.core.model.especialidad.CampoExamenFisico
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
    fun los_antecedentes_familiares_de_la_web_son_los_del_catalogo_que_se_envian_mas_las_notas() {
        val items = catalogoHcGeneral.seccion("antecedentes_familiares")!!.antecedentes.single { it.categoria == "familiares" }.items
        val queSeEnvian = items.filter { casillaSeEnvia("familiares", it.clave) }.map { it.clave }.toSet()
        val web = antecedentesDeLaWeb(paciente, historia).map { it.clave }.toSet()
        // T17 (D9, RF-3): `tabaquismo` sigue en el catálogo solo para leer lo ya cargado, marcado `sinEnvio`; la web no lo tiene y
        // no se inventa. Las dos notas no son casillas.
        assertEquals(queSeEnvian + CLAVE_NOTA + CLAVE_NOTA_PERFIL4, web)
        assertTrue("tabaquismo" !in web && "tabaquismo" !in queSeEnvian)
    }

    // ---------- T17 (RF-3, RF-4, D9): lo que la web no guarda sale de lo que se envía; lo cargado se conserva ----------

    @Test
    fun la_unica_casilla_sin_envio_del_catalogo_es_tabaquismo_y_sigue_ahi_para_leer_lo_cargado() {
        val sinEnvio = (catalogoHcGeneral.secciones + catalogoHcGeneral.legadas).flatMap { it.antecedentes }
            .flatMap { c -> c.items.filter { it.sinEnvio }.map { c.categoria to it.clave } }
        assertEquals(listOf("familiares" to "tabaquismo"), sinEnvio)
        // Y la marca es la regla de `SeccionesEnviables`, no una lista aparte: ninguna otra casilla, de ninguna categoría, la contradice.
        (catalogoHcGeneral.secciones + catalogoHcGeneral.legadas).flatMap { it.antecedentes }.filter { it.categoria == "familiares" }.forEach { c ->
            c.items.forEach { assertEquals(!casillaSeEnvia(c.categoria, it.clave), it.sinEnvio, "familiares/${it.clave}") }
        }
        // Lo mismo en las definiciones de las que el catálogo copia: nada cambia de lugar, solo se marca.
        assertEquals(listOf("tabaquismo"), clinicaMedica.antecedentes.single { it.categoria == "familiares" }.items.filter { it.sinEnvio }.map { it.clave })
        assertEquals(listOf("tabaquismo"), hepatologia.antecedentes.single { it.categoria == "familiares" }.items.filter { it.sinEnvio }.map { it.clave })
    }

    @Test
    fun del_examen_fisico_de_la_hc_general_se_ofrece_lo_que_viaja_y_frecuencia_y_circunferencia_son_solo_lectura() {
        val base = catalogoHcGeneral.base
        assertEquals(base.examenFisicoCampos, CampoExamenFisico.entries.filter { campoDelExamenSeEnvia(it) }.toSet(), "se ofrece exactamente lo que viaja")
        assertEquals(CAMPOS_DEL_EXAMEN_QUE_LA_WEB_NO_GUARDA, base.examenFisicoSoloLectura)
        assertTrue(base.examenFisicoCampos.intersect(base.examenFisicoSoloLectura).isEmpty())
        // Lo ya cargado se conserva y se ve, solo para leer, aunque la consulta esté abierta; sin valor no aparece.
        CAMPOS_DEL_EXAMEN_QUE_LA_WEB_NO_GUARDA.forEach {
            assertEquals(ModoCampoExamen.LECTURA, modoCampoExamen(base, it, "72", soloLectura = false), "$it con valor")
            assertEquals(ModoCampoExamen.OCULTO, modoCampoExamen(base, it, "", soloLectura = false), "$it sin valor")
            assertEquals(AVISO_NO_SE_ENVIA, avisoDeCampoDelExamen(it))
        }
        // La clínica de antes (no registrada, fija para RF-33) sigue ofreciéndolos: no es la que se envía.
        assertTrue(CampoExamenFisico.FRECUENCIA_CARDIACA in clinicaMedica.examenFisicoCampos)
    }

    @Test
    fun diagnosticos_audio_y_antecedentes_personales_no_estan_en_el_catalogo_que_se_ofrece_sino_en_las_legadas() {
        SECCIONES_QUE_LA_WEB_NO_GUARDA.forEach { id ->
            assertTrue(id !in catalogoHcGeneral.ids, "$id no se ofrece en perfiles")
            assertTrue(catalogoHcGeneral.legada(id) != null, "$id sigue en las legadas para leer lo ya cargado")
            assertEquals(EnvioDeSeccion.NUNCA, envioDeSeccion(id, seccionesDe(SubentregaDeEscritura.E4)), id)
            assertEquals(AVISO_NO_SE_ENVIA, avisoDeSeccion(id, seccionesDe(SubentregaDeEscritura.E4)), id)
        }
        // La nota que algún perfil rotula «antecedentes personales» es la de los antecedentes familiares: esa sí viaja.
        assertTrue(casillaSeEnvia("familiares", CLAVE_NOTA))
    }

    @Test
    fun las_25_secciones_siguen_decididas_y_esta_version_ofrece_las_del_alcance() {
        assertEquals(25, catalogoHcGeneral.ids.size)
        val estados = catalogoHcGeneral.ids.associateWith { envioDeSeccion(it) }
        // Ninguna del catálogo es de las que la web no guarda.
        assertTrue(estados.values.none { it == EnvioDeSeccion.NUNCA })
        // T67 reducida más T88 reducida: los textos, el examen, enfermedades, las ocho secciones del alcance del usuario (las de E2 que pidió y no
        // la alimentación) y el laboratorio general y el peso (E3; decisión del usuario del 2026-11-12).
        assertEquals(
            setOf(
                "motivo_consulta", "datos_subjetivos", "datos_objetivos", "conductas", "nota", "habitos", "diuresis_catarsis", "somnia",
                "examen_fisico", "enfermedades", "serologias", "child_pugh", "antecedentes_familiares", "eco_abdominal", "veda",
                "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn", "laboratorio_general", "tabla_peso",
            ),
            catalogoHcGeneral.ids.filter { seccionSeOfrece(it) }.toSet(),
        )
        // Todo lo demás del catálogo dice «todavía no» y se habilita por subentrega: ninguna queda sin decidir. Son la alimentación, los exámenes
        // complementarios y las interconsultas (E2, fuera del alcance), el laboratorio reducido (E3, fuera de la E3 reducida) y los adjuntos (E4).
        assertEquals(
            setOf("alimentacion", "examenes_complementarios", "interconsulta", "laboratorio_reducido", "documentos"),
            estados.filterValues { it == EnvioDeSeccion.TODAVIA_NO }.keys,
        )
        assertEquals(5, estados.values.count { it == EnvioDeSeccion.TODAVIA_NO })
        // Con todas las subentregas habilitadas se envían las 24 que viajan en una consulta; `enfermedades` va por su API.
        assertEquals(
            catalogoHcGeneral.ids.toSet() - "enfermedades",
            catalogoHcGeneral.ids.filter { envioDeSeccion(it, seccionesDe(SubentregaDeEscritura.E4)) == EnvioDeSeccion.SE_ENVIA }.toSet(),
        )
    }

    @Test
    fun una_seccion_del_perfil_que_todavia_no_se_envia_no_se_ofrece_pero_no_se_pierde() {
        // El laboratorio general ya se ofrece (T88 reducida); el que sigue en «todavía no» es el reducido (fuera del alcance de E3).
        val perfil = listOf("antecedentes_familiares", "motivo_consulta", "laboratorio_reducido", "examen_fisico", "enfermedades")
        assertEquals(listOf("antecedentes_familiares", "motivo_consulta", "examen_fisico", "enfermedades"), seccionesQueSeOfrecen(perfil))
        assertTrue(perfil.all { catalogoHcGeneral.seccion(it) != null }, "las secciones del perfil siguen en el catálogo, se muestran para leer")
        assertEquals("Esta sección todavía no se envía a la web", avisoDeSeccion("laboratorio_reducido"))
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
