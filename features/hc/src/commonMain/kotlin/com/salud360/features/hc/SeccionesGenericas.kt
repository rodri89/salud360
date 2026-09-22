package com.salud360.features.hc

import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CategoriaAntecedentes
import com.salud360.core.model.especialidad.ItemAntecedente
import com.salud360.core.model.especialidad.RegistroDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionesComunes as C
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoSeccion

/**
 * Secciones genéricas reutilizadas por varias especialidades (motivo, SOAP, conductas, notas,
 * exámenes complementarios, interconsulta, adjuntos, audio). Cada especialidad las incluye por id.
 * Son las secciones que se repetían, con el mismo esquema, en los siete proyectos originales.
 */
object SeccionesGenericas {
    val motivo = SeccionDef(C.MOTIVO_CONSULTA, "Motivo de consulta", TipoSeccion.TEXTO, icono = "motivo")
    val evolucion = SeccionDef(C.EVOLUCION, "Evolución", TipoSeccion.TEXTO, icono = "motivo", conArchivos = true)
    val conductas = SeccionDef(C.CONDUCTAS, "Conductas", TipoSeccion.TEXTO, icono = "conductas")
    val observaciones = SeccionDef(C.OBSERVACIONES, "Observaciones", TipoSeccion.TEXTO, icono = "observaciones")
    val nota = SeccionDef(C.NOTA, "Nota", TipoSeccion.TEXTO, icono = "nota")
    val plan = SeccionDef(C.PLAN, "Plan", TipoSeccion.TEXTO, icono = "plan")
    val resumen = SeccionDef(C.RESUMEN, "Resumen", TipoSeccion.TEXTO, icono = "resumen")
    val subjetivo = SeccionDef(C.DATOS_SUBJETIVOS, "Datos subjetivos", TipoSeccion.TEXTO, icono = "subjetivo")
    val objetivo = SeccionDef(C.DATOS_OBJETIVOS, "Datos objetivos", TipoSeccion.TEXTO, icono = "objetivo")
    val medicacion = SeccionDef(C.MEDICACION_ACTUAL, "Medicación actual", TipoSeccion.TEXTO, icono = "medicacion")
    val habitos = SeccionDef("habitos", "Hábitos", TipoSeccion.TEXTO, icono = "habitos")
    val examenFisico = SeccionDef(C.EXAMEN_FISICO, "Examen físico", TipoSeccion.EXAMEN_FISICO, icono = "examen")
    val examenesComplementarios = SeccionDef(C.EXAMENES_COMPLEMENTARIOS, "Exámenes complementarios", TipoSeccion.REGISTROS, registroTipo = "examen_complementario", icono = "estudios")
    val interconsulta = SeccionDef(C.INTERCONSULTA, "Interconsultas", TipoSeccion.REGISTROS, registroTipo = "interconsulta", icono = "interconsulta")
    val fotos = SeccionDef(C.FOTOS, "Historia clínica digitalizada (fotos)", TipoSeccion.ARCHIVOS, icono = "archivos")
    val documentos = SeccionDef("documentos", "Documentos (PDF e imágenes)", TipoSeccion.ARCHIVOS, icono = "archivos")
    val audio = SeccionDef(C.AUDIO, "Grabación de audio de la consulta", TipoSeccion.AUDIO)
    val diagnosticos = SeccionDef(C.DIAGNOSTICO, "Diagnósticos / enfermedades", TipoSeccion.DIAGNOSTICOS, icono = "diagnostico")
    val screening = SeccionDef("screening", "Screening", TipoSeccion.REGISTROS, registroTipo = "screening", icono = "screening", inicialmenteExpandida = false)

    val registroExamenComplementario = RegistroDef(
        "examen_complementario", "Exámenes complementarios", "Examen complementario",
        listOf(CampoDef("solicito", "Solicitó"), CampoDef("respuesta", "Resultado", TipoCampo.TEXTO_LARGO)), conArchivos = true,
    )
    val registroInterconsulta = RegistroDef(
        "interconsulta", "Interconsultas", "Interconsulta",
        listOf(CampoDef("especialista", "Especialista"), CampoDef("solicito", "Solicitó"), CampoDef("respuesta", "Respuesta", TipoCampo.TEXTO_LARGO)),
    )
    val registroScreening = RegistroDef(
        "screening", "Screenings", "Screening",
        listOf(CampoDef("evaluacion", "Evaluación"), CampoDef("respuesta", "Resultado", TipoCampo.TEXTO_LARGO)), porPaciente = true,
    )
    val registrosBase = listOf(registroExamenComplementario, registroInterconsulta, registroScreening)

    /** Antecedentes personales estándar (alergias, cirugías, traumatismos, transfusiones, internaciones). */
    val personalesEstandar = CategoriaAntecedentes(
        "personales", "Antecedentes personales",
        listOf(
            ItemAntecedente("enfermedad_actual", "Enfermedad actual", soloDetalle = true),
            ItemAntecedente("alergias", "Alergias"), ItemAntecedente("qx", "Cirugías (Qx)"), ItemAntecedente("traumatismos", "Traumatismos"),
            ItemAntecedente("transfusiones", "Transfusiones"), ItemAntecedente("internaciones", "Internaciones"), ItemAntecedente("otro", "Otro"),
        ),
    )
    val familiaresTexto = CategoriaAntecedentes("familiares", "Antecedentes familiares", emptyList(), notaLibre = true)
    val seccionPersonales = SeccionDef(C.ANTECEDENTES_PERSONALES, "Antec. personales", TipoSeccion.ANTECEDENTES, categoriaAntecedentes = "personales", icono = "antecedentes")
    val seccionFamiliares = SeccionDef(C.ANTECEDENTES_FAMILIARES, "Antec. familiares", TipoSeccion.ANTECEDENTES, categoriaAntecedentes = "familiares", icono = "familia")
    val pestaniasEstandar = listOf(C.DATOS_PACIENTE, C.ANTECEDENTES_PERSONALES, C.ANTECEDENTES_FAMILIARES)

    /** Convierte una lista de etiquetas en analitos con clave normalizada. */
    fun analitos(vararg etiquetas: String) = etiquetas.map { com.salud360.core.model.especialidad.AnalitoDef(clave(it), it) }
    fun clave(etiqueta: String) = etiqueta.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    fun items(vararg pares: Pair<String, String>) = pares.map { ItemAntecedente(it.first, it.second) }
    fun texto(id: String, titulo: String, icono: String? = null, expandida: Boolean = false) = SeccionDef(id, titulo, TipoSeccion.TEXTO, icono = icono, inicialmenteExpandida = expandida)
    fun lab(id: String, titulo: String, tipo: String = id, expandida: Boolean = true) = SeccionDef(id, titulo, TipoSeccion.LABORATORIO, laboratorioTipo = tipo, icono = "laboratorio", inicialmenteExpandida = expandida)
}
