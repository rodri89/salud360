package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.CategoriaAntecedentes
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.LaboratorioDef
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.PestaniaPerfil
import com.salud360.core.model.especialidad.RegistroDef
import com.salud360.core.model.especialidad.SeccionCatalogo
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoConsultaHc.CONSULTA
import com.salud360.core.model.especialidad.TipoConsultaHc.TELEMEDICINA
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.ExamenFisico
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Secciones con datos que quedan fuera del perfil y se muestran solo para leer: las de la consulta al final de ella
 * (RF-22) y las del paciente una sola vez en "Datos" (RF-34); RF-28 y RF-30.
 */
class SeccionesSoloLecturaTest {
    private val catalogo = CatalogoHc(
        base = EspecialidadDefinition("clinica", "Clínica", "hclinica", emptyList(), emptyList()),
        secciones = listOf(
            SeccionCatalogo(
                SeccionDef("antecedentes_familiares", "Antecedentes familiares", TipoSeccion.ANTECEDENTES, categoriaAntecedentes = "familiares"),
                antecedentes = listOf(CategoriaAntecedentes("familiares", "Antecedentes familiares", emptyList())),
            ),
            SeccionCatalogo(SeccionDef("motivo_consulta", "Motivo de consulta", TipoSeccion.TEXTO)),
            SeccionCatalogo(SeccionDef("documentos", "Adjuntos (PDF)", TipoSeccion.ARCHIVOS)),
            SeccionCatalogo(
                SeccionDef("laboratorio_general", "Laboratorio general", TipoSeccion.LABORATORIO, laboratorioTipo = "laboratorio_general"),
                laboratorios = listOf(LaboratorioDef("laboratorio_general", "Laboratorio general", emptyList())),
            ),
            SeccionCatalogo(
                SeccionDef(
                    "alimentacion", "Alimentación", TipoSeccion.FORM,
                    campos = listOf(CampoDef("hiposodica", "Hiposódica", TipoCampo.CHECK_DETALLE), CampoDef("otra", "Otra", TipoCampo.CHECK_DETALLE)),
                ),
            ),
            SeccionCatalogo(SeccionDef("child_pugh", "Child-Pugh", TipoSeccion.CUSTOM, renderKey = "child_pugh")),
            SeccionCatalogo(SeccionDef("examen_fisico", "Examen físico", TipoSeccion.EXAMEN_FISICO)),
            SeccionCatalogo(
                SeccionDef("interconsulta", "Interconsultas", TipoSeccion.REGISTROS, registroTipo = "interconsulta"),
                registros = listOf(RegistroDef("interconsulta", "Interconsultas", "Interconsulta", emptyList())),
            ),
            SeccionCatalogo(SeccionDef("conductas", "Conductas", TipoSeccion.TEXTO)),
            SeccionCatalogo(SeccionDef("nota", "Nota", TipoSeccion.TEXTO)),
            // Registro por paciente: no es de ninguna consulta.
            SeccionCatalogo(
                SeccionDef("screening", "Screening", TipoSeccion.REGISTROS, registroTipo = "screening"),
                registros = listOf(RegistroDef("screening", "Screenings", "Screening", emptyList(), porPaciente = true)),
            ),
        ),
        legadas = listOf(
            SeccionCatalogo(SeccionDef("diagnostico", "Diagnósticos", TipoSeccion.DIAGNOSTICOS)),
            SeccionCatalogo(SeccionDef("audio", "Grabación de audio", TipoSeccion.AUDIO)),
            SeccionCatalogo(SeccionDef("fotos", "HC digitalizada (fotos)", TipoSeccion.ARCHIVOS)),
            SeccionCatalogo(
                SeccionDef("antecedentes_personales", "Antecedentes personales", TipoSeccion.ANTECEDENTES, categoriaAntecedentes = "personales"),
                antecedentes = listOf(CategoriaAntecedentes("personales", "Antecedentes personales", emptyList())),
            ),
        ),
    )

