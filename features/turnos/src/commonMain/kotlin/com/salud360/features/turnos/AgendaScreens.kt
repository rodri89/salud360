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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Phone
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import com.salud360.core.ui.components.DatoCopiable
import com.salud360.core.ui.components.linkWhatsApp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.salud360.core.data.repos.ResultadoTurno
import com.salud360.core.model.Id
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.turnos.Asistencia
import com.salud360.core.model.turnos.EstadoTurno
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.Turno
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.BackButton
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.InitialsAvatar
import com.salud360.core.ui.components.LinkButton
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

/** Color del chip de tipo de turno en la tarjeta de la agenda. */
fun TipoTurno.color() = when (this) {
    TipoTurno.CONSULTA -> Salud360Colors.TipoConsulta
    TipoTurno.VIDEOLLAMADA -> Salud360Colors.TipoVideollamada
    TipoTurno.CONSULTA_ONLINE -> Salud360Colors.TipoConsultaOnline
    TipoTurno.ECOGRAFIA -> Salud360Colors.TipoEcografia
    TipoTurno.DEPORTOLOGIA -> Salud360Colors.TipoDeportologia
    TipoTurno.CONSULTA_ECO -> Salud360Colors.TipoConsultaEco
}

/** Texto editable de la caja: vacío si es 0, sin ".0" si es entero. */
private fun Double.aTextoCaja(): String = when {
    this == 0.0 -> ""
    this == kotlin.math.floor(this) -> toLong().toString()
    else -> toString()
}

/** Importe con separador de miles y sin decimales si es entero (`12.500` / `12.500,50`). Sin String.format: no existe en wasm. */
fun Double.aImporte(): String {
    val centavos = kotlin.math.round(kotlin.math.abs(this) * 100).toLong()
    val entero = centavos / 100
    val dec = centavos % 100
    val miles = entero.toString().reversed().chunked(3).joinToString(".").reversed()
    val signo = if (this < 0) "-" else ""
    return if (dec == 0L) "$signo$miles" else "$signo$miles,${dec.toString().padStart(2, '0')}"
}

fun ResultadoTurno.mensaje(): String = when (this) {
    is ResultadoTurno.Ok -> "Turno registrado"
    ResultadoTurno.HorarioOcupado -> "El horario ya fue reservado por otra persona"
    ResultadoTurno.PacienteYaTieneTurnoEseDia -> "El paciente ya tiene un turno ese día"
    ResultadoTurno.PacienteYaTieneTurnoEsteMes -> "El paciente ya tiene un turno este mes (solo un turno por mes)"
    ResultadoTurno.CupoPrimerControlAgotado -> "No quedan cupos de primer control para ese día"
    ResultadoTurno.Feriado -> "Ese día es feriado"
    ResultadoTurno.HorarioNoVisible -> "Ese horario no está disponible"
    is ResultadoTurno.Error -> mensaje
}

/** Fila de la agenda del día: un turno ya asignado (o bloqueado) o un horario libre para asignar. */
private sealed interface FilaAgendaDia {
    data class ConTurno(val turno: Turno) : FilaAgendaDia
    data class Libre(val horario: LocalTime) : FilaAgendaDia
}

/**
 * Agenda del día: un único listado —ordenado por horario— con los turnos ya asignados (con asistencia,
 * caja y comentario) y los horarios libres (con botón para agregar turno), para ver y asignar sin
 * cambiar de pantalla. Bloquear horarios o el día completo se hace desde "Ver semana".
 */
