package com.salud360.especialidades.clinica

import com.salud360.core.data.hcgeneral.armarDefinicion
import com.salud360.core.data.hcgeneral.copiaDiagnosticos
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.ModoForm
import com.salud360.core.model.especialidad.ModoRegistro
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.SeccionCatalogo
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoConsultaHc
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoConsultaHc.TELEMEDICINA
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.features.hc.SeccionesGenericas as G
import com.salud360.features.hc.iconoSeccion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Catálogo de la HC general de la app contra el del servidor y los perfiles iniciales (RF-31, RF-33). */
class CatalogoHcGeneralTest {
    // Copia de `CatalogoSecciones::SECCIONES` de hclinica: ids, orden y nombre por defecto ("Decidido" B1).
    private val catalogoServidor = listOf(
        "antecedentes_familiares" to "Antecedentes familiares",
        "enfermedades" to "Enfermedades",
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

    // RF-38: los cinco estudios guardan una entrada por consulta; el resto de los registros, una lista.
    @Test
    fun los_estudios_con_historial_son_uno_por_consulta_y_los_demas_registros_lista() {
        val estudios = listOf("eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn")
        val modos = (catalogoHcGeneral.secciones + catalogoHcGeneral.legadas).flatMap { it.registros }.associate { it.tipo to it.modo }
        assertEquals(estudios.associateWith { ModoRegistro.UNO_POR_CONSULTA }, modos.filterKeys { it in estudios })
        assertEquals(
            mapOf("examen_complementario" to ModoRegistro.LISTA, "interconsulta" to ModoRegistro.LISTA),
            modos.filterKeys { it !in estudios },
        )
        // La definición armada usa los del catálogo; las de `hepatologia` (fuente de las secciones) no cambian (RF-33).
        val def = armarDefinicion(catalogoHcGeneral, hepatologiaInicial).definicion
        estudios.forEach { assertEquals(ModoRegistro.UNO_POR_CONSULTA, def.registro(it)?.modo, it) }
        assertTrue(hepatologia.registros.all { it.modo == ModoRegistro.LISTA })
        assertTrue(clinicaMedica.registros.all { it.modo == ModoRegistro.LISTA })
    }

    // RF-65, RF-68, RF-73: serologías de la HC general en grilla, con los mismos campos que se guardaban hasta ahora.
    @Test
    fun serologias_en_grilla_con_los_16_marcadores_de_siempre() {
        val def = catalogoHcGeneral.seccion("serologias")!!.def
        assertEquals(ModoForm.GRILLA_SI_NO, def.modoForm)
        assertEquals(TipoSeccion.FORM, def.tipo)
        assertEquals(
            listOf(
                "igm_hav" to "IgM HAV", "ags_hbv" to "AgS HBV", "ig_anti_hcv" to "Ig anti HCV", "fan" to "FAN",
                "anti_lkm" to "Anti LKM", "igg_hav" to "IgG HAV", "ig_anti_core" to "Ig anti core",
                "ig_anti_s_hbv" to "Ig anti S HBV", "ig_anti_hdv" to "Ig anti HDV", "asma" to "ASMA",
                "fibrotest" to "Fibrotest", "hiv" to "HIV", "ag_e" to "Ag e", "anti_ag_e" to "Anti Ag e",
                "ig_anti_hev" to "Ig anti HEV", "ama" to "AMA",
            ),
            def.campos.map { it.clave to it.etiqueta },
        )
        assertTrue(def.campos.all { it.tipo == TipoCampo.SELECT && it.opciones == listOf("SI", "NO") })
        // Mismos campos que la de hepatología: lo guardado no cambia (RF-68).
        assertEquals(hepatologia.seccion("serologias")!!.campos, def.campos)
        assertEquals(ModoForm.GRILLA_SI_NO, armarDefinicion(catalogoHcGeneral, hepatologiaInicial).definicion.seccion("serologias")?.modoForm)
    }

    @Test
    fun ninguna_otra_seccion_ni_especialidad_en_grilla() {
        val enGrilla = (catalogoHcGeneral.secciones + catalogoHcGeneral.legadas).map { it.def }
            .filter { it.modoForm == ModoForm.GRILLA_SI_NO }.map { it.id }
        assertEquals(listOf("serologias"), enGrilla)
        assertTrue(hepatologia.secciones.all { it.modoForm == ModoForm.FILAS })
        assertTrue(clinicaMedica.secciones.all { it.modoForm == ModoForm.FILAS })
    }

    // RF-83, RF-90: solo el motivo de la HC general muestra las consultas previas al lado; lo guardado no cambia.
    @Test
    fun solo_el_motivo_de_la_hc_general_con_historial() {
        val motivo = catalogoHcGeneral.seccion("motivo_consulta")!!.def
        assertTrue(motivo.conHistorial)
        assertEquals(G.motivo.copy(conHistorial = true), motivo)
        assertTrue(armarDefinicion(catalogoHcGeneral, hepatologiaInicial).definicion.seccion("motivo_consulta")!!.conHistorial)
        val conHistorial = (catalogoHcGeneral.secciones + catalogoHcGeneral.legadas).map { it.def }.filter { it.conHistorial }.map { it.id }
        assertEquals(listOf("motivo_consulta"), conHistorial)
        assertFalse(G.motivo.conHistorial)
        assertTrue(hepatologia.secciones.none { it.conHistorial })
        assertTrue(clinicaMedica.secciones.none { it.conHistorial })
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

    // RF-29, RF-30: la hepatología ya no es una HC (no está registrada), pero lo que guardó se sigue leyendo desde la HC general.
    // `hepatologia` es solo la fuente de las secciones, laboratorios y registros del catálogo: si alguien la cambia o la borra, esto avisa.
    @Test
    fun las_secciones_de_hepatologia_se_leen_desde_la_hc_general() {
        val delCatalogo = catalogoHcGeneral.secciones + catalogoHcGeneral.legadas
        // El catálogo copia de `hepatologia`, así que comparar solo contra ella no detectaría un cambio en ella: lo guardado se fija a mano.
        assertEquals(
            listOf(
                "antecedentes_personales", "antecedentes_familiares", "motivo_consulta", "audio", "examen_fisico", "laboratorio_general",
                "laboratorio_reducido", "tabla_peso", "serologias", "child_pugh", "eco_abdominal", "veda", "biopsia_hepatica",
                "estudios_cardiologicos", "tc_rmn", "documentos", "conductas", "nota", "datos_subjetivos", "datos_objetivos", "fotos",
            ),
            hepatologia.secciones.map { it.id },
        )
        assertEquals(
            listOf("hemograma", "ferritina", "hepatograma", "glucemia", "hba1c", "colesterol_total", "ldl", "tg", "urea", "creatinina", "rac", "cortisol_matutino", "testosterona_total", "cpk", "otros"),
            hepatologia.laboratorio("laboratorio_reducido")!!.analitos.map { it.clave },
        )
        assertEquals(listOf("peso"), hepatologia.laboratorio("peso")!!.analitos.map { it.clave })
        assertEquals(52, hepatologia.laboratorio("laboratorio_general")!!.analitos.size)
        hepatologia.secciones.forEach { s ->
            val c = assertNotNull(delCatalogo.firstOrNull { it.id == s.id }, "sección ${s.id} en el catálogo o en las legadas").def
            assertEquals(s.campos.map { it.clave to it.tipo }, c.campos.map { it.clave to it.tipo }, "campos de ${s.id}")
            assertEquals(s.registroTipo, c.registroTipo, "registroTipo de ${s.id}")
            assertEquals(s.laboratorioTipo, c.laboratorioTipo, "laboratorioTipo de ${s.id}")
            assertEquals(s.categoriaAntecedentes, c.categoriaAntecedentes, "categoriaAntecedentes de ${s.id}")
        }
        // Laboratorios: los tres tipos de hepatología, con los mismos analitos.
        val laboratorios = delCatalogo.flatMap { it.laboratorios }.associateBy { it.tipo }
        assertEquals(listOf("laboratorio_general", "laboratorio_reducido", "peso"), hepatologia.laboratorios.map { it.tipo })
        hepatologia.laboratorios.forEach { l ->
            val c = assertNotNull(laboratorios[l.tipo], "laboratorio ${l.tipo} en el catálogo")
            assertEquals(l.analitos.map { it.clave }, c.analitos.map { it.clave }, "analitos de ${l.tipo}")
        }
        // Registros que usa alguna sección (los estudios con fecha): mismas claves de campo.
        val registros = delCatalogo.flatMap { it.registros }.associateBy { it.tipo }
        val usados = hepatologia.secciones.mapNotNull { it.registroTipo }
        assertEquals(listOf("eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn"), usados)
        usados.forEach { tipo ->
            val c = assertNotNull(registros[tipo], "registro $tipo en el catálogo")
            assertEquals(hepatologia.registro(tipo)!!.campos.map { it.clave to it.tipo }, c.campos.map { it.clave to it.tipo }, "campos del registro $tipo")
        }
        // Antecedentes: las dos categorías que guardaba existen en el catálogo o en las legadas (RF-30), con las mismas casillas.
        val antecedentes = delCatalogo.flatMap { it.antecedentes }.associateBy { it.categoria }
        assertEquals(listOf("personales", "familiares"), hepatologia.antecedentes.map { it.categoria })
        hepatologia.antecedentes.forEach { a ->
            val c = assertNotNull(antecedentes[a.categoria], "antecedentes ${a.categoria} en el catálogo o en las legadas")
            assertEquals(a.items.map { it.clave }, c.items.map { it.clave }, "casillas de ${a.categoria}")
        }
        // Lo que se dibuja a medida (Child-Pugh) lo registra la HC general.
        assertTrue("child_pugh" in hcGeneralContribution.renderers.keys)
        assertEquals(setOf("child_pugh"), hepatologia.secciones.mapNotNull { it.renderKey }.toSet())
    }

    @Test
    fun la_contribucion_registra_la_hc_general_con_su_catalogo_child_pugh_y_enfermedades() {
        assertEquals("clinica", hcGeneralContribution.definicion.codigo)
        assertEquals("Clínica", hcGeneralContribution.definicion.nombre)
        assertEquals(catalogoHcGeneral, hcGeneralContribution.catalogo)
        assertEquals(setOf("child_pugh", "enfermedades"), hcGeneralContribution.renderers.keys)
        assertEquals("child_pugh", catalogoHcGeneral.seccion("child_pugh")?.def?.renderKey)
    }

    // RF-5: toda sección CUSTOM del catálogo tiene su renderer (si no, la pantalla dice "Sección no disponible").
    @Test
    fun cada_renderKey_del_catalogo_tiene_su_renderer_registrado() {
        val claves = catalogoHcGeneral.secciones.mapNotNull { it.def.renderKey }.toSet()
        assertTrue(claves.isNotEmpty())
        assertEquals(emptySet(), claves - hcGeneralContribution.renderers.keys)
    }

    // RF-56: el ícono de "Enfermedades" no es el ojo de la legada de diagnósticos ni el de reserva (decisión P4-A).
    @Test
    fun enfermedades_tiene_icono_propio() {
        val icono = iconoSeccion(catalogoHcGeneral.seccion("enfermedades")!!.def.icono)
        assertNotNull(icono)
        assertEquals("enfermedades", catalogoHcGeneral.seccion("enfermedades")!!.def.icono)
        assertNotEquals(iconoSeccion("diagnostico"), icono)
        assertNotEquals(iconoSeccion("clave_inexistente"), icono)
        assertNotEquals(iconoSeccion("form"), icono)
        // Ninguna otra clave conocida comparte su ícono.
        val otras = listOf(
            "motivo", "antecedentes", "familia", "examen", "laboratorio", "estudios", "registros", "medicacion", "vacunas",
            "alimentacion", "escolaridad", "desarrollo", "cardio", "corazon", "embarazo", "psico", "diagnostico", "archivos",
            "texto", "form", "diuresis", "sueno", "actividades", "pantallas", "habitos", "menarca", "conductas", "observaciones",
            "nota", "subjetivo", "objetivo", "internacion", "screening", "interconsulta", "curvas", "lactancia", "dibujo",
            "plan", "resumen",
        )
        assertTrue(otras.none { iconoSeccion(it) == icono })
    }

    // RF-56: "Enfermedades" es una sección propia (CUSTOM), distinta de la legada de diagnósticos.
    @Test
    fun enfermedades_es_custom_segunda_y_no_es_diagnostico_ni_legada() {
        val seccion = catalogoHcGeneral.seccion("enfermedades")!!
        assertEquals(TipoSeccion.CUSTOM, seccion.def.tipo)
        assertEquals(seccion.def.id, seccion.def.renderKey)
        assertEquals("Enfermedades", seccion.def.titulo)
        assertEquals(1, catalogoHcGeneral.secciones.indexOfFirst { it.id == "enfermedades" })
        assertTrue(catalogoHcGeneral.legadas.none { it.id == "enfermedades" })
        assertTrue(catalogoHcGeneral.secciones.none { it.id == SeccionesComunes.DIAGNOSTICO })
        assertTrue(seccion.laboratorios.isEmpty() && seccion.registros.isEmpty() && seccion.antecedentes.isEmpty())
    }

    // "Decidido" C2: la legada de diagnósticos no hace copiar; la clínica médica de antes sí copiaba.
    @Test
    fun copia_de_diagnosticos_con_el_catalogo_real() {
        assertFalse(copiaDiagnosticos(armarDefinicion(catalogoHcGeneral, hepatologiaInicial).definicion))
        assertFalse(copiaDiagnosticos(hcGeneralContribution.definicion))
        assertTrue(copiaDiagnosticos(clinicaMedica))
    }
}
