package com.salud360.especialidades.clinica

import com.salud360.core.data.hcgeneral.armarDefinicion
import com.salud360.core.data.hcgeneral.copiaDiagnosticos
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionCatalogo
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.especialidad.TipoConsultaHc
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoConsultaHc.TELEMEDICINA
import com.salud360.core.model.especialidad.TipoSeccion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Catálogo de la HC general de la app contra el del servidor y los perfiles iniciales (RF-31, RF-33). */
class CatalogoHcGeneralTest {
    // Copia de `CatalogoSecciones::SECCIONES` de hclinica: ids, orden y nombre por defecto ("Decidido" B1).
    private val catalogoServidor = listOf(
        "antecedentes_familiares" to "Antecedentes familiares",
        "motivo_consulta" to "Motivo de consulta",
        "documentos" to "Adjuntos (PDF)",
        "laboratorio_general" to "Laboratorio general",
        "laboratorio_reducido" to "Laboratorio reducido",
        "eco_abdominal" to "Ecografía abdominal",
        "serologias" to "Serologías de hepatitis",
        "tabla_peso" to "Peso",
        "child_pugh" to "Child-Pugh",
        "veda" to "Videoendoscopía digestiva alta",
        "biopsia_hepatica" to "Biopsia hepática",
        "estudios_cardiologicos" to "Estudios cardiológicos",
        "tc_rmn" to "TC / RMN / colangio-RMN",
        "datos_subjetivos" to "Datos subjetivos",
        "datos_objetivos" to "Datos objetivos",
        "alimentacion" to "Alimentación",
        "diuresis_catarsis" to "Diuresis y catarsis",
        "somnia" to "Somnia",
        "habitos" to "Hábitos",
        "examen_fisico" to "Examen físico",
        "examenes_complementarios" to "Exámenes complementarios",
        "interconsulta" to "Interconsultas",
        "conductas" to "Conductas",
        "nota" to "Nota",
    )
    private val nombreServidor = catalogoServidor.toMap()

    // Copia de `CargaInicialPerfiles::PERFILES` (tabla de la spec): pestaña → sección → tipos.
    private val clinicaGeneral = perfil(
        "Clínica general",
        "Antec. Familiares" to listOf("antecedentes_familiares" to "CT"),
        "Consulta" to listOf(
            "datos_subjetivos" to "T", "datos_objetivos" to "T", "alimentacion" to "C", "diuresis_catarsis" to "C",
            "somnia" to "C", "habitos" to "C", "examen_fisico" to "C", "examenes_complementarios" to "CT",
            "interconsulta" to "CT", "conductas" to "CT", "nota" to "C",
        ),
    )
    private val hepatologiaInicial = perfil(
        "Hepatología",
        "Antec. Familiares" to listOf("antecedentes_familiares" to "CT"),
        "Consulta" to listOf(
            "motivo_consulta" to "C", "documentos" to "C", "laboratorio_general" to "C", "eco_abdominal" to "C",
            "serologias" to "C", "tabla_peso" to "C", "child_pugh" to "C", "veda" to "C", "biopsia_hepatica" to "C",
            "estudios_cardiologicos" to "C", "tc_rmn" to "C", "datos_subjetivos" to "T", "datos_objetivos" to "T",
            "examenes_complementarios" to "T", "interconsulta" to "T", "conductas" to "T",
        ),
    )

    private fun perfil(nombre: String, vararg pestanias: Pair<String, List<Pair<String, String>>>) = PerfilHc(
        id = 1,
        nombre = nombre,
        pestanias = pestanias.map { (pestania, secciones) ->
            PestaniaPerfil(pestania, secciones.map { (id, tipos) ->
                SeccionPerfil(id, nombreServidor.getValue(id), buildSet {
                    if ('C' in tipos) add(CONSULTA)
                    if ('T' in tipos) add(TELEMEDICINA)
                })
            })
        },
    )

    private fun grupos(perfil: PerfilHc, tipo: TipoConsultaHc) =
        armarDefinicion(catalogoHcGeneral, perfil).definicion.tipoConsulta(tipo.codigo)!!.grupos.map { it.titulo to it.secciones }

    @Test
    fun ids_y_nombres_por_defecto_iguales_al_catalogo_del_servidor_y_en_orden() {
        assertEquals(catalogoServidor, catalogoHcGeneral.secciones.map { it.id to it.def.titulo })
    }

    @Test
    fun cada_seccion_tiene_lo_que_necesita_para_mostrarse() {
        (catalogoHcGeneral.secciones + catalogoHcGeneral.legadas).forEach { s -> assertDependencias(s) }
    }

    private fun assertDependencias(s: SeccionCatalogo) {
        val def = s.def
        when (def.tipo) {
            TipoSeccion.LABORATORIO -> assertNotNull(s.laboratorios.firstOrNull { it.tipo == def.laboratorioTipo }, "laboratorio de ${def.id}")
            TipoSeccion.REGISTROS -> assertNotNull(s.registros.firstOrNull { it.tipo == def.registroTipo }, "registro de ${def.id}")
            TipoSeccion.ANTECEDENTES -> assertNotNull(s.antecedentes.firstOrNull { it.categoria == def.categoriaAntecedentes }, "antecedentes de ${def.id}")
            TipoSeccion.FORM -> assertTrue(def.campos.isNotEmpty(), "campos de ${def.id}")
            TipoSeccion.CUSTOM -> assertEquals(def.id, def.renderKey, "renderer de ${def.id}")
            else -> Unit
        }
    }

