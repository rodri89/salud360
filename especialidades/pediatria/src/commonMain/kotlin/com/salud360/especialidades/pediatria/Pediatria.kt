package com.salud360.especialidades.pediatria

import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.CategoriaAntecedentes
import com.salud360.core.model.especialidad.CondicionSeccion
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.ItemAntecedente
import com.salud360.core.model.especialidad.RegistroDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionesComunes as C
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.features.hc.RENDER_CURVAS_CRECIMIENTO
import com.salud360.features.hc.SeccionesGenericas as G
import com.salud360.features.hc.EspecialidadContribution

private fun masMenos(clave: String, etiqueta: String) = CampoDef(clave, etiqueta, TipoCampo.RADIO, listOf("+", "-"), grupo = "sero")

private val perinatales = SeccionDef(
    "perinatales", "Antec. perinatales", TipoSeccion.FORM_PACIENTE, icono = "embarazo",
    campos = listOf(
        CampoDef("embarazo", "Embarazo", TipoCampo.SELECT, listOf("CONTROLADO", "POCO CONTROLADO", "NO CONTROLADO"), grupo = "emb"),
        CampoDef("controles", "N° controles", TipoCampo.NUMERO, grupo = "emb"),
        CampoDef("patologias", "Patologías", TipoCampo.SI_NO_DETALLE),
        CampoDef("hisop_sbhb", "Hisopado SBHB", TipoCampo.RADIO, listOf("+", "-"), grupo = "s1"), CampoDef("hisop_sbhb_detalle", "Detalle", grupo = "s1"),
        CampoDef("serologia1", "Serología 1° trimestre", TipoCampo.RADIO, listOf("+", "-"), grupo = "s2"), CampoDef("serologia1_detalle", "Detalle", grupo = "s2"),
        CampoDef("serologia3", "Serología 3° trimestre", TipoCampo.RADIO, listOf("+", "-"), grupo = "s3"), CampoDef("serologia3_detalle", "Detalle", grupo = "s3"),
        CampoDef("parto", "Parto", TipoCampo.SELECT, listOf("CESÁREA", "EUTÓCICO", "DISTÓCICO"), grupo = "parto"), CampoDef("parto_detalle", "Detalle", grupo = "parto"),
        CampoDef("eg", "EG", TipoCampo.NUMERO, unidad = "sem", grupo = "n1"), CampoDef("peso", "Peso", TipoCampo.NUMERO, unidad = "kg", grupo = "n1"),
        CampoDef("talla", "Talla", TipoCampo.NUMERO, unidad = "cm", grupo = "n1"), CampoDef("pc", "PC", TipoCampo.NUMERO, unidad = "cm", grupo = "n1"),
        CampoDef("apgar", "Apgar", grupo = "n2"), CampoDef("caida_cordon", "Caída de cordón", TipoCampo.NUMERO, unidad = "días", grupo = "n2"),
        CampoDef("meconio", "Meconio", TipoCampo.NUMERO, unidad = "días", grupo = "n2"), CampoDef("gyf", "Grupo y factor", grupo = "n2"),
        CampoDef("fei", "FEI (pesquisa neonatal)", TipoCampo.RADIO, listOf("Normal", "Anormal"), grupo = "fei"), CampoDef("fei_detalle", "Detalle", grupo = "fei"),
        masMenos("vdrl", "VDRL"), masMenos("chagas", "Chagas"), CampoDef("oea", "OEA", TipoCampo.RADIO, listOf("Presentes", "Ausentes"), grupo = "sero"),
    ),
)

private val neonatales = SeccionDef("neonatales", "Antec. neonatales", TipoSeccion.FORM_PACIENTE, conArchivos = true, icono = "antecedentes",
    campos = listOf(CampoDef("nota", "Antecedentes neonatales patológicos", TipoCampo.TEXTO_LARGO)))

/**
 * Alimentación con las mismas claves que la tabla `alimentacions` de la web (pecho, leche_maternizada, leche_vaca,
 * dieta_tipo, dieta_comidas, hierro, vitamina). El detalle de las casillas se guarda como `<clave>|<detalle>`;
 * en la web hierro y vitamina guardan la dosis en `hierro_dosis` / `vitamina_dosis`.
 */
private val alimentacion = SeccionDef(
    "alimentacion", "Alimentación", TipoSeccion.FORM, icono = "alimentacion",
    campos = listOf(
        CampoDef("pecho", "Pecho", TipoCampo.CHECK_DETALLE), CampoDef("leche_maternizada", "Leche maternizada", TipoCampo.CHECK_DETALLE), CampoDef("leche_vaca", "Leche de vaca", TipoCampo.CHECK_DETALLE),
        CampoDef("dieta_tipo", "Dieta - tipo", TipoCampo.TEXTO_LARGO), CampoDef("dieta_comidas", "Comidas por día", TipoCampo.NUMERO),
        CampoDef("hierro", "Hierro", TipoCampo.CHECK_DETALLE, placeholder = "Dosis"), CampoDef("vitamina", "Vitaminas", TipoCampo.CHECK_DETALLE, placeholder = "Dosis"),
    ),
)

