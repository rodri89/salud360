package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.ExamenFisico

/**
 * Lo cargado en una consulta, tal como sale de la base, para saber qué secciones tienen datos en ella (RF-22).
 * Los antecedentes no van acá: son del paciente y se muestran aparte, en "Datos" (RF-34).
 *
 * - [valores]: `seccion_valor` de la consulta, sección → campo → valor (la misma forma que `ConsultaViewModel.valores`).
 * - [examenFisico]: la fila de `examen_fisico` de la consulta.
 * - [tiposRegistro], [tiposLaboratorio]: tipos de `registro_clinico` y `laboratorio` con filas activas de la consulta.
 * - [seccionesArchivo]: secciones de `archivo` con adjuntos activos de la consulta (incluye audio y fotos).
 * - [diagnosticos]: cuántos diagnósticos tiene asignados la consulta.
 */
data class DatosConsulta(
    val valores: Map<String, Map<String, String>> = emptyMap(),
    val examenFisico: ExamenFisico? = null,
    val tiposRegistro: Set<String> = emptySet(),
    val tiposLaboratorio: Set<String> = emptySet(),
    val seccionesArchivo: Set<String> = emptySet(),
    val diagnosticos: Int = 0,
)

/**
 * Si [valor] es un dato cargado en [campo]: vacío, `0` (sin cargar) y `0|` (casilla sin marcar y sin detalle) no
 * cuentan; una etiqueta nunca. Los valores con detalle (`flag|detalle`) cuentan si tienen la marca o el detalle.
 */
fun campoTieneDato(campo: CampoDef, valor: String?): Boolean = campo.tipo != TipoCampo.ETIQUETA && tieneDato(valor)

/**
 * Ids de las secciones de [def] (la definición armada, con las legadas) que tienen datos en la consulta. Las que se
 * guardan por paciente (antecedentes, ficha, registros `porPaciente`) nunca cuentan, aunque algo suyo quede atado a la
 * consulta: van en [seccionesDelPacienteSoloLectura].
 */
fun seccionesConDatos(datos: DatosConsulta, def: EspecialidadDefinition): Set<String> =
    def.secciones.filter { s -> !esDelPaciente(s, def) && seccionTieneDatos(s, datos, def) }.map { it.id }.toSet()

/**
 * Secciones para mostrar solo para leer al final de la consulta (RF-22, RF-30): las de [conDatos] que no están en
 * [enPerfilParaTipo] (las secciones del perfil para el tipo de la consulta), con el nombre por defecto, primero en
 * orden de catálogo y después las legadas. Lo que la app no conoce se omite.
 *
 * [ocultas] es la preferencia del médico tal cual (RF-28): una oculta que está en su perfil no sale acá (la vuelve a
 * mostrar desde Configuración); una que ya no está en el perfil se ignora y, si tiene datos, sale como cualquier otra.
 * Para eso [enPerfil] son las secciones del perfil en todos los tipos (obligatorio: sin él, una oculta del perfil que
 * no va en este tipo saldría acá).
 */
fun seccionesSoloLectura(
    catalogo: CatalogoHc,
    enPerfilParaTipo: Set<String>,
    conDatos: Set<String>,
    ocultas: Set<String>,
    enPerfil: Set<String>,
): List<SeccionDef> =
    (catalogo.secciones + catalogo.legadas)
        .filter { it.id in conDatos && fueraDelPerfil(it.id, enPerfilParaTipo, ocultas, enPerfil) }
        .map { it.def }

/**
 * Secciones del paciente para mostrar una sola vez, solo para leer, en "Datos" (RF-34, RF-30): las de antecedentes
 * con datos en [antecedentes] (los del paciente en la HC) que no están en el perfil en ningún tipo ([enPerfil]), con el
 * nombre por defecto, primero en orden de catálogo y después las legadas. [ocultas] como en [seccionesSoloLectura]:
 * una oculta del perfil no sale; fuera del perfil se ignora.
 */
fun seccionesDelPacienteSoloLectura(
    catalogo: CatalogoHc,
    antecedentes: List<Antecedente>,
    enPerfil: Set<String>,
    ocultas: Set<String>,
): List<SeccionDef> =
    (catalogo.secciones + catalogo.legadas)
        .filter { it.def.tipo == TipoSeccion.ANTECEDENTES && fueraDelPerfil(it.id, enPerfil, ocultas, enPerfil) }
        .filter { s -> antecedentes.any { a -> a.activo && a.categoria == s.def.categoriaAntecedentes && (a.flag || tieneDato(a.detalle)) } }
        .map { it.def }

