package com.salud360.especialidades.hematologia

import androidx.compose.runtime.Composable
import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.CategoriaAntecedentes
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.LaboratorioDef
import com.salud360.core.model.especialidad.ModoAntecedentes
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionesComunes as C
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.features.hc.EspecialidadContribution
import com.salud360.features.hc.SeccionesGenericas as G
import com.salud360.features.hc.secciones.DibujoSeccion
import org.jetbrains.compose.resources.painterResource
import salud360.especialidades.hematologia.generated.resources.Res
import salud360.especialidades.hematologia.generated.resources.silueta

private fun t(clave: String, etiqueta: String, grupo: String? = null) = CampoDef(clave, etiqueta, grupo = grupo)
private fun siNo(clave: String, etiqueta: String, grupo: String? = null) = CampoDef(clave, etiqueta, TipoCampo.SI_NO, grupo = grupo)
private fun radio(clave: String, etiqueta: String, vararg opciones: String, grupo: String? = null) = CampoDef(clave, etiqueta, TipoCampo.RADIO, opciones.toList(), grupo = grupo)
private val ECOG = arrayOf("0 Sin repercusión", "1 Tolera actividad diaria", "2 Menos de la mitad del tiempo en cama", "3 Más de la mitad del tiempo en cama", "4 Confinado a silla o cama", "5 Muerte")
private val SEROLOGIAS = listOf(t("hbv", "Serología HBV", "s"), t("hcv", "Serología HCV", "s"), t("hzv", "Serología HZV", "s"), t("cmv", "Serología CMV", "s2"), t("hiv", "Serología HIV", "s2"), t("vdrl", "Serología VDRL", "s2"))
private val COMORBILIDADES = listOf(siNo("antigripal", "Antigripal", "v"), siNo("antineumococica", "Antineumocócica", "v"), radio("ecog", "ECOG performance status", *ECOG),
    t("insuficiencia_cardiaca", "Insuficiencia cardíaca", "c"), t("epoc", "EPOC", "c"), t("insuficiencia_renal", "Insuficiencia renal", "c"),
    t("hepatopatia", "Hepatopatía", "c2"), t("diabetes", "Diabetes", "c2"), t("hiv_enf", "HIV", "c2"), t("otro", "Otro", "c2"), t("charlson", "Score de Charlson (puntaje)"))

