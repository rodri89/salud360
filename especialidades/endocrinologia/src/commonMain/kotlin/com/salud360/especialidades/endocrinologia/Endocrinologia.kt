package com.salud360.especialidades.endocrinologia

import com.salud360.core.model.especialidad.AnalitoDef
import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.CategoriaAntecedentes
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.LaboratorioDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionesComunes as C
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.features.hc.EspecialidadContribution
import com.salud360.features.hc.SeccionesGenericas as G

val endocrinologia = EspecialidadDefinition(
    codigo = "endocrinologia", nombre = "Endocrinología", nombreLegacy = "hcendocrinologia",
    tiposConsulta = listOf(
        TipoConsultaDef(C.TIPO_CONTROL, "Consulta", "control", pestanias = G.pestaniasEstandar,
            secciones = listOf(C.MOTIVO_CONSULTA, "medicacion_hormonal", C.EXAMEN_FISICO, "estudios_complementarios", C.PLAN, "laboratorio", "glucosa", "frax", C.NOTA)),
        TipoConsultaDef(C.TIPO_TELEMEDICINA, "Telemedicina", "telemedicina", pestanias = G.pestaniasEstandar,
            secciones = listOf(C.DATOS_SUBJETIVOS, C.DATOS_OBJETIVOS, C.CONDUCTAS)),
        TipoConsultaDef(C.TIPO_FOTO, "HC digitalizada", "foto", pestanias = listOf(C.DATOS_PACIENTE), secciones = listOf(C.FOTOS)),
    ),
    secciones = listOf(
        G.seccionPersonales, G.seccionFamiliares, G.motivo,
        G.texto("medicacion_hormonal", "Medicación hormonal", "medicacion", expandida = true),
        G.examenFisico,
        SeccionDef("estudios_complementarios", "Estudios complementarios", TipoSeccion.TEXTO, icono = "estudios", conArchivos = true),
        G.plan, G.lab("laboratorio", "Laboratorio"), G.lab("glucosa", "Valores de glucosa en sangre", expandida = false),
        SeccionDef("frax", "Riesgo de fractura", TipoSeccion.FORM, inicialmenteExpandida = false, campos = listOf(
            CampoDef("frax_info", "Calculadora FRAX (Universidad de Sheffield): frax.shef.ac.uk — cargá acá el resultado.", TipoCampo.ETIQUETA),
            CampoDef("frax_mayor", "Fractura osteoporótica mayor (%)", TipoCampo.NUMERO, grupo = "f"), CampoDef("frax_cadera", "Fractura de cadera (%)", TipoCampo.NUMERO, grupo = "f"),
        )),
        G.nota, G.subjetivo, G.objetivo, G.conductas, G.fotos,
    ),
    antecedentes = listOf(
        CategoriaAntecedentes("personales", "Antecedentes personales", G.items(
            "cardio_vascular" to "Cardiovascular", "cirugias" to "Cirugías", "respiratorio" to "Respiratorio", "gastrointestinal" to "Gastrointestinal", "alergia" to "Alergias",
            "tabaquismo" to "Tabaquismo", "metabolico" to "Metabólico", "op" to "Osteoporosis", "fracturas" to "Fracturas", "uso_corticoides" to "Uso de corticoides",
            "gineco_obstetrico" to "Gineco-obstétrico", "fum" to "FUM", "menopausia" to "Menopausia", "metodo_anticonceptivo" to "Método anticonceptivo", "pc" to "PC", "otro" to "Otro",
        )),
        G.familiaresTexto,
    ),
    laboratorios = listOf(
        LaboratorioDef("laboratorio", "Laboratorio", listOf(
            AnalitoDef("hto", "HTO", grupo = "Hemograma"), AnalitoDef("hb", "HB", grupo = "Hemograma"), AnalitoDef("gb", "GB", grupo = "Hemograma"), AnalitoDef("plaquetas", "Plaquetas", grupo = "Hemograma"),
            AnalitoDef("glucemia", "Glucemia", grupo = "Metabólico"), AnalitoDef("hba1c", "HbA1c", grupo = "Metabólico"), AnalitoDef("creatinina", "Creatinina", grupo = "Metabólico"), AnalitoDef("filtrado_glomerular", "Filtrado glomerular", grupo = "Metabólico"),
            AnalitoDef("col_t", "Colesterol total", grupo = "Metabólico"), AnalitoDef("hdl", "HDL", grupo = "Metabólico"), AnalitoDef("ldl", "LDL", grupo = "Metabólico"), AnalitoDef("tgc", "TGC", grupo = "Metabólico"),
            AnalitoDef("hepatograma", "Hepatograma", grupo = "Metabólico"), AnalitoDef("ac_urico", "Ácido úrico", grupo = "Metabólico"), AnalitoDef("calcio_t", "Calcio total", grupo = "Metabólico"), AnalitoDef("vitamina_d", "Vitamina D", grupo = "Metabólico"),
            AnalitoDef("rac", "RAC", grupo = "Metabólico"), AnalitoDef("vsg", "VSG", grupo = "Metabólico"),
            AnalitoDef("tsh", "TSH", grupo = "Tiroides"), AnalitoDef("t4", "T4", grupo = "Tiroides"), AnalitoDef("atpo", "ATPO", grupo = "Tiroides"), AnalitoDef("atg", "ATG", grupo = "Tiroides"),
            AnalitoDef("fsh", "FSH", grupo = "Hormonal"), AnalitoDef("lh", "LH", grupo = "Hormonal"), AnalitoDef("e2", "E2", grupo = "Hormonal"), AnalitoDef("cortisol", "Cortisol", grupo = "Hormonal"),
            AnalitoDef("gh", "GH", grupo = "Hormonal"), AnalitoDef("igf1", "IGF1", grupo = "Hormonal"), AnalitoDef("pth", "PTH", grupo = "Hormonal"), AnalitoDef("depyr", "Depyr", grupo = "Hormonal"),
        )),
        LaboratorioDef("glucosa", "Glucosa en sangre", listOf(
            AnalitoDef("desayuno_antes", "Desayuno - antes"), AnalitoDef("desayuno_despues", "Desayuno - después"), AnalitoDef("almuerzo_antes", "Almuerzo - antes"), AnalitoDef("almuerzo_despues", "Almuerzo - después"),
            AnalitoDef("merienda_antes", "Merienda - antes"), AnalitoDef("merienda_despues", "Merienda - después"), AnalitoDef("cena_antes", "Cena - antes"), AnalitoDef("cena_despues", "Cena - después"), AnalitoDef("notas", "Notas"),
        )),
    ),
    registros = G.registrosBase,
    examenFisicoExtra = listOf(CampoDef("tiroides", "Tiroides")),
    examenFisicoCampos = setOf(CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL, CampoExamenFisico.TENSION_ARTERIAL, CampoExamenFisico.NOTA),
)

val endocrinologiaContribution = EspecialidadContribution(endocrinologia)
