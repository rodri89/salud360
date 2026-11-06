package com.salud360.core.data.repos

import com.salud360.core.model.Id

/**
 * Consultas previas de un texto para mostrar junto al área de escritura: sin la consulta que se está escribiendo, sin
 * las que quedaron vacías y de la más reciente a la más antigua por fecha. Con la misma fecha se respeta el orden de
 * entrada (la base ya desempata por última modificación).
 */
fun consultasPrevias(historial: List<ValorHistorico>, consultaActualId: Id): List<ValorHistorico> =
    historial
        .filter { it.consultaId != consultaActualId && it.valor.isNotBlank() }
        .sortedByDescending { it.fecha }

/** La consulta previa más reciente con texto, o `null` si no hay ninguna. */
fun ultimaConsultaPrevia(historial: List<ValorHistorico>, consultaActualId: Id): ValorHistorico? =
    consultasPrevias(historial, consultaActualId).firstOrNull()