private val linfomaHodgkin = listOf(
    SeccionDef("lh_sangre", "Sangre", TipoSeccion.FORM, campos = listOf(t("hto_hb_vcm", "Hto/Hb/VCM", "a"), t("leucocitos", "Leucocitos", "a"), t("pqts", "PQTs", "a"), t("urea_creatinina", "Urea / creatinina", "a"),
        t("ci_creat", "CI Creat ml/min", "b"), t("tgo_tgp_fal_bb", "TGO/TGP/FAL/Bb", "b"), t("uricemia", "Uricemia", "b"), t("vsg", "VSG", "b"),
        t("ldh", "LDH", "c"), t("b2_microglobulina", "B2 microglobulina", "c"), t("proteinograma", "Proteinograma", "c")) + SEROLOGIAS),
    SeccionDef("lh_examen", "Examen físico (silueta)", TipoSeccion.CUSTOM, renderKey = "dibujo_silueta"),
    SeccionDef("lh_imagenes", "Imágenes", TipoSeccion.FORM, campos = listOf(CampoDef("fecha_tc", "Fecha TC", TipoCampo.FECHA, grupo = "tc"), CampoDef("tc", "TC", TipoCampo.TEXTO_LARGO), CampoDef("fecha_pet", "Fecha PET", TipoCampo.FECHA, grupo = "pet"), CampoDef("pet", "PET", TipoCampo.TEXTO_LARGO))),
    SeccionDef("lh_biopsia", "Biopsia", TipoSeccion.TEXTO),
    SeccionDef("lh_estadio", "Estadio", TipoSeccion.FORM, campos = listOf(t("ann_arbor", "Ann Arbor"),
        radio("riesgo_localizada", "Riesgo enf. localizada E I-II (NCCN): masa mediastinal > 1/3, ERS > 50, síntomas B, ≥ 3 áreas nodales", "Favorable (0)", "Desfavorable (1 o más)"),
        radio("riesgo_avanzada", "Riesgo enf. avanzada E III-IV (Hasenclever): albúmina, Hb, sexo masculino, > 45 años, leucocitosis > 15 mil, linfocitopenia, E IV", "Bajo (0-1)", "Intermedio (2-3)", "Alto (4-7)"))),
    SeccionDef("lh_antecedentes", "Antecedentes (LH)", TipoSeccion.FORM, campos = COMORBILIDADES),
)
private val linfomaNoHodgkin = listOf(
    SeccionDef("lnh_antecedentes", "Antecedentes (LNH)", TipoSeccion.FORM, campos = SEROLOGIAS + COMORBILIDADES),
    SeccionDef("lnh_examen", "Examen físico (silueta)", TipoSeccion.CUSTOM, renderKey = "dibujo_silueta"),
    SeccionDef("lnh_imagenes", "Imágenes", TipoSeccion.TEXTO), SeccionDef("lnh_biopsia", "Biopsia - CMF", TipoSeccion.TEXTO),
    SeccionDef("lnh_estadio", "Estadio", TipoSeccion.FORM, campos = listOf(t("ann_arbor", "Ann Arbor", "e"), t("lugano", "Lugano", "e"), t("murphy", "Murphy", "e"))),
    SeccionDef("lnh_pronostico", "Pronóstico", TipoSeccion.FORM, campos = listOf(t("ecog", "ECOG", "p1"), t("ldh", "LDH", "p1"), t("testiculo", "Testículo", "p1"), t("extra_nodal", "Extra nodal / renal", "p1"),
        t("snc", "SNC", "p2"), t("edad_60", "Edad > 60", "p2"), t("doble_hit", "Doble hit", "p2"), t("sintomas_b", "Síntomas B", "p2"), t("m_osea", "M. ósea", "p3"), t("bulky", "Bulky", "p3"), t("hiv", "HIV", "p3"),
        CampoDef("ripi_t", "RIPI (1 punto c/u): LDH, edad > 60, E III-IV, ≥ 2 sitios EN, ECOG ≥ 2 → bajo 0-1, intermedio bajo 2, intermedio alto 3, alto 4-5", TipoCampo.ETIQUETA), t("ripi", "RIPI puntaje"),
        CampoDef("flipi_t", "FLIPI: LDH, edad > 60, E III-IV, ≥ 4 sitios ganglionares, Hb → bajo 0-1, intermedio 2, alto ≥ 3", TipoCampo.ETIQUETA), t("flipi", "FLIPI puntaje"),
        CampoDef("mipi_t", "MIPI", TipoCampo.ETIQUETA), radio("mipi_edad", "Edad", "< 50 (0)", "50-59 (1)", "60-69 (2)", "> 70 (3)", grupo = "mipi"), radio("mipi_ecog", "ECOG", "0-1 (0)", "2-4 (1)", grupo = "mipi"),
        radio("mipi_ldh", "LDH", "< 0,67 (0)", "0,67-0,99 (1)", "1,00-1,49 (2)", "≥ 1,50 (3)", grupo = "mipi2"), radio("mipi_leucocitos", "Leucocitos", "< 6.700 (0)", "6.700-9.999 (1)", "10.000-14.999 (2)", "≥ 15.000 (3)", grupo = "mipi2"),
        CampoDef("mipi_r", "MIPI: riesgo bajo 0-3, intermedio 4-5, alto 6-11", TipoCampo.ETIQUETA))),
)
private val gammapatia = listOf(
    SeccionDef("gm_sangre", "Sangre", TipoSeccion.FORM, campos = listOf(t("hto_hb_vcm", "Hto/Hb/VCM", "a"), t("leucocitos", "Leucocitos", "a"), t("plaquetas", "Plaquetas", "a"), t("urea_creatinina", "Urea / creatinina", "a"),
        t("ci_creat", "CI Creat", "b"), t("calcemia", "Calcemia", "b"), t("vsg", "VSG", "b"), t("ldh", "LDH", "b"), t("b2", "B2 microglobulina", "b"),
        CampoDef("prot_t", "Proteinograma", TipoCampo.ETIQUETA), t("prot_totales", "Prot. totales", "p"), t("albumina", "Albúmina", "p"), t("alfa", "Alfa", "p"), t("beta", "Beta", "p"), t("gamma", "Gamma", "p"), t("obs", "Obs", "p"),
        radio("issr", "ISS-R", "1: B2 micro < 3,5", "2: B2 micro 3,5-5,5", "3: B2 micro > 5,5 / LDH alta / citogenético alto riesgo"),
        t("igg", "IgG", "ig"), t("iga", "IgA", "ig"), t("igm", "IgM", "ig"), t("igd", "IgD", "ig"), t("ige", "IgE", "ig"),
        t("inmunofijacion", "Inmunofijación", "i"), t("k_l_libre", "K libre / L libre", "i"), t("componente_m", "Componente M", "i"), t("b_cross_laps", "B cross-laps", "i"))),
    SeccionDef("gm_orina", "Orina", TipoSeccion.FORM, campos = listOf(t("proteinuria", "Proteinuria", "o"), t("proteinograma", "Proteinograma", "o"), t("if_orina", "IF orina", "o"), t("bence_jones", "Comp. M / Bence Jones", "o"))),
    SeccionDef("gm_oseo", "Óseo (RX - RMN - PET)", TipoSeccion.CUSTOM, renderKey = "dibujo_silueta"),
    SeccionDef("gm_medula", "Médula ósea", TipoSeccion.FORM, campos = listOf(t("cmf_plasmocitos", "CMF plasmocitos", "m"), t("aberrantes", "% aberrantes", "m"), t("bandeo_g", "Bandeo G", "m2"), t("fish_17p", "FISH del(17p)", "m2"), t("fish_1q", "FISH 1q", "m2"), t("fish_14q32", "FISH 14q32", "m2"), t("biopsia_plasmocitos", "Biopsia plasmocitos %"))),
    SeccionDef("gm_antecedentes", "Antecedentes personales (GM)", TipoSeccion.FORM, campos = SEROLOGIAS + COMORBILIDADES + listOf(CampoDef("katz_t", "Índice de Katz (D = dependiente, I = independiente)", TipoCampo.ETIQUETA),
        radio("katz_banio", "Baño", "D", "I", grupo = "k"), radio("katz_vestimenta", "Vestimenta", "D", "I", grupo = "k"), radio("katz_movilidad", "Movilidad", "D", "I", grupo = "k"), radio("katz_continencia", "Continencia", "D", "I", grupo = "k"), radio("katz_alimentacion", "Alimentación", "D", "I", grupo = "k"))),
    SeccionDef("gm_diagnostico", "Diagnóstico", TipoSeccion.TEXTO),
    SeccionDef("gm_tratamiento", "Tratamiento", TipoSeccion.FORM, campos = listOf(t("rvd", "RVD", "tr"), t("rd", "RD", "tr"), t("cybord", "CyBorD", "tr"), t("mpv", "MPV", "tr"), t("mp", "MP", "tr"))),
)
private val mielodisplasia = listOf(
    SeccionDef("smd_estudios", "Estudios diagnósticos", TipoSeccion.FORM, campos = listOf(CampoDef("fecha1", "Fecha 1", TipoCampo.FECHA, grupo = "f"), CampoDef("fecha2", "Fecha 2", TipoCampo.FECHA, grupo = "f"),
        t("hto_hb", "Hto/Hb", "a"), t("vcm", "VCM", "a"), t("gb_pmn", "GB/PMN", "a"), t("plaquetas", "Plaquetas", "a"), t("albumina", "Albúmina", "b"), t("ldh", "LDH", "b"), t("ers", "ERS", "b"), t("ferremia", "Ferremia", "b"),
        t("ferritina", "Ferritina", "c"), t("transferrina", "Transferrina", "c"), t("sat_transf", "Sat. transf.", "c"), t("dosaje_epo", "Dosaje EPO", "c"), t("hiv", "HIV", "d"), t("vdrl", "VDRL", "d"), t("hbv", "HBV", "d"), t("hcv", "HCV", "d"),
        t("otro", "Otro", "e"), t("gyf", "G y F", "e"), t("pcd", "PCD", "e"), t("pci", "PCI", "e"),
        CampoDef("biopsia_mo", "Biopsia MO", TipoCampo.TEXTO_LARGO), CampoDef("citogenetico", "Citogenético", TipoCampo.TEXTO_LARGO), CampoDef("who", "Categoría WHO (2016)", TipoCampo.TEXTO_LARGO), CampoDef("comorbilidades", "Comorbilidades", TipoCampo.TEXTO_LARGO),
        t("puntaje_ipssr", "Puntaje IPSS-R", "g"), t("mortalidad", "Mortalidad", "g"), t("progresion_leucemia", "Progresión leucemia", "g"), t("charlson", "Score de Charlson", "g"))),
    SeccionDef("smd_ipssr", "IPSS-R", TipoSeccion.FORM, campos = listOf(
        radio("riesgo_ctg", "Riesgo CTG", "Muy bueno (0)", "Bueno (1)", "Intermedio (2)", "Pobre (3)", "Muy pobre (4)"),
        radio("blastos", "Blastos %", "0-2 (0)", "3-4,9 (1)", "5-10 (2)", "> 10 (3)"), radio("hb", "Hb (g/dl)", "> 10 (0)", "8-9,9 (1)", "< 8 (1,5)"),
        radio("pqt", "Plaquetas", "> 100.000 (0)", "50-99 mil (0,5)", "< 50 mil (1)"), radio("neutrofilos", "Neutrófilos", "> 800 (0)", "< 800 (0,5)"),
        CampoDef("citog_t", "Citogenético: muy bueno del(11q) -Y 4% · bueno normal, del(20p), del(20q) 72% · intermedio del(7q), +8, i(17q), +19 13% · pobre -7, cariotipo complejo (hasta 3) 4% · muy pobre complejo (> 3) 7%", TipoCampo.ETIQUETA),
        radio("puntaje", "Puntaje total", "Muy bajo 0-1,5 (sobrevida 8,8 años)", "Bajo 1,5-3 (5,3 años / LMA 10,8)", "Intermedio 3-4,5 (3 años / 3,2)", "Alto 4,5-6 (1,6 / 1,4)", "Muy alto > 6 (0,8 / 0,73)"))),
    SeccionDef("smd_tratamiento", "Tratamiento", TipoSeccion.TEXTO),
    G.lab("planilla_mm", "Planilla de control mensual MM"),
)

