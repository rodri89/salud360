package com.salud360.especialidades.cardiologia

import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.CategoriaAntecedentes
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.RegistroDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionesComunes as C
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.features.hc.EspecialidadContribution
import com.salud360.features.hc.SeccionesGenericas as G

private fun check(clave: String, etiqueta: String, grupo: String? = null) = CampoDef(clave, etiqueta, TipoCampo.CHECK, grupo = grupo)
private fun radio(clave: String, etiqueta: String, vararg opciones: String, grupo: String? = null) = CampoDef(clave, etiqueta, TipoCampo.RADIO, opciones.toList(), grupo = grupo)
private fun sel(clave: String, etiqueta: String, vararg opciones: String, grupo: String? = null) = CampoDef(clave, etiqueta, TipoCampo.SELECT, opciones.toList(), grupo = grupo)
private val DERIVACIONES = listOf("I", "II", "III", "AVR", "AVL", "AVF", "V1", "V2", "V3", "V4", "V5", "V6")
private val GRADO = arrayOf("NORMAL", "< 50%", "50 - 70%", "> 70%")
private fun coronaria(clave: String, etiqueta: String) = listOf(
    radio(clave, etiqueta, "Normal", "Obstrucción", "ATC c/Stent", grupo = clave), radio("${clave}_obstruccion", "Grado", "L", "M", "S", "100%", grupo = clave),
)
private fun selOtro(clave: String, etiqueta: String, vararg opciones: String, unidad: String? = null) = listOf(
    CampoDef(clave, etiqueta, TipoCampo.SELECT, opciones.toList() + "OTRO", unidad = unidad, grupo = clave), CampoDef("${clave}_otro", "Otro valor", grupo = clave),
)

