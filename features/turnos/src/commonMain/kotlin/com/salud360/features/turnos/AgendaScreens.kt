package com.salud360.features.turnos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Today
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.salud360.core.data.repos.ResultadoTurno
import com.salud360.core.model.Id
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.turnos.Asistencia
import com.salud360.core.model.turnos.EstadoTurno
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.Turno
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.CancelButton
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.InitialsAvatar
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.PlainCard
import com.salud360.core.ui.components.ScreenTitle
import com.salud360.core.ui.components.SlotState
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.components.TextField
import com.salud360.core.ui.components.TimeSlotCircle
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun LocalTime.hhmm() = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
fun DayOfWeek.nombre(): String = when (this) {
    DayOfWeek.MONDAY -> "Lunes"; DayOfWeek.TUESDAY -> "Martes"; DayOfWeek.WEDNESDAY -> "Miércoles"; DayOfWeek.THURSDAY -> "Jueves"
    DayOfWeek.FRIDAY -> "Viernes"; DayOfWeek.SATURDAY -> "Sábado"; else -> "Domingo"
}
fun LocalDate.conDia(): String = "${dayOfWeek.nombre()} ${toDisplay()}"

fun ResultadoTurno.mensaje(): String = when (this) {
    is ResultadoTurno.Ok -> "Turno registrado"
    ResultadoTurno.HorarioOcupado -> "El horario ya fue reservado por otra persona"
    ResultadoTurno.PacienteYaTieneTurnoEseDia -> "El paciente ya tiene un turno ese día"
    ResultadoTurno.PacienteYaTieneTurnoEsteMes -> "El paciente ya tiene un turno este mes (solo un turno por mes)"
    ResultadoTurno.CupoPrimerControlAgotado -> "No quedan cupos de primer control para ese día"
    ResultadoTurno.Feriado -> "Ese día es feriado"
    ResultadoTurno.HorarioNoVisible -> "Ese horario no está disponible"
}

/** Agenda del día: listado de turnos con asistencia, caja y comentario (home del médico en turnosonlinebb). */
@Composable
fun AgendaDiaScreen(
    medicoId: Id, consultorioId: Id, operador: String,
    tituloMedico: String? = null,
    onAbrirPaciente: ((Id) -> Unit)? = null,
    onVerSemana: () -> Unit,
    onAsignar: () -> Unit,
    vm: AgendaViewModel = koinViewModel(key = "agenda-$medicoId-$consultorioId") { parametersOf(medicoId, consultorioId, operador) },
) {
    val state by vm.state.collectAsState()
    var sobreturno by remember { mutableStateOf(false) }
    var cancelar by remember { mutableStateOf<Turno?>(null) }
    var bloquearDia by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Agenda del día", tituloMedico)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = vm::diaAnterior) { Icon(Icons.Default.ChevronLeft, contentDescription = "Día anterior") }
            DateField("Fecha", state.fecha, { it?.let(vm::irA) }, Modifier.width(200.dp))
            IconButton(onClick = vm::diaSiguiente) { Icon(Icons.Default.ChevronRight, contentDescription = "Día siguiente") }
            IconButton(onClick = vm::hoyMismo) { Icon(Icons.Default.Today, contentDescription = "Hoy") }
            Text(state.fecha.dayOfWeek.nombre(), fontWeight = FontWeight.SemiBold)
            if (state.esFeriado) StatusChip("Feriado", Salud360Colors.Danger)
            Spacer(Modifier.weight(1f))
            PillButton("Asignar turno", onClick = onAsignar)
            AcceptButton("Sobreturno", onClick = { sobreturno = true })
            TextButton(onClick = onVerSemana) { Text("Ver semana") }
            IconButton(onClick = { bloquearDia = true }) { Icon(Icons.Default.Block, contentDescription = "Bloquear día", tint = Salud360Colors.Danger) }
        }
        aviso?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val activos = state.turnosDelDia.filter { it.estado != EstadoTurno.CANCELADO }
        if (activos.isEmpty()) EmptyState("No hay turnos para este día", icon = Icons.Default.EventBusy)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(activos, key = { it.id }) { t ->
                TurnoCard(t, conCaja = state.conCaja, onAsistencia = { vm.asistencia(t, it) }, onCaja = { vm.caja(t, it) }, onComentario = { vm.comentario(t, it) },
                    onCancelar = { cancelar = t }, onAbrirPaciente = t.pacienteId?.let { id -> onAbrirPaciente?.let { f -> { f(id) } } })
            }
        }
    }

    if (sobreturno) SobreturnoDialog(vm, state.fecha, onCerrar = { sobreturno = false }, onAviso = { aviso = it })
    cancelar?.let { t ->
        ConfirmDialog("Cancelar turno", "¿Cancelar el turno de ${t.pacienteNombre.ifBlank { "este horario" }} a las ${t.horario.hhmm()}?",
            onConfirm = { vm.cancelar(t); cancelar = null }, onDismiss = { cancelar = null }, confirmText = "Cancelar turno", destructive = true)
    }
    if (bloquearDia) ConfirmDialog("Bloquear día completo", "Se bloquean todos los horarios libres del ${state.fecha.toDisplay()}. Solo es posible si no hay pacientes con turno.",
        onConfirm = { bloquearDia = false; vm.bloquearDia(state.fecha) { ok -> aviso = if (ok) "Día bloqueado" else "No se puede bloquear: hay pacientes con turno ese día" } },
        onDismiss = { bloquearDia = false }, confirmText = "Bloquear", destructive = true)
}