    // "nota" solo en Consulta y con nombre propio; "conductas" en los dos tipos.
    private val perfil = PerfilHc(
        id = 3,
        nombre = "Perfil de prueba",
        pestanias = listOf(
            PestaniaPerfil(
                "Consulta",
                listOf(
                    SeccionPerfil("motivo_consulta", "Por qué viene", setOf(CONSULTA)),
                    SeccionPerfil("conductas", "Indicaciones", setOf(CONSULTA, TELEMEDICINA)),
                    SeccionPerfil("nota", "Apunte", setOf(CONSULTA)),
                ),
            ),
        ),
    )

    private val def = armarDefinicion(catalogo, perfil).definicion

    private fun enPerfil(tipo: String) = assertNotNull(def.tipoConsulta(tipo)).secciones.toSet()

    /** Las del perfil en todos los tipos. */
    private val perfilEntero get() = enPerfil(SeccionesComunes.TIPO_CONTROL) + enPerfil(SeccionesComunes.TIPO_TELEMEDICINA)

    private fun conDatos(datos: DatosConsulta) = seccionesConDatos(datos, def)

    private fun soloLectura(tipo: String, datos: DatosConsulta, ocultas: Set<String> = emptySet()) =
        seccionesSoloLectura(catalogo, enPerfil(tipo), conDatos(datos), ocultas, perfilEntero)

    private fun delPaciente(antecedentes: List<Antecedente>, enPerfil: Set<String> = perfilEntero, ocultas: Set<String> = emptySet()) =
        seccionesDelPacienteSoloLectura(catalogo, antecedentes, enPerfil, ocultas).map { it.id }

    private fun texto(vararg secciones: Pair<String, String>) = secciones.associate { (s, v) -> s to mapOf("texto" to v) }

    @Test
    fun campo_vacio_cero_y_cero_con_barra_no_cuentan() {
        val check = CampoDef("hiposodica", "Hiposódica", TipoCampo.CHECK_DETALLE)
        val texto = CampoDef("detalle", "Detalle", TipoCampo.TEXTO)
        assertFalse(campoTieneDato(texto, ""))
        assertFalse(campoTieneDato(texto, "   "))
        assertFalse(campoTieneDato(texto, "0"))
        assertFalse(campoTieneDato(check, "0|"))
        assertFalse(campoTieneDato(check, "0|  "))
        assertFalse(campoTieneDato(check, "|"))
        assertTrue(campoTieneDato(check, "1|"))
        assertTrue(campoTieneDato(check, "0|sin sal"))
        assertTrue(campoTieneDato(texto, "Control"))
        assertTrue(campoTieneDato(CampoDef("igm_hav", "IgM HAV", TipoCampo.SELECT, listOf("SI", "NO")), "NO"))
        assertFalse(campoTieneDato(CampoDef("titulo", "Título", TipoCampo.ETIQUETA), "algo"))
    }

    @Test
    fun cada_tipo_de_seccion_sabe_si_tiene_datos() {
        val datos = DatosConsulta(
            valores = texto("motivo_consulta" to "Control") +
                mapOf("alimentacion" to mapOf("hiposodica" to "1|"), "child_pugh" to mapOf("ascitis" to "2")),
            examenFisico = ExamenFisico("c1", peso = "70"),
            tiposRegistro = setOf("interconsulta"),
            tiposLaboratorio = setOf("laboratorio_general"),
            seccionesArchivo = setOf("documentos", "audio", "fotos"),
            diagnosticos = 1,
        )
        assertEquals(
            setOf(
                "motivo_consulta", "documentos", "laboratorio_general", "alimentacion", "child_pugh",
                "examen_fisico", "interconsulta", "diagnostico", "audio", "fotos",
            ),
            conDatos(datos),
        )
    }

    @Test
    fun sin_nada_cargado_ninguna_seccion_tiene_datos() {
        val datos = DatosConsulta(
            valores = texto("motivo_consulta" to "", "nota" to "0") +
                mapOf("alimentacion" to mapOf("hiposodica" to "0|", "otra" to "0|"), "child_pugh" to mapOf("ascitis" to "")),
            examenFisico = ExamenFisico("c1"),
        )
        assertEquals(emptySet(), conDatos(datos))
    }

