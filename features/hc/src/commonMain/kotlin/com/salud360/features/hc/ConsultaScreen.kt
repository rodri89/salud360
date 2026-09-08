package com.salud360.features.hc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.BackButton
import com.salud360.core.ui.components.BrandPanel
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.InitialsAvatar
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.LoadingIndicator
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.secciones.AntecedentesSeccion
import com.salud360.features.hc.secciones.ArchivosSeccion
import com.salud360.features.hc.secciones.AudioSeccion
import com.salud360.features.hc.secciones.DatosPacienteSeccion
import com.salud360.features.hc.secciones.DiagnosticosSeccion
import com.salud360.features.hc.secciones.ExamenFisicoSeccion
import com.salud360.features.hc.secciones.FormSeccion
import com.salud360.features.hc.secciones.LaboratorioSeccion
import com.salud360.features.hc.secciones.RegistrosSeccion
import com.salud360.features.hc.secciones.TextoSeccion
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Pantalla de una consulta: cabecera del paciente, pestañas superiores (datos, antecedentes) y
 * secciones en acordeón, renderizadas a partir de la definición de la especialidad.
 * Cuando la consulta está cerrada se muestra en modo lectura.
 */
@Composable
fun ConsultaScreen(
    consultaId: Id,
    medicoId: Id,
    onCerrada: () -> Unit,
    onVolver: () -> Unit,
    vm: ConsultaViewModel = koinViewModel(key = "consulta-$consultaId") { parametersOf(consultaId, medicoId) },
) {
    val ui by vm.ui.collectAsState()
    val valores by vm.valores.collectAsState()
    val guardadoEn by vm.guardadoEn.collectAsState()
    val registry = koinInject<EspecialidadRegistry>()
    val consulta = ui.consulta
    val def = ui.definicion
    val tipo = ui.tipo
    if (consulta == null || def == null || tipo == null || ui.paciente == null) { LoadingIndicator(); return }

    var pestania by remember { mutableIntStateOf(0) }
    var confirmarCierre by remember { mutableStateOf(false) }
    var mostrarPendiente by remember { mutableStateOf(false) }
    val soloLectura = ui.soloLectura
    val color = Salud360Colors.especialidad(consulta.especialidad)

    val ctx = remember(consulta.id, soloLectura, ui.edadMeses, ui.paciente?.sexo) {
        object : SeccionContext {
            override val consultaId = consulta.id
            override val pacienteId = consulta.pacienteId
            override val especialidad = consulta.especialidad
            override val soloLectura = soloLectura
            override val edadMeses = ui.edadMeses
            override val sexo = ui.paciente?.sexo?.name
            override val vm = vm
            override fun valor(seccion: String, campo: String) = vm.valor(seccion, campo)
            override fun setValor(seccion: String, campo: String, valor: String) = vm.setValor(seccion, campo, valor)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- cabecera ----
        BrandPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialsAvatar(ui.paciente!!.nombreCompleto, size = 48, color = Color.White.copy(alpha = 0.25f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.paciente!!.nombreCompleto, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text("${def.nombre} · ${tipo.nombre} · ${consulta.edadMostrar}".trimEnd(' ', '·'), color = Color.White.copy(alpha = 0.9f))
                }
                if (soloLectura) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    StatusChip("Cerrada", Color.White)
                } else {
                    StatusChip(guardadoEn?.let { "Guardado" } ?: "En edición", Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("Fecha de consulta", consulta.fecha, { it?.let(vm::cambiarFecha) }, Modifier.width(220.dp), readOnly = soloLectura)
                if (ui.pendientes.isNotEmpty()) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Salud360Colors.Warning)
                    Text("${ui.pendientes.size} pendiente(s)", color = Color.White)
                }
            }
        }

        ui.pendientes.takeIf { it.isNotEmpty() }?.let { lista ->
            SectionCard("Pendientes de la consulta anterior", icon = Icons.Default.NotificationsActive, initiallyExpanded = true) {
                lista.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• ${p.texto}", modifier = Modifier.weight(1f))
                        if (!soloLectura) LinkButton("Resuelto", onClick = { vm.resolverPendiente(p) })
                    }
                }
            }
        }

        // ---- pestañas superiores ----
        val pestanias = tipo.pestanias.mapNotNull { id -> if (id == SeccionesComunes.DATOS_PACIENTE) SeccionDef(id, "Datos", TipoSeccion.CUSTOM) else def.seccion(id) }
        if (pestanias.isNotEmpty()) {
            ScrollableTabRow(selectedTabIndex = pestania.coerceIn(0, pestanias.lastIndex), edgePadding = 0.dp, containerColor = Color.Transparent, contentColor = color) {
                pestanias.forEachIndexed { i, s -> Tab(selected = pestania == i, onClick = { pestania = i }, text = { Text(s.titulo, fontWeight = FontWeight.SemiBold) }) }
            }
            val actual = pestanias[pestania.coerceIn(0, pestanias.lastIndex)]
            if (actual.id == SeccionesComunes.DATOS_PACIENTE) DatosPacienteSeccion(ui.paciente!!, def, ctx) else RenderSeccion(actual, ctx, registry, valores)
        }

        // ---- secciones ----
        vm.seccionesVisibles(ui).filter { it.id !in tipo.pestanias }.forEach { s -> RenderSeccion(s, ctx, registry, valores) }

        // ---- acciones ----
        ActionRow {
            BackButton(onClick = onVolver)
            if (!soloLectura) {
                TextButton(onClick = { mostrarPendiente = true }) { Text("Agregar pendiente") }
                PillButton("Guardar y cerrar consulta", onClick = { confirmarCierre = true })
            } else {
                AcceptButton("Reabrir para editar", onClick = { vm.reabrir() })
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (confirmarCierre) ConfirmDialog(
        "Cerrar consulta", "Al cerrar la consulta queda registrada en la historia clínica. Podés reabrirla después si necesitás corregir algo.",
        onConfirm = { confirmarCierre = false; vm.cerrar(onCerrada) }, onDismiss = { confirmarCierre = false }, confirmText = "Cerrar consulta",
    )
    if (mostrarPendiente) PendienteDialog(onGuardar = { vm.agregarPendiente(it); mostrarPendiente = false }, onCancelar = { mostrarPendiente = false })
}

@Composable
private fun PendienteDialog(onGuardar: (String) -> Unit, onCancelar: () -> Unit) {
    var texto by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text("Pendiente para la próxima consulta") },
        text = { TextAreaField("Esta información se recordará al abrir la próxima consulta", texto, { texto = it }, minLines = 4) },
        confirmButton = { TextButton(onClick = { if (texto.isNotBlank()) onGuardar(texto) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}

/** Despacha cada sección al renderizador que corresponde a su tipo. */
@Composable
fun RenderSeccion(s: SeccionDef, ctx: SeccionContext, registry: EspecialidadRegistry, valores: Map<String, Map<String, String>>) {
    val ui by ctx.vm.ui.collectAsState()
    when (s.tipo) {
        TipoSeccion.FORM -> FormSeccion(s, ctx, valores[s.id] ?: emptyMap())
        TipoSeccion.TEXTO -> TextoSeccion(s, ctx, valores[s.id] ?: emptyMap())
        TipoSeccion.EXAMEN_FISICO -> ExamenFisicoSeccion(s, ctx, ui, valores[s.id] ?: emptyMap())
        TipoSeccion.LABORATORIO -> LaboratorioSeccion(s, ctx, ui)
        TipoSeccion.REGISTROS -> RegistrosSeccion(s, ctx, ui)
        TipoSeccion.ANTECEDENTES -> AntecedentesSeccion(s, ctx, ui)
        TipoSeccion.ARCHIVOS -> ArchivosSeccion(s, ctx)
        TipoSeccion.AUDIO -> AudioSeccion(s, ctx)
        TipoSeccion.DIAGNOSTICOS -> DiagnosticosSeccion(s, ctx, ui)
        TipoSeccion.FORM_PACIENTE -> com.salud360.features.hc.secciones.FormPacienteSeccion(s, ctx)
        TipoSeccion.CUSTOM -> {
            val r = s.renderKey?.let { registry.renderer(it) }
            if (r != null) r(s, ctx) else SectionCard(s.titulo) { Text("Sección no disponible: ${s.renderKey}", color = MaterialTheme.colorScheme.error) }
        }
    }
}
