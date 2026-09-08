package com.salud360.especialidades.desarrolloinfantil

import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.RegistroDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionesComunes as C
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.features.hc.EspecialidadContribution
import com.salud360.features.hc.SeccionesGenericas as G

private fun t(clave: String, etiqueta: String, grupo: String? = null) = CampoDef(clave, etiqueta, grupo = grupo)
private fun tl(clave: String, etiqueta: String) = CampoDef(clave, etiqueta, TipoCampo.TEXTO_LARGO)
private fun sn(clave: String, etiqueta: String) = CampoDef(clave, etiqueta, TipoCampo.SI_NO_DETALLE)
private fun radio(clave: String, etiqueta: String, vararg opciones: String, grupo: String? = null) = CampoDef(clave, etiqueta, TipoCampo.RADIO, opciones.toList(), grupo = grupo)
private fun check(clave: String, etiqueta: String, grupo: String) = CampoDef(clave, etiqueta, TipoCampo.CHECK, grupo = grupo)
private val GYF = listOf(CampoDef("gyf_signo", "G y F signo", TipoCampo.SELECT, listOf("+", "-"), grupo = "gyf"), CampoDef("gyf_letra", "G y F grupo", TipoCampo.SELECT, listOf("A", "B", "O", "AB"), grupo = "gyf"), t("pci", "PCI", "gyf"))