@Composable
fun AgendaDiaScreen(
    medicoId: Id, consultorioId: Id, operador: String,
    tituloMedico: String? = null,
    onAbrirPaciente: ((Id) -> Unit)? = null,
    onVerSemana: () -> Unit,
    /** Avatar del médico (foto o iniciales) que acompaña al título cuando la agenda es de otro médico (secretaria). */
    fotoMedico: (@Composable () -> Unit)? = null,
    /** Habilita "Modificar" en los turnos (mover a otro horario); solo secretaria. */
    puedeModificar: Boolean = false,
    vm: AgendaViewModel = koinViewModel(key = "agenda-$medicoId-$consultorioId") { parametersOf(medicoId, consultorioId, operador) },
) {
    val state by vm.state.collectAsState()
    var sobreturno by remember { mutableStateOf(false) }
    var cancelar by remember { mutableStateOf<Turno?>(null) }
    var slotLibre by remember { mutableStateOf<LocalTime?>(null) }
    var slotBloquear by remember { mutableStateOf<LocalTime?>(null) }
    var slotMover by remember { mutableStateOf<LocalTime?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var slots by remember { mutableStateOf<List<SlotAgenda>>(emptyList()) }
    LaunchedEffect(state.fecha, state.turnosDelDia) { slots = vm.slotsDelDia(state.fecha) }

    val turnosDia = state.turnosDelDia.filter { it.estado != EstadoTurno.CANCELADO }
    val horariosConTurno = turnosDia.map { it.horario }.toSet()
    val libres = slots.filter { it.libre && it.horario !in horariosConTurno }.map { it.horario }
    val filas: List<FilaAgendaDia> = (turnosDia.map { FilaAgendaDia.ConTurno(it) } + libres.map { FilaAgendaDia.Libre(it) })
        .sortedBy { when (it) { is FilaAgendaDia.ConTurno -> it.turno.horario; is FilaAgendaDia.Libre -> it.horario } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (fotoMedico != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            fotoMedico()
            ScreenTitle("Agenda del día", tituloMedico)
        } else ScreenTitle("Agenda del día", tituloMedico)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::diaAnterior) { Icon(Icons.Default.ChevronLeft, contentDescription = "Día anterior") }
            DateField("Fecha", state.fecha, { it?.let(vm::irA) }, Modifier.width(200.dp))
            IconButton(onClick = vm::diaSiguiente) { Icon(Icons.Default.ChevronRight, contentDescription = "Día siguiente") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(state.fecha.dayOfWeek.nombre(), fontWeight = FontWeight.SemiBold)
            if (state.esFeriado) { Spacer(Modifier.width(8.dp)); StatusChip("Feriado", Salud360Colors.Danger) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            AcceptButton("Sobreturno", onClick = { sobreturno = true })
            BackButton("Ver semana", onClick = onVerSemana)
        }
        if (state.conCaja) {
            val totalCaja = turnosDia.filter { it.estado != EstadoTurno.BLOQUEADO }.sumOf { it.caja }
            Text("Caja del día: $ ${totalCaja.aImporte()}", fontWeight = FontWeight.SemiBold, color = Salud360Colors.SuccessDark, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (state.proximasFechas.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Próximas fechas disponibles:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            state.proximasFechas.forEach { d -> androidx.compose.material3.AssistChip(onClick = { vm.irA(d) }, label = { Text(d.conDia()) }) }
        }
        state.turnoAModificar?.let { BannerModificacion(it, onCancelar = vm::cancelarModificacion) }
        aviso?.let { Text(it, color = if (it.startsWith("Turno registrado") || it.startsWith("Turno movido") || it == "Horario bloqueado") Salud360Colors.SuccessDark else MaterialTheme.colorScheme.error) }
        state.mensaje?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.cargando) com.salud360.core.ui.components.LoadingIndicator()
        if (filas.isEmpty()) EmptyState("Este día no atiende", icon = Icons.Default.EventBusy)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filas, key = { fila -> when (fila) { is FilaAgendaDia.ConTurno -> "t-${fila.turno.id}"; is FilaAgendaDia.Libre -> "l-${fila.horario}" } }) { fila ->
                val modificando = state.turnoAModificar
                when (fila) {
                    is FilaAgendaDia.ConTurno -> TurnoCard(fila.turno, conCaja = state.conCaja, onAsistencia = { vm.asistencia(fila.turno, it) }, onCaja = { vm.caja(fila.turno, it) }, onComentario = { vm.comentario(fila.turno, it) },
                        onCancelar = { cancelar = fila.turno }, onAbrirPaciente = fila.turno.pacienteId?.let { id -> onAbrirPaciente?.let { f -> { f(id) } } },
                        onModificar = if (puedeModificar && fila.turno.pacienteId != null && modificando?.id != fila.turno.id) ({ vm.iniciarModificacion(fila.turno) }) else null,
                        paciente = fila.turno.pacienteId?.let { state.pacientesDelDia[it] })
                    is FilaAgendaDia.Libre ->
                        if (modificando != null) SlotCardLibre(fila.horario.hhmm(), onAsignar = { slotMover = fila.horario }, etiqueta = "Mover acá")
                        else SlotCardLibre(fila.horario.hhmm(), onAsignar = { slotLibre = fila.horario }, onBloquear = { slotBloquear = fila.horario })
                }
            }
        }
    }

    slotMover?.let { h ->
        state.turnoAModificar?.let { t -> ConfirmarMoverDialog(vm, t, state.fecha, h, onCerrar = { slotMover = null }, onAviso = { aviso = it }) } ?: run { slotMover = null }
    }
    if (sobreturno) SobreturnoDialog(vm, state.fecha, onCerrar = { sobreturno = false }, onAviso = { aviso = it })
    slotBloquear?.let { h ->
        ConfirmDialog("Bloquear horario", "¿Bloquear el ${state.fecha.conDia()} a las ${h.hhmm()}? Nadie va a poder reservarlo hasta que lo liberes.",
            onConfirm = { vm.bloquear(state.fecha, h) { r -> aviso = r.mensajeBloqueo() }; slotBloquear = null }, onDismiss = { slotBloquear = null }, confirmText = "Bloquear", destructive = true)
    }
    cancelar?.let { t ->
        ConfirmDialog("Cancelar turno", "¿Cancelar el turno de ${t.pacienteNombre.ifBlank { "este horario" }} a las ${t.horario.hhmm()}?",
            onConfirm = { vm.cancelar(t); cancelar = null }, onDismiss = { cancelar = null }, confirmText = "Cancelar turno", destructive = true)
    }
    slotLibre?.let { h ->
        val ordenados = slots.sortedBy { it.horario }
        val idx = ordenados.indexOfFirst { it.horario == h }
        val siguiente = ordenados.getOrNull(idx + 1)?.takeIf { it.libre && it.horario !in horariosConTurno }?.horario
        val doble = ordenados.getOrNull(idx)?.doble ?: false
        AsignarTurnoDialog(vm, state.fecha, h, siguiente, primerControlDoble = state.primerControlDoble && doble, onCerrar = { slotLibre = null }, onAviso = { aviso = it })
    }
}

