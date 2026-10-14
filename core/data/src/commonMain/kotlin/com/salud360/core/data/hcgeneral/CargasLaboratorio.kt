package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Laboratorio

// Varias cargas de un laboratorio por consulta (RF-55 a RF-64): cada "Nueva carga" es una fila nueva de `laboratorio`;
// las guardadas no se editan, se borran y se recargan, como la web.

/** Una columna guardada de la tabla: la carga, si es de la consulta abierta y si lleva tacho (RF-60, RF-61). */
data class ColumnaLaboratorio(val carga: Laboratorio, val deEstaConsulta: Boolean, val puedeBorrar: Boolean)

/** Varias cargas por consulta solo en la HC general; las demás siguen con una que se edita (RF-64, RF-33). */
fun cargasMultiples(codigo: String): Boolean = codigo == CodigosHc.GENERAL

/**
 * Columnas guardadas, sin las inactivas, por fecha descendente. El orden es estable: a igual fecha queda el de
 * [historico], que la consulta trae por `updated_at DESC` (RF-61). Todas las de [consultaId] y las primeras [maxOtras]
 * de otras consultas, intercaladas (RF-62). El tacho, en todas mientras la consulta se pueda editar (RF-60, RF-63).
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
        .map { ColumnaLaboratorio(it, deEstaConsulta = it.consultaId == consultaId, puedeBorrar = editable) }
}

/** "Nueva carga" mientras se pueda editar y no haya una columna editable abierta, aunque ya haya cargas (RF-55). */
fun mostrarNuevaCarga(editable: Boolean, abierta: Boolean): Boolean = editable && !abierta

/** Una carga sin ningún valor escrito no se guarda (RF-57). */
fun cargaNuevaValida(valores: Map<String, String>): Boolean = valores.values.any { it.isNotBlank() }