private val datosFamiliares = SeccionDef("datos_familiares", "Datos familiares", TipoSeccion.FORM_PACIENTE, icono = "familia", campos = listOf(
    CampoDef("padre_t", "Padre", TipoCampo.ETIQUETA), t("padre_nombre", "Nombre", "p"), t("padre_apellido", "Apellido", "p"), t("padre_edad", "Edad", "p"), t("padre_trabajo", "Trabajo", "p"), t("padre_estudio", "Estudio", "p"),
    CampoDef("madre_t", "Madre", TipoCampo.ETIQUETA), t("madre_nombre", "Nombre", "m"), t("madre_apellido", "Apellido", "m"), t("madre_edad", "Edad", "m"), t("madre_trabajo", "Trabajo", "m"), t("madre_estudio", "Estudio", "m"),
    tl("convivientes", "Convivientes"),
))
private val hermanos = SeccionDef("hermanos", "Hermanos", TipoSeccion.REGISTROS, registroTipo = "hermano", inicialmenteExpandida = false)
private val motivoDerivacion = SeccionDef("motivo_derivacion", "Motivo de consulta / derivación", TipoSeccion.FORM, icono = "motivo", campos = listOf(
    t("profesional_deriva", "Profesional que deriva", "a"), t("pediatra", "Pediatra de cabecera", "a"), t("diagnostico_actual", "Diagnóstico actual", "b"), t("impresion_diagnostica", "Impresión diagnóstica", "b"), t("dd", "DD", "b"),
    tl("plan_accion", "Plan de acción"), t("motivo_derivacion", "Motivo de derivación"), tl("motivo_consulta", "Motivo de consulta"),
))
private val embarazo = SeccionDef("embarazo", "Antec. personales: embarazo", TipoSeccion.FORM_PACIENTE, icono = "embarazo", campos = listOf(
    t("g", "GPCA - G", "gpca"), t("p", "P", "gpca"), t("c", "C", "gpca"), t("a", "A", "gpca"), t("controles", "Controles", "gpca")) + GYF + listOf(
    t("medicaciones", "Medicaciones", "me"), t("enfermedades", "Enfermedades", "me"),
    t("vdrl", "VDRL", "s1"), t("chagas", "Chagas", "s1"), t("hb", "HB", "s1"), t("hc", "HC", "s1"), t("hiv", "HIV", "s2"), t("toxo", "Toxo", "s2"), t("rubeola", "Rubeola", "s2"), t("stgb", "STGB", "s2"),
    t("exp_toxicos", "Exposición a tóxicos", "x"), t("stress", "Stress", "x"),
))
private val parto = SeccionDef("parto", "Antec. personales: parto", TipoSeccion.FORM_PACIENTE, icono = "embarazo", campos = listOf(
    t("eg", "EG", "a"), CampoDef("forma_nacimiento", "Forma de nacimiento", TipoCampo.SELECT, listOf("CESÁREA", "VAGINAL"), grupo = "a"), t("rpm", "RPM", "a"), t("lam", "LAM", "a"),
    t("peso", "Peso", "b"), t("pc", "PC", "b"), t("talla", "Talla", "b"), t("apgar", "Apgar", "b"), t("reanimacion", "Reanimación", "b"),
    radio("internacion_neo", "Internación neonatal", "SI", "NO"),
    check("hiperbilirrubina", "Hiperbilirrubina", "i1"), check("sepsis", "Sepsis", "i1"), check("oxigenoterapia", "Oxigenoterapia", "i1"), check("cirugia", "Cirugía", "i1"), check("hemorragia_cerebral", "Hemorragia cerebral", "i1"),
    check("ehi", "EHI", "i2"), check("dbp", "DBP", "i2"), check("convulsiones", "Convulsiones", "i2"), check("metabolopatia", "Metabolopatía", "i2"), check("corioamnionitis", "Corioamnionitis", "i2"),
    check("exanguinotransf", "Exanguinotransfusión", "i3"), check("rciu", "RCIU", "i3"), check("rop", "ROP", "i3"), CampoDef("otras", "Otras", TipoCampo.CHECK_DETALLE),
) + GYF)
private val postnatales = SeccionDef("postnatales", "Antec. post natales y vacunación", TipoSeccion.FORM_PACIENTE, campos = listOf(
    tl("medicacion", "Medicación"), tl("enfermedad_cronica", "Enfermedad crónica"),
    radio("vacunacion", "Vacunación", "Completa", "Incompleta"), tl("vacunacion_detalle", "Detalle de vacunación"),
))
private val internaciones = SeccionDef("internaciones", "Internaciones", TipoSeccion.REGISTROS, registroTipo = "internacion", inicialmenteExpandida = false)
private val antecFamiliares = SeccionDef("antec_familiares_di", "Antec. familiares", TipoSeccion.FORM_PACIENTE, icono = "familia", conArchivos = true, campos = listOf(
    CampoDef("genograma_t", "Genograma: adjuntá la imagen abajo", TipoCampo.ETIQUETA),
    tl("consanguinidad", "Consanguinidad"), tl("enf_genetica", "Enfermedad genética / cromosómica"), tl("trastorno_desarrollo", "Trastorno del desarrollo"),
    tl("enf_inmunologicas", "Enf. inmunológicas"), tl("enf_psiquiatricas", "Enf. psiquiátricas"), tl("otras", "Otras"),
))
private val escolaridad = SeccionDef("escolaridad", "Escolaridad", TipoSeccion.FORM, icono = "escolaridad", conArchivos = true, campos = listOf(tl("informe", "Informe"), t("tipo", "Tipo", "e"), t("jornada", "Jornada", "e"), t("comportamiento", "Comportamiento", "e")))
private val pautas = SeccionDef("pautas", "Adquisición de pautas", TipoSeccion.FORM, icono = "desarrollo", conArchivos = true, campos = listOf(
    CampoDef("mg_t", "Motricidad gruesa", TipoCampo.ETIQUETA), sn("sosten_cefalico", "Sostén cefálico"), sn("sedestacion", "Sedestación"), sn("gateo", "Gateo"), sn("marcha", "Marcha independiente"),
    CampoDef("mf_t", "Motricidad fina", TipoCampo.ETIQUETA), sn("pinza", "Pinza"), sn("utensilios", "Uso de utensilios"), sn("lapiz", "Uso de lápiz"),
    CampoDef("lc_t", "Lenguaje y comunicación", TipoCampo.ETIQUETA), sn("sonrisa_social", "Sonrisa social"), sn("angustia_8", "Angustia del 8° mes"), sn("atencion_conjunta", "Atención conjunta"), sn("uso_no", "Uso del \"no\""),
    sn("rta_nombre", "Respuesta al nombre"), sn("ordenes_simples", "Comprende órdenes simples"), sn("gestos", "Gestos"),
    t("palabra_cantidad", "Palabra: cantidad", "pa"), t("palabra_forma", "Forma", "pa"), t("palabra_contenido", "Contenido", "pa"), t("palabra_uso", "Uso", "pb"), t("palabra_intencion", "Intención comunicativa", "pb"), t("palabra_balbuceo", "Balbuceo", "pb"),
    CampoDef("so_t", "Socialización", TipoCampo.ETIQUETA), t("relacion_padres", "Relación con padres", "so"), t("relacion_pares", "Relación con pares", "so"), t("juego", "Juego", "so"),
    CampoDef("co_t", "Conducta", TipoCampo.ETIQUETA), t("autorregulacion", "Autorregulación", "co"), t("temperamento", "Temperamento", "co"), t("procesamiento_sensorial", "Procesamiento sensorial", "co"), t("nivel_actividad", "Nivel de actividad", "co"),
    CampoDef("ad_t", "Actividad diaria", TipoCampo.ETIQUETA), t("higiene", "Higiene", "ad"), t("suenio", "Sueño", "ad"), t("comida", "Comida", "ad"), t("control_esfinter", "Control de esfínter", "ad2"), t("rutinas", "Rutinas", "ad2"), t("tiempo_libre", "Tiempo libre", "ad2"),
    tl("cognicion", "Cognición"), tl("dibujo", "Dibujo (DFH) - adjuntá la imagen abajo"), tl("acompaniante", "Acompañante"), sn("seguimiento_especialistas", "Seguimiento con otros especialistas"),
))
private val estudiosOtros = SeccionDef("estudios_otros", "Otros estudios", TipoSeccion.FORM, icono = "estudios", conArchivos = true, inicialmenteExpandida = false, campos = listOf(
    CampoDef("eco_abdominal_fecha", "Eco abdominal - fecha", TipoCampo.FECHA, grupo = "e1"), t("eco_abdominal", "Detalle", "e1"),
    CampoDef("ecg_fecha", "ECG - fecha", TipoCampo.FECHA, grupo = "e2"), t("ecg", "Detalle", "e2"), CampoDef("ecocardio_fecha", "Ecocardio - fecha", TipoCampo.FECHA, grupo = "e3"), t("ecocardio", "Detalle", "e3"),
    CampoDef("eeg_fecha", "EEG - fecha", TipoCampo.FECHA, grupo = "e4"), t("eeg", "Detalle", "e4"), CampoDef("eco_cerebral_fecha", "Eco cerebral - fecha", TipoCampo.FECHA, grupo = "e5"), t("eco_cerebral", "Detalle", "e5"),
    CampoDef("tac_rmn_fecha", "TAC/RMN - fecha", TipoCampo.FECHA, grupo = "e6"), t("tac_rmn", "Detalle", "e6"), CampoDef("peav_fecha", "PEAV - fecha", TipoCampo.FECHA, grupo = "e7"), t("peav", "Detalle", "e7"),
    CampoDef("fo_fecha", "Fondo de ojo - fecha", TipoCampo.FECHA, grupo = "e8"), t("fo", "Detalle", "e8"),
    tl("laboratorio", "Laboratorio"), tl("orina", "Orina"), tl("mf", "Materia fecal"),
))
private val diagnostico = SeccionDef("diagnostico_di", "Diagnóstico", TipoSeccion.FORM, icono = "diagnostico", campos = listOf(tl("diagnostico_actual", "Diagnóstico actual"), tl("impresion_diagnostica", "Impresión diagnóstica"), tl("dd", "DD"), tl("plan_accion", "Plan de acción")))

