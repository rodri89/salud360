package com.salud360.especialidades.clinica

import com.salud360.core.model.especialidad.AnalitoDef
import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.CategoriaAntecedentes
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.ItemAntecedente
import com.salud360.core.model.especialidad.LaboratorioDef
import com.salud360.core.model.especialidad.ModoAntecedentes
import com.salud360.core.model.especialidad.RegistroDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionesComunes as C
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.features.hc.EspecialidadContribution
import com.salud360.features.hc.SeccionesGenericas


private val labGeneralClinica = LaboratorioDef(
    "laboratorio_general", "Laboratorio general",
    listOf("HTO", "HB", "Blancos", "Plaquetas", "Glucemia", "Urea", "Creatinina", "NA", "K", "CL", "CA", "MG", "Albúmina", "Fósforo", "TGO", "TGP", "FAL",
        "BT", "BD", "BI", "GGT", "TP", "RIN", "Concentración", "KPTT", "LDH", "PCR", "VSG", "CT", "LDL", "TGC", "HDL", "Ferritina", "Sat de Transf", "MELD",
        "Proteinograma", "TSH T4L ANTITPO", "HBA1C", "Vitamina D3", "Ácido úrico", "B12 Homocisteína Fólico", "PSAT", "AGS IGG anticore Antis HBV",
        "IGG HAV", "IGG HCV", "HIV", "VDRL", "FAN", "ASMA AMA ANTILKM", "SOMF", "PESO", "Otro")
        .map { AnalitoDef(it.lowercase().replace(Regex("[^a-z0-9]+"), "_"), it) },
)

private val alimentacionClinica = SeccionDef(
    "alimentacion", "Alimentación", TipoSeccion.FORM, icono = "alimentacion", inicialmenteExpandida = false,
    campos = listOf(
        CampoDef("hiposodica", "Hiposódica", TipoCampo.CHECK_DETALLE), CampoDef("diabetes", "Diabetes", TipoCampo.CHECK_DETALLE),
        CampoDef("celiaco", "Celíaco", TipoCampo.CHECK_DETALLE), CampoDef("otra", "Otra", TipoCampo.CHECK_DETALLE),
    ),
)

/** Clínica médica (perfil 3 de hclinica): enfermedades, motivo con audio, PDF, laboratorio de 51 analitos. */
val clinicaMedica = EspecialidadDefinition(
    codigo = "clinica", nombre = "Clínica médica", nombreLegacy = "hclinica",
    tiposConsulta = listOf(
        TipoConsultaDef(C.TIPO_CONTROL, "Consulta", "control", pestanias = SeccionesGenericas.pestaniasEstandar,
            secciones = listOf(C.DIAGNOSTICO, C.MOTIVO_CONSULTA, C.AUDIO, C.EXAMEN_FISICO, "laboratorio_general", "alimentacion", C.EXAMENES_COMPLEMENTARIOS, C.INTERCONSULTA, "documentos", C.CONDUCTAS, C.NOTA)),
        TipoConsultaDef(C.TIPO_TELEMEDICINA, "Telemedicina", "telemedicina", pestanias = SeccionesGenericas.pestaniasEstandar,
            secciones = listOf(C.DATOS_SUBJETIVOS, C.DATOS_OBJETIVOS, C.EXAMENES_COMPLEMENTARIOS, C.INTERCONSULTA, C.CONDUCTAS)),
        TipoConsultaDef(C.TIPO_FOTO, "HC digitalizada", "foto", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf(C.FOTOS), descripcion = "Fotos de la historia en papel"),
    ),
    secciones = listOf(
        SeccionesGenericas.seccionPersonales, SeccionesGenericas.seccionFamiliares, SeccionesGenericas.diagnosticos, SeccionesGenericas.motivo,
        SeccionesGenericas.audio, SeccionesGenericas.examenFisico, SeccionDef("laboratorio_general", "Laboratorio general", TipoSeccion.LABORATORIO, laboratorioTipo = "laboratorio_general", icono = "laboratorio"),
        alimentacionClinica, SeccionesGenericas.examenesComplementarios, SeccionesGenericas.interconsulta, SeccionesGenericas.documentos,
        SeccionesGenericas.conductas, SeccionesGenericas.nota, SeccionesGenericas.subjetivo, SeccionesGenericas.objetivo, SeccionesGenericas.fotos, SeccionesGenericas.habitos,
    ),
    antecedentes = listOf(
        CategoriaAntecedentes("personales", "Antecedentes personales patológicos", emptyList(), notaLibre = true),
        CategoriaAntecedentes("familiares", "Antecedentes familiares", listOf(
            "hta" to "HTA", "dbt_nir" to "DBT (NIR)", "dbt_ir" to "DBT (IR)", "acv" to "ACV", "dsl" to "DSL", "fa" to "FA", "mp" to "MP", "analfabeto" to "Analfabeto",
            "postrado" to "Postrado", "af_ca_mama" to "AF Ca mama", "ca_mama" to "Ca mama", "institucionalizado" to "Institucionalizado", "psicofarmacos" to "Psicofármacos",
            "dependiente_terceros" to "Dependiente de terceros", "tabaquismo" to "Tabaquismo", "otro" to "Otro",
        ).map { ItemAntecedente(it.first, it.second) }),
    ),
    laboratorios = listOf(labGeneralClinica),
    registros = SeccionesGenericas.registrosBase,
    examenFisicoCampos = setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.FRECUENCIA_CARDIACA, CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL, CampoExamenFisico.NOTA),
)