@Composable
fun TurnoCard(
    t: Turno, conCaja: Boolean, onAsistencia: (Asistencia) -> Unit, onCaja: (Double) -> Unit, onComentario: (String) -> Unit, onCancelar: () -> Unit, onAbrirPaciente: (() -> Unit)?,
    /** "Modificar" (mover el turno a otro horario); solo lo tiene la secretaria. */
    onModificar: (() -> Unit)? = null,
    /** Ficha cacheada del paciente, para número de afiliado y plan (el turno solo trae el nombre de la obra social). */
    paciente: Paciente? = null,
) {
    val uriHandler = LocalUriHandler.current
    // Caja y comentario se editan localmente y se guardan al salir del campo (no tecla a tecla): así se puede borrar
    // sin que el valor guardado vuelva a pisar lo escrito, y en turnosonlinebb se hace un solo pedido por edición.
    var caja by remember(t.id) { mutableStateOf(t.caja.aTextoCaja()) }
    var comentario by remember(t.id) { mutableStateOf(t.comentario) }
    var cajaEnfocada by remember(t.id) { mutableStateOf(false) }
    var comentarioEnfocado by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(t.caja) { if (!cajaEnfocada) caja = t.caja.aTextoCaja() }
    LaunchedEffect(t.comentario) { if (!comentarioEnfocado) comentario = t.comentario }
    fun guardarCaja() {
        val valor = if (caja.isBlank()) 0.0 else caja.replace(',', '.').toDoubleOrNull() ?: return
        if (valor != t.caja) onCaja(valor)
    }
    fun guardarComentario() { if (comentario != t.comentario) onComentario(comentario) }
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
                    // Datos de contacto y cobertura: DNI y nº de afiliado se copian al tocarlos; el teléfono abre WhatsApp.
                    val telefono = t.pacienteTelefono.ifBlank { paciente?.telefono ?: "" }
                    val obraSocial = t.pacienteObraSocial.ifBlank { paciente?.obraSocial ?: "" }
                    val afiliado = paciente?.numeroAfiliado?.trim().orEmpty()
                    val plan = paciente?.obraSocialPlan?.trim().orEmpty()
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (t.pacienteDni.isNotBlank()) DatoCopiable("DNI ${t.pacienteDni}", valor = t.pacienteDni)
                        if (telefono.isNotBlank()) {
                            Row(
                                Modifier.clip(RoundedCornerShape(6.dp)).clickable { uriHandler.openUri(linkWhatsApp(telefono)) }.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(Icons.Default.Phone, contentDescription = "WhatsApp", modifier = Modifier.size(14.dp), tint = Salud360Colors.SuccessDark)
                                Text(telefono, style = MaterialTheme.typography.bodyMedium, color = Salud360Colors.SuccessDark)
                            }
                        }
                        if (obraSocial.isNotBlank()) {
                            val textoOs = listOfNotNull(obraSocial, afiliado.ifBlank { null }?.let { "Nº $it" }, plan.ifBlank { null }?.let { "Plan $it" }).joinToString(" · ")
                            if (afiliado.isNotBlank()) DatoCopiable(textoOs, valor = afiliado)
                            else Text(textoOs, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }
                // Arriba a la derecha: tipo de turno (color por tipo) y, si corresponde, sobreturno y primer control.
                Column(Modifier.align(Alignment.Top), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusChip(t.tipoTurno.etiqueta, t.tipoTurno.color(), pequeno = true)
                    if (t.sobreturno) StatusChip("Sobreturno", Salud360Colors.Warning, pequeno = true)
                    if (t.primerControl) StatusChip("1er control", Salud360Colors.Info, pequeno = true)
                }
            }
        }
        if (t.estado != EstadoTurno.BLOQUEADO) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (t.pagado) StatusChip("Pagado", Salud360Colors.Success)
            AsistenciaSelector(t.asistencia, onAsistencia)
            if (onAbrirPaciente != null) LinkButton("Ficha", onAbrirPaciente)
            if (onModificar != null) LinkButton("Modificar", onModificar)
            LinkButton("Cancelar", onCancelar)
        }
        if (t.estado != EstadoTurno.BLOQUEADO && (conCaja || t.comentario.isNotBlank())) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (conCaja) TextField(
                "Caja $", caja, { caja = it },
                Modifier.width(140.dp).onFocusChanged { f -> if (cajaEnfocada && !f.isFocused) guardarCaja(); cajaEnfocada = f.isFocused },
                keyboardType = KeyboardType.Decimal,
            )
            TextField(
                "Comentario", comentario, { comentario = it },
                Modifier.weight(1f).onFocusChanged { f -> if (comentarioEnfocado && !f.isFocused) guardarComentario(); comentarioEnfocado = f.isFocused },
            )
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

