package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Laboratorio
import kotlinx.datetime.LocalDate

// Varias cargas de un laboratorio por consulta (RF-55 a RF-64): cada "Nueva carga" es una fila nueva de `laboratorio`;
// las guardadas no se editan, se borran y se recargan, como la web.

/** Una columna guardada de la tabla: la carga, si es de la consulta abierta y si lleva tacho (RF-60, RF-61). */
data class ColumnaLaboratorio(val carga: Laboratorio, val deEstaConsulta: Boolean, val puedeBorrar: Boolean)

/** La carga vino de la web (`hcc-l<tipo>-<id>`, laboratorio o peso): la web es su dueña y la app no la borra (RF-62). */
fun Laboratorio.esDeLaWeb(): Boolean = id.startsWith(PREFIJO_ID_WEB)

/** Tacho de una columna: la consulta se puede editar y la carga no es de la web, porque el cambio no llegaría allá (RF-62). */
fun puedeBorrarCarga(carga: Laboratorio, editable: Boolean): Boolean = editable && !carga.esDeLaWeb()

/** Varias cargas por consulta solo en la HC general; las demás siguen con una que se edita (RF-64, RF-33). */
fun cargasMultiples(codigo: String): Boolean = codigo == CodigosHc.GENERAL

/**
 * Columnas guardadas, sin las inactivas, por fecha descendente. El orden es estable: a igual fecha queda el de
 * [historico], que la consulta trae por `updated_at DESC` (RF-61). Todas las de [consultaId] y las primeras [maxOtras]
 * de otras consultas, intercaladas (RF-62). El tacho, en las locales mientras la consulta se pueda editar (RF-60, RF-63);
 * las que vinieron de la web se muestran igual pero sin tacho ([puedeBorrarCarga], RF-62 de la spec 002).
 */
fun columnasLaboratorio(
    historico: List<Laboratorio>,
    consultaId: Id,
    editable: Boolean,
    maxOtras: Int = 8,
): List<ColumnaLaboratorio> {
    var otras = 0
    return historico
        .filter { it.activo }
        .sortedByDescending { it.fecha }
        .filter { it.consultaId == consultaId || otras++ < maxOtras }
        .map { ColumnaLaboratorio(it, deEstaConsulta = it.consultaId == consultaId, puedeBorrar = puedeBorrarCarga(it, editable)) }
}

/** "Nueva carga" mientras se pueda editar y no haya una columna editable abierta, aunque ya haya cargas (RF-55). */
fun mostrarNuevaCarga(editable: Boolean, abierta: Boolean): Boolean = editable && !abierta

/** Una carga sin ningún valor escrito no se guarda (RF-57). */
fun cargaNuevaValida(valores: Map<String, String>): Boolean = valores.values.any { it.isNotBlank() }

// La columna abierta de la HC general (spec 005, T87-a; RF-41, Q-E3-1): se completa con el autoguardado y, si el médico no le elige
// fecha, la que se guarda y viaja es la de la consulta («si la carga no tiene fecha, usa la fecha de la consulta», 002 RF-20/RF-21).
// Nunca la de hoy ni un marcador de «sin fecha»: esa fecha no llega a verla ni la lectura ni la API.

/** El aviso de una columna abierta a la que no se le eligió fecha: «Sin fecha: se usa la de la consulta» (RF-41). */
const val SIN_FECHA_SE_USA_LA_DE_LA_CONSULTA = "Sin fecha: se usa la de la consulta"

/** La fecha con la que se guarda la columna abierta: la elegida o, si no hay, la de la consulta (la de [fechaAEnviar], T82-a). */
fun fechaDeLaCargaAbierta(elegida: LocalDate?, fechaDeLaConsulta: LocalDate): LocalDate = elegida ?: fechaDeLaConsulta

/** [SIN_FECHA_SE_USA_LA_DE_LA_CONSULTA] mientras la columna abierta no tenga fecha; `null` si ya tiene una. */
fun avisoDeCargaSinFecha(fecha: LocalDate?): String? = SIN_FECHA_SE_USA_LA_DE_LA_CONSULTA.takeIf { fecha == null }
