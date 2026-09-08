package com.salud360.features.turnos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.salud360.core.model.Id
import com.salud360.core.model.turnos.EstadoReceta
import com.salud360.core.model.turnos.HorarioMedico
import com.salud360.core.model.turnos.ModuloTurnos
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.BackButton
import com.salud360.core.ui.components.CheckboxField
import com.salud360.core.ui.components.DataTable
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.NumberField
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.PlainCard
import com.salud360.core.ui.components.ScreenTitle
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.SelectField
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.components.TableColumn
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.components.TextField
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private val HORAS_CANDIDATAS: List<String> = (6..22).flatMap { h -> listOf(0, 15, 30, 45).map { m -> LocalTime(h, m).hhmm() } }
private val DIAS = DayOfWeek.entries

/** Horarios fijos: grilla Lunes a Domingo con alta de slot, generación por rango, vigencia y fechas agregadas. */
@Composable
fun HorariosScreen(
    medicoId: Id, consultorioId: Id, onVolver: () -> Unit,
    vm: HorariosViewModel = koinViewModel(key = "horarios-$medicoId") { parametersOf(medicoId, consultorioId) },
) {
    val horarios by vm.horarios.collectAsState()
    val fechas by vm.fechasAgregadas.collectAsState()
    var dia by remember { mutableStateOf(DayOfWeek.MONDAY) }
    var hora by remember { mutableStateOf("08:00") }
    var desde by remember { mutableStateOf("08:00") }
    var hasta by remember { mutableStateOf("12:00") }
    var intervalo by remember { mutableStateOf("30") }
    var tipo by remember { mutableStateOf(TipoTurno.CONSULTA) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var fechaNueva by remember { mutableStateOf<LocalDate?>(null) }
    var horasFecha by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Horarios de atención", "Plantilla semanal del consultorio")
        SectionCard("Agregar horarios", collapsible = false) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectField("Día", dia.nombre(), DIAS.map { it.nombre() }, { v -> DIAS.firstOrNull { it.nombre() == v }?.let { dia = it } }, Modifier.width(170.dp), allowEmpty = false)
                SelectField("Tipo", tipo.etiqueta, TipoTurno.entries.map { it.etiqueta }, { v -> TipoTurno.entries.firstOrNull { it.etiqueta == v }?.let { tipo = it } }, Modifier.width(190.dp), allowEmpty = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectField("Horario", hora, HORAS_CANDIDATAS, { it?.let { h -> hora = h } }, Modifier.width(140.dp), allowEmpty = false)
                AcceptButton("Agregar", onClick = { vm.agregar(dia, LocalTime.parse(hora), tipo, false) { ok -> aviso = if (ok) "Horario agregado" else "Ese horario ya existe" } })
                Spacer(Modifier.width(24.dp))
                SelectField("Desde", desde, HORAS_CANDIDATAS, { it?.let { h -> desde = h } }, Modifier.width(130.dp), allowEmpty = false)
                SelectField("Hasta", hasta, HORAS_CANDIDATAS, { it?.let { h -> hasta = h } }, Modifier.width(130.dp), allowEmpty = false)
                SelectField("Cada (min)", intervalo, listOf("10", "15", "20", "30", "40", "45", "60"), { it?.let { v -> intervalo = v } }, Modifier.width(130.dp), allowEmpty = false)
                PillButton("Generar rango", onClick = { vm.generar(dia, LocalTime.parse(desde), LocalTime.parse(hasta), intervalo.toInt(), tipo) { n -> aviso = "Se crearon $n horarios" } })
            }
            aviso?.let { Text(it, color = Salud360Colors.SuccessDark) }
        }

        DIAS.forEach { d ->
            val delDia = horarios.filter { it.dia == d && it.consultorioId == consultorioId }.sortedBy { it.horario }
            SectionCard("${d.nombre()} (${delDia.size})", initiallyExpanded = delDia.isNotEmpty()) {
                if (delDia.isEmpty()) Text("Sin horarios", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    delDia.forEach { h -> HorarioChip(h, onEliminar = { vm.eliminar(h) }, onDoble = { vm.doble(h, it) }, onVigencia = { a, b -> vm.vigencia(h, a, b) }) }
                }
            }
        }

        SectionCard("Fechas con horarios especiales", initiallyExpanded = false) {
            Text("Para un día puntual (por ejemplo un sábado extra) podés definir horarios que reemplazan a los de la plantilla.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                DateField("Fecha", fechaNueva, { fechaNueva = it }, Modifier.width(200.dp))
                TextField("Horarios (HH:MM separados por coma)", horasFecha, { horasFecha = it }, Modifier.weight(1f), placeholder = "09:00, 09:30, 10:00")
                AcceptButton("Agregar fecha", enabled = fechaNueva != null && horasFecha.isNotBlank(), onClick = {
                    val hs = horasFecha.split(',').mapNotNull { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
                    fechaNueva?.let { f -> vm.agregarFecha(f, hs); horasFecha = ""; fechaNueva = null }
                })
            }
            fechas.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.fecha.conDia(), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(220.dp))
                    Text(f.horarios.joinToString(", ") { it.hhmm() }, modifier = Modifier.weight(1f))
                    LinkButton("Quitar", onClick = { vm.eliminarFecha(f) })
                }
            }
        }
        BackButton(onClick = onVolver)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HorarioChip(h: HorarioMedico, onEliminar: () -> Unit, onDoble: (Boolean) -> Unit, onVigencia: (LocalDate?, LocalDate?) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    androidx.compose.material3.InputChip(
        selected = abierto, onClick = { abierto = !abierto },
        label = { Text(h.horario.hhmm() + (if (h.tipoTurno != TipoTurno.CONSULTA) " ${h.tipoTurno.etiqueta}" else "") + (if (h.doble) " ×2" else "") + (if (h.validoHasta != null || h.validoDesde != null) " *" else "")) },
    )
    if (abierto) androidx.compose.material3.AlertDialog(
        onDismissRequest = { abierto = false },
        title = { Text("${h.dia.nombre()} ${h.horario.hhmm()}") },
        text = {
            var desde by remember { mutableStateOf(h.validoDesde) }
            var hasta by remember { mutableStateOf(h.validoHasta) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckboxField("Permite primer control doble", h.doble, onDoble)
                DateField("Válido desde", desde, { desde = it; onVigencia(it, hasta) })
                DateField("Válido hasta", hasta, { hasta = it; onVigencia(desde, it) })
            }
        },
        confirmButton = { TextButton(onClick = { abierto = false }) { Text("Listo") } },
        dismissButton = { TextButton(onClick = { onEliminar(); abierto = false }) { Text("Eliminar horario", color = Salud360Colors.Danger) } },
    )
}