/**
 * Lo que una consulta de la HC general muestra solo para leer: [alFinal], debajo de la pestaña elegida (RF-22), y
 * [enDatos], debajo de la ficha del paciente (RF-34).
 */
data class SoloLecturaConsulta(
    val alFinal: List<SeccionDef> = emptyList(),
    val enDatos: List<SeccionDef> = emptyList(),
)

/**
 * [seccionesSoloLectura] y [seccionesDelPacienteSoloLectura] para una consulta de [tipo] con la definición armada
 * [def]: el perfil para el tipo son sus grupos y el perfil entero, los grupos de todos los tipos (C3). Sin perfil o
 * sin configuración no hay grupos, así que todo lo cargado se lee.
 */
fun soloLecturaConsulta(
    catalogo: CatalogoHc,
    def: EspecialidadDefinition,
    tipo: String,
    datos: DatosConsulta,
    antecedentes: List<Antecedente>,
    ocultas: Set<String>,
): SoloLecturaConsulta {
    val enPerfil = def.tiposConsulta.flatMap { t -> t.grupos.flatMap { it.secciones } }.toSet()
    val enPerfilParaTipo = def.tipoConsulta(tipo)?.grupos.orEmpty().flatMap { it.secciones }.toSet()
    return SoloLecturaConsulta(
        alFinal = seccionesSoloLectura(catalogo, enPerfilParaTipo, seccionesConDatos(datos, def), ocultas, enPerfil),
        enDatos = seccionesDelPacienteSoloLectura(catalogo, antecedentes, enPerfil, ocultas),
    )
}

private fun fueraDelPerfil(id: String, enPerfilAca: Set<String>, ocultas: Set<String>, enPerfil: Set<String>) =
    id !in enPerfilAca && !(id in ocultas && id in enPerfil)

private fun esDelPaciente(s: SeccionDef, def: EspecialidadDefinition): Boolean = when (s.tipo) {
    TipoSeccion.ANTECEDENTES, TipoSeccion.FORM_PACIENTE -> true
    TipoSeccion.REGISTROS -> s.registroTipo?.let { def.registro(it) }?.porPaciente == true
    else -> false
}

private fun seccionTieneDatos(s: SeccionDef, datos: DatosConsulta, def: EspecialidadDefinition): Boolean {
    val valores = datos.valores[s.id].orEmpty()
    val archivos = s.conArchivos && s.id in datos.seccionesArchivo
    return archivos || when (s.tipo) {
        TipoSeccion.FORM -> s.campos.any { campoTieneDato(it, valores[it.clave]) }
        TipoSeccion.TEXTO -> tieneDato(valores["texto"])
        TipoSeccion.CUSTOM -> valores.values.any { tieneDato(it) }
        TipoSeccion.EXAMEN_FISICO ->
            datos.examenFisico?.let { ex -> valoresExamen(ex).any { tieneDato(it) } } == true ||
                def.examenFisicoExtra.any { campoTieneDato(it, valores[it.clave]) }
        TipoSeccion.REGISTROS -> s.registroTipo in datos.tiposRegistro
        TipoSeccion.LABORATORIO -> s.laboratorioTipo in datos.tiposLaboratorio
        TipoSeccion.ARCHIVOS, TipoSeccion.AUDIO -> s.id in datos.seccionesArchivo
        TipoSeccion.DIAGNOSTICOS -> datos.diagnosticos > 0
        // Del paciente, no de la consulta (ver esDelPaciente).
        TipoSeccion.ANTECEDENTES, TipoSeccion.FORM_PACIENTE -> false
    }
}

// Incluye frecuencia cardíaca y circunferencia abdominal: aunque la HC general ya no las pida, lo cargado se ve.
private fun valoresExamen(ex: ExamenFisico) = listOf(
    ex.peso, ex.pesoPercentil, ex.talla, ex.tallaPercentil, ex.imc, ex.imcPercentil, ex.perimetroCefalico,
    ex.perimetroCefalicoPercentil, ex.circunferenciaAbdominal, ex.tensionArterial, ex.frecuenciaCardiaca,
    ex.temperatura, ex.saturacion, ex.ipd, ex.nota,
)

internal fun tieneDato(valor: String?): Boolean {
    val v = valor?.trim().orEmpty()
    if ('|' !in v) return v.isNotEmpty() && v != "0"
    val marca = v.substringBefore('|').trim()
    return (marca.isNotEmpty() && marca != "0") || v.substringAfter('|').isNotBlank()
}