/**
 * Agenda semanal: 5 días con atención, cada slot como círculo (verde libre / rojo ocupado / gris bloqueado).
 * Desde acá también se bloquea: el ícono de cada día bloquea el día completo, y tocar un horario libre
 * deja elegir entre asignar un turno o bloquear ese horario en particular.
 */
@Composable
fun AgendaSemanaScreen(
    medicoId: Id, consultorioId: Id, operador: String,
    onVolver: () -> Unit,
    /** Habilita "Modificar" en los turnos ocupados (mover a otro horario); solo secretaria. */
    puedeModificar: Boolean = false,
    vm: AgendaViewModel = koinViewModel(key = "agenda-$medicoId-$consultorioId") { parametersOf(medicoId, consultorioId, operador) },
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { if (state.semana.isEmpty()) vm.cargarSemana() }
    var slotElegido by remember { mutableStateOf<Pair<LocalDate, SlotAgenda>?>(null) }
    var bloquearDia by remember { mutableStateOf<LocalDate?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Agenda semanal", "Próximos días con atención")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::semanaAnterior) { Icon(Icons.Default.ChevronLeft, contentDescription = "Semana anterior") }
            DateField("Desde", state.semanaDesde ?: state.semana.firstOrNull()?.fecha, { it?.let { d -> vm.cargarSemana(d) } }, Modifier.width(200.dp))
            IconButton(onClick = vm::semanaSiguiente) { Icon(Icons.Default.ChevronRight, contentDescription = "Semana siguiente") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { TextButton(onClick = onVolver) { Text("Volver al día") } }
        state.turnoAModificar?.let { BannerModificacion(it, onCancelar = vm::cancelarModificacion) }
        aviso?.let { Text(it, color = if (it.startsWith("Turno registrado") || it.startsWith("Turno movido")) Salud360Colors.SuccessDark else MaterialTheme.colorScheme.error) }
        state.mensaje?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.cargandoSemana) com.salud360.core.ui.components.LoadingIndicator()
        if (!state.cargandoSemana && state.semana.isEmpty()) EmptyState("No hay horarios cargados para los próximos días. Cargá horarios fijos desde Configuración.")
        state.semana.forEach { dia ->
            PlainCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dia.fecha.conDia(), style = MaterialTheme.typography.titleMedium, color = Salud360Colors.Indigo, modifier = Modifier.weight(1f))
                    IconButton(onClick = { bloquearDia = dia.fecha }) { Icon(Icons.Default.Block, contentDescription = "Bloquear día", tint = Salud360Colors.Danger) }
                }
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
        SlotDialog(vm, f, s, primerControlDoble = state.primerControlDoble, modificando = state.turnoAModificar, puedeModificar = puedeModificar, onCerrar = { slotElegido = null }, onAviso = { aviso = it })
    }
    bloquearDia?.let { f ->
        ConfirmDialog("Bloquear día completo", "Se bloquean todos los horarios libres del ${f.toDisplay()}. Solo es posible si no hay pacientes con turno.",
            onConfirm = { vm.bloquearDia(f) { error -> aviso = error ?: "Día bloqueado" }; bloquearDia = null },
            onDismiss = { bloquearDia = null }, confirmText = "Bloquear", destructive = true)
    }
}

