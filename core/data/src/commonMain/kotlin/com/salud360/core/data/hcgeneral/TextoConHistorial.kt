package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import kotlinx.datetime.LocalDate

// Secciones de texto con historial (`SeccionDef.conHistorial`, RF-83 a RF-85): el texto de cada consulta se guarda en
// `seccion_valor` como siempre y no tiene fecha propia: la de cada entrada es la de su consulta. Las consultas son las
// del paciente en la HC tal como las da `consultasDePaciente` (fecha DESC y, a igual fecha, updated_at DESC); acá se
// reordenan por fecha sin romper ese desempate, que el modelo no trae.

/** El texto de una consulta con la fecha de esa consulta. */
data class EntradaTexto(val consultaId: Id, val fecha: LocalDate, val texto: String)

/**
 * "Consultas previas": el texto de [valores] (consulta → texto) de cada una de [consultas] menos [consultaActual]
 * (también las posteriores, como la web), sin las anuladas ni las de texto en blanco, de la fecha más reciente a la
 * más antigua. Los valores de consultas que no están en [consultas] se ignoran.
 */
fun historialTexto(consultas: List<Consulta>, valores: Map<Id, String>, consultaActual: Id): List<EntradaTexto> =
    consultas
        .filter { it.id != consultaActual && it.estado != EstadoConsulta.ANULADA }
        .mapNotNull { c -> valores[c.id]?.takeIf { it.isNotBlank() }?.let { EntradaTexto(c.id, c.fecha, it) } }
        .sortedByDescending { it.fecha }