private val motivo = SeccionDef(C.MOTIVO_CONSULTA, "Motivo de consulta", TipoSeccion.FORM, icono = "motivo", campos = listOf(
    check("dtx", "DTX", "a"), check("hta", "HTA", "a"), check("ci", "CI", "a"), check("ic", "IC", "a"),
    check("arritmia", "Arritmia", "b"), check("mostrar_estudio", "Mostrar estudio", "b"), check("dolor_toracico", "Dolor torácico", "b"), check("control_rutina", "Control de rutina", "b"),
    check("disnea", "Disnea", "c"), check("control_cardiologico", "Control cardiológico", "c"), check("sincope", "Síncope / presíncope", "c"), check("palpitaciones", "Palpitaciones", "c"),
    check("precompetitivo", "Control precompetitivo", "d"), check("evp", "Enf. vascular periférica", "d"), check("post_atc", "Post ATC", "d"), check("miscelanea", "Miscelánea", "d"),
    CampoDef("otro", "Otro", TipoCampo.CHECK_DETALLE),
))
private val examenCardio = SeccionDef("examen_cardio", "Auscultación cardíaca", TipoSeccion.FORM, icono = "corazon", campos = listOf(
    radio("r1", "R1", "Normofonético", "Hipofonético", grupo = "r"), radio("r2", "R2", "Normofonético", "Hipofonético", grupo = "r"),
    radio("r3", "R3", "Ausente", "Presente", grupo = "r2"), radio("r4", "R4", "Ausente", "Presente", grupo = "r2"),
))
private val soplo = SeccionDef("soplo", "Soplo", TipoSeccion.FORM, icono = "corazon", inicialmenteExpandida = false, campos = listOf(
    radio("soplo", "Soplo", "No", "Si"),
    CampoDef("valvular_titulo", "Valvular", TipoCampo.ETIQUETA),
    check("aortico_sistolico", "Aórtico sistólico", "v1"), check("aortico_regurgitante", "Aórtico regurgitante", "v1"), check("mitral_sistolico", "Mitral sistólico", "v1"), check("mitral_regurgitante", "Mitral regurgitante", "v1"),
    check("pulmonar_sistolico", "Pulmonar sistólico", "v2"), check("pulmonar_regurgitante", "Pulmonar regurgitante", "v2"), check("tricuspide_sistolico", "Tricúspide sistólico", "v2"), check("tricuspide_regurgitante", "Tricúspide regurgitante", "v2"),
    CampoDef("otro_titulo", "Otro", TipoCampo.ETIQUETA),
    check("civ", "CIV", "o"), check("cia", "CIA", "o"), check("ductus", "Ductus arterioso", "o"), CampoDef("otro_detalle", "Otro", grupo = "o"),
))
private val carotidas = SeccionDef("carotidas", "Carótidas (auscultación)", TipoSeccion.FORM, inicialmenteExpandida = false, campos = listOf(
    radio("auscultacion", "Auscultación", "Normal", "Carótida primitiva", "Carótida interna", "Bulbo carotídeo"),
))
private val examenGeneral = SeccionDef("examen_general", "Examen general", TipoSeccion.FORM, inicialmenteExpandida = false, campos = listOf(
    check("normal", "Normal"),
    CampoDef("respiratorio", "Respiratorio", TipoCampo.CHECK_DETALLE), CampoDef("abdominal", "Abdominal", TipoCampo.CHECK_DETALLE), CampoDef("vascular_periferico", "Vascular periférico", TipoCampo.CHECK_DETALLE),
    CampoDef("renal", "Renal", TipoCampo.CHECK_DETALLE), CampoDef("hepatico", "Hepático", TipoCampo.CHECK_DETALLE), CampoDef("neurologico", "Neurológico", TipoCampo.CHECK_DETALLE),
    CampoDef("endocrino", "Endócrino", TipoCampo.CHECK_DETALLE), CampoDef("otro", "Otro", TipoCampo.CHECK_DETALLE),
))
private val ecg = SeccionDef("ecg", "ECG", TipoSeccion.FORM, icono = "cardio", campos = listOf(
    check("normal", "Normal"),
    radio("rs", "Ritmo sinusal", "Si", "No", grupo = "rs"), radio("rs_no", "Si no", "FA", "Aleteo auricular", "Ritmo MCP", grupo = "rs"),
    radio("arritmias", "Arritmias", "No", "Si"),
    check("fa", "FA", "ar"), check("bav", "BAV", "ar"), radio("bav_grado", "BAV", "2/1", "Completo", grupo = "ar"), radio("bav_2_1", "2/1", "Wenckebach", "Mobitz", grupo = "ar"),
    check("bcrd", "BCRD", "ar2"), check("hbai_bird", "HBAI y BIRD", "ar2"), check("hbai_bcrd", "HBAI y BCRD", "ar2"), check("bcri", "BCRI", "ar2"),
    CampoDef("observaciones", "Observaciones", TipoCampo.TEXTO_LARGO),
))
private val conducta = SeccionDef("conducta", "Conducta", TipoSeccion.FORM, campos = listOf(
    radio("solicitar", "Solicitar estudios complementarios", "No", "Si"),
    check("peg", "PEG", "e1"), check("holter", "Holter", "e1"), check("mapa", "MAPA", "e1"), check("ccg", "CCG", "e1"),
    check("tcms", "TCMS", "e2"), check("rmnc", "RMNC", "e2"), check("evc", "EVC", "e2"), check("ecocardiograma", "Ecocardiograma", "e2"),
    check("epm", "EPM", "e3"), check("tilt_test", "Tilt test", "e3"), check("eef", "EEF", "e3"), check("laboratorio", "Laboratorio", "e3"),
    radio("proxima_sugerida", "Fecha de próxima consulta sugerida", "No", "Si", grupo = "px"),
    radio("proxima", "Próxima consulta", "1 semana", "2 semanas", "1 mes", "3 meses", "6 meses", "1 año", grupo = "px2"), CampoDef("proxima_otro", "Otro", grupo = "px2"),
))
private val impresion = SeccionDef("impresion_diagnostica", "Impresión diagnóstica", TipoSeccion.FORM, icono = "diagnostico", campos = listOf(
    check("examen_cv_normal", "Examen CV normal", "i1"), check("cardiopatia_hta", "Cardiopatía HTA", "i1"), check("cardiopatia_isquemica", "Cardiopatía isquémica", "i1"), check("ic", "IC", "i1"),
    check("evp", "EVP", "i2"), check("enf_valvular", "Enf. valvular", "i2"), check("crm_previa", "CRM previa", "i2"), check("arritmia", "Arritmia", "i2"),
    check("dolor_toracico", "Dolor torácico o valvular", "i3"), check("disnea_estudio", "Disnea en estudio", "i3"), check("mareos", "Mareos / presíncope", "i3"), CampoDef("otro", "Otro", grupo = "i3"),
))
private val observacionesFinales = SeccionDef("observaciones_finales", "Observaciones finales", TipoSeccion.FORM, campos = listOf(
    CampoDef("personales", "Observaciones personales", TipoCampo.TEXTO_LARGO), CampoDef("paciente", "Observaciones para el paciente", TipoCampo.TEXTO_LARGO), radio("epicrisis", "Epicrisis", "No", "Si"),
))