/** Horario libre: primero deja elegir entre asignar un turno o bloquearlo; ocupado/bloqueado va directo a cancelar/liberar. */
@Composable
private fun SlotDialog(
    vm: AgendaViewModel, f: LocalDate, s: SlotAgenda, primerControlDoble: Boolean,
    modificando: Turno? = null, puedeModificar: Boolean = false,
    onCerrar: () -> Unit, onAviso: (String) -> Unit,
) {
    val t = s.turno
    if (t != null) {
        val puedeMover = puedeModificar && t.estado != EstadoTurno.BLOQUEADO && t.pacienteId != null && modificando?.id != t.id
        if (puedeMover) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onCerrar,
                title = { Text("${f.conDia()} ${s.horario.hhmm()}") },
                text = { Text("Turno de ${t.pacienteNombre} (DNI ${t.pacienteDni}). Podés moverlo a otro horario o cancelarlo.") },
                confirmButton = { TextButton(onClick = { vm.iniciarModificacion(t); onCerrar() }) { Text("Modificar") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = onCerrar) { Text("Cerrar") }
                        TextButton(onClick = { vm.liberar(t); onCerrar() }) { Text("Cancelar turno", color = Salud360Colors.Danger) }
                    }
                },
            )
        } else ConfirmDialog(
            title = "${f.conDia()} ${s.horario.hhmm()}",
            message = if (t.estado == EstadoTurno.BLOQUEADO) "Horario bloqueado. ¿Querés liberarlo?" else "Turno de ${t.pacienteNombre} (DNI ${t.pacienteDni}). ¿Cancelar el turno?",
            onConfirm = { vm.liberar(t); onCerrar() }, onDismiss = onCerrar,
            confirmText = if (t.estado == EstadoTurno.BLOQUEADO) "Liberar" else "Cancelar turno", destructive = true,
        )
        return
    }
    if (modificando != null) {
        ConfirmarMoverDialog(vm, modificando, f, s.horario, onCerrar = onCerrar, onAviso = onAviso)
        return
    }
    var accion by remember { mutableStateOf<String?>(null) }
    when (accion) {
        "bloquear" -> ConfirmDialog("Bloquear horario", "¿Bloquear el ${f.conDia()} a las ${s.horario.hhmm()}?",
            onConfirm = { vm.bloquear(f, s.horario) { r -> onAviso(r.mensajeBloqueo()) }; onCerrar() }, onDismiss = onCerrar, confirmText = "Bloquear")
        "asignar" -> AsignarTurnoDialog(vm, f, s.horario, segundoHorario = null, primerControlDoble = primerControlDoble && s.doble, onCerrar = onCerrar, onAviso = onAviso)
        else -> androidx.compose.material3.AlertDialog(
            onDismissRequest = onCerrar,
            title = { Text("${f.conDia()} ${s.horario.hhmm()}") },
            text = { Text("Horario libre. ¿Qué querés hacer?") },
            confirmButton = { TextButton(onClick = { accion = "asignar" }) { Text("Asignar turno") } },
            dismissButton = { TextButton(onClick = { accion = "bloquear" }) { Text("Bloquear horario") } },
        )
    }
}

