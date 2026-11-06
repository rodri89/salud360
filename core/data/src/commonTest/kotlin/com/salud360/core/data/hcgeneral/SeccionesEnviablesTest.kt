package com.salud360.core.data.hcgeneral

import com.salud360.core.data.hcgeneral.EnvioDeSeccion.APARTE
import com.salud360.core.data.hcgeneral.EnvioDeSeccion.NUNCA
import com.salud360.core.data.hcgeneral.EnvioDeSeccion.SE_ENVIA
import com.salud360.core.data.hcgeneral.EnvioDeSeccion.TODAVIA_NO
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura.E1
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura.E2
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura.E3
import com.salud360.core.data.hcgeneral.SubentregaDeEscritura.E4
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.hc.Antecedente
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 005 RF-3 y RF-4 (T17, D9): qué secciones de la HC general se ofrecen para cargar y se envían a la web en cada subentrega, y
 * qué no se envía nunca porque la web no lo guarda. Puro: ids de sección, casillas y campos del examen; el catálogo real se
 * contrasta en `especialidades/clinica` (`LecturaClinicaCatalogoTest`).
 */
class SeccionesEnviablesTest {
    private val textos = listOf(
        "motivo_consulta", "datos_subjetivos", "datos_objetivos", "conductas", "nota", "habitos", "diuresis_catarsis", "somnia",
    )
    private val formularios = listOf("alimentacion", "serologias", "child_pugh")
    private val antecedentesYListas = listOf(
        "antecedentes_familiares", "eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn",
        "examenes_complementarios", "interconsulta",
    )
    private val laboratorio = listOf("laboratorio_general", "laboratorio_reducido", "tabla_peso", "pendientes")
    private val adjuntos = listOf("documentos")
    private val laWebNoGuarda = listOf("diagnostico", "audio", "antecedentes_personales")

    private fun estados(ids: List<String>, habilitada: SubentregaDeEscritura) = ids.associateWith { envioDeSeccion(it, habilitada) }

    // ---------- Qué ofrece cada versión ----------

    @Test
    fun la_version_de_esta_entrega_es_la_e1() {
        assertEquals(E1, SUBENTREGA_HABILITADA)
    }

    @Test
    fun en_la_e1_se_ofrecen_los_textos_y_el_examen_fisico() {
        assertEquals((textos + "examen_fisico").associateWith { SE_ENVIA }, estados(textos + "examen_fisico", E1))
        (textos + "examen_fisico").forEach { assertTrue(seccionSeOfrece(it, E1), it) }
    }

    @Test
    fun en_la_e1_el_resto_de_la_hc_general_todavia_no_se_envia() {
        val resto = formularios + antecedentesYListas + laboratorio + adjuntos
        assertEquals(resto.associateWith { TODAVIA_NO }, estados(resto, E1))
        resto.forEach {
            assertFalse(seccionSeOfrece(it, E1), it)
            assertEquals("Esta sección todavía no se envía a la web", avisoDeSeccion(it, E1), it)
        }
    }

    @Test
    fun el_texto_del_aviso_de_una_seccion_que_todavia_no_se_envia_es_el_de_la_spec() {
        assertEquals("Esta sección todavía no se envía a la web", AVISO_TODAVIA_NO_SE_ENVIA)
        assertEquals("No se envía a la web", AVISO_NO_SE_ENVIA)
    }

    @Test
    fun cada_subentrega_suma_sus_secciones_y_no_quita_ninguna() {
        // E2: formularios, antecedentes familiares, estudios y listas.
        assertTrue((formularios + antecedentesYListas).all { envioDeSeccion(it, E2) == SE_ENVIA })
        assertTrue((laboratorio + adjuntos).all { envioDeSeccion(it, E2) == TODAVIA_NO })
        // E3: laboratorio, peso y pendientes.
        assertTrue((formularios + antecedentesYListas + laboratorio).all { envioDeSeccion(it, E3) == SE_ENVIA })
        assertTrue(adjuntos.all { envioDeSeccion(it, E3) == TODAVIA_NO })
        // E4: adjuntos.
        assertTrue((textos + "examen_fisico" + formularios + antecedentesYListas + laboratorio + adjuntos).all { envioDeSeccion(it, E4) == SE_ENVIA })
        // Una subentrega posterior ofrece todo lo que ofrece la anterior.
        val subentregas = SubentregaDeEscritura.entries
        val todas = textos + "examen_fisico" + formularios + antecedentesYListas + laboratorio + adjuntos
        subentregas.zipWithNext().forEach { (antes, despues) ->
            todas.filter { seccionSeOfrece(it, antes) }.forEach { assertTrue(seccionSeOfrece(it, despues), "$it en $despues") }
        }
    }

    @Test
    fun lo_que_la_web_no_guarda_no_se_ofrece_en_ninguna_subentrega() {
        SubentregaDeEscritura.entries.forEach { sub ->
            laWebNoGuarda.forEach {
                assertEquals(NUNCA, envioDeSeccion(it, sub), "$it en $sub")
                assertFalse(seccionSeOfrece(it, sub), "$it en $sub")
                assertEquals("No se envía a la web", avisoDeSeccion(it, sub), "$it en $sub")
            }
        }
        assertEquals(laWebNoGuarda.toSet(), SECCIONES_QUE_LA_WEB_NO_GUARDA)
    }