/** Configuración de la agenda del médico. `esAdmin` habilita editar los módulos. */
@Composable
fun ConfigAgendaScreen(
    medicoId: Id, esAdmin: Boolean, onVolver: () -> Unit,
    vm: ConfigAgendaViewModel = koinViewModel(key = "config-$medicoId") { parametersOf(medicoId) },
) {
    val ui by vm.ui.collectAsState()
    val u = ui ?: return
    var ventana by remember(u.config.ventanaDias) { mutableStateOf(u.config.ventanaDias.toString()) }
    var valor by remember(u.config.valorConsulta) { mutableStateOf(if (u.config.valorConsulta == 0.0) "" else u.config.valorConsulta.toString()) }
    var titulo by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Configuración de la agenda")
        SectionCard("Reservas", collapsible = false) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("Ventana de días para reservar", ventana, { ventana = it }, Modifier.width(240.dp), suffix = "días")
                NumberField("Valor de la consulta", valor, { valor = it }, Modifier.width(220.dp), suffix = "$")
                AcceptButton("Guardar", onClick = { vm.guardarConfig(u.config.copy(ventanaDias = ventana.toIntOrNull() ?: 180, valorConsulta = valor.replace(',', '.').toDoubleOrNull() ?: 0.0)) })
            }
        }
        SectionCard("Cupo de primeros controles por día") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DIAS.forEach { d ->
                    var cupo by remember(u.config.cupoPrimerControl[d]) { mutableStateOf(u.config.cupoPrimerControl[d]?.toString() ?: "") }
                    NumberField(d.nombre(), cupo, { v -> cupo = v; vm.guardarConfig(u.config.copy(cupoPrimerControl = u.config.cupoPrimerControl + (d to (v.toIntOrNull() ?: 0)))) }, Modifier.width(120.dp))
                }
            }
        }
        SectionCard("Módulos habilitados", initiallyExpanded = esAdmin) {
            if (!esAdmin) Text("Solo el administrador puede cambiar los módulos.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ModuloTurnos.entries.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = m in u.modulos, onCheckedChange = { vm.setModulo(m, it) }, enabled = esAdmin)
                    Spacer(Modifier.width(10.dp))
                    Text("${m.codigo}. ${m.descripcion}")
                }
            }
        }
        SectionCard("Mensajes especiales para pacientes", initiallyExpanded = u.mensajes.isNotEmpty()) {
            u.mensajes.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.titulo, fontWeight = FontWeight.SemiBold); Text(m.descripcion, style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(m.validoDesde?.let { "desde ${it.toDisplay()}" }, m.validoHasta?.let { "hasta ${it.toDisplay()}" }).joinToString(" "), style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = m.activo, onCheckedChange = { vm.guardarMensaje(m.copy(activo = it)) })
                }
                HorizontalDivider()
            }
            TextField("Título", titulo, { titulo = it })
            TextAreaField("Mensaje", descripcion, { descripcion = it }, minLines = 2)
            ActionRow { AcceptButton("Agregar mensaje", enabled = titulo.isNotBlank(), onClick = { vm.nuevoMensaje(titulo, descripcion, null, null); titulo = ""; descripcion = "" }) }
        }
        BackButton(onClick = onVolver)
        Spacer(Modifier.height(24.dp))
    }
}