/** Aviso de que hay un turno en modificación: qué turno es y cómo elegir el nuevo horario, con opción de desistir. */
@Composable
private fun BannerModificacion(t: Turno, onCancelar: () -> Unit) {
    PlainCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Modificando el turno de ${t.pacienteNombre}", fontWeight = FontWeight.SemiBold, color = Salud360Colors.Indigo)
                Text("Actual: ${t.fecha.conDia()} ${t.horario.hhmm()}. Tocá el horario libre al que querés moverlo (en este día o en Ver semana).", style = MaterialTheme.typography.bodyMedium)
            }
            LinkButton("Cancelar", onCancelar)
        }
    }
}

/** Confirma mover el turno en modificación a `f`/`horario`: se registra el nuevo y se cancela el anterior. */
@Composable
private fun ConfirmarMoverDialog(vm: AgendaViewModel, t: Turno, f: LocalDate, horario: LocalTime, onCerrar: () -> Unit, onAviso: (String) -> Unit) {
    ConfirmDialog(
        "Mover turno",
        "¿Mover el turno de ${t.pacienteNombre} del ${t.fecha.conDia()} ${t.horario.hhmm()} al ${f.conDia()} ${horario.hhmm()}? El turno anterior queda cancelado.",
        onConfirm = {
            vm.moverTurno(f, horario) { r -> onAviso(if (r is ResultadoTurno.Ok) "Turno movido al ${f.conDia()} ${horario.hhmm()}" else "No se pudo mover: ${r.mensaje()}. El turno anterior se mantiene.") }
            onCerrar()
        },
        onDismiss = onCerrar, confirmText = "Mover turno",
    )
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

/** Card de un horario libre: hora + botón para agregar el turno. */
@Composable
fun SlotCardLibre(horario: String, onAsignar: () -> Unit, onBloquear: (() -> Unit)? = null, etiqueta: String = "Agregar turno") {
    PlainCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(horario, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Salud360Colors.Indigo, modifier = Modifier.weight(1f))
            if (onBloquear != null) LinkButton("Bloquear", onBloquear)
            AcceptButton(etiqueta, onClick = onAsignar)
        }
    }
}

/** Texto para el resultado de bloquear un horario (el bloqueo se registra como turno del paciente "bloqueo"). */
fun ResultadoTurno.mensajeBloqueo(): String = when (this) {
    is ResultadoTurno.Ok -> "Horario bloqueado"
    // La API rechazaba el segundo bloqueo del día con "mismo_dia" hasta la corrección en turnosonlinebb.
    ResultadoTurno.PacienteYaTieneTurnoEseDia -> "La web de turnos no permitió otro bloqueo ese día (hay que actualizar turnosonlinebb)"
    else -> mensaje()
}

/** Card de un horario ocupado o bloqueado: hora + quién lo tiene, sin acción. */
@Composable
fun SlotCardOcupado(horario: String, bloqueado: Boolean, pacienteNombre: String?) {
    PlainCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(horario, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
            Text(if (bloqueado) "Bloqueado" else pacienteNombre?.ifBlank { null } ?: "Ocupado", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
    }
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
        if (slots.isEmpty()) EmptyState("Este día no atiende")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            slots.sortedBy { it.horario }.forEach { s ->
                if (s.libre) SlotCardLibre(s.horario.hhmm(), onAsignar = { slot = s })
                else SlotCardOcupado(s.horario.hhmm(), bloqueado = s.turno?.estado == EstadoTurno.BLOQUEADO, pacienteNombre = s.turno?.pacienteNombre)
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
