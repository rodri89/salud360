package com.salud360.especialidades.gineco

import androidx.compose.runtime.Composable
import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.CategoriaAntecedentes
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.LaboratorioDef
import com.salud360.core.model.especialidad.RegistroDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionesComunes as C
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.features.hc.EspecialidadContribution
import com.salud360.features.hc.SeccionesGenericas as G
import com.salud360.features.hc.secciones.DibujoSeccion
import org.jetbrains.compose.resources.painterResource
import salud360.especialidades.gineco.generated.resources.Res
import salud360.especialidades.gineco.generated.resources.pap

private val antecGinecologicos = SeccionDef("antec_ginecologicos", "Antec. ginecológicos", TipoSeccion.FORM_PACIENTE, icono = "antecedentes", campos = listOf(
    CampoDef("menarca", "Menarca", grupo = "a"), CampoDef("ritmo_menstrual", "Ritmo menstrual", grupo = "a"), CampoDef("fum", "FUM", grupo = "a"),
    CampoDef("mac", "MAC (anticoncepción)", grupo = "b"), CampoDef("irs", "IRS", grupo = "b"), CampoDef("pap", "PAP", grupo = "b"),
    CampoDef("otros", "Otros", TipoCampo.TEXTO_LARGO),
))
private val antecObstetricos = SeccionDef("antec_obstetricos", "Antec. obstétricos", TipoSeccion.FORM_PACIENTE, icono = "embarazo", campos = listOf(
    CampoDef("gesta", "Gesta (G)", TipoCampo.NUMERO, grupo = "gpa"), CampoDef("paridad", "Paridad (P)", TipoCampo.NUMERO, grupo = "gpa"), CampoDef("aborto", "Abortos (A)", TipoCampo.NUMERO, grupo = "gpa"),
))
private val gestas = SeccionDef("gestas", "Detalle por gesta", TipoSeccion.REGISTROS, registroTipo = "gesta", inicialmenteExpandida = false)

private val datosConsulta = SeccionDef("datos_consulta", "Datos de la consulta", TipoSeccion.FORM, campos = listOf(
    CampoDef("gyf", "Grupo y factor", grupo = "a"), CampoDef("talla", "Talla", TipoCampo.NUMERO, unidad = "m", grupo = "a"), CampoDef("peso", "Peso", TipoCampo.NUMERO, unidad = "kg", grupo = "a"),
    CampoDef("vacunas", "Vacunas", TipoCampo.TEXTO_LARGO),
))
private val datosConsultaObst = SeccionDef("datos_consulta_obst", "Datos del control", TipoSeccion.FORM, icono = "embarazo", campos = listOf(
    CampoDef("eg", "Edad gestacional", grupo = "a"), CampoDef("peso", "Peso", TipoCampo.NUMERO, unidad = "kg", grupo = "a"), CampoDef("imc", "IMC", grupo = "a"), CampoDef("ta", "TA", grupo = "a"),
    CampoDef("altura_uterina", "Altura uterina", grupo = "b"), CampoDef("presentacion", "Presentación", grupo = "b"), CampoDef("lcf", "LCF", grupo = "b"), CampoDef("maf", "MAF", grupo = "b"),
))
private val datosEmbarazo = SeccionDef("datos_embarazo", "Datos del embarazo", TipoSeccion.FORM_PACIENTE, icono = "embarazo", campos = listOf(
    CampoDef("gyf", "Grupo y factor", grupo = "a"), CampoDef("talla", "Talla", grupo = "a"), CampoDef("peso_previo", "Peso previo", grupo = "a"),
    CampoDef("fum", "FUM", TipoCampo.FECHA, grupo = "b"), CampoDef("fpp", "FPP", TipoCampo.FECHA, grupo = "b"), CampoDef("control_mamario", "Control mamario", grupo = "b"),
    CampoDef("vacunas", "Vacunas", TipoCampo.TEXTO_LARGO), CampoDef("pap", "PAP", TipoCampo.TEXTO_LARGO),
))

