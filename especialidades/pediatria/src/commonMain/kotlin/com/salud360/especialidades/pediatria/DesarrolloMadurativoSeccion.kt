package com.salud360.especialidades.pediatria

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import com.salud360.features.hc.secciones.SeccionVacia

/** Cuatro columnas entran en el ancho de una tablet (808 dp de contenido); en teléfono la tabla se desplaza horizontalmente. */
private val ANCHO_COLUMNA = 196.dp

/**
 * Tabla de hitos del desarrollo madurativo como en la web: cuatro columnas (motor grueso, motor fino, psicosocial,
 * lenguaje) y una fila por hito del tramo de edad del paciente; se puede revisar otro tramo con los chips.
 * Los hitos pertenecen al paciente: cada casilla muestra lo cargado en esta consulta o, si no hay, lo último
 * cargado en consultas anteriores; tildar o destildar escribe en la consulta actual. La observación es de la consulta.
 */
@Composable
fun DesarrolloMadurativoSeccion(s: SeccionDef, ctx: SeccionContext) {
    val valores by ctx.vm.valores.collectAsState()
    val previos by remember(ctx.consultaId) { ctx.vm.valoresPrevios(s.id) }.collectAsState(emptyMap())
    val v = valores[s.id] ?: emptyMap()
    val edad = ctx.edadMeses ?: 0
    var tramo by remember(edad) { mutableIntStateOf(HitosDesarrollo.tramoPara(edad)) }
    val ro = ctx.soloLectura
    fun logrado(h: HitoDesarrollo) = (v[h.clave] ?: previos[h.clave]) == "1"
    // En lectura solo se listan los hitos logrados; si no hay ninguno en ningún tramo ni observación, la sección no se muestra.
    val columnas = HitosDesarrollo.columnasDeTramo(tramo).map { (area, hitos) -> area to (if (ro) hitos.filter(::logrado) else hitos) }
    val filas = columnas.maxOf { it.second.size }
    val observacion = v["observacion"] ?: ""
    if (ro) {
        val algunLogrado = HitosDesarrollo.tramos.any { t -> HitosDesarrollo.columnasDeTramo(t).any { (_, hitos) -> hitos.any(::logrado) } }
        if (!algunLogrado && observacion.isBlank()) { SeccionVacia(); return }
    }

    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "desarrollo"), initiallyExpanded = s.inicialmenteExpandida) {
        Text(HitosDesarrollo.tituloDeTramo(tramo), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Salud360Colors.Indigo)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HitosDesarrollo.tramos.forEach { t ->
                FilterChip(selected = t == tramo, onClick = { tramo = t }, label = { Text(HitosDesarrollo.etiquetaCorta(t)) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column {
                Row(Modifier.clip(MaterialTheme.shapes.small).background(Salud360Colors.BrandGradient).padding(vertical = 8.dp)) {
                    columnas.forEach { (area, _) ->
                        Text(area.etiqueta, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(ANCHO_COLUMNA).padding(horizontal = 8.dp))
                    }
                }
                repeat(filas) { i ->
                    Row(Modifier.background(if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent), verticalAlignment = Alignment.Top) {
                        columnas.forEach { (_, hitos) ->
                            val h = hitos.getOrNull(i)
                            Box(Modifier.width(ANCHO_COLUMNA).padding(horizontal = 4.dp)) {
                                if (h != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = logrado(h), enabled = !ro, onCheckedChange = { on -> ctx.setValor(s.id, h.clave, if (on) "1" else "0") })
                                    Text(h.descripcion, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
        if (!ro || observacion.isNotBlank()) TextAreaField("Observación", observacion, { ctx.setValor(s.id, "observacion", it) }, minLines = 3, readOnly = ro)
    }
}