    @Test
    fun las_secciones_por_paciente_no_cuentan_como_datos_de_la_consulta() {
        // Aunque algo de ellas quede atado a la consulta (un adjunto, un valor viejo, el tipo de registro), son del
        // paciente: van en "Datos" (RF-34), no al final de cada consulta.
        val datos = DatosConsulta(
            valores = mapOf("antecedentes_personales" to mapOf("alergias" to "1|"), "antecedentes_familiares" to mapOf("nota" to "Madre HTA")),
            tiposRegistro = setOf("screening"),
            seccionesArchivo = setOf("antecedentes_personales", "antecedentes_familiares"),
        )
        assertEquals(emptySet(), conDatos(datos))
        assertEquals(emptyList(), soloLectura(SeccionesComunes.TIPO_TELEMEDICINA, datos))
    }

    @Test
    fun antecedentes_personales_con_datos_salen_en_los_del_paciente_y_no_en_la_consulta() {
        val antecedentes = listOf(antecedente("personales", "alergias", flag = true))
        assertEquals(listOf("antecedentes_personales"), delPaciente(antecedentes))
        val r = seccionesDelPacienteSoloLectura(catalogo, antecedentes, perfilEntero, emptySet())
        assertEquals("Antecedentes personales", r.single().titulo)
        // En la consulta no salen ni con algo de la sección atado a ella.
        val datos = DatosConsulta(seccionesArchivo = setOf("antecedentes_personales"))
        assertEquals(emptyList(), soloLectura(SeccionesComunes.TIPO_CONTROL, datos))
    }

    @Test
    fun antecedentes_sin_datos_o_dados_de_baja_no_salen_en_los_del_paciente() {
        assertEquals(emptyList(), delPaciente(emptyList()))
        assertEquals(
            emptyList(),
            delPaciente(
                listOf(
                    antecedente("personales", "alergias"),
                    antecedente("personales", "nota", detalle = " "),
                    antecedente("familiares", "hta", flag = true).copy(activo = false),
                ),
            ),
        )
    }

    @Test
    fun antecedentes_familiares_en_el_perfil_no_salen_en_ninguna() {
        val antecedentes = listOf(antecedente("familiares", "nota", detalle = "Madre HTA"))
        val conFamiliares = perfilEntero + "antecedentes_familiares"
        assertEquals(emptyList(), delPaciente(antecedentes, enPerfil = conFamiliares))
        // Fuera del perfil, sí.
        assertEquals(listOf("antecedentes_familiares"), delPaciente(antecedentes))
    }

    @Test
    fun antecedentes_del_paciente_en_orden_de_catalogo_y_las_legadas_al_final() {
        val antecedentes = listOf(antecedente("personales", "alergias", flag = true), antecedente("familiares", "hta", flag = true))
        assertEquals(listOf("antecedentes_familiares", "antecedentes_personales"), delPaciente(antecedentes))
    }

    @Test
    fun oculta_por_el_medico_y_en_el_perfil_no_sale_en_ninguna() {
        val conFamiliares = perfilEntero + "antecedentes_familiares"
        val ocultas = setOf("antecedentes_familiares", "conductas")
        assertEquals(emptyList(), delPaciente(listOf(antecedente("familiares", "hta", flag = true)), conFamiliares, ocultas))
        assertEquals(emptyList(), soloLectura(SeccionesComunes.TIPO_CONTROL, DatosConsulta(valores = texto("conductas" to "Dieta")), ocultas))
        // Oculta pero ya fuera del perfil: se ignora y los datos se ven en "Datos".
        assertEquals(listOf("antecedentes_familiares"), delPaciente(listOf(antecedente("familiares", "hta", flag = true)), ocultas = ocultas))
    }