private val CATEGORIA_ASQ = arrayOf("Normal", "Riesgo", "Retraso", "Otro")
private fun asq(area: String, etiqueta: String) = listOf(CampoDef("${area}_puntaje", "$etiqueta - puntaje", TipoCampo.NUMERO, grupo = area), CampoDef("${area}_categoria", "Categoría", TipoCampo.SELECT, CATEGORIA_ASQ.toList(), grupo = area))
private val TADI = arrayOf("≥ 60 Avanzada", "40-59 Normal", "30-39 Riesgo", "≤ 29 Retraso")
private val BAYLEY = arrayOf("> 130 Muy superior", "120-129 Superior", "110-119 Medio alto", "90-109 Medio", "80-89 Medio bajo", "70-79 Límite", "< 69 Muy bajo")
private fun escalaBase(pref: String) = listOf(t("${pref}_edad", "Edad de realización", "eb"), CampoDef("${pref}_fecha", "Fecha de realización", TipoCampo.FECHA, grupo = "eb"))
private val escalas = listOf(
    SeccionDef("asq", "ASQ-3", TipoSeccion.FORM, icono = "psico", inicialmenteExpandida = false, campos = escalaBase("asq") + asq("comunicacion", "Comunicación") + asq("motor_grueso", "Motor grueso") + asq("motor_fino", "Motor fino") + asq("resolucion", "Resolución de problemas") + asq("socio", "Socio-individual") + tl("resumen", "Resumen de la prueba")),
    SeccionDef("mchat", "M-CHAT (16-30 meses)", TipoSeccion.FORM, icono = "psico", inicialmenteExpandida = false, campos = escalaBase("mchat") + radio("riesgo", "Resultado", "Bajo riesgo", "Alto riesgo")),
    SeccionDef("qchat", "Q-CHAT (16-30 meses)", TipoSeccion.FORM, icono = "psico", inicialmenteExpandida = false, campos = escalaBase("qchat") + radio("riesgo", "Resultado", "Bajo riesgo", "Alto riesgo")),
    SeccionDef("prunape", "PRUNAPE", TipoSeccion.FORM, icono = "psico", inicialmenteExpandida = false, campos = escalaBase("prunape") + radio("resultado", "Resultado", "Pasa prueba", "Falla prueba") + tl("resumen", "Resumen de la prueba")),
    SeccionDef("tadi", "TADI", TipoSeccion.FORM, icono = "psico", inicialmenteExpandida = false, campos = escalaBase("tadi") + listOf("cognicion" to "Cognición", "motricidad" to "Motricidad", "lenguaje" to "Lenguaje", "socioemocional" to "Socioemocional").flatMap { (k, n) -> listOf(radio(k, "$n - puntaje T", *TADI), tl("${k}_resumen", "$n - resumen")) }),
    SeccionDef("bayley", "BAYLEY III", TipoSeccion.FORM, icono = "psico", inicialmenteExpandida = false, campos = escalaBase("bayley") + listOf("cognicion" to "Cognición", "motricidad" to "Motricidad", "lenguaje" to "Lenguaje").flatMap { (k, n) -> listOf(radio(k, "$n - puntuación compuesta", *BAYLEY), tl("${k}_resumen", "$n - resumen")) }),
)