@Composable
fun TurnoCard(t: Turno, conCaja: Boolean, onAsistencia: (Asistencia) -> Unit, onCaja: (Double) -> Unit, onComentario: (String) -> Unit, onCancelar: () -> Unit, onAbrirPaciente: (() -> Unit)?) {
    var caja by remember(t.id, t.caja) { mutableStateOf(if (t.caja == 0.0) "" else t.caja.toString()) }
    var comentario by remember(t.id, t.comentario) { mutableStateOf(t.comentario) }
    PlainCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t.horario.hhmm(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Salud360Colors.Indigo, modifier = Modifier.width(64.dp))
            if (t.estado == EstadoTurno.BLOQUEADO) {
                Text("Bloqueado", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                LinkButton("Liberar", onCancelar)
            } else {
                InitialsAvatar(t.pacienteNombre.ifBlank { "?" }, size = 36)
                Column(Modifier.weight(1f)) {
                    Text(t.pacienteNombre.ifBlank { "Sin paciente" }, fontWeight = FontWeight.SemiBold)
                    Text(listOf("DNI ${t.pacienteDni}", t.pacienteTelefono, t.pacienteObraSocial).filter { it.isNotBlank() && it != "DNI " }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (t.sobreturno) StatusChip("Sobreturno", Salud360Colors.Warning)
                if (t.primerControl) StatusChip("1er control", Salud360Colors.Info)
                if (t.pagado) StatusChip("Pagado", Salud360Colors.Success)
                AsistenciaSelector(t.asistencia, onAsistencia)
                if (onAbrirPaciente != null) LinkButton("Ficha", onAbrirPaciente)
                LinkButton("Cancelar", onCancelar)
            }
        }
        if (t.estado != EstadoTurno.BLOQUEADO && (conCaja || t.comentario.isNotBlank())) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (conCaja) TextField("Caja $", caja, { caja = it; it.replace(',', '.').toDoubleOrNull()?.let(onCaja) }, Modifier.width(140.dp), keyboardType = KeyboardType.Decimal)
            TextField("Comentario", comentario, { comentario = it; onComentario(it) }, Modifier.weight(1f))
        }
    }
}

@Composable
fun AsistenciaSelector(actual: Asistencia, onChange: (Asistencia) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val opciones = listOf(Asistencia.ASISTIO to "Asistió", Asistencia.NO_ASISTIO to "No asistió")
        opciones.forEach { (a, txt) ->
            val activo = actual == a
            val color = if (a == Asistencia.ASISTIO) Salud360Colors.Success else Salud360Colors.Danger
            androidx.compose.material3.FilterChip(selected = activo, onClick = { onChange(if (activo) Asistencia.SIN_MARCAR else a) }, label = { Text(txt) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = 0.2f), selectedLabelColor = color))
        }
    }
}