private fun siNo(clave: String, etiqueta: String) = CampoDef(clave, etiqueta, TipoCampo.SELECT, listOf("SI", "NO"), grupo = "serologias")

/** Hepatología (perfil 2 de hclinica): serologías, Child-Pugh, tabla de peso, estudios con fecha. */
val hepatologia = EspecialidadDefinition(
    codigo = "hepatologia", nombre = "Hepatología", nombreLegacy = "hclinica (perfil 2)",
    tiposConsulta = listOf(
        TipoConsultaDef(C.TIPO_CONTROL, "Consulta", "control", pestanias = SeccionesGenericas.pestaniasEstandar,
            secciones = listOf(C.MOTIVO_CONSULTA, C.AUDIO, C.EXAMEN_FISICO, "laboratorio_general", "laboratorio_reducido", "tabla_peso", "serologias", "child_pugh",
                "eco_abdominal", "veda", "biopsia_hepatica", "estudios_cardiologicos", "tc_rmn", "documentos", C.CONDUCTAS, C.NOTA)),
        TipoConsultaDef(C.TIPO_TELEMEDICINA, "Telemedicina", "telemedicina", pestanias = SeccionesGenericas.pestaniasEstandar,
            secciones = listOf(C.DATOS_SUBJETIVOS, C.DATOS_OBJETIVOS, C.CONDUCTAS)),
        TipoConsultaDef(C.TIPO_FOTO, "HC digitalizada", "foto", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf(C.FOTOS)),
    ),
    secciones = listOf(
        SeccionesGenericas.seccionPersonales, SeccionesGenericas.seccionFamiliares, SeccionesGenericas.motivo, SeccionesGenericas.audio, SeccionesGenericas.examenFisico,
        SeccionDef("laboratorio_general", "Laboratorio general", TipoSeccion.LABORATORIO, laboratorioTipo = "laboratorio_general", icono = "laboratorio"),
        SeccionDef("laboratorio_reducido", "Laboratorio (reducido)", TipoSeccion.LABORATORIO, laboratorioTipo = "laboratorio_reducido", icono = "laboratorio", inicialmenteExpandida = false),
        SeccionDef("tabla_peso", "Tabla de peso", TipoSeccion.LABORATORIO, laboratorioTipo = "peso", icono = "examen", inicialmenteExpandida = false),
        SeccionDef("serologias", "Serologías hepatitis", TipoSeccion.FORM, icono = "laboratorio", inicialmenteExpandida = false, campos = listOf(
            siNo("igm_hav", "IgM HAV"), siNo("ags_hbv", "AgS HBV"), siNo("ig_anti_hcv", "Ig anti HCV"), siNo("fan", "FAN"), siNo("anti_lkm", "Anti LKM"), siNo("igg_hav", "IgG HAV"),
            siNo("ig_anti_core", "Ig anti core"), siNo("ig_anti_s_hbv", "Ig anti S HBV"), siNo("ig_anti_hdv", "Ig anti HDV"), siNo("asma", "ASMA"), siNo("fibrotest", "Fibrotest"), siNo("hiv", "HIV"),
            siNo("ag_e", "Ag e"), siNo("anti_ag_e", "Anti Ag e"), siNo("ig_anti_hev", "Ig anti HEV"), siNo("ama", "AMA"),
        )),
        SeccionDef("child_pugh", "Child-Pugh-Turcotte", TipoSeccion.CUSTOM, renderKey = "child_pugh", inicialmenteExpandida = false),
        SeccionDef("eco_abdominal", "Ecografía abdominal", TipoSeccion.REGISTROS, registroTipo = "eco_abdominal", inicialmenteExpandida = false),
        SeccionDef("veda", "Videoendoscopia digestiva alta", TipoSeccion.REGISTROS, registroTipo = "veda", inicialmenteExpandida = false),
        SeccionDef("biopsia_hepatica", "Biopsia hepática", TipoSeccion.REGISTROS, registroTipo = "biopsia_hepatica", inicialmenteExpandida = false),
        SeccionDef("estudios_cardiologicos", "Estudios cardiológicos", TipoSeccion.REGISTROS, registroTipo = "estudios_cardiologicos", inicialmenteExpandida = false),
        SeccionDef("tc_rmn", "TC / RMN / ColangioRMN", TipoSeccion.REGISTROS, registroTipo = "tc_rmn", inicialmenteExpandida = false),
        SeccionesGenericas.documentos, SeccionesGenericas.conductas, SeccionesGenericas.nota, SeccionesGenericas.subjetivo, SeccionesGenericas.objetivo, SeccionesGenericas.fotos,
    ),
    antecedentes = clinicaMedica.antecedentes.map { if (it.categoria == "personales") SeccionesGenericas.personalesEstandar else it },
    laboratorios = listOf(
        labGeneralClinica,
        LaboratorioDef("laboratorio_reducido", "Laboratorio reducido", listOf("Hemograma", "Ferritina", "Hepatograma", "Glucemia", "HbA1c", "Colesterol total", "LDL", "TG", "Urea", "Creatinina", "RAC", "Cortisol matutino", "Testosterona total", "CPK", "Otros").map { AnalitoDef(it.lowercase().replace(Regex("[^a-z0-9]+"), "_"), it) }),
        LaboratorioDef("peso", "Peso", listOf(AnalitoDef("peso", "Peso", "kg"))),
    ),
    registros = SeccionesGenericas.registrosBase + listOf("eco_abdominal" to "Ecografía abdominal", "veda" to "VEDA", "biopsia_hepatica" to "Biopsia hepática", "estudios_cardiologicos" to "Estudio cardiológico", "tc_rmn" to "TC / RMN")
        .map { (t, n) -> RegistroDef(t, n, n, listOf(CampoDef("detalle", "Detalle", TipoCampo.TEXTO_LARGO))) },
    examenFisicoCampos = clinicaMedica.examenFisicoCampos,
)

val clinicaContribution = EspecialidadContribution(clinicaMedica)
val hepatologiaContribution = EspecialidadContribution(hepatologia, mapOf("child_pugh" to { s, ctx -> ChildPughSeccion(s, ctx) }))