val hematologia = EspecialidadDefinition(
    codigo = "hematologia", nombre = "Hematología", nombreLegacy = "hchematologia",
    tiposConsulta = listOf(
        TipoConsultaDef(C.TIPO_CONTROL, "Control", "control", pestanias = G.pestaniasEstandar,
            secciones = listOf(C.DIAGNOSTICO, C.MOTIVO_CONSULTA, "laboratorio_general", "anticoagulacion", "trombofilia", "hemorragiparo", C.MEDICACION_ACTUAL, C.EVOLUCION, C.AUDIO, C.RESUMEN)),
        TipoConsultaDef("lh", "Linfoma de Hodgkin", "estudio", pestanias = G.pestaniasEstandar, secciones = linfomaHodgkin.map { it.id }),
        TipoConsultaDef("lnh", "Linfoma no Hodgkin", "estudio", pestanias = G.pestaniasEstandar, secciones = linfomaNoHodgkin.map { it.id }),
        TipoConsultaDef("gm", "Gammapatía monoclonal", "estudio", pestanias = G.pestaniasEstandar, secciones = gammapatia.map { it.id }),
        TipoConsultaDef("smd", "Síndrome mielodisplásico", "estudio", pestanias = G.pestaniasEstandar, secciones = mielodisplasia.map { it.id }),
        TipoConsultaDef(C.TIPO_TELEMEDICINA, "Telemedicina", "telemedicina", pestanias = G.pestaniasEstandar, secciones = listOf(C.DATOS_SUBJETIVOS, C.EVOLUCION, C.CONDUCTAS)),
        TipoConsultaDef(C.TIPO_FOTO, "HC digitalizada", "foto", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf(C.FOTOS)),
    ),
    secciones = listOf(
        G.seccionPersonales, G.seccionFamiliares, G.diagnosticos, G.motivo,
        G.lab("laboratorio_general", "Laboratorio general"), G.lab("anticoagulacion", "Control de anticoagulación", expandida = false),
        G.lab("trombofilia", "Trombofilia", expandida = false), G.lab("hemorragiparo", "Trastorno hemorragíparo", expandida = false),
        G.medicacion, G.evolucion, G.audio, G.resumen, G.subjetivo, G.conductas, G.fotos,
    ) + linfomaHodgkin + linfomaNoHodgkin + gammapatia + mielodisplasia,
    antecedentes = listOf(
        CategoriaAntecedentes("personales", "Antecedentes personales", emptyList(), modo = ModoAntecedentes.TEXTO_ACUMULATIVO, notaLibre = false),
        CategoriaAntecedentes("familiares", "Antecedentes familiares", emptyList(), modo = ModoAntecedentes.TEXTO_ACUMULATIVO, notaLibre = false),
    ),
    laboratorios = listOf(
        LaboratorioDef("laboratorio_general", "Laboratorio general", G.analitos("Rto GR", "Hto", "Hb", "VCM", "HCM", "CHCM", "GB", "Pqt", "FSP", "LDH", "ERS", "B2 microglob", "Urea", "Creatinina", "Glucemia", "TGO", "TGP", "FAL", "Bb total",
            "Ferremia", "Transferrina", "Sat transferrina", "TIBC", "Ferritina", "Vit B12", "Folato", "Prot totales", "Albúmina", "Alfa", "Beta", "Gamma", "Pico monoclonal", "IgG", "IgA", "IgM", "IgE", "IgD",
            "Interpretación", "Inmunofijación", "CLL", "Kappa", "Lambda", "Calcio", "Fósforo", "Proteinuria", "TP", "RIN", "APTT")),
        LaboratorioDef("anticoagulacion", "Control de anticoagulación", G.analitos("TP (seg %)", "RIN", "APTT (seg)")),
        LaboratorioDef("trombofilia", "Trombofilia", G.analitos("TP", "RIN", "APTT", "TT", "TS", "F VIII", "PC", "PS", "RPCA", "Ac anti B2 GP IgG", "Ac anti B2 GP IgM", "Ac anticardiolipina IgG", "Ac anticardiolipina IgM", "Anticoagulante lúpico", "Mutación FV Leiden", "Mutación PT 20210", "Homocisteinemia", "Antitrombina III")),
        LaboratorioDef("hemorragiparo", "Trastorno hemorragíparo", G.analitos("Plaquetas", "TP", "RIN", "APTT", "Factor V", "Fibrinógeno", "TT", "TS", "Retracción del coágulo", "Factor VW (Ag)", "Cofactor de ristocetina", "TP corrección", "APTT corrección", "Factor II", "Factor VII", "Factor VIII", "Factor IX", "Factor X", "Inh VIII", "Inh IX")),
        LaboratorioDef("planilla_mm", "Planilla control mensual MM", G.analitos("Hto/Hb", "VCM", "GB/PMN", "Plaquetas", "LDH", "Ferremia", "Ferritina", "Transferrina", "Sat transf", "N° UGR", "P Coombs D", "N° unidades PQT", "Dosaje EPO", "EPO (ui/sem)", "AHM (dosis diaria)", "G-CSF (dosis semanal)", "Agonistas trombopoy")),
    ),
    registros = G.registrosBase,
    examenFisicoCampos = setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.NOTA),
)

@Composable
private fun SiluetaSeccion(s: SeccionDef, ctx: com.salud360.features.hc.SeccionContext) =
    DibujoSeccion(s, ctx, fondo = painterResource(Res.drawable.silueta), colorInicial = androidx.compose.ui.graphics.Color(0xFFE53935), campoResultado = "nota")

val hematologiaContribution = EspecialidadContribution(hematologia, mapOf("dibujo_silueta" to { s, ctx -> SiluetaSeccion(s, ctx) }))