/** Agenda semanal: 5 días con atención, cada slot como círculo (verde libre / rojo ocupado / gris bloqueado). */
@Composable
fun AgendaSemanaScreen(
    medicoId: Id, consultorioId: Id, operador: String,
    modoBloqueo: Boolean = false,
    onVolver: () -> Unit,
    vm: AgendaViewModel = koinViewModel(key = "agenda-$medicoId-$consultorioId") { parametersOf(medicoId, consultorioId, operador) },
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { if (state.semana.isEmpty()) vm.cargarSemana() }
    var slotElegido by remember { mutableStateOf<Pair<LocalDate, SlotAgenda>?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle(if (modoBloqueo) "Bloquear turnos" else "Agenda semanal", "Próximos días con atención")
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::semanaAnterior) { Icon(Icons.Default.ChevronLeft, contentDescription = "Anterior") }
            IconButton(onClick = vm::semanaSiguiente) { Icon(Icons.Default.ChevronRight, contentDescription = "Siguiente") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onVolver) { Text("Volver al día") }
        }
        aviso?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.cargandoSemana) com.salud360.core.ui.components.LoadingIndicator()
        if (!state.cargandoSemana && state.semana.isEmpty()) EmptyState("No hay horarios cargados para los próximos días. Cargá horarios fijos desde Configuración.")
        state.semana.forEach { dia ->
            PlainCard {
                Text(dia.fecha.conDia(), style = MaterialTheme.typography.titleMedium, color = Salud360Colors.Indigo)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    dia.slots.forEach { s ->
                        val estado = when {
                            s.turno?.estado == EstadoTurno.BLOQUEADO -> SlotState.BLOQUEADO
                            !s.libre -> SlotState.OCUPADO
                            else -> SlotState.LIBRE
                        }
                        TimeSlotCircle(s.horario.hhmm(), estado, subtitle = s.turno?.pacienteNombre?.substringBefore(",")?.take(12), onClick = { slotElegido = dia.fecha to s })
                    }
                }
            }
        }
    }

    slotElegido?.let { (f, s) ->
        SlotDialog(vm, f, s, modoBloqueo, primerControlDoble = state.primerControlDoble, onCerrar = { slotElegido = null }, onAviso = { aviso = it })
    }
}

@Composable
private fun SlotDialog(vm: AgendaViewModel, f: LocalDate, s: SlotAgenda, modoBloqueo: Boolean, primerControlDoble: Boolean, onCerrar: () -> Unit, onAviso: (String) -> Unit) {
    val t = s.turno
    if (t != null) {
        ConfirmDialog(
            title = "${f.conDia()} ${s.horario.hhmm()}",
            message = if (t.estado == EstadoTurno.BLOQUEADO) "Horario bloqueado. ¿Querés liberarlo?" else "Turno de ${t.pacienteNombre} (DNI ${t.pacienteDni}). ¿Cancelar el turno?",
            onConfirm = { vm.liberar(t); onCerrar() }, onDismiss = onCerrar,
            confirmText = if (t.estado == EstadoTurno.BLOQUEADO) "Liberar" else "Cancelar turno", destructive = true,
        )
    } else if (modoBloqueo) {
        ConfirmDialog("Bloquear horario", "¿Bloquear el ${f.conDia()} a las ${s.horario.hhmm()}?", onConfirm = { vm.bloquear(f, s.horario); onCerrar() }, onDismiss = onCerrar, confirmText = "Bloquear")
    } else {
        AsignarTurnoDialog(vm, f, s.horario, segundoHorario = null, primerControlDoble = primerControlDoble && s.doble, onCerrar = onCerrar, onAviso = onAviso)
    }
}