private val ecodoppler = SeccionDef("ecodoppler", "Ecodoppler carotídeo", TipoSeccion.FORM, icono = "estudios", campos = listOf(
    CampoDef("ld", "Lado derecho", TipoCampo.ETIQUETA),
    sel("ld_primitiva", "Carótida primitiva", *GRADO, grupo = "ld"), sel("ld_bulbo", "Bulbo carotídeo", *GRADO, grupo = "ld"), sel("ld_interna", "Carótida interna", *GRADO, grupo = "ld"), sel("ld_externa", "Carótida externa", *GRADO, grupo = "ld"),
    CampoDef("li", "Lado izquierdo", TipoCampo.ETIQUETA),
    sel("li_primitiva", "Carótida primitiva", *GRADO, grupo = "li"), sel("li_bulbo", "Bulbo carotídeo", *GRADO, grupo = "li"), sel("li_interna", "Carótida interna", *GRADO, grupo = "li"), sel("li_externa", "Carótida externa", *GRADO, grupo = "li"),
    CampoDef("nota", "Nota", TipoCampo.TEXTO_LARGO),
))
private val cine = SeccionDef("cine", "Cinecoronariografía", TipoSeccion.FORM, icono = "estudios", campos =
    coronaria("tci", "TCI") + coronaria("da", "DA") + coronaria("cx", "Cx") + coronaria("cd", "CD") + CampoDef("conclusion", "Conclusión", TipoCampo.TEXTO_LARGO))
private val peg = SeccionDef("peg", "PEG / Ergometría", TipoSeccion.FORM, icono = "estudios", campos = listOf(
    radio("prueba", "Prueba", "Suficiente", "Insuficiente"),
    sel("mets", "METS", "> 4", "< 19", "OTRO", grupo = "m"), CampoDef("mets_otro", "Otro", grupo = "m"),
    sel("fc_maxima", "FC máxima (%)", "> 60", "> 100", "OTRO", grupo = "f"), CampoDef("fc_maxima_otro", "Otro", grupo = "f"),
    radio("ta", "TA", "Normal", "HTA sistólica", "HTA diastólica", "HTA sistodiastólica", "Caída paradojal de la TA"),
    radio("segmento_st", "Segmento ST", "Normal", "Infra ST", "Supra ST", grupo = "st"), radio("infra_st", "Infra ST", "Baja carga", "Moderada carga", "Alta carga", "Recuperación", grupo = "st"),
    radio("conclusion", "Conclusión", "PEG normal", "PEG anormal por arritmia", "PEG anormal por HTA", "PEG anormal por infra ST"),
))
private val eco = SeccionDef("ecocardiograma", "Ecocardiograma", TipoSeccion.FORM, icono = "estudios", campos =
    selOtro("ddvi", "DDVI (mm)", "< 20", "> 80") + selOtro("dsvi", "DSVI (mm)", "< 10", "> 60") + selOtro("siv", "SIV (mm)", "< 6", "> 19") +
    selOtro("fey", "FEy (%)", "< 20", "> 90") + selOtro("fa", "FA (%)", "< 15", "> 80") +
    listOf(
        sel("fx_sistolica", "Fx sistólica", "NORMAL", "DEPRIMIDA L", "DEPRIMIDA M", "DEPRIMIDA S"),
        sel("fx_diastolica", "Fx diastólica", "NORMAL/PROLONGADA", "ALTERACIÓN INESPECÍFICA DE LA RELAJACIÓN VI", "PATRÓN RESTRICTIVO", "PSEUDO-NORMAL"),
        sel("auricula_izquierda", "Diámetro aurícula izquierda", "NORMAL", "AUMENTADO LEVE", "AUMENTADO MEDIO", "AUMENTADO SEVERO", "MEGA AURÍCULA IZQUIERDA"),
        CampoDef("aorta_ascendente", "Aorta ascendente", grupo = "ao"), CampoDef("aorta_ascendente_detalle", "Detalle", grupo = "ao"),
        CampoDef("diametro_vd", "Diámetro VD", grupo = "vd"), CampoDef("fx_sistolica_vd", "Fx sistólica VD", grupo = "vd"), CampoDef("tapse", "TAPSE", grupo = "vd"),
        CampoDef("valvula_aortica", "Válvula aórtica", TipoCampo.TEXTO_LARGO), CampoDef("valvula_mitral", "Válvula mitral", TipoCampo.TEXTO_LARGO),
        CampoDef("realizado_por", "Realizado por"), CampoDef("observacion", "Observación", TipoCampo.TEXTO_LARGO),
    ))
