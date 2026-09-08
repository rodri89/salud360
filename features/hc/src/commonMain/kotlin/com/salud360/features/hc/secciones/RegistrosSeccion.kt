package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.ConsultaUi
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.datetime.LocalDate

/**
 * Registros repetibles (interconsultas, exámenes complementarios, internaciones, estudios,
 * screenings, hermanos...). Lista con navegación + diálogo de alta/edición y adjuntos opcionales.
 */
@Composable
fun RegistrosSeccion(s: SeccionDef, ctx: SeccionContext, ui: ConsultaUi) {
    val def = ui.definicion ?: return
    val regDef = s.registroTipo?.let { def.registro(it) } ?: return
    val registros by ctx.vm.registros(regDef.tipo, regDef.porPaciente).collectAsState(emptyList())
    val historicos by (if (regDef.porPaciente) ctx.vm.registrosDelPaciente(regDef.tipo) else ctx.vm.registrosDelPaciente(regDef.tipo)).collectAsState(emptyList())
    var editando by remember { mutableStateOf<RegistroClinico?>(null) }
    var nuevo by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf<RegistroClinico?>(null) }
    val previos = historicos.filter { r -> r.consultaId != ctx.consultaId && registros.none { it.id == r.id } }

    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "registros"), initiallyExpanded = s.inicialmenteExpandida) {
        if (!ctx.soloLectura) ActionRow { AcceptButton("Agregar ${regDef.tituloSingular.lowercase()}", onClick = { nuevo = true }) }
        if (registros.isEmpty() && previos.isEmpty()) Text("Sin registros", color = MaterialTheme.colorScheme.onSurfaceVariant)
        registros.forEach { r -> RegistroFila(r, regDef.campos, esActual = true, soloLectura = ctx.soloLectura, onEditar = { editando = r }, onBorrar = { borrar = r }, ctx = ctx, conArchivos = regDef.conArchivos) }
        if (previos.isNotEmpty()) {
            Text("Anteriores", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            previos.take(20).forEach { r -> RegistroFila(r, regDef.campos, esActual = false, soloLectura = true, onEditar = {}, onBorrar = {}, ctx = ctx, conArchivos = regDef.conArchivos) }
        }
    }

    if (nuevo || editando != null) RegistroDialog(
        titulo = regDef.tituloSingular, campos = regDef.campos, conFecha = regDef.conFecha,
        inicial = editando, fechaDefault = fechaConsulta(ui),
        onGuardar = { fecha, campos -> ctx.vm.guardarRegistro(regDef.tipo, editando?.id, fecha, campos, regDef.porPaciente); nuevo = false; editando = null },
        onCancelar = { nuevo = false; editando = null },
    )
    borrar?.let { r ->
        ConfirmDialog("Eliminar", "¿Eliminar este registro?", onConfirm = { ctx.vm.eliminarRegistro(r.id); borrar = null }, onDismiss = { borrar = null }, confirmText = "Eliminar", destructive = true)
    }
}

@Composable
private fun RegistroFila(
    r: RegistroClinico, campos: List<com.salud360.core.model.especialidad.CampoDef>, esActual: Boolean, soloLectura: Boolean,
    onEditar: () -> Unit, onBorrar: () -> Unit, ctx: SeccionContext, conArchivos: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            r.fecha?.let { Text(it.toDisplay(), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(100.dp)) }
            Column(Modifier.weight(1f)) {
                campos.forEach { c ->
                    val v = r.campos[c.clave].orEmpty()
                    if (v.isNotBlank()) Text("${c.etiqueta}: ${v.replace("|", " - ")}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!esActual) StatusChip("Anterior", Salud360Colors.Grey)
            if (!soloLectura) { LinkButton("Editar", onEditar); LinkButton("Quitar", onBorrar) }
        }
        if (conArchivos) ArchivosInline(seccion = "registro", ctx = ctx, registroId = r.id, soloLectura = soloLectura || !esActual)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }
}

@Composable
fun RegistroDialog(
    titulo: String, campos: List<com.salud360.core.model.especialidad.CampoDef>, conFecha: Boolean,
    inicial: RegistroClinico?, fechaDefault: LocalDate,
    onGuardar: (LocalDate?, Map<String, String>) -> Unit, onCancelar: () -> Unit,
) {
    var fecha by remember { mutableStateOf(inicial?.fecha ?: fechaDefault) }
    var valores by remember { mutableStateOf(inicial?.campos ?: emptyMap()) }
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(if (inicial == null) "Nuevo: $titulo" else "Editar: $titulo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (conFecha) DateField("Fecha", fecha, { it?.let { f -> fecha = f } })
                CamposForm(campos, valores, soloLectura = false) { k, v -> valores = valores + (k to v) }
            }
        },
        confirmButton = { TextButton(onClick = { onGuardar(if (conFecha) fecha else null, valores) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}
