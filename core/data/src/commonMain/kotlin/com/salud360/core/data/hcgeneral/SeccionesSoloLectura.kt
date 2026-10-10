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
 * Lo cargado en una consulta, tal como sale de la base, para saber qué secciones tienen datos (RF-22).
 *
 * - [valores]: `seccion_valor` de la consulta, sección → campo → valor (la misma forma que `ConsultaViewModel.valores`).
 * - [examenFisico]: la fila de `examen_fisico` de la consulta.
 * - [antecedentes]: los del paciente en la HC: se guardan por paciente y se acumulan entre consultas, y el bloque de
 *   lectura muestra esos mismos.
 * - [tiposRegistro], [tiposLaboratorio]: tipos de `registro_clinico` y `laboratorio` con filas activas de la consulta.
 * - [seccionesArchivo]: secciones de `archivo` con adjuntos activos de la consulta (incluye audio y fotos).
 * - [diagnosticos]: cuántos diagnósticos tiene asignados la consulta.
 */
data class DatosConsulta(
    val valores: Map<String, Map<String, String>> = emptyMap(),
    val examenFisico: ExamenFisico? = null,
    val antecedentes: List<Antecedente> = emptyList(),
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

/** Ids de las secciones de [def] (la definición armada, con las legadas) que tienen datos en la consulta. */
fun seccionesConDatos(datos: DatosConsulta, def: EspecialidadDefinition): Set<String> =
    def.secciones.filter { s -> seccionTieneDatos(s, datos, def) }.map { it.id }.toSet()

/**
 * Secciones para mostrar solo para leer al final de la consulta (RF-22, RF-30): las de [conDatos] que no están en
 * [enPerfilParaTipo] (las secciones del perfil para el tipo de la consulta), con el nombre por defecto, primero en
 * orden de catálogo y después las legadas. Lo que la app no conoce se omite.
 *
 * [ocultas] es la preferencia del médico tal cual (RF-28): una oculta que está en su perfil no sale acá (la vuelve a
 * mostrar desde Configuración); una que ya no está en el perfil se ignora y, si tiene datos, sale como cualquier otra.
 * Para eso [enPerfil] son las secciones del perfil en todos los tipos; si no se pasan, solo se miran las del tipo.
 */
fun seccionesSoloLectura(
    catalogo: CatalogoHc,
    enPerfilParaTipo: Set<String>,
    conDatos: Set<String>,
    ocultas: Set<String>,
    enPerfil: Set<String> = enPerfilParaTipo,
): List<SeccionDef> =
    (catalogo.secciones + catalogo.legadas)
        .filter { it.id in conDatos && it.id !in enPerfilParaTipo && !(it.id in ocultas && it.id in enPerfil) }
        .map { it.def }

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
        TipoSeccion.ANTECEDENTES -> datos.antecedentes.any { a ->
            a.activo && a.categoria == s.categoriaAntecedentes && (a.flag || tieneDato(a.detalle))
        }
        TipoSeccion.DIAGNOSTICOS -> datos.diagnosticos > 0
        // Se guarda en la ficha del paciente, no en la consulta.
        TipoSeccion.FORM_PACIENTE -> false
    }
}

// Incluye frecuencia cardíaca y circunferencia abdominal: aunque la HC general ya no las pida, lo cargado se ve.
private fun valoresExamen(ex: ExamenFisico) = listOf(
    ex.peso, ex.pesoPercentil, ex.talla, ex.tallaPercentil, ex.imc, ex.imcPercentil, ex.perimetroCefalico,
    ex.perimetroCefalicoPercentil, ex.circunferenciaAbdominal, ex.tensionArterial, ex.frecuenciaCardiaca,
    ex.temperatura, ex.saturacion, ex.ipd, ex.nota,
)

private fun tieneDato(valor: String?): Boolean {
    val v = valor?.trim().orEmpty()
    if ('|' !in v) return v.isNotEmpty() && v != "0"
    val marca = v.substringBefore('|').trim()
    return (marca.isNotEmpty() && marca != "0") || v.substringAfter('|').isNotBlank()
}