    @Test
    fun diuresis_y_somnia_son_texto() {
        assertEquals(TipoSeccion.TEXTO, catalogoHcGeneral.seccion("diuresis_catarsis")?.def?.tipo)
        assertEquals(TipoSeccion.TEXTO, catalogoHcGeneral.seccion("somnia")?.def?.tipo)
    }

    @Test
    fun base_clinica_con_examen_fisico_sin_frecuencia_ni_circunferencia() {
        val base = catalogoHcGeneral.base
        assertEquals("clinica", base.codigo)
        assertEquals("Clínica", base.nombre)
        assertEquals(
            setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.NOTA),
            base.examenFisicoCampos,
        )
    }

    @Test
    fun legadas_solo_para_leer_y_fuera_del_catalogo() {
        assertEquals(
            listOf(SeccionesComunes.DIAGNOSTICO, SeccionesComunes.AUDIO, SeccionesComunes.FOTOS, SeccionesComunes.ANTECEDENTES_PERSONALES),
            catalogoHcGeneral.legadas.map { it.id },
        )
        assertTrue(catalogoHcGeneral.legadas.none { it.id in nombreServidor })
    }

    @Test
    fun clinica_general_inicial_arma_las_pestanias_de_la_spec() {
        assertEquals(emptyList(), armarDefinicion(catalogoHcGeneral, clinicaGeneral).desconocidas)
        assertEquals(
            listOf(
                "Antec. Familiares" to listOf("antecedentes_familiares"),
                "Consulta" to listOf(
                    "alimentacion", "diuresis_catarsis", "somnia", "habitos", "examen_fisico", "examenes_complementarios",
                    "interconsulta", "conductas", "nota",
                ),
            ),
            grupos(clinicaGeneral, CONSULTA),
        )
        assertEquals(
            listOf(
                "Antec. Familiares" to listOf("antecedentes_familiares"),
                "Consulta" to listOf("datos_subjetivos", "datos_objetivos", "examenes_complementarios", "interconsulta", "conductas"),
            ),
            grupos(clinicaGeneral, TELEMEDICINA),
        )
    }

    @Test
    fun hepatologia_inicial_arma_las_pestanias_de_la_spec() {
        assertEquals(emptyList(), armarDefinicion(catalogoHcGeneral, hepatologiaInicial).desconocidas)
        assertEquals(
            listOf(
                "Antec. Familiares" to listOf("antecedentes_familiares"),
                "Consulta" to listOf(
                    "motivo_consulta", "documentos", "laboratorio_general", "eco_abdominal", "serologias", "tabla_peso",
                    "child_pugh", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn",
                ),
            ),
            grupos(hepatologiaInicial, CONSULTA),
        )
        assertEquals(
            listOf(
                "Antec. Familiares" to listOf("antecedentes_familiares"),
                "Consulta" to listOf("datos_subjetivos", "datos_objetivos", "examenes_complementarios", "interconsulta", "conductas"),
            ),
            grupos(hepatologiaInicial, TELEMEDICINA),
        )
    }

    @Test
    fun la_definicion_armada_tiene_las_dependencias_de_sus_secciones() {
        val def = armarDefinicion(catalogoHcGeneral, hepatologiaInicial).definicion
        assertEquals("clinica", def.codigo)
        def.secciones.forEach { s ->
            s.laboratorioTipo?.let { assertNotNull(def.laboratorio(it), "laboratorio de ${s.id}") }
            s.registroTipo?.let { assertNotNull(def.registro(it), "registro de ${s.id}") }
            s.categoriaAntecedentes?.let { c -> assertTrue(def.antecedentes.any { it.categoria == c }, "antecedentes de ${s.id}") }
        }
    }

    // RF-33: la HC general convive con las definiciones de siempre sin cambiarlas.
    @Test
    fun clinica_medica_sigue_igual() {
        assertEquals("clinica", clinicaMedica.codigo)
        assertEquals("Clínica médica", clinicaMedica.nombre)
        assertEquals(
            listOf(
                "control" to listOf("diagnostico", "motivo_consulta", "audio", "examen_fisico", "laboratorio_general", "alimentacion", "examenes_complementarios", "interconsulta", "documentos", "conductas", "nota"),
                "telemedicina" to listOf("datos_subjetivos", "datos_objetivos", "examenes_complementarios", "interconsulta", "conductas"),
                "foto" to listOf("fotos"),
            ),
            clinicaMedica.tiposConsulta.map { it.codigo to it.secciones },
        )
        assertTrue(clinicaMedica.tiposConsulta.all { it.grupos.isEmpty() })
        assertEquals(
            listOf(
                "antecedentes_personales" to "Antec. personales", "antecedentes_familiares" to "Antec. familiares",
                "diagnostico" to "Diagnósticos / enfermedades", "motivo_consulta" to "Motivo de consulta",
                "audio" to "Grabación de audio de la consulta", "examen_fisico" to "Examen físico",
                "laboratorio_general" to "Laboratorio general", "alimentacion" to "Alimentación",
                "examenes_complementarios" to "Exámenes complementarios", "interconsulta" to "Interconsultas",
                "documentos" to "Documentos (PDF e imágenes)", "conductas" to "Conductas", "nota" to "Nota",
                "datos_subjetivos" to "Datos subjetivos", "datos_objetivos" to "Datos objetivos",
                "fotos" to "Historia clínica digitalizada (fotos)", "habitos" to "Hábitos",
            ),
            clinicaMedica.secciones.map { it.id to it.titulo },
        )
        assertEquals(listOf("personales", "familiares"), clinicaMedica.antecedentes.map { it.categoria })
        assertEquals(listOf("laboratorio_general"), clinicaMedica.laboratorios.map { it.tipo })
        assertEquals(52, clinicaMedica.laboratorios.single().analitos.size)
        assertEquals(
            setOf(
                CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL,
                CampoExamenFisico.FRECUENCIA_CARDIACA, CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL, CampoExamenFisico.NOTA,
            ),
            clinicaMedica.examenFisicoCampos,
        )
    }

    @Test
    fun hepatologia_sigue_igual() {
        assertEquals("hepatologia", hepatologia.codigo)
        assertEquals("Hepatología", hepatologia.nombre)
        assertEquals(
            listOf(
                "control" to listOf("motivo_consulta", "audio", "examen_fisico", "laboratorio_general", "laboratorio_reducido", "tabla_peso", "serologias", "child_pugh",
                    "eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn", "documentos", "conductas", "nota"),
                "telemedicina" to listOf("datos_subjetivos", "datos_objetivos", "conductas"),
                "foto" to listOf("fotos"),
            ),
            hepatologia.tiposConsulta.map { it.codigo to it.secciones },
        )
        assertTrue(hepatologia.tiposConsulta.all { it.grupos.isEmpty() })
        assertEquals(
            listOf(
                "antecedentes_personales" to "Antec. personales", "antecedentes_familiares" to "Antec. familiares",
                "motivo_consulta" to "Motivo de consulta", "audio" to "Grabación de audio de la consulta",
                "examen_fisico" to "Examen físico", "laboratorio_general" to "Laboratorio general",
                "laboratorio_reducido" to "Laboratorio (reducido)", "tabla_peso" to "Tabla de peso",
                "serologias" to "Serologías hepatitis", "child_pugh" to "Child-Pugh-Turcotte",
                "eco_abdominal" to "Ecografía abdominal", "veda" to "Videoendoscopia digestiva alta",
                "biopsia_hepatica" to "Biopsia hepática", "estudios_cardiologicos" to "Estudios cardiológicos",
                "tc_rmn" to "TC / RMN / ColangioRMN", "documentos" to "Documentos (PDF e imágenes)",
                "conductas" to "Conductas", "nota" to "Nota", "datos_subjetivos" to "Datos subjetivos",
                "datos_objetivos" to "Datos objetivos", "fotos" to "Historia clínica digitalizada (fotos)",
            ),
            hepatologia.secciones.map { it.id to it.titulo },
        )
        assertEquals(listOf("laboratorio_general", "laboratorio_reducido", "peso"), hepatologia.laboratorios.map { it.tipo })
        assertEquals(
            listOf("examen_complementario", "interconsulta", "screening", "eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn"),
            hepatologia.registros.map { it.tipo },
        )
        assertEquals(clinicaMedica.examenFisicoCampos, hepatologia.examenFisicoCampos)
        assertEquals(setOf("child_pugh"), hepatologiaContribution.renderers.keys)
    }

    @Test
    fun la_contribucion_registra_la_hc_general_con_su_catalogo_y_child_pugh() {
        assertEquals("clinica", hcGeneralContribution.definicion.codigo)
        assertEquals("Clínica", hcGeneralContribution.definicion.nombre)
        assertEquals(catalogoHcGeneral, hcGeneralContribution.catalogo)
        assertEquals(setOf("child_pugh"), hcGeneralContribution.renderers.keys)
        assertEquals("child_pugh", catalogoHcGeneral.seccion("child_pugh")?.def?.renderKey)
        assertEquals(null, hepatologiaContribution.catalogo)
    }

    // "Decidido" C2: la legada de diagnósticos no hace copiar; la clínica médica de antes sí copiaba.
    @Test
    fun copia_de_diagnosticos_con_el_catalogo_real() {
        assertFalse(copiaDiagnosticos(armarDefinicion(catalogoHcGeneral, hepatologiaInicial).definicion))
        assertFalse(copiaDiagnosticos(hcGeneralContribution.definicion))
        assertTrue(copiaDiagnosticos(clinicaMedica))
        assertFalse(copiaDiagnosticos(hepatologia))
    }
}