val ginecologia = EspecialidadDefinition(
    codigo = "gineco", nombre = "Ginecología y obstetricia", nombreLegacy = "hcgineco",
    tiposConsulta = listOf(
        TipoConsultaDef("ginecologico", "Control ginecológico", "control", pestanias = listOf(C.DATOS_PACIENTE, "antec_ginecologicos", "antec_obstetricos", C.ANTECEDENTES_PERSONALES, C.ANTECEDENTES_FAMILIARES),
            secciones = listOf(C.MOTIVO_CONSULTA, "datos_consulta", C.EXAMEN_FISICO, "ecografias", "mamografia", "pap", "laboratorio_general", "laboratorio_hormonal", C.CONDUCTAS)),
        TipoConsultaDef("obstetrico", "Control obstétrico", "obstetrica", pestanias = listOf(C.DATOS_PACIENTE, "antec_ginecologicos", "antec_obstetricos", C.ANTECEDENTES_PERSONALES, C.ANTECEDENTES_FAMILIARES),
            secciones = listOf("datos_consulta_obst", "datos_embarazo", C.EXAMEN_FISICO, "ecografias_obst", "lab_trim1", "lab_trim2", "lab_trim3", C.NOTA)),
        TipoConsultaDef(C.TIPO_TELEMEDICINA, "Telemedicina", "telemedicina", pestanias = listOf(C.DATOS_PACIENTE, "antec_ginecologicos", "antec_obstetricos", C.ANTECEDENTES_PERSONALES, C.ANTECEDENTES_FAMILIARES),
            secciones = listOf(C.DATOS_OBJETIVOS, C.CONDUCTAS, "laboratorio_general", "laboratorio_hormonal")),
        TipoConsultaDef(C.TIPO_FOTO, "HC digitalizada", "foto", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf(C.FOTOS)),
    ),
    secciones = listOf(
        antecGinecologicos, antecObstetricos, gestas, G.seccionPersonales, G.seccionFamiliares, G.motivo, datosConsulta, G.examenFisico,
        SeccionDef("ecografias", "Ecografías", TipoSeccion.REGISTROS, registroTipo = "ecografia", icono = "estudios"),
        SeccionDef("mamografia", "Mamografía", TipoSeccion.REGISTROS, registroTipo = "mamografia", icono = "estudios"),
        SeccionDef("pap", "PAP / Colposcopía", TipoSeccion.CUSTOM, renderKey = "dibujo_pap"),
        G.lab("laboratorio_general", "Laboratorio general"), G.lab("laboratorio_hormonal", "Laboratorio hormonal", expandida = false),
        G.conductas, G.objetivo.copy(titulo = "Consulta"), G.nota, G.fotos,
        datosConsultaObst, datosEmbarazo,
        SeccionDef("ecografias_obst", "Ecografías obstétricas", TipoSeccion.REGISTROS, registroTipo = "ecografia_obst", icono = "estudios"),
        G.lab("lab_trim1", "Laboratorio 1° trimestre", expandida = false), G.lab("lab_trim2", "Laboratorio 2° trimestre", expandida = false), G.lab("lab_trim3", "Laboratorio 3° trimestre", expandida = false),
    ),
    antecedentes = listOf(
        CategoriaAntecedentes("personales", "Antecedentes personales", G.items(
            "enf_cronica" to "Enfermedad crónica", "medicamentos" to "Medicamentos", "cirugias" to "Cirugías", "alergias" to "Alergias",
            "tabaquismo" to "Tabaquismo", "actividad_fisica" to "Actividad física", "otro" to "Otro",
        )),
        G.familiaresTexto,
    ),
    laboratorios = listOf(
        LaboratorioDef("laboratorio_general", "Laboratorio general", G.analitos("Hemograma", "Glucemia ayunas", "Hepatograma", "Colesterol total", "HDL", "LDL", "TG", "Urea", "Creatinina", "TSH", "T4 libre", "OH Vit D3", "Insulinemia", "HOMA", "Orina", "Otros")),
        LaboratorioDef("laboratorio_hormonal", "Laboratorio hormonal", G.analitos("FSH", "LH", "Estradiol", "PRL", "DHEA", "DHEA-S", "Androstenediona", "17 OH progesterona", "Estrona", "Androstenediol glucurónido", "Testosterona total", "Testosterona libre", "GLAE", "Cortisol", "Progesterona", "AMH", "HCG subunidad B", "Otros")),
        LaboratorioDef("lab_trim1", "1° trimestre", G.analitos("Hemograma", "Glucemia ayunas", "HIV", "VDRL", "VHB", "Toxo IgG", "Toxo IgM", "Chagas", "Rubeola IgG", "Rubeola IgM", "TSH", "T4 libre", "OH Vit D3", "Urocultivo", "Otros")),
        LaboratorioDef("lab_trim2", "2° trimestre", G.analitos("Hemograma", "Glucemia ayunas", "PTOG", "Toxo", "Urocultivo", "Otros")),
        LaboratorioDef("lab_trim3", "3° trimestre", G.analitos("Hemograma", "Coagulograma", "Glucemia ayunas", "HIV", "VDRL", "Toxo", "SBH", "Otros")),
    ),
    registros = G.registrosBase + listOf(
        RegistroDef("ecografia", "Ecografías", "Ecografía", listOf(CampoDef("solicito", "Solicitó"), CampoDef("resultado", "Resultado", TipoCampo.TEXTO_LARGO)), conArchivos = true),
        RegistroDef("mamografia", "Mamografías", "Mamografía", listOf(CampoDef("solicito", "Solicitó"), CampoDef("descripcion", "Descripción", TipoCampo.TEXTO_LARGO)), conArchivos = true),
        RegistroDef("ecografia_obst", "Ecografías obstétricas", "Ecografía obstétrica", listOf(CampoDef("numero_embarazo", "N° embarazo", TipoCampo.NUMERO), CampoDef("descripcion", "Descripción", TipoCampo.TEXTO_LARGO)), conArchivos = true),
        RegistroDef("gesta", "Gestas", "Gesta", listOf(CampoDef("detalle", "Detalle (embarazo / parto / aborto)", TipoCampo.TEXTO_LARGO)), conFecha = false, porPaciente = true),
    ),
    examenFisicoCampos = setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.NOTA),
)

@Composable
private fun PapSeccion(s: SeccionDef, ctx: com.salud360.features.hc.SeccionContext) =
    DibujoSeccion(s, ctx, fondo = painterResource(Res.drawable.pap), colorInicial = androidx.compose.ui.graphics.Color(0xFFD100A4))

val ginecoContribution = EspecialidadContribution(ginecologia, mapOf("dibujo_pap" to { s, ctx -> PapSeccion(s, ctx) }))