/**
 * Antecedentes personales como en la web: casillas con detalle (enfermedad actual, alergias, Qx, traumatismos,
 * transfusiones, otro) y, debajo, la lista de internaciones con fotos. "Internaciones" no va como casilla porque
 * ya está la lista.
 */
private val personalesPediatria = CategoriaAntecedentes(
    "personales", "Antecedentes personales",
    G.personalesEstandar.items.filter { it.clave != "internaciones" },
)
private val seccionPersonalesPediatria = G.seccionPersonales.copy(subsecciones = listOf("internaciones"))

private fun texto(id: String, titulo: String, icono: String? = null, condicion: CondicionSeccion? = null) =
    SeccionDef(id, titulo, TipoSeccion.TEXTO, icono = icono, condicion = condicion, inicialmenteExpandida = false)

private val prenatalFamilia = SeccionDef("prenatal_familia", "Familia", TipoSeccion.FORM, icono = "familia", campos = listOf(
    CampoDef("mama", "Mamá", grupo = "m"), CampoDef("mama_edad", "Edad", grupo = "m"), CampoDef("mama_ocupacion", "Ocupación", grupo = "m"),
    CampoDef("papa", "Papá", grupo = "p"), CampoDef("papa_edad", "Edad", grupo = "p"), CampoDef("papa_ocupacion", "Ocupación", grupo = "p"),
    CampoDef("bebe", "Bebé", grupo = "b"), CampoDef("eg", "EG", grupo = "b"), CampoDef("fpp", "FPP", grupo = "b"), CampoDef("hermanos", "Hermanos", TipoCampo.SI_NO),
))
private val prenatalEmbarazo = SeccionDef("prenatal_embarazo", "Embarazo actual", TipoSeccion.FORM, icono = "embarazo", campos = listOf(
    CampoDef("obstetra", "Obstetra", grupo = "a"), CampoDef("eg", "EG", TipoCampo.NUMERO, unidad = "sem", grupo = "a"), CampoDef("controles", "N° controles", TipoCampo.NUMERO, grupo = "a"),
    CampoDef("serologia1", "Serología 1° trim", TipoCampo.RADIO, listOf("+", "-"), grupo = "s"), CampoDef("serologia1_detalle", "Detalle", grupo = "s"),
    CampoDef("serologia2", "Serología 2° trim", TipoCampo.RADIO, listOf("+", "-"), grupo = "s2"), CampoDef("serologia2_detalle", "Detalle", grupo = "s2"),
    CampoDef("hisop_sbhb", "Hisopado SBHB", TipoCampo.RADIO, listOf("+", "-"), grupo = "h"), CampoDef("hisop_detalle", "Detalle", grupo = "h"),
    CampoDef("ptog", "PTOG", TipoCampo.RADIO, listOf("N", "P"), grupo = "pt"), CampoDef("ptog_detalle", "Detalle", grupo = "pt"),
    CampoDef("vacunas", "Vacunas", TipoCampo.TEXTO_LARGO), CampoDef("parto", "Parto", TipoCampo.RADIO, listOf("Parto", "Cesárea"), grupo = "pa"), CampoDef("cesarea_detalle", "Detalle", grupo = "pa"),
    CampoDef("ecografia", "Ecografía", TipoCampo.TEXTO_LARGO), CampoDef("observaciones", "Observaciones", TipoCampo.TEXTO_LARGO),
))
private val prenatalObstetricos = SeccionDef("prenatal_obstetricos", "Antecedentes obstétricos", TipoSeccion.FORM, icono = "antecedentes", campos = listOf(
    CampoDef("g", "G (gestas)", TipoCampo.NUMERO, grupo = "gpa"), CampoDef("p", "P (partos)", TipoCampo.NUMERO, grupo = "gpa"), CampoDef("a", "A (abortos)", TipoCampo.NUMERO, grupo = "gpa"),
    CampoDef("detalle", "Detalle", TipoCampo.TEXTO_LARGO),
))