/** Obras sociales: catálogo global + activación e importes por médico. */
@Composable
fun ObrasSocialesScreen(
    medicoId: Id?, onVolver: () -> Unit,
    vm: ObrasSocialesViewModel = koinViewModel(key = "os-$medicoId") { parametersOf(medicoId) },
) {
    val filas by vm.filas.collectAsState()
    var nueva by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Obras sociales", if (medicoId != null) "Activá las que atendés y definí importes" else "Catálogo")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField("Nueva obra social", nueva, { nueva = it }, Modifier.width(320.dp))
            AcceptButton("Agregar", enabled = nueva.isNotBlank(), onClick = { vm.altaObraSocial(nueva); nueva = "" })
            if (medicoId != null) { Spacer(Modifier.weight(1f)); LinkButton("Activar todas", onClick = { vm.activarTodas(true) }); LinkButton("Desactivar todas", onClick = { vm.activarTodas(false) }) }
        }
        if (filas.isEmpty()) EmptyState("Todavía no hay obras sociales cargadas")
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filas.size, key = { filas[it].obraSocial.id }) { i ->
                val f = filas[i]
                PlainCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(f.obraSocial.nombre, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (medicoId != null) {
                            var importe by remember(f.vinculo?.importe) { mutableStateOf(f.vinculo?.importe?.takeIf { it != 0.0 }?.toString() ?: "") }
                            var reserva by remember(f.vinculo?.importeReserva) { mutableStateOf(f.vinculo?.importeReserva?.takeIf { it != 0.0 }?.toString() ?: "") }
                            NumberField("Diferencial $", importe, { importe = it; vm.setImportes(f, it.replace(',', '.').toDoubleOrNull() ?: 0.0, reserva.replace(',', '.').toDoubleOrNull() ?: 0.0) }, Modifier.width(150.dp))
                            NumberField("Reserva online $", reserva, { reserva = it; vm.setImportes(f, importe.replace(',', '.').toDoubleOrNull() ?: 0.0, it.replace(',', '.').toDoubleOrNull() ?: 0.0) }, Modifier.width(170.dp))
                            Switch(checked = f.vinculo?.activo == true, onCheckedChange = { vm.setActiva(f, it) })
                        }
                    }
                }
            }
        }
        BackButton(onClick = onVolver)
    }
}

