package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.HcClinicaBackend
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico

// La consulta de la web en la pantalla (spec 002, entrega L: RF-39, RF-43 a RF-45, RF-56, RF-58, RF-62, RF-63). Puro: la
// pantalla muestra lo que esto decide y no decide nada (constitución, punto 4). Una consulta de la web se lee siempre, abierta
// o cerrada, propia o de otro médico: sin editar, sin cerrar, sin reabrir y sin anular (RF-45); y lo que vino de la web
// tampoco se toca desde una consulta del dispositivo, porque el cambio no llegaría y los dos lados quedarían distintos (RF-62).

// ---------- Qué es «de la web» ----------

/** El id es de algo traído de la web de la HC general (consulta, carga, fila, pendiente, antecedente o médico): `hcc-…`. */
fun esIdDeLaWeb(id: Id): Boolean = id.startsWith(PREFIJO_ID_WEB)

/**
 * La consulta se abre solo para leer porque la trajo la web: tiene id `hcc-…` (siempre, con cualquier backend: ese prefijo lo
 * usa solo esta lectura) o tiene `remoto_id` y su backend es de solo lectura ([backendSoloLectura]). En pediatría una consulta
 * creada en el dispositivo también tiene `remoto_id` una vez enviada, y esa se sigue editando; lo mismo una [Consulta.propia]
 * de la HC general ya enviada (RF-62).
 */
fun Consulta.esSoloLecturaWeb(backendSoloLectura: Boolean): Boolean = esIdDeLaWeb(id) || (backendSoloLectura && remotoId.isNotBlank() && !propia)

// ---------- Qué se puede hacer con la consulta (RF-45) ----------

/** Qué ofrece la pantalla de una consulta: si se edita, si tiene «Guardar y cerrar» y si tiene «Reabrir para editar». */
data class EdicionDeConsulta(val soloLectura: Boolean, val puedeCerrar: Boolean, val puedeReabrir: Boolean)

/**
 * La regla de siempre para las consultas del dispositivo: se lee la que no está abierta o la que su tipo deja solo para
 * leer ([soloLecturaPorTipo]); se cierra la que se edita; se reabre la que se lee si su tipo lo permite
 * ([puedeReabrirPorTipo]). Una consulta de la web ([soloLecturaWeb]) se lee siempre y no se cierra ni se reabre, esté
 * abierta o cerrada allá.
 */
fun edicionDeConsulta(estado: EstadoConsulta, soloLecturaPorTipo: Boolean, puedeReabrirPorTipo: Boolean, soloLecturaWeb: Boolean): EdicionDeConsulta {
    val soloLectura = soloLecturaWeb || estado != EstadoConsulta.ABIERTA || soloLecturaPorTipo
    return EdicionDeConsulta(
        soloLectura = soloLectura,
        puedeCerrar = !soloLectura,
        puedeReabrir = soloLectura && !soloLecturaWeb && puedeReabrirPorTipo,
    )
}

/** La etiqueta del estado en la cabecera de una consulta que se lee: «Solo lectura» o «Cerrada» (una de la web abierta no está cerrada). */
fun etiquetaDeEstadoEnLectura(estado: EstadoConsulta, soloLecturaPorTipo: Boolean, soloLecturaWeb: Boolean): String =
    if (soloLecturaPorTipo || (soloLecturaWeb && estado == EstadoConsulta.ABIERTA)) "Solo lectura" else "Cerrada"

// ---------- Filas de la web dentro de una consulta del dispositivo (RF-62) ----------

/** El registro (estudio, examen complementario o interconsulta) vino de la web (`hcc-r<tipo>-<id>`). */
fun RegistroClinico.esDeLaWeb(): Boolean = esIdDeLaWeb(id)

/** El antecedente vino de la web (`hcc-a<ficha>-<clave>`). */
fun Antecedente.esDeLaWeb(): Boolean = esIdDeLaWeb(id)

/** «Editar» y «Quitar» de una fila: la consulta se puede editar y el registro no es de la web (RF-62). */
fun puedeEditarRegistro(registro: RegistroClinico, editable: Boolean): Boolean = editable && !registro.esDeLaWeb()

/** La casilla o la nota de antecedentes se edita si la consulta se edita y la fila, si existe, no es de la web (RF-62). */
fun puedeEditarAntecedente(antecedente: Antecedente?, editable: Boolean): Boolean = editable && (antecedente == null || !antecedente.esDeLaWeb())

/** Los pendientes de la web sin resolver: el cierre de una consulta del dispositivo los muestra aparte, solo para leer (RF-62). */
fun pendientesDeLaWeb(pendientes: List<Pendiente>): List<Pendiente> = pendientes.filter { it.esDeLaWeb() && !it.resuelto }

// ---------- Avisos de la pantalla (RF-43 a RF-45, RF-63) ----------

/**
 * Los avisos de la pantalla de [consulta]: los de [avisosDeConsulta] si es una consulta que se lee porque vino de la web
 * ([esSoloLecturaWeb]) y ninguno si no (las del dispositivo y las de pediatría se ven como siempre).
 */
fun avisosDeLaPantallaDeConsulta(
    consulta: Consulta,
    backendSoloLectura: Boolean,
    medicoSesionId: Id,
    nombresMedicos: Map<Id, String>,
    lectura: ExtrasDeLectura,
): AvisosDeConsulta =
    if (consulta.esSoloLecturaWeb(backendSoloLectura)) avisosDeConsulta(consulta, medicoSesionId, nombresMedicos, lectura) else AvisosDeConsulta.NINGUNO

// ---------- «Ya no figura en la web» en una sección (RF-63) ----------

/** La marca de una sección de una consulta: [texto] y, si se sabe cómo se llaman, los [campos] que la web dejó de mandar. */
data class MarcaDeSeccion(val texto: String, val campos: List<String>)

/**
 * La marca de la sección [seccion] de la consulta [consultaId], o `null` si la web no dejó de mandar nada de ella. [etiquetas]
 * son los nombres de los campos de la sección (clave → etiqueta): un campo sin etiqueta conocida no se nombra, y el campo
 * `texto` de una sección de texto es la sección misma. Lo marcado se sigue mostrando como estaba.
 */
fun marcaDeSeccion(ausencias: AusenciasEnLaWeb, consultaId: Id, seccion: String, etiquetas: Map<String, String> = emptyMap()): MarcaDeSeccion? {
    val campos = ausencias.campos(consultaId, seccion)
    if (campos.isEmpty()) return null
    return MarcaDeSeccion(YA_NO_FIGURA_EN_LA_WEB, campos.filter { it != CAMPO_TEXTO }.mapNotNull { etiquetas[it] })
}

// ---------- La lectura de la consulta (RF-56, RF-58) ----------

/** Cómo va la lectura de la consulta al abrirla: leyendo, o el [error] con el que falló (lo ya traído se sigue viendo). */
data class LecturaDeLaConsulta(val leyendo: Boolean = false, val error: Throwable? = null)

/**
 * El mensaje de una lectura que falló, nombrando la historia clínica ([nombreHc]) y el motivo (RF-56), o `null` si no hay
 * nada que decir: el `404 consulta_no_encontrada` no es un error de lectura, es que la consulta ya no figura en la web, y
 * eso lo dice su marca (E6.4b la escribió) con el aviso de [avisosDeConsulta].
 */
fun mensajeDeLaLecturaDeConsulta(nombreHc: String, error: Throwable): String? {
    if (error is TobbException && error.status == 404 && error.codigo == HcClinicaBackend.CODIGO_CONSULTA_NO_ENCONTRADA) return null
    return motivoDeLaLectura(nombreHc, error).texto
}