    @Test
    fun enfermedades_tiene_su_propia_api_y_se_ofrece_siempre() {
        SubentregaDeEscritura.entries.forEach { sub ->
            assertEquals(APARTE, envioDeSeccion("enfermedades", sub))
            assertTrue(seccionSeOfrece("enfermedades", sub))
            assertEquals(null, avisoDeSeccion("enfermedades", sub), "no lleva aviso: no depende del envío de la consulta")
        }
    }

    @Test
    fun una_seccion_que_no_se_conoce_no_se_ofrece() {
        assertEquals(TODAVIA_NO, envioDeSeccion("seccion_inventada", E4))
        assertFalse(seccionSeOfrece("seccion_inventada", E4))
        assertEquals(TODAVIA_NO, envioDeSeccion("fotos", E4), "la historia digitalizada no está en ninguna subentrega de la 005")
    }

    @Test
    fun la_oferta_de_una_lista_de_secciones_conserva_el_orden_y_el_candado_de_la_version() {
        val perfil = listOf("antecedentes_familiares", "motivo_consulta", "laboratorio_general", "examen_fisico", "conductas", "diagnostico", "enfermedades")
        assertEquals(listOf("motivo_consulta", "examen_fisico", "conductas", "enfermedades"), seccionesQueSeOfrecen(perfil, E1))
        assertEquals(listOf("antecedentes_familiares", "motivo_consulta", "examen_fisico", "conductas", "enfermedades"), seccionesQueSeOfrecen(perfil, E2))
        assertEquals(listOf("antecedentes_familiares", "motivo_consulta", "laboratorio_general", "examen_fisico", "conductas", "enfermedades"), seccionesQueSeOfrecen(perfil, E3))
        // Sin indicar la subentrega rige la de la versión instalada.
        assertEquals(seccionesQueSeOfrecen(perfil, SUBENTREGA_HABILITADA), seccionesQueSeOfrecen(perfil))
    }

    // ---------- Dentro de una sección ----------

    @Test
    fun tabaquismo_nunca_se_ofrece_ni_se_envia_y_el_resto_de_los_antecedentes_familiares_si() {
        assertFalse(casillaSeEnvia("familiares", "tabaquismo"))
        assertEquals(setOf("tabaquismo"), CASILLAS_QUE_LA_WEB_NO_GUARDA)
        listOf("hta", "dbt_nir", "dbt_ir", "acv", "dsl", "fa", "mp", "analfabeto", "postrado", "af_ca_mama", "ca_mama", "institucionalizado", "psicofarmacos", "dependiente_terceros", "otro")
            .forEach { assertTrue(casillaSeEnvia("familiares", it), it) }
        // La nota que acompaña a los antecedentes familiares sí viaja, aunque algún perfil la rotule «antecedentes personales» (RF-3).
        assertTrue(casillaSeEnvia("familiares", CLAVE_NOTA))
    }

    @Test
    fun los_antecedentes_personales_del_catalogo_viejo_no_se_envian_en_ninguna_casilla() {
        assertFalse(casillaSeEnvia("personales", "alergias"))
        assertFalse(casillaSeEnvia("personales", "enfermedad_actual"))
        assertFalse(casillaSeEnvia("personales", CLAVE_NOTA))
    }

    @Test
    fun del_examen_fisico_solo_viajan_peso_talla_imc_tension_y_nota() {
        val viajan = CampoExamenFisico.entries.filter { campoDelExamenSeEnvia(it) }.toSet()
        assertEquals(
            setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.NOTA),
            viajan,
        )
        assertFalse(campoDelExamenSeEnvia(CampoExamenFisico.FRECUENCIA_CARDIACA))
        assertFalse(campoDelExamenSeEnvia(CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL))
        assertEquals(setOf(CampoExamenFisico.FRECUENCIA_CARDIACA, CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL), CAMPOS_DEL_EXAMEN_QUE_LA_WEB_NO_GUARDA)
    }

    // ---------- Lo ya cargado se conserva y se marca ----------

    private fun antecedente(categoria: String, clave: String, flag: Boolean = true, activo: Boolean = true) =
        Antecedente("a-$categoria-$clave", "p1", "clinica", categoria, clave, flag, "detalle de $clave", activo = activo)

    @Test
    fun lo_ya_cargado_de_lo_que_la_web_no_guarda_se_marca_no_se_envia_a_la_web() {
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeAntecedente(antecedente("familiares", "tabaquismo")))
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeAntecedente(antecedente("personales", "alergias")))
        assertEquals(null, avisoDeAntecedente(antecedente("familiares", "hta")))
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeCampoDelExamen(CampoExamenFisico.FRECUENCIA_CARDIACA))
        assertEquals(AVISO_NO_SE_ENVIA, avisoDeCampoDelExamen(CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL))
        assertEquals(null, avisoDeCampoDelExamen(CampoExamenFisico.PESO))
    }

    @Test
    fun de_una_lista_de_antecedentes_solo_pueden_viajar_los_que_la_web_guarda_y_ninguno_se_borra() {
        val todos = listOf(
            antecedente("familiares", "hta"), antecedente("familiares", "tabaquismo"), antecedente("personales", "alergias"),
            antecedente("familiares", CLAVE_NOTA), antecedente("familiares", "otro", flag = false),
        )
        val viajan = antecedentesQueSePuedenEnviar(todos)
        assertEquals(listOf("hta", CLAVE_NOTA, "otro"), viajan.map { it.clave })
        // Lo que no viaja sigue en la lista de origen, tal cual: filtrar no es borrar.
        assertEquals(5, todos.size)
        assertTrue(todos.any { it.clave == "tabaquismo" } && todos.any { it.categoria == "personales" })
    }
}