/** Recetas solicitadas por los pacientes, con cambio de estado. */
@Composable
fun RecetasScreen(
    medicoIds: List<Id>, onVolver: () -> Unit,
    vm: RecetasViewModel = koinViewModel(key = "recetas-${medicoIds.joinToString()}") { parametersOf(medicoIds) },
) {
    val recetas by vm.recetas.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Recetas", "${recetas.count { it.estado == EstadoReceta.SOLICITADA }} pendientes")
        if (recetas.isEmpty()) EmptyState("No hay recetas pendientes")
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(recetas.size, key = { recetas[it].id }) { i ->
                val r = recetas[i]
                PlainCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.pacienteNombre} · DNI ${r.pacienteDni}", fontWeight = FontWeight.SemiBold)
                            Text(r.motivo, style = MaterialTheme.typography.bodyMedium)
                            if (r.retiraConsultorio) Text("Retira por consultorio", style = MaterialTheme.typography.labelMedium, color = Salud360Colors.Indigo)
                        }
                        StatusChip(r.estado.etiqueta, when (r.estado) { EstadoReceta.SOLICITADA -> Salud360Colors.Warning; EstadoReceta.RECHAZADA, EstadoReceta.CANCELADA -> Salud360Colors.Danger; else -> Salud360Colors.Success })
                        SelectField("Cambiar estado", null, EstadoReceta.entries.map { it.etiqueta }, { v -> EstadoReceta.entries.firstOrNull { it.etiqueta == v }?.let { vm.cambiarEstado(r, it) } }, Modifier.width(190.dp))
                    }
                }
            }
        }
        BackButton(onClick = onVolver)
    }
}

/** Secretaria: elegir consultorio y médico antes de operar la agenda. */
@Composable
fun SelectorMedicoScreen(
    consultorioIds: List<Id>, medicoIds: List<Id>,
    onElegido: (medicoId: Id, consultorioId: Id, nombreMedico: String) -> Unit,
    vm: SelectorMedicoViewModel = koinViewModel(key = "selector-${medicoIds.size}") { parametersOf(consultorioIds, medicoIds) },
) {
    val consultorios by vm.consultorios.collectAsState()
    val medicos by vm.medicos.collectAsState()
    var consultorio by remember { mutableStateOf<Id?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Elegí el médico", "Consultorio y profesional para gestionar la agenda")
        if (consultorios.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            consultorios.forEach { c -> androidx.compose.material3.FilterChip(selected = consultorio == c.id, onClick = { consultorio = c.id }, label = { Text(c.nombre) }) }
        } else consultorio = consultorios.firstOrNull()?.id
        val lista = medicos.filter { consultorio == null || it.consultorioId == consultorio || it.consultorioId == null }
        if (lista.isEmpty()) EmptyState("No hay médicos asignados a este consultorio")
        DataTable(
            columns = listOf(TableColumn("Médico", 240.dp), TableColumn("Especialidades", 220.dp), TableColumn("Agenda", 100.dp)),
            rows = lista,
            cells = { m -> listOf(m.nombreCompleto, m.especialidadesHc.joinToString(", "), if (m.tieneTurnos) "Sí" else "No") },
            trailing = { m -> LinkButton("Elegir", onClick = { onElegido(m.id, m.consultorioId ?: consultorio ?: "", m.nombreCompleto) }) },
        )
    }
}