/** Diálogo para asignar un turno: buscar paciente por DNI/apellido y confirmar. */
@Composable
fun AsignarTurnoDialog(vm: AgendaViewModel, f: LocalDate, horario: LocalTime, segundoHorario: LocalTime?, primerControlDoble: Boolean, onCerrar: () -> Unit, onAviso: (String) -> Unit) {
    var busqueda by remember { mutableStateOf("") }
    var resultados by remember { mutableStateOf<List<Paciente>>(emptyList()) }
    var elegido by remember { mutableStateOf<Paciente?>(null) }
    var primerControl by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(busqueda) { if (busqueda.length >= 2) resultados = vm.buscarPacientes(busqueda) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Asignar turno · ${f.conDia()} ${horario.hhmm()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.salud360.core.ui.components.SearchBar(busqueda, { busqueda = it; elegido = null })
                val e = elegido
                if (e != null) {
                    Text("Paciente: ${e.nombreCompleto} (DNI ${e.dni})", fontWeight = FontWeight.SemiBold)
                    com.salud360.core.ui.components.CheckboxField("Primer control", primerControl, { primerControl = it })
                    if (primerControl && primerControlDoble && segundoHorario != null) Text("Se reservarán dos horarios consecutivos", style = MaterialTheme.typography.bodyMedium)
                } else {
                    resultados.take(8).forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(p.nombreCompleto, fontWeight = FontWeight.SemiBold); Text("DNI ${p.dni} · ${p.telefono}", style = MaterialTheme.typography.bodyMedium) }
                            LinkButton("Elegir", onClick = { elegido = p })
                        }
                        HorizontalDivider()
                    }
                    if (busqueda.length >= 2 && resultados.isEmpty()) Text("No se encontró el paciente. Cargalo primero desde Pacientes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = elegido != null, onClick = {
                val p = elegido ?: return@TextButton
                scope.launch { vm.asignar(p, f, horario, primerControl, if (primerControl) segundoHorario else null) { r -> onAviso(r.mensaje()) } }
                onCerrar()
            }) { Text("Confirmar turno") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

/** Sobreturno: cualquier horario (no necesita estar en la plantilla). */
@Composable
private fun SobreturnoDialog(vm: AgendaViewModel, f: LocalDate, onCerrar: () -> Unit, onAviso: (String) -> Unit) {
    var hora by remember { mutableStateOf("") }
    var busqueda by remember { mutableStateOf("") }
    var resultados by remember { mutableStateOf<List<Paciente>>(emptyList()) }
    var elegido by remember { mutableStateOf<Paciente?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(busqueda) { if (busqueda.length >= 2) resultados = vm.buscarPacientes(busqueda) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Sobreturno · ${f.conDia()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField("Horario (HH:MM)", hora, { hora = it }, keyboardType = KeyboardType.Number, placeholder = "10:15")
                com.salud360.core.ui.components.SearchBar(busqueda, { busqueda = it; elegido = null })
                val e = elegido
                if (e != null) Text("Paciente: ${e.nombreCompleto}", fontWeight = FontWeight.SemiBold)
                else resultados.take(6).forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.nombreCompleto} · DNI ${p.dni}", Modifier.weight(1f)); LinkButton("Elegir", onClick = { elegido = p })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = elegido != null && runCatching { LocalTime.parse(hora.padStart(5, '0')) }.isSuccess, onClick = {
                val p = elegido ?: return@TextButton
                val h = runCatching { LocalTime.parse(hora.padStart(5, '0')) }.getOrNull() ?: return@TextButton
                scope.launch { vm.sobreturno(p, f, h) { r -> onAviso(r.mensaje()) } }
                onCerrar()
            }) { Text("Registrar sobreturno") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

/** Pantalla "Asignar turnos": elegir fecha, ver slots libres y asignar. */
@Composable
fun AsignarTurnoScreen(
    medicoId: Id, consultorioId: Id, operador: String,
    pacientePreseleccionado: Paciente? = null,
    onVolver: () -> Unit,
    vm: AgendaViewModel = koinViewModel(key = "agenda-$medicoId-$consultorioId") { parametersOf(medicoId, consultorioId, operador) },
) {
    val state by vm.state.collectAsState()
    var fecha by remember { mutableStateOf(state.fecha) }
    var slots by remember { mutableStateOf<List<SlotAgenda>>(emptyList()) }
    var sugeridas by remember { mutableStateOf<List<LocalDate>>(emptyList()) }
    var slot by remember { mutableStateOf<SlotAgenda?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(fecha) { slots = vm.slotsDelDia(fecha); if (sugeridas.isEmpty()) sugeridas = vm.proximasFechas() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Asignar turno", pacientePreseleccionado?.nombreCompleto)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField("Fecha", fecha, { it?.let { f -> fecha = f } }, Modifier.width(200.dp))
            Text(fecha.dayOfWeek.nombre())
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onVolver) { Text("Volver") }
        }
        if (sugeridas.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Próximas fechas con turnos:", style = MaterialTheme.typography.bodyMedium)
            sugeridas.forEach { d -> androidx.compose.material3.AssistChip(onClick = { fecha = d }, label = { Text(d.toDisplay()) }) }
        }
        aviso?.let { Text(it, color = if (it.startsWith("Turno registrado")) Salud360Colors.SuccessDark else MaterialTheme.colorScheme.error) }
        if (slots.isEmpty()) EmptyState("No hay horarios para esta fecha")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            slots.forEach { s ->
                val estado = when { s.turno?.estado == EstadoTurno.BLOQUEADO -> SlotState.BLOQUEADO; !s.libre -> SlotState.OCUPADO; else -> SlotState.LIBRE }
                TimeSlotCircle(s.horario.hhmm(), estado, subtitle = s.turno?.pacienteNombre?.substringBefore(",")?.take(12), onClick = if (s.libre) ({ slot = s }) else null)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    slot?.let { s ->
        val siguiente = slots.indexOf(s).let { i -> slots.getOrNull(i + 1)?.takeIf { it.libre }?.horario }
        if (pacientePreseleccionado != null) {
            ConfirmDialog("Confirmar turno", "${pacientePreseleccionado.nombreCompleto} · ${fecha.conDia()} ${s.horario.hhmm()}",
                onConfirm = { scope.launch { vm.asignar(pacientePreseleccionado, fecha, s.horario, false) { r -> aviso = r.mensaje(); scope.launch { slots = vm.slotsDelDia(fecha) } } }; slot = null },
                onDismiss = { slot = null }, confirmText = "Confirmar")
        } else {
            AsignarTurnoDialog(vm, fecha, s.horario, siguiente, primerControlDoble = state.primerControlDoble && s.doble, onCerrar = { slot = null },
                onAviso = { aviso = it; scope.launch { slots = vm.slotsDelDia(fecha) } })
        }
    }
}
