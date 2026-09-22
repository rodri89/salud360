package com.salud360.features.hc.secciones

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.data.repos.hoy
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.ConsultaUi
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.datetime.LocalDate

/**
 * Tabla longitudinal: una fila por analito y una columna por fecha (histórico en solo lectura)
 * más una columna editable para la carga nueva. Equivale a las tablas `laboratorio_general`,
 * `labs`, `trombofilia`, `glucosa`, etc. de las apps originales, unificadas.
 */
@Composable
fun LaboratorioSeccion(s: SeccionDef, ctx: SeccionContext, ui: ConsultaUi) {
    val def = ui.definicion ?: return
    val labDef = s.laboratorioTipo?.let { def.laboratorio(it) } ?: return
    val historico by ctx.vm.laboratorios(labDef.tipo).collectAsState(emptyList())
    val enEstaConsulta = historico.firstOrNull { it.consultaId == ctx.consultaId }
    var editando by remember(enEstaConsulta?.id) { mutableStateOf(!ctx.soloLectura && enEstaConsulta == null) }
    var fecha by remember(enEstaConsulta?.id) { mutableStateOf(enEstaConsulta?.fecha ?: ui.consulta?.fecha ?: hoy()) }
    val valores = remember(enEstaConsulta?.id) { mutableStateOf(enEstaConsulta?.valores ?: emptyMap()) }
    var borrar by remember { mutableStateOf<Laboratorio?>(null) }
    val scroll = rememberScrollState()

    if (ctx.soloLectura && enEstaConsulta == null) { SeccionVacia(); return }
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "laboratorio"), initiallyExpanded = s.inicialmenteExpandida) {
        val columnas = historico.filter { it.id != enEstaConsulta?.id }.take(8)
        if (!ctx.soloLectura) ActionRow {
            if (editando || enEstaConsulta != null) DateField("Fecha", fecha, { it?.let { f -> fecha = f } }, Modifier.width(200.dp))
            if (!editando) AcceptButton(if (enEstaConsulta == null) "Nueva carga" else "Editar", onClick = { editando = true })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Column {
                // cabecera
                Row(Modifier.clip(MaterialTheme.shapes.small).background(Salud360Colors.BrandGradient).padding(vertical = 8.dp)) {
                    Text("Analito", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(190.dp).padding(horizontal = 8.dp))
                    if (editando || enEstaConsulta != null) Text(fecha.toDisplay(), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp).padding(horizontal = 8.dp))
                    columnas.forEach { l ->
                        Row(Modifier.width(120.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(l.fecha.toDisplay(), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (!ctx.soloLectura) IconButton(onClick = { borrar = l }, modifier = Modifier.height(24.dp)) { Icon(Icons.Default.Delete, contentDescription = "Eliminar columna", tint = Color.White) }
                        }
                    }
                }
                var grupo: String? = null
                labDef.analitos.forEachIndexed { i, a ->
                    if (a.grupo != null && a.grupo != grupo) {
                        grupo = a.grupo
                        Text(a.grupo!!, style = MaterialTheme.typography.labelLarge, color = Salud360Colors.Indigo, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp))
                    }
                    Row(
                        Modifier.background(if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(a.etiqueta + (a.unidad?.let { " ($it)" } ?: ""), modifier = Modifier.width(190.dp).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        if (editando) {
                            CeldaEditable(valores.value[a.clave] ?: "") { v -> valores.value = valores.value + (a.clave to v) }
                        } else if (enEstaConsulta != null) {
                            Text(enEstaConsulta.valores[a.clave] ?: "", modifier = Modifier.width(120.dp).padding(horizontal = 8.dp), fontWeight = FontWeight.SemiBold)
                        }
                        columnas.forEach { l -> Text(l.valores[a.clave] ?: "", modifier = Modifier.width(120.dp).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
        if (editando) ActionRow {
            androidx.compose.material3.TextButton(onClick = { editando = false; valores.value = enEstaConsulta?.valores ?: emptyMap() }) { Text("Cancelar") }
            PillButton("Guardar laboratorio", onClick = { ctx.vm.guardarLaboratorio(labDef.tipo, enEstaConsulta?.id, fecha, valores.value); editando = false })
        }
        if (historico.isEmpty() && !editando) Text("Sin cargas anteriores", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    borrar?.let { l ->
        ConfirmDialog("Eliminar columna", "¿Eliminar el laboratorio del ${l.fecha.toDisplay()}?", onConfirm = { ctx.vm.eliminarLaboratorio(l.id); borrar = null }, onDismiss = { borrar = null }, confirmText = "Eliminar", destructive = true)
    }
}

@Composable
private fun CeldaEditable(valor: String, onCambio: (String) -> Unit) {
    Box(
        Modifier.width(120.dp).padding(horizontal = 6.dp).clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        BasicTextField(
            value = valor, onValueChange = onCambio, singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Fecha por defecto para nuevas cargas: la de la consulta. */
internal fun fechaConsulta(ui: ConsultaUi): LocalDate = ui.consulta?.fecha ?: hoy()
