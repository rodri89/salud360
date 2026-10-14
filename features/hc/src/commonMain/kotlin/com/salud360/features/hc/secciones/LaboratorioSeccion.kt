package com.salud360.features.hc.secciones

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.data.hcgeneral.alternarFila
import com.salud360.core.data.hcgeneral.cargaNuevaValida
import com.salud360.core.data.hcgeneral.cargasMultiples
import com.salud360.core.data.hcgeneral.clavePreferenciaFilasOcultas
import com.salud360.core.data.hcgeneral.columnasLaboratorio
import com.salud360.core.data.hcgeneral.esUltimaVisible
import com.salud360.core.data.hcgeneral.filasVisibles
import com.salud360.core.data.hcgeneral.mostrarNuevaCarga
import com.salud360.core.data.hcgeneral.mostrarTodasLasFilas
import com.salud360.core.data.hcgeneral.ocultasDePreferencia
import com.salud360.core.data.hcgeneral.puedeElegirFilas
import com.salud360.core.data.hcgeneral.textoBotonFilas
import com.salud360.core.data.repos.hoy
import com.salud360.core.model.especialidad.AnalitoDef
import com.salud360.core.model.especialidad.LaboratorioDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.CeldaEditable
import com.salud360.core.ui.components.CheckboxField
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.LinkButton
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
    if (cargasMultiples(def.codigo)) { LaboratorioVariasCargas(s, ctx, ui, labDef); return }
    val historico by ctx.vm.laboratorios(labDef.tipo).collectAsState(emptyList())
    val enEstaConsulta = historico.firstOrNull { it.consultaId == ctx.consultaId }
    var editando by remember(enEstaConsulta?.id) { mutableStateOf(!ctx.soloLectura && enEstaConsulta == null) }
    var fecha by remember(enEstaConsulta?.id) { mutableStateOf(enEstaConsulta?.fecha ?: ui.consulta?.fecha ?: hoy()) }
    val valores = remember(enEstaConsulta?.id) { mutableStateOf(enEstaConsulta?.valores ?: emptyMap()) }
    var borrar by remember { mutableStateOf<Laboratorio?>(null) }
    var eligiendoFilas by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // Filas ocultas por el médico (RF-43 a RF-49): solo se dejan de mostrar; `valores` sigue con todas las claves y
    // al guardar las ocultas quedan como estaban (RF-46).
    val clavePreferencia = clavePreferenciaFilasOcultas(def.codigo, labDef.tipo)
    val ocultas = ocultasDePreferencia(ui.preferencias[clavePreferencia])
    val visibles = filasVisibles(labDef.analitos, ocultas)
    val trailing: (@Composable () -> Unit)? = if (puedeElegirFilas(def.codigo, labDef.analitos)) {
        { LinkButton(textoBotonFilas(labDef.analitos, ocultas), onClick = { eligiendoFilas = true }) }
    } else null

    if (ctx.soloLectura && enEstaConsulta == null) { SeccionVacia(); return }
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "laboratorio"), initiallyExpanded = s.inicialmenteExpandida, trailing = trailing) {
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
                visibles.forEachIndexed { i, a ->
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
                            CeldaEditable(valores.value[a.clave] ?: "", onCambio = { v -> valores.value = valores.value + (a.clave to v) })
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
    if (eligiendoFilas) ElegirFilasDialog(labDef.analitos, ocultas, { ctx.vm.guardarPreferencia(clavePreferencia, it.joinToString(",")) }, onCerrar = { eligiendoFilas = false })
}

/**
 * HC general (RF-55 a RF-64): cada "Nueva carga" abre una columna editable primera que se guarda como una carga nueva;
 * las guardadas, de esta consulta o de otras, solo se leen y se borran con el tacho mientras la consulta se pueda editar.
 */
@Composable
private fun LaboratorioVariasCargas(s: SeccionDef, ctx: SeccionContext, ui: ConsultaUi, labDef: LaboratorioDef) {
    val def = ui.definicion ?: return
    val historico by ctx.vm.laboratorios(labDef.tipo).collectAsState(emptyList())
    val editable = !ctx.soloLectura
    var abierta by remember { mutableStateOf(false) }
    var fecha by remember { mutableStateOf(fechaConsulta(ui)) }
    var valores by remember { mutableStateOf(emptyMap<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var borrar by remember { mutableStateOf<Laboratorio?>(null) }
    var eligiendoFilas by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val clavePreferencia = clavePreferenciaFilasOcultas(def.codigo, labDef.tipo)
    val ocultas = ocultasDePreferencia(ui.preferencias[clavePreferencia])
    val visibles = filasVisibles(labDef.analitos, ocultas)
    val trailing: (@Composable () -> Unit)? = if (puedeElegirFilas(def.codigo, labDef.analitos)) {
        { LinkButton(textoBotonFilas(labDef.analitos, ocultas), onClick = { eligiendoFilas = true }) }
    } else null
    val columnas = columnasLaboratorio(historico, ctx.consultaId, editable)
    val cerrar = { abierta = false; valores = emptyMap(); error = null }

    if (ctx.soloLectura && columnas.none { it.deEstaConsulta }) { SeccionVacia(); return }
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "laboratorio"), initiallyExpanded = s.inicialmenteExpandida, trailing = trailing) {
        if (editable) ActionRow {
            if (abierta) DateField("Fecha", fecha, { it?.let { f -> fecha = f } }, Modifier.width(200.dp))
            if (mostrarNuevaCarga(editable, abierta)) AcceptButton("Nueva carga", onClick = { fecha = fechaConsulta(ui); valores = emptyMap(); error = null; abierta = true })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Column {
                // cabecera
                Row(Modifier.clip(MaterialTheme.shapes.small).background(Salud360Colors.BrandGradient).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Analito", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(190.dp).padding(horizontal = 8.dp))
                    if (abierta) Text(fecha.toDisplay(), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp).padding(horizontal = 8.dp))
                    columnas.forEach { c ->
                        Row(Modifier.width(120.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.carga.fecha.toDisplay(), color = Color.White, fontWeight = FontWeight.SemiBold)
                                if (c.deEstaConsulta) Text("Esta consulta", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                            if (c.puedeBorrar) IconButton(onClick = { borrar = c.carga }, modifier = Modifier.height(24.dp)) { Icon(Icons.Default.Delete, contentDescription = "Eliminar columna", tint = Color.White) }
                        }
                    }
                }
                var grupo: String? = null
                visibles.forEachIndexed { i, a ->
                    if (a.grupo != null && a.grupo != grupo) {
                        grupo = a.grupo
                        Text(a.grupo!!, style = MaterialTheme.typography.labelLarge, color = Salud360Colors.Indigo, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp))
                    }
                    Row(
                        Modifier.background(if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(a.etiqueta + (a.unidad?.let { " ($it)" } ?: ""), modifier = Modifier.width(190.dp).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        if (abierta) CeldaEditable(valores[a.clave] ?: "", onCambio = { v -> valores = valores + (a.clave to v); error = null })
                        columnas.forEach { c ->
                            Text(
                                c.carga.valores[a.clave] ?: "", modifier = Modifier.width(120.dp).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (c.deEstaConsulta) FontWeight.SemiBold else null,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
        if (abierta) ActionRow {
            TextButton(onClick = cerrar) { Text("Cancelar") }
            PillButton("Guardar laboratorio", onClick = {
                if (cargaNuevaValida(valores)) { ctx.vm.guardarLaboratorio(labDef.tipo, null, fecha, valores); cerrar() }
                else error = "Cargá al menos un valor"
            })
        }
        if (columnas.isEmpty() && !abierta) Text("Sin cargas anteriores", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    borrar?.let { l ->
        ConfirmDialog("Eliminar columna", "¿Eliminar el laboratorio del ${l.fecha.toDisplay()}?", onConfirm = { ctx.vm.eliminarLaboratorio(l.id); borrar = null }, onDismiss = { borrar = null }, confirmText = "Eliminar", destructive = true)
    }
    if (eligiendoFilas) ElegirFilasDialog(labDef.analitos, ocultas, { ctx.vm.guardarPreferencia(clavePreferencia, it.joinToString(",")) }, onCerrar = { eligiendoFilas = false })
}

/** RF-43 a RF-48: una casilla por fila, en el orden y los grupos del laboratorio; cada cambio se guarda al momento. */
@Composable
private fun ElegirFilasDialog(analitos: List<AnalitoDef>, ocultas: Set<String>, onGuardar: (Set<String>) -> Unit, onCerrar: () -> Unit) {
    val visibles = filasVisibles(analitos, ocultas).map { it.clave }.toSet()
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Elegir filas") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                var grupo: String? = null
                analitos.forEach { a ->
                    if (a.grupo != null && a.grupo != grupo) {
                        grupo = a.grupo
                        Text(a.grupo!!, style = MaterialTheme.typography.labelLarge, color = Salud360Colors.Indigo, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    val ultima = esUltimaVisible(analitos, ocultas, a.clave)
                    CheckboxField(
                        a.etiqueta + (a.unidad?.let { " ($it)" } ?: ""), a.clave in visibles,
                        { onGuardar(alternarFila(analitos, ocultas, a.clave, it)) }, enabled = !ultima,
                    )
                    if (ultima) Text("Tiene que quedar al menos una fila visible", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
        dismissButton = { LinkButton("Mostrar todas", onClick = { onGuardar(mostrarTodasLasFilas(analitos, ocultas)) }) },
    )
}

/** Fecha por defecto para nuevas cargas: la de la consulta. */
internal fun fechaConsulta(ui: ConsultaUi): LocalDate = ui.consulta?.fecha ?: hoy()