val desarrolloInfantil = EspecialidadDefinition(
    codigo = "desarrollo_infantil", nombre = "Desarrollo infantil", nombreLegacy = "desarrollo_infantil",
    tiposConsulta = listOf(
        TipoConsultaDef(C.TIPO_CONTROL, "Consulta", "control", pestanias = listOf(C.DATOS_PACIENTE, "datos_familiares", "hermanos", "motivo_derivacion", "embarazo", "parto", "postnatales", "internaciones", "antec_familiares_di"),
            secciones = listOf(C.AUDIO, C.EVOLUCION, "escolaridad", "pautas", "estudios_audiologia", "estudios_geneticos", "estudios_otros", C.EXAMEN_FISICO, C.INTERCONSULTA, "diagnostico_di")),
        TipoConsultaDef("escalas", "Escalas y evaluaciones", "escalas", pestanias = listOf(C.DATOS_PACIENTE), secciones = escalas.map { it.id }),
        TipoConsultaDef(C.TIPO_FOTO, "HC digitalizada", "foto", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf(C.FOTOS)),
    ),
    secciones = listOf(datosFamiliares, hermanos, motivoDerivacion, embarazo, parto, postnatales, internaciones, antecFamiliares, G.audio, G.evolucion.copy(conArchivos = false), escolaridad, pautas,
        SeccionDef("estudios_audiologia", "Audiología", TipoSeccion.REGISTROS, registroTipo = "audiologia", icono = "estudios", inicialmenteExpandida = false),
        SeccionDef("estudios_geneticos", "Estudio genético / pesquisa metabólica", TipoSeccion.REGISTROS, registroTipo = "genetico", icono = "estudios", inicialmenteExpandida = false),
        estudiosOtros, G.examenFisico, SeccionDef(C.INTERCONSULTA, "Interconsultas", TipoSeccion.REGISTROS, registroTipo = "interconsulta_di", icono = "registros"), diagnostico, G.fotos) + escalas,
    registros = listOf(
        RegistroDef("hermano", "Hermanos", "Hermano", listOf(t("nombre", "Nombre", "h"), t("apellido", "Apellido", "h"), t("edad", "Edad", "h"), t("patologia", "Patología del desarrollo")), conFecha = false, porPaciente = true),
        RegistroDef("internacion", "Internaciones", "Internación", listOf(tl("motivo", "Motivo")), porPaciente = true),
        RegistroDef("audiologia", "Audiología", "Estudio", listOf(CampoDef("tipo", "Estudio", TipoCampo.SELECT, listOf("OEA", "PEAT", "Audiometría CL", "Audiometría tonal", "Timpanometría", "LOGO", "Impedanciometría")), t("resultado", "Resultado"))),
        RegistroDef("genetico", "Genético / metabólico", "Estudio", listOf(CampoDef("tipo", "Tipo", TipoCampo.SELECT, listOf("Estudio genético / cromosómico", "Pesquisa metabólica")), t("subtipo", "Tipo de estudio"), t("resultado", "Resultado"))),
        RegistroDef("interconsulta_di", "Interconsultas", "Interconsulta", listOf(CampoDef("tipo", "Especialidad", TipoCampo.SELECT, listOf("TO", "Kinesiología", "Psicopedagogía", "Psicología", "Neurología", "Nutrición", "Fonoaudiología")), tl("detalle", "Detalle"))),
    ),
    examenFisicoCampos = setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.PERIMETRO_CEFALICO, CampoExamenFisico.NOTA),
    conPercentilos = true,
)

val desarrolloInfantilContribution = EspecialidadContribution(desarrolloInfantil)
