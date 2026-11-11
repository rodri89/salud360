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
 * Spec 005 RF-3 y RF-4 (T17, D9; T60-bis): qué secciones de la HC general se ofrecen para cargar y se envían a la web con cada candado (el conjunto de secciones habilitadas), y
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

    private fun estados(ids: List<String>, habilitadas: Set<String>) = ids.associateWith { envioDeSeccion(it, habilitadas) }

    // ---------- Qué ofrece cada versión ----------

    @Test
    fun la_version_de_esta_entrega_es_la_e1() {
        assertEquals(E1, SUBENTREGA_HABILITADA)
    }

    @Test
    fun en_la_e1_se_ofrecen_los_textos_y_el_examen_fisico() {
        assertEquals((textos + "examen_fisico").associateWith { SE_ENVIA }, estados(textos + "examen_fisico", seccionesDe(E1)))
        (textos + "examen_fisico").forEach { assertTrue(seccionSeOfrece(it, seccionesDe(E1)), it) }
    }

    @Test
    fun en_la_e1_el_resto_de_la_hc_general_todavia_no_se_envia() {
        val resto = formularios + antecedentesYListas + laboratorio + adjuntos
        assertEquals(resto.associateWith { TODAVIA_NO }, estados(resto, seccionesDe(E1)))
        resto.forEach {
            assertFalse(seccionSeOfrece(it, seccionesDe(E1)), it)
            assertEquals("Esta sección todavía no se envía a la web", avisoDeSeccion(it, seccionesDe(E1)), it)
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
        assertTrue((formularios + antecedentesYListas).all { envioDeSeccion(it, seccionesDe(E2)) == SE_ENVIA })
        assertTrue((laboratorio + adjuntos).all { envioDeSeccion(it, seccionesDe(E2)) == TODAVIA_NO })
        // E3: laboratorio, peso y pendientes.
        assertTrue((formularios + antecedentesYListas + laboratorio).all { envioDeSeccion(it, seccionesDe(E3)) == SE_ENVIA })
        assertTrue(adjuntos.all { envioDeSeccion(it, seccionesDe(E3)) == TODAVIA_NO })
        // E4: adjuntos.
        assertTrue((textos + "examen_fisico" + formularios + antecedentesYListas + laboratorio + adjuntos).all { envioDeSeccion(it, seccionesDe(E4)) == SE_ENVIA })
        // Una subentrega posterior ofrece todo lo que ofrece la anterior.
        val subentregas = SubentregaDeEscritura.entries
        val todas = textos + "examen_fisico" + formularios + antecedentesYListas + laboratorio + adjuntos
        subentregas.zipWithNext().forEach { (antes, despues) ->
            todas.filter { seccionSeOfrece(it, seccionesDe(antes)) }.forEach { assertTrue(seccionSeOfrece(it, seccionesDe(despues)), "$it en $despues") }
        }
    }

    @Test
    fun lo_que_la_web_no_guarda_no_se_ofrece_en_ninguna_subentrega() {
        SubentregaDeEscritura.entries.forEach { sub ->
            laWebNoGuarda.forEach {
                assertEquals(NUNCA, envioDeSeccion(it, seccionesDe(sub)), "$it en $sub")
                assertFalse(seccionSeOfrece(it, seccionesDe(sub)), "$it en $sub")
                assertEquals("No se envía a la web", avisoDeSeccion(it, seccionesDe(sub)), "$it en $sub")
            }
        }
        assertEquals(laWebNoGuarda.toSet(), SECCIONES_QUE_LA_WEB_NO_GUARDA)
    }

    @Test
    fun enfermedades_tiene_su_propia_api_y_se_ofrece_siempre() {
        SubentregaDeEscritura.entries.forEach { sub ->
            assertEquals(APARTE, envioDeSeccion("enfermedades", seccionesDe(sub)))
            assertTrue(seccionSeOfrece("enfermedades", seccionesDe(sub)))
            assertEquals(null, avisoDeSeccion("enfermedades", seccionesDe(sub)), "no lleva aviso: no depende del envío de la consulta")
        }
    }

    @Test
    fun una_seccion_que_no_se_conoce_no_se_ofrece() {
        assertEquals(TODAVIA_NO, envioDeSeccion("seccion_inventada", seccionesDe(E4)))
        assertFalse(seccionSeOfrece("seccion_inventada", seccionesDe(E4)))
        assertEquals(TODAVIA_NO, envioDeSeccion("fotos", seccionesDe(E4)), "la historia digitalizada no está en ninguna subentrega de la 005")
    }

    @Test
    fun la_oferta_de_una_lista_de_secciones_conserva_el_orden_y_el_candado_de_la_version() {
        val perfil = listOf("antecedentes_familiares", "motivo_consulta", "laboratorio_general", "examen_fisico", "conductas", "diagnostico", "enfermedades")
        assertEquals(listOf("motivo_consulta", "examen_fisico", "conductas", "enfermedades"), seccionesQueSeOfrecen(perfil, seccionesDe(E1)))
        assertEquals(listOf("antecedentes_familiares", "motivo_consulta", "examen_fisico", "conductas", "enfermedades"), seccionesQueSeOfrecen(perfil, seccionesDe(E2)))
        assertEquals(listOf("antecedentes_familiares", "motivo_consulta", "laboratorio_general", "examen_fisico", "conductas", "enfermedades"), seccionesQueSeOfrecen(perfil, seccionesDe(E3)))
        // Sin indicar el conjunto rige el de la versión instalada.
        assertEquals(seccionesQueSeOfrecen(perfil, SECCIONES_HABILITADAS), seccionesQueSeOfrecen(perfil))
    }

    // ---------- El candado por sección (T60-bis, RF-4) ----------

    private val todasLasDeLaHcGeneral = textos + "examen_fisico" + formularios + antecedentesYListas + laboratorio + adjuntos

    @Test
    fun el_candado_habilitado_es_el_de_la_e1_mas_las_ocho_secciones_del_alcance_del_usuario() {
        // Decisión del usuario (2026-11-11): habilitar antes de cerrar E1 solo estas de E2 —serologías, Child-Pugh, antecedentes familiares y los
        // cinco estudios—; el resto de E2, E3 y E4 sigue "todavía no".
        val delAlcanceDeE2 = listOf("serologias", "child_pugh", "antecedentes_familiares", "eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn")
        assertEquals((textos + "examen_fisico" + delAlcanceDeE2).toSet(), SECCIONES_HABILITADAS)
        assertEquals(17, SECCIONES_HABILITADAS.size, "los nueve de E1 (ocho textos y el examen) y las ocho del alcance; enfermedades va aparte y no entra")
        assertEquals(delAlcanceDeE2.associateWith { SE_ENVIA }, estados(delAlcanceDeE2, SECCIONES_HABILITADAS))
        (textos + "examen_fisico").forEach { assertEquals(SE_ENVIA, envioDeSeccion(it), it) }
        assertFalse("enfermedades" in SECCIONES_HABILITADAS, "enfermedades tiene su API y no es una sección de una subentrega")
        assertEquals(E1, SUBENTREGA_HABILITADA, "la constante no cambia: el candado real es SECCIONES_HABILITADAS")
    }

    @Test
    fun lo_que_el_alcance_del_usuario_deja_afuera_sigue_todavia_no() {
        // Ni la alimentación (E2), ni los exámenes complementarios ni las interconsultas (E2), ni E3, ni los adjuntos (E4).
        val fueraDelAlcance = listOf(
            "alimentacion", "examenes_complementarios", "interconsulta",
            "laboratorio_general", "laboratorio_reducido", "tabla_peso", "pendientes", "documentos",
        )
        assertEquals(fueraDelAlcance.associateWith { TODAVIA_NO }, estados(fueraDelAlcance, SECCIONES_HABILITADAS))
        fueraDelAlcance.forEach {
            assertFalse(seccionSeOfrece(it), it)
            assertEquals(AVISO_TODAVIA_NO_SE_ENVIA, avisoDeSeccion(it), it)
        }
    }

    @Test
    fun seccionesDe_suma_las_de_las_subentregas_anteriores_y_deja_afuera_lo_nunca_y_lo_aparte() {
        val e1 = seccionesDe(E1)
        val e2 = seccionesDe(E2)
        val e3 = seccionesDe(E3)
        val e4 = seccionesDe(E4)
        assertTrue(e2.containsAll(e1) && e3.containsAll(e2) && e4.containsAll(e3), "cada subentrega contiene a la anterior")
        assertTrue(e1 != e2 && e2 != e3 && e3 != e4, "y suma algo")
        assertEquals((formularios + antecedentesYListas).toSet(), e2 - e1)
        assertEquals(laboratorio.toSet(), e3 - e2)
        assertEquals(adjuntos.toSet(), e4 - e3)
        assertEquals(todasLasDeLaHcGeneral.toSet(), e4)
        for (conjunto in listOf(e1, e2, e3, e4)) {
            assertTrue((laWebNoGuarda + "enfermedades").none { it in conjunto }, "lo que la web no guarda y lo que tiene su API no son secciones de una subentrega")
        }
    }

    @Test
    fun un_conjunto_propio_enciende_solo_esos_ids_y_el_resto_queda_todavia_no() {
        val solo = setOf("serologias")
        assertEquals(SE_ENVIA, envioDeSeccion("serologias", solo))
        assertTrue(seccionSeOfrece("serologias", solo))
        assertEquals(null, avisoDeSeccion("serologias", solo))
        (todasLasDeLaHcGeneral - "serologias").forEach {
            assertEquals(TODAVIA_NO, envioDeSeccion(it, solo), it)
            assertFalse(seccionSeOfrece(it, solo), it)
            assertEquals(AVISO_TODAVIA_NO_SE_ENVIA, avisoDeSeccion(it, solo), it)
        }
    }

    @Test
    fun se_puede_encender_de_a_una_sin_tocar_lo_de_la_e1_y_sin_encender_lo_que_no_se_pidio() {
        val habilitadas = seccionesDe(E1) + setOf("serologias", "child_pugh", "antecedentes_familiares")
        val encendidas = setOf("serologias", "child_pugh", "antecedentes_familiares")
        (textos + "examen_fisico").forEach { assertEquals(SE_ENVIA, envioDeSeccion(it, habilitadas), it) }
        encendidas.forEach { assertEquals(SE_ENVIA, envioDeSeccion(it, habilitadas), it) }
        // Lo que el alcance nuevo deja afuera sigue apagado aunque sea de la misma subentrega.
        listOf("alimentacion", "examenes_complementarios", "interconsulta", "laboratorio_reducido", "pendientes", "eco_abdominal", "documentos")
            .forEach { assertEquals(TODAVIA_NO, envioDeSeccion(it, habilitadas), it) }
    }

    @Test
    fun un_conjunto_vacio_no_ofrece_ninguna_seccion_de_la_consulta_pero_enfermedades_sigue_y_lo_nunca_sigue_nunca() {
        todasLasDeLaHcGeneral.forEach { assertEquals(TODAVIA_NO, envioDeSeccion(it, emptySet()), it) }
        assertEquals(APARTE, envioDeSeccion("enfermedades", emptySet()))
        laWebNoGuarda.forEach { assertEquals(NUNCA, envioDeSeccion(it, emptySet()), it) }
    }

    @Test
    fun nunca_y_aparte_mandan_sobre_el_conjunto_aunque_alguien_los_ponga_adentro() {
        val conTodo = todasLasDeLaHcGeneral.toSet() + laWebNoGuarda + "enfermedades"
        laWebNoGuarda.forEach {
            assertEquals(NUNCA, envioDeSeccion(it, conTodo), it)
            assertFalse(seccionSeOfrece(it, conTodo), it)
            assertEquals(AVISO_NO_SE_ENVIA, avisoDeSeccion(it, conTodo), it)
        }
        assertEquals(APARTE, envioDeSeccion("enfermedades", conTodo))
        assertTrue(seccionSeOfrece("enfermedades", conTodo))
    }

    @Test
    fun un_id_desconocido_sigue_todavia_no_aunque_este_en_el_conjunto() {
        val conjunto = setOf("seccion_inventada", "fotos", "serologias")
        assertEquals(TODAVIA_NO, envioDeSeccion("seccion_inventada", conjunto))
        assertEquals(TODAVIA_NO, envioDeSeccion("fotos", conjunto), "la historia digitalizada no está en ninguna subentrega de la 005")
        assertFalse(seccionSeOfrece("seccion_inventada", conjunto))
        assertEquals(AVISO_TODAVIA_NO_SE_ENVIA, avisoDeSeccion("seccion_inventada", conjunto))
    }

    @Test
    fun sin_indicar_el_conjunto_rige_el_candado_de_la_version() {
        assertEquals(SE_ENVIA, envioDeSeccion("nota"))
        assertEquals(SE_ENVIA, envioDeSeccion("examen_fisico"))
        assertEquals(SE_ENVIA, envioDeSeccion("serologias"), "las del alcance del usuario viajan por omisión (T67 reducida)")
        assertEquals(SE_ENVIA, envioDeSeccion("eco_abdominal"))
        assertEquals(TODAVIA_NO, envioDeSeccion("alimentacion"), "la alimentación quedó fuera del alcance")
        assertEquals(TODAVIA_NO, envioDeSeccion("laboratorio_general"))
        for (id in todasLasDeLaHcGeneral + laWebNoGuarda + "enfermedades") {
            assertEquals(envioDeSeccion(id, SECCIONES_HABILITADAS), envioDeSeccion(id), id)
            assertEquals(seccionSeOfrece(id, SECCIONES_HABILITADAS), seccionSeOfrece(id), id)
            assertEquals(avisoDeSeccion(id, SECCIONES_HABILITADAS), avisoDeSeccion(id), id)
        }
    }

    @Test
    fun la_oferta_de_una_lista_con_un_conjunto_propio_conserva_el_orden() {
        val perfil = listOf("antecedentes_familiares", "serologias", "motivo_consulta", "alimentacion", "child_pugh", "diagnostico", "enfermedades")
        val habilitadas = seccionesDe(E1) + setOf("serologias", "child_pugh", "antecedentes_familiares")
        assertEquals(listOf("antecedentes_familiares", "serologias", "motivo_consulta", "child_pugh", "enfermedades"), seccionesQueSeOfrecen(perfil, habilitadas))
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