    @Test
    fun frecuencia_cardiaca_sola_cuenta_como_dato_del_examen_fisico() {
        assertEquals(setOf("examen_fisico"), conDatos(DatosConsulta(examenFisico = ExamenFisico("c1", frecuenciaCardiaca = "80"))))
        assertEquals(setOf("examen_fisico"), conDatos(DatosConsulta(examenFisico = ExamenFisico("c1", circunferenciaAbdominal = "90"))))
    }

    @Test
    fun seccion_quitada_del_perfil_con_datos_aparece_con_el_nombre_por_defecto() {
        val datos = DatosConsulta(valores = texto("motivo_consulta" to "Control"), examenFisico = ExamenFisico("c1", peso = "70"))
        val r = soloLectura(SeccionesComunes.TIPO_CONTROL, datos)
        assertEquals(listOf("examen_fisico"), r.map { it.id })
        assertEquals("Examen físico", r.single().titulo)
    }

    @Test
    fun seccion_quitada_sin_datos_o_solo_con_cero_no_aparece() {
        val datos = DatosConsulta(valores = mapOf("alimentacion" to mapOf("hiposodica" to "0|", "otra" to "")))
        assertEquals(emptyList(), soloLectura(SeccionesComunes.TIPO_CONTROL, datos))
        assertEquals(emptyList(), soloLectura(SeccionesComunes.TIPO_CONTROL, DatosConsulta()))
    }

    @Test
    fun seccion_de_consulta_en_una_telemedicina_aparece_con_el_nombre_por_defecto() {
        // "nota" está en el perfil solo para Consulta y se llama "Apunte"; "conductas" está en los dos tipos.
        val datos = DatosConsulta(valores = texto("nota" to "Vuelve en un mes", "conductas" to "Dieta"))
        val r = soloLectura(SeccionesComunes.TIPO_TELEMEDICINA, datos)
        assertEquals(listOf("nota"), r.map { it.id })
        assertEquals("Nota", r.single().titulo)
        assertEquals(emptyList(), soloLectura(SeccionesComunes.TIPO_CONTROL, datos))
    }

    @Test
    fun en_orden_de_catalogo_y_las_legadas_al_final() {
        val datos = DatosConsulta(
            valores = texto("nota" to "Algo") + mapOf("alimentacion" to mapOf("otra" to "1|Sin TACC")),
            tiposRegistro = setOf("interconsulta"),
            seccionesArchivo = setOf("fotos", "documentos", "audio"),
            diagnosticos = 2,
        )
        assertEquals(
            listOf("documentos", "alimentacion", "interconsulta", "nota", "diagnostico", "audio", "fotos"),
            soloLectura(SeccionesComunes.TIPO_TELEMEDICINA, datos).map { it.id },
        )
    }

    @Test
    fun legada_con_diagnostico_aparece_despues_de_las_del_catalogo() {
        val datos = DatosConsulta(examenFisico = ExamenFisico("c1", tensionArterial = "120/80"), diagnosticos = 1)
        val r = soloLectura(SeccionesComunes.TIPO_CONTROL, datos)
        assertEquals(listOf("examen_fisico", "diagnostico"), r.map { it.id })
        assertEquals("Diagnósticos", r.last().titulo)
    }

    @Test
    fun hc_digitalizada_muestra_sus_fotos_solo_para_leer() {
        val r = soloLectura(SeccionesComunes.TIPO_FOTO, DatosConsulta(seccionesArchivo = setOf("fotos")))
        assertEquals(listOf("fotos"), r.map { it.id })
    }

    @Test
    fun oculta_por_el_medico_pero_en_el_perfil_no_aparece_como_solo_lectura() {
        val datos = DatosConsulta(valores = texto("motivo_consulta" to "Control", "conductas" to "Dieta"))
        assertEquals(emptyList(), soloLectura(SeccionesComunes.TIPO_CONTROL, datos, ocultas = setOf("motivo_consulta", "conductas")))
    }

    @Test
    fun oculta_que_ya_no_esta_en_el_perfil_se_ignora_y_sus_datos_se_ven() {
        // El administrador quitó "examen_fisico" después de que el médico la ocultara: los datos no se pierden de vista.
        val datos = DatosConsulta(examenFisico = ExamenFisico("c1", peso = "70"))
        assertEquals(listOf("examen_fisico"), soloLectura(SeccionesComunes.TIPO_CONTROL, datos, ocultas = setOf("examen_fisico")).map { it.id })
    }

