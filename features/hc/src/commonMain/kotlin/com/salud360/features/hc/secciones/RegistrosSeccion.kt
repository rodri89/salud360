package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.CampoDef
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Registros repetibles (interconsultas, exámenes complementarios, internaciones, estudios,
 * screenings, hermanos...). Con más de un registro se muestran de a uno, deslizando hacia la derecha,
 * con "n - total" y flechas; diálogo de alta/edición y adjuntos opcionales.
 * En lectura solo se muestran los registros de esta consulta (o del paciente, si son por paciente).
 */
@Composable
fun RegistrosSeccion(s: SeccionDef, ctx: SeccionContext, ui: ConsultaUi) {
    val def = ui.definicion ?: return
    val regDef = s.registroTipo?.let { def.registro(it) } ?: return
    val registros by ctx.vm.registros(regDef.tipo, regDef.porPaciente).collectAsState(emptyList())
    // "Anteriores" solo tiene sentido para registros por consulta (los de otras consultas del paciente);
    // los registros por paciente ya se listan completos en `registros`. En lectura no se muestran.
    val historicos by (if (regDef.porPaciente || ctx.soloLectura) flowOf(emptyList()) else ctx.vm.registrosDelPaciente(regDef.tipo)).collectAsState(emptyList())
    var editando by remember { mutableStateOf<RegistroClinico?>(null) }
    var nuevo by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf<RegistroClinico?>(null) }
    val previos = historicos.filter { r -> r.consultaId != ctx.consultaId && registros.none { it.id == r.id } }

    if (ctx.soloLectura && registros.isEmpty()) { SeccionVacia(); return }

    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "registros"), initiallyExpanded = s.inicialmenteExpandida) {
        if (!ctx.soloLectura) ActionRow { AcceptButton("Agregar ${regDef.tituloSingular.lowercase()}", onClick = { nuevo = true }) }
        if (registros.isEmpty() && previos.isEmpty()) Text("Sin registros", color = MaterialTheme.colorScheme.onSurfaceVariant)
        RegistrosPaginados(
            items = registros.map { it to true } + previos.map { it to false },
            campos = regDef.campos, soloLectura = ctx.soloLectura, ctx = ctx, conArchivos = regDef.conArchivos,
            onEditar = { editando = it }, onBorrar = { borrar = it },
        )
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

/**
 * Un registro por página, deslizable hacia la derecha, con "n - total" y flechas para avanzar y retroceder.
 * Con un solo registro no hay controles. Cada par es (registro, esActual): los que no son de esta consulta
 * se marcan "Anterior" y no se editan. Al agregar un registro el pager salta a él.
 */
@Composable
private fun RegistrosPaginados(
    items: List<Pair<RegistroClinico, Boolean>>, campos: List<CampoDef>, soloLectura: Boolean, ctx: SeccionContext, conArchivos: Boolean,
    onEditar: (RegistroClinico) -> Unit, onBorrar: (RegistroClinico) -> Unit,
) {
    if (items.isEmpty()) return
    if (items.size == 1) {
        val (r, esActual) = items.single()
        RegistroFila(r, campos, esActual, soloLectura || !esActual, { onEditar(r) }, { onBorrar(r) }, ctx, conArchivos, conDivider = false)
        return
    }
    val pager = rememberPagerState { items.size }
    val scope = rememberCoroutineScope()
    val ids = items.map { it.first.id }
    var idsVistos by remember { mutableStateOf(ids) }
    LaunchedEffect(ids) {
        val nuevos = ids - idsVistos.toSet()
        idsVistos = ids
        nuevos.firstOrNull()?.let { id -> pager.animateScrollToPage(ids.indexOf(id)) }
        if (pager.currentPage > items.lastIndex) pager.animateScrollToPage(items.lastIndex)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Anterior")
        }
        Text("${pager.currentPage + 1} - ${items.size}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Salud360Colors.Indigo)
        IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < items.lastIndex) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Siguiente")
        }
    }
    HorizontalPager(pager, Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, pageSpacing = 12.dp) { i ->
        val (r, esActual) = items[i]
        RegistroFila(r, campos, esActual, soloLectura || !esActual, { onEditar(r) }, { onBorrar(r) }, ctx, conArchivos, conDivider = false)
    }
}

@Composable
private fun RegistroFila(
    r: RegistroClinico, campos: List<CampoDef>, esActual: Boolean, soloLectura: Boolean,
    onEditar: () -> Unit, onBorrar: () -> Unit, ctx: SeccionContext, conArchivos: Boolean, conDivider: Boolean = true,
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
        if (conDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    }
}

@Composable
fun RegistroDialog(
    titulo: String, campos: List<CampoDef>, conFecha: Boolean,
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