/** Pediatría: control de salud, enfermedad, telemedicina, foto, prenatal y lactancia. */
val pediatria = EspecialidadDefinition(
    codigo = "pediatria", nombre = "Pediatría", nombreLegacy = "hcpediatria",
    tiposConsulta = listOf(
        TipoConsultaDef(C.TIPO_CONTROL, "Control de salud", "control", pestanias = listOf(C.DATOS_PACIENTE, "perinatales", "neonatales", C.ANTECEDENTES_PERSONALES, C.ANTECEDENTES_FAMILIARES),
            secciones = listOf("vacunas", "alimentacion", "diuresis_catarsis", "somnia", "escolaridad", "actividades", "pantallas", "habitos", "menarca", "desarrollo",
                C.EXAMEN_FISICO, CURVAS, C.EXAMENES_COMPLEMENTARIOS, C.INTERCONSULTA, "screening", C.CONDUCTAS, C.OBSERVACIONES, C.NOTA)),
        TipoConsultaDef(C.TIPO_ENFERMEDAD, "Enfermedad", "enfermedad", pestanias = listOf(C.DATOS_PACIENTE, "perinatales", "neonatales", C.ANTECEDENTES_PERSONALES, C.ANTECEDENTES_FAMILIARES),
            secciones = listOf(C.MOTIVO_CONSULTA, C.EXAMEN_FISICO, CURVAS, C.EXAMENES_COMPLEMENTARIOS, C.INTERCONSULTA, C.CONDUCTAS, C.OBSERVACIONES)),
        TipoConsultaDef(C.TIPO_TELEMEDICINA, "Telemedicina", "telemedicina", pestanias = listOf(C.DATOS_PACIENTE, C.ANTECEDENTES_PERSONALES, C.ANTECEDENTES_FAMILIARES),
            secciones = listOf(C.DATOS_SUBJETIVOS, C.DATOS_OBJETIVOS, C.EXAMENES_COMPLEMENTARIOS, C.INTERCONSULTA, C.CONDUCTAS, C.OBSERVACIONES)),
        TipoConsultaDef("prenatal", "Consulta prenatal", "prenatal", pestanias = listOf(C.DATOS_PACIENTE),
            secciones = listOf("prenatal_familia", "prenatal_embarazo", "prenatal_obstetricos", "lactancia_previa")),
        TipoConsultaDef("lactancia", "Lactancia", "lactancia", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf(C.OBSERVACIONES)),
        TipoConsultaDef(C.TIPO_FOTO, "HC digitalizada", "foto", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf(C.FOTOS)),
    ),
    secciones = listOf(
        perinatales, neonatales, seccionPersonalesPediatria, G.seccionFamiliares,
        // Vacunas en texto libre (módulo "vacunas_dos" de la web); el calendario en grilla quedó fuera por ahora.
        G.texto("vacunas", "Vacunas", "vacunas", expandida = true),
        SeccionDef(CURVAS, "Curvas de crecimiento", TipoSeccion.CUSTOM, renderKey = RENDER_CURVAS_CRECIMIENTO, icono = "curvas", inicialmenteExpandida = false),
        alimentacion, texto("diuresis_catarsis", "Diuresis / catarsis", "diuresis"), texto("somnia", "Sueño", "sueno"), texto("escolaridad", "Escolaridad", "escolaridad"),
        texto("actividades", "Actividades extraescolares", "actividades"), texto("pantallas", "Pantallas", "pantallas"), G.habitos.copy(inicialmenteExpandida = false),
        texto("menarca", "Menarca", "menarca", condicion = CondicionSeccion.SoloFemenino),
        SeccionDef("desarrollo", "Desarrollo madurativo", TipoSeccion.CUSTOM, renderKey = "desarrollo_madurativo", icono = "desarrollo", condicion = CondicionSeccion.EdadMaximaMeses(72)),
        G.examenFisico, G.examenesComplementarios, G.interconsulta,
        SeccionDef("internaciones", "Internaciones", TipoSeccion.REGISTROS, registroTipo = "internacion", icono = "internacion", inicialmenteExpandida = false),
        G.screening, G.motivo, G.conductas, G.observaciones, G.nota, G.subjetivo, G.objetivo, G.fotos,
        prenatalFamilia, prenatalEmbarazo, prenatalObstetricos, texto("lactancia_previa", "Lactancia de embarazo previo", "lactancia"),
    ),
    antecedentes = listOf(
        personalesPediatria,
        CategoriaAntecedentes("familiares", "Antecedentes familiares", listOf(
            "hta" to "HTA", "dbt" to "DBT", "asma" to "Asma", "alergia" to "Alergia", "enf_cv" to "Enf. cardiovascular", "muerte_subita" to "Muerte súbita",
            "enf_celiaca" to "Enf. celíaca", "enf_tiroideas" to "Enf. tiroideas", "enf_neurologicas" to "Enf. neurológicas", "convulsion_febril" to "Convulsión febril",
            "enf_psiquiatrica" to "Enf. psiquiátrica", "enf_oh" to "Enf. O-H", "tabaquismo" to "Tabaquismo", "otro" to "Otro",
        ).map { ItemAntecedente(it.first, it.second) }),
    ),
    registros = G.registrosBase + RegistroDef("internacion", "Internaciones", "Internación",
        listOf(CampoDef("motivo", "Motivo"), CampoDef("lugar", "Lugar"), CampoDef("duracion", "Duración"), CampoDef("indicacion_alta", "Indicación al alta", TipoCampo.TEXTO_LARGO)),
        conArchivos = true, porPaciente = true),
    examenFisicoCampos = setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.PERIMETRO_CEFALICO, CampoExamenFisico.IPD, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.NOTA),
    conPercentilos = true,
    // Fase siguiente: API propia de hc_pediatria ("https://hcpediatrica.com"); mientras tanto la HC se guarda en la base local.
    apiBaseUrl = null,
)

/** Id de la sección de curvas de crecimiento (OMS) en pediatría. */
private const val CURVAS = "curvas_crecimiento"

val pediatriaContribution = EspecialidadContribution(
    pediatria,
    mapOf(
        "desarrollo_madurativo" to { s, ctx -> DesarrolloMadurativoSeccion(s, ctx) },
    ),
)