private val electro = SeccionDef("electrocardiograma", "Electrocardiograma", TipoSeccion.FORM, icono = "cardio", campos = listOf(
    radio("ritmo", "Ritmo", "Ritmo sinusal", "Otro", grupo = "ri"), radio("ritmo_otro", "Ritmo (otro)", "FA", "M", "TV", "FN", "MCP", "Otro", grupo = "ri"), CampoDef("ritmo_detalle", "Detalle", grupo = "ri"),
    sel("fc", "FC", "< 120", "> 40", "OTRO", grupo = "fc"), CampoDef("fc_otro", "Otro", grupo = "fc"),
    radio("bloqueo_av", "Bloqueo AV", "1° grado", "2° grado", "3° grado"),
    radio("rama", "Bloqueo de rama", "BIRD", "BCRD", "HBAI", "BIRI", "BCRI", "HBPI"),
    radio("secuela_iam", "Secuela IAM", "Anterior", "Anterolateral", "Lateral", "Lateral alto", "Inferior", "Inferolateral", "Inferoposterior"),
    CampoDef("onda_t_titulo", "Onda T (-) en derivaciones", TipoCampo.ETIQUETA),
) + DERIVACIONES.map { check("onda_t_${it.lowercase()}", it, "ot") } + listOf(CampoDef("st_titulo", "ST recto en derivaciones", TipoCampo.ETIQUETA)) + DERIVACIONES.map { check("st_${it.lowercase()}", it, "st") } + listOf(
    radio("informe", "Informe", "Electrocardiograma normal", "Imagen ECG de sobrecarga de VI", "Trastorno fijo de la conducción IV", "FA ARV", "Arritmia SV", "Taquicardia sinusal"),
    CampoDef("conclusion", "Conclusión", TipoCampo.TEXTO_LARGO),
))

val cardiologia = EspecialidadDefinition(
    codigo = "cardiologia", nombre = "Cardiología", nombreLegacy = "hccardiologia",
    tiposConsulta = listOf(
        TipoConsultaDef(C.TIPO_CONTROL, "Consulta cardiológica", "cardio", pestanias = listOf(C.DATOS_PACIENTE, C.ANTECEDENTES_PERSONALES, "estudios_realizados"),
            secciones = listOf(C.MOTIVO_CONSULTA, "antecedentes_detalle", C.EXAMEN_FISICO, "examen_cardio", "soplo", "carotidas", "examen_general", "ecg", "conducta", "impresion_diagnostica", "observaciones_finales", "miscelanea", "epicrisis")),
        TipoConsultaDef("ecodoppler", "Ecodoppler carotídeo", "estudio", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf("ecodoppler")),
        TipoConsultaDef("ecocardiograma", "Ecocardiograma", "estudio", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf("ecocardiograma")),
        TipoConsultaDef("cine", "Cinecoronariografía", "estudio", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf("cine")),
        TipoConsultaDef("electro", "Electrocardiograma", "estudio", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf("electrocardiograma")),
        TipoConsultaDef("peg", "PEG / Ergometría", "estudio", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf("peg")),
    ),
    secciones = listOf(
        G.seccionPersonales.copy(titulo = "Antecedentes"),
        SeccionDef("estudios_realizados", "Estudios realizados", TipoSeccion.ANTECEDENTES, categoriaAntecedentes = "estudios_realizados", icono = "estudios"),
        motivo,
        SeccionDef("antecedentes_detalle", "Antecedentes y estudios con fecha", TipoSeccion.REGISTROS, registroTipo = "antecedente_detalle", inicialmenteExpandida = false),
        G.examenFisico, examenCardio, soplo, carotidas, examenGeneral, ecg, conducta, impresion, observacionesFinales,
        G.texto("miscelanea", "Miscelánea"), G.texto("epicrisis", "Epicrisis", expandida = false),
        ecodoppler, cine, peg, eco, electro,
    ),
    antecedentes = listOf(
        CategoriaAntecedentes("personales", "Antecedentes", G.items("hta" to "HTA", "dbt" to "DBT", "dlp" to "DLP", "evp" to "EVP", "ci" to "CI", "enf_valvular" to "Enf. valvular",
            "cirugia_valvula" to "Cirugía válvula protésica", "crm" to "CRM", "aorta" to "Aorta", "cardiopatia_congenita" to "Cardiopatía congénita"), notaLibre = false),
        CategoriaAntecedentes("estudios_realizados", "Estudios realizados", G.items("lab" to "LAB", "peg" to "PEG", "ecocardio" to "Ecocardio", "evc" to "EVC", "holter" to "Holter",
            "mapa" to "MAPA", "ccg" to "CCG", "epm" to "EPM", "tcms" to "TCMS", "rmnc" to "RMNC"), notaLibre = false),
    ),
    registros = G.registrosBase + RegistroDef("antecedente_detalle", "Antecedentes con fecha", "Antecedente / estudio",
        listOf(CampoDef("motivo", "Motivo"), CampoDef("detalle", "Detalle", TipoCampo.TEXTO_LARGO), CampoDef("pendiente", "Pendiente de resultado", TipoCampo.CHECK)), porPaciente = true),
    examenFisicoCampos = setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.FRECUENCIA_CARDIACA, CampoExamenFisico.SATURACION),
)

val cardiologiaContribution = EspecialidadContribution(cardiologia)