    @Test
    fun oculta_del_perfil_en_otro_tipo_no_aparece_si_se_pasa_el_perfil_entero() {
        // "nota" solo va en Consulta y el médico la ocultó: en una telemedicina con nota no sale (la puede volver a
        // mostrar desde Configuración). Sin ocultarla, sale.
        val datos = DatosConsulta(valores = texto("nota" to "Vuelve en un mes"))
        val tele = enPerfil(SeccionesComunes.TIPO_TELEMEDICINA)
        assertEquals(emptyList(), seccionesSoloLectura(catalogo, tele, conDatos(datos), setOf("nota"), perfilEntero))
        assertEquals(listOf("nota"), seccionesSoloLectura(catalogo, tele, conDatos(datos), emptySet(), perfilEntero).map { it.id })
    }

    @Test
    fun consulta_arma_lo_de_solo_lectura_con_el_perfil_entero_de_la_definicion() {
        // Telemedicina con "nota" (oculta, en el perfil solo para Consulta) y examen físico (fuera del perfil), y el
        // paciente con antecedentes personales: al final solo el examen; en "Datos", los antecedentes.
        val datos = DatosConsulta(valores = texto("nota" to "Vuelve en un mes"), examenFisico = ExamenFisico("c1", peso = "70"))
        val antecedentes = listOf(antecedente("personales", "alergias", flag = true))
        val r = soloLecturaConsulta(catalogo, def, SeccionesComunes.TIPO_TELEMEDICINA, datos, antecedentes, setOf("nota"))
        assertEquals(listOf("examen_fisico"), r.alFinal.map { it.id })
        assertEquals(listOf("antecedentes_personales"), r.enDatos.map { it.id })
        // Sin ocultarla, la nota sale con el nombre por defecto y no con el del perfil.
        val sinOcultas = soloLecturaConsulta(catalogo, def, SeccionesComunes.TIPO_TELEMEDICINA, datos, antecedentes, emptySet())
        assertEquals(listOf("Examen físico", "Nota"), sinOcultas.alFinal.map { it.titulo })
    }

    @Test
    fun consulta_hc_digitalizada_muestra_sus_fotos_al_final() {
        val r = soloLecturaConsulta(catalogo, def, SeccionesComunes.TIPO_FOTO, DatosConsulta(seccionesArchivo = setOf("fotos")), emptyList(), emptySet())
        assertEquals(listOf("fotos"), r.alFinal.map { it.id })
        assertEquals(emptyList(), r.enDatos)
    }

    @Test
    fun consulta_sin_perfil_muestra_todo_lo_cargado_solo_para_leer() {
        // Sin perfil (RF-19) nada está en el perfil: lo cargado se lee con el nombre por defecto.
        val sinPerfil = armarDefinicion(catalogo, null).definicion
        val datos = DatosConsulta(valores = texto("motivo_consulta" to "Control"))
        val antecedentes = listOf(antecedente("familiares", "hta", flag = true))
        val r = soloLecturaConsulta(catalogo, sinPerfil, SeccionesComunes.TIPO_CONTROL, datos, antecedentes, setOf("motivo_consulta"))
        assertEquals(listOf("Motivo de consulta"), r.alFinal.map { it.titulo })
        assertEquals(listOf("antecedentes_familiares"), r.enDatos.map { it.id })
    }

    @Test
    fun ids_con_datos_que_la_app_no_conoce_se_omiten() {
        assertEquals(emptyList(), seccionesSoloLectura(catalogo, emptySet(), setOf("seccion_del_futuro"), emptySet(), emptySet()))
    }

    private fun antecedente(categoria: String, clave: String, flag: Boolean = false, detalle: String = "") =
        Antecedente("a-$categoria-$clave", "p1", "clinica", categoria, clave, flag, detalle)
}
