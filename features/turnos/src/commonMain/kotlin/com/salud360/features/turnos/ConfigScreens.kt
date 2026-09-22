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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.salud360.core.model.Id
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.turnos.EstadoReceta
import com.salud360.core.model.turnos.HorarioMedico
import com.salud360.core.model.turnos.MensajeEspecial
import com.salud360.core.model.turnos.ModuloTurnos
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.BackButton
import com.salud360.core.ui.components.CheckboxField
import com.salud360.core.ui.components.DataTable
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.InitialsAvatar
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.NavCard
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
private val HORAS_PILL: List<Int> = (6..22).toList()
private val MINUTOS_PILL: List<Int> = listOf(0, 10, 15, 20, 25, 30)
private val DIAS = DayOfWeek.entries

/** Horarios fijos: grilla Lunes a Domingo con alta de slot, generación por rango, vigencia y fechas agregadas. */
@Composable
fun HorariosScreen(
    medicoId: Id, consultorioId: Id, onVolver: () -> Unit,
    vm: HorariosViewModel = koinViewModel(key = "horarios-$medicoId") { parametersOf(medicoId, consultorioId) },
) {
    val horarios by vm.horarios.collectAsState()
    val fechas by vm.fechasAgregadas.collectAsState()
    val estado by vm.estado.collectAsState()
    var dia by remember { mutableStateOf(DayOfWeek.MONDAY) }
    var tipo by remember { mutableStateOf(TipoTurno.CONSULTA) }
    var desde by remember { mutableStateOf("08:00") }
    var hasta by remember { mutableStateOf("12:00") }
    var intervalo by remember { mutableStateOf("30") }
    var horaSel by remember { mutableStateOf(8) }
    var minutoSel by remember { mutableStateOf(0) }
    var minutoOtro by remember { mutableStateOf(false) }
    var minutoTexto by remember { mutableStateOf("") }
    var quincenal by remember { mutableStateOf(false) }
    var validoDesde by remember { mutableStateOf<LocalDate?>(null) }
    var validoHasta by remember { mutableStateOf<LocalDate?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var avisoError by remember { mutableStateOf(false) }
    var fechaNueva by remember { mutableStateOf<LocalDate?>(null) }
    var horasFecha by remember { mutableStateOf("") }

    val minuto: Int? = if (minutoOtro) minutoTexto.toIntOrNull()?.takeIf { it in 0..59 } else minutoSel
    val horaElegida: LocalTime? = minuto?.let { LocalTime(horaSel, it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Horarios de atención", "Plantilla semanal del consultorio")
        if (estado.cargando) Text("Trayendo los horarios de turnosonlinebb…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        estado.error?.let { Text(it, color = Salud360Colors.Danger) }

        SectionCard("Agregar horarios", collapsible = false) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectField("Día", dia.nombre(), DIAS.map { it.nombre() }, { v -> DIAS.firstOrNull { it.nombre() == v }?.let { dia = it } }, Modifier.width(170.dp), allowEmpty = false)
                SelectField("Tipo", tipo.etiqueta, TipoTurno.entries.map { it.etiqueta }, { v -> TipoTurno.entries.firstOrNull { it.etiqueta == v }?.let { tipo = it } }, Modifier.width(190.dp), allowEmpty = false)
            }
            Text("Vigencia", style = MaterialTheme.typography.titleSmall)
            Text("Si no cargás \"Válido desde\" el horario rige a partir de hoy; si no cargás \"Válido hasta\" rige para siempre.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Válido desde", validoDesde, { validoDesde = it }, Modifier.width(200.dp))
                DateField("Válido hasta", validoHasta, { validoHasta = it }, Modifier.width(200.dp))
            }
            CheckboxField("Mostrar este horario cada 15 días (semana por medio)", quincenal, { quincenal = it })
            HorizontalDivider()
            Text("Horario", style = MaterialTheme.typography.titleSmall)
            Text("Podés generar un rango de horarios (por ejemplo, de 08:00 a 12:00 cada 30 minutos) o agregar un solo horario.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Generar rango", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField("Desde", desde, HORAS_CANDIDATAS, { it?.let { h -> desde = h } }, Modifier.width(130.dp), allowEmpty = false)
                SelectField("Hasta", hasta, HORAS_CANDIDATAS, { it?.let { h -> hasta = h } }, Modifier.width(130.dp), allowEmpty = false)
                SelectField("Cada (min)", intervalo, listOf("10", "15", "20", "30", "40", "45", "60"), { it?.let { v -> intervalo = v } }, Modifier.width(130.dp), allowEmpty = false)
                PillButton("Generar rango", onClick = {
                    vm.generar(dia, LocalTime.parse(desde), LocalTime.parse(hasta), intervalo.toInt(), tipo, quincenal, validoDesde, validoHasta) { n -> aviso = "Se crearon $n horarios"; avisoError = n == 0 }
                })
            }
            HorizontalDivider()
            Text("Un solo horario", style = MaterialTheme.typography.labelLarge)
            Text("Hora", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HORAS_PILL) { h -> FilterChip(selected = horaSel == h, onClick = { horaSel = h }, label = { Text(h.toString().padStart(2, '0')) }) }
            }
            Text("Minutos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MINUTOS_PILL) { m -> FilterChip(selected = !minutoOtro && minutoSel == m, onClick = { minutoOtro = false; minutoSel = m }, label = { Text(m.toString().padStart(2, '0')) }) }
                item { FilterChip(selected = minutoOtro, onClick = { minutoOtro = true }, label = { Text("Otro") }) }
            }
            if (minutoOtro) NumberField("Minutos (0 a 59)", minutoTexto, { minutoTexto = it.filter { c -> c.isDigit() }.take(2) }, Modifier.width(160.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AcceptButton("Agregar", enabled = horaElegida != null, onClick = {
                    horaElegida?.let { h ->
                        vm.agregar(dia, h, tipo, false, quincenal, validoDesde, validoHasta) { motivo -> aviso = motivo ?: "Horario agregado: ${dia.nombre()} ${h.hhmm()}"; avisoError = motivo != null }
                    }
                })
                Text(
                    horaElegida?.let {
                        "${dia.nombre()} ${it.hhmm()}" + (if (quincenal) " · cada 15 días" else "") +
                            " · desde ${validoDesde?.toDisplay() ?: "hoy"}" + (validoHasta?.let { h -> " hasta ${h.toDisplay()}" } ?: " sin fecha de fin")
                    } ?: "Ingresá los minutos (0 a 59)",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            aviso?.let { Text(it, color = if (avisoError) Salud360Colors.Danger else Salud360Colors.SuccessDark) }
        }

        DIAS.forEach { d ->
            val delDia = horarios.filter { it.dia == d && it.consultorioId == consultorioId }.sortedBy { it.horario }
            SectionCard("${d.nombre()} (${delDia.size})", initiallyExpanded = delDia.isNotEmpty()) {
                if (delDia.isEmpty()) Text("Sin horarios", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    delDia.forEach { h ->
                        HorarioChip(h, onEliminar = { vm.eliminar(h) }, onDoble = { vm.doble(h, it) }, onQuincenal = { vm.quincenal(h, it) }, onVigencia = { a, b -> vm.vigencia(h, a, b) })
                    }
                }
            }
        }

        SectionCard("Fechas con horarios especiales", initiallyExpanded = false) {
            Text("Para un día puntual (por ejemplo un sábado extra) podés definir horarios que reemplazan a los de la plantilla.", style = MaterialTheme.typography.bodyMedium)
            DateField("Fecha", fechaNueva, { fechaNueva = it }, Modifier.width(200.dp))
            TextField("Horarios (HH:MM separados por coma)", horasFecha, { horasFecha = it }, Modifier.fillMaxWidth(), placeholder = "09:00, 09:30, 10:00")
            ActionRow {
                AcceptButton("Agregar fecha", enabled = fechaNueva != null && horasFecha.isNotBlank(), onClick = {
                    val hs = horasFecha.split(',').mapNotNull { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
                    fechaNueva?.let { f -> vm.agregarFecha(f, hs); horasFecha = ""; fechaNueva = null }
                })
            }
            if (fechas.isNotEmpty()) HorizontalDivider()
            fechas.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(f.fecha.conDia(), fontWeight = FontWeight.SemiBold)
                        Text(f.horarios.joinToString(", ") { it.hhmm() }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinkButton("Quitar", onClick = { vm.eliminarFecha(f) })
                }
            }
        }
        BackButton(onClick = onVolver)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HorarioChip(h: HorarioMedico, onEliminar: () -> Unit, onDoble: (Boolean) -> Unit, onQuincenal: (Boolean) -> Unit, onVigencia: (LocalDate?, LocalDate?) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    androidx.compose.material3.InputChip(
        selected = abierto, onClick = { abierto = !abierto },
        label = {
            Text(
                h.horario.hhmm() + (if (h.tipoTurno != TipoTurno.CONSULTA) " ${h.tipoTurno.etiqueta}" else "") + (if (h.doble) " ×2" else "") +
                    (if (h.quincenal) " c/15d" else "") + (if (h.validoHasta != null || h.validoDesde != null) " *" else ""),
            )
        },
    )
    if (abierto) androidx.compose.material3.AlertDialog(
        onDismissRequest = { abierto = false },
        title = { Text("${h.dia.nombre()} ${h.horario.hhmm()}") },
        text = {
            var desde by remember { mutableStateOf(h.validoDesde) }
            var hasta by remember { mutableStateOf(h.validoHasta) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckboxField("Permite primer control doble", h.doble, onDoble)
                CheckboxField("Cada 15 días (semana por medio)", h.quincenal, onQuincenal)
                DateField("Válido desde", desde, { desde = it; onVigencia(it, hasta) })
                DateField("Válido hasta", hasta, { hasta = it; onVigencia(desde, it) })
            }
        },
        confirmButton = { TextButton(onClick = { abierto = false }) { Text("Listo") } },
        dismissButton = { TextButton(onClick = { onEliminar(); abierto = false }) { Text("Eliminar horario", color = Salud360Colors.Danger) } },
    )
}

/** Secciones del menú de configuración de la agenda. `ruta` es el segmento que usa la navegación. */
enum class SeccionConfig(val ruta: String, val titulo: String, val subtitulo: String) {
    HORARIOS("horarios", "Horarios de atención", "Plantilla semanal y fechas especiales"),
    OBRAS_SOCIALES("obras-sociales", "Obras sociales", "Activá las que atendés y definí importes"),
    RESERVAS("reservas", "Reservas", "Ventana de días para reservar y valor de la consulta"),
    CUPO("cupo", "Cupo de primeros controles", "Cuántos primeros controles por día"),
    MODULOS("modulos", "Módulos habilitados", "Funciones de la agenda activas para este médico"),
    MENSAJES("mensajes", "Mensajes para pacientes", "Avisos que ven los pacientes al reservar"),
    /** Pantalla propia en `features/hc` (la navegación la resuelve la app). */
    HISTORIA_CLINICA("historia-clinica", "Secciones de la historia clínica", "Elegí qué secciones ves en cada consulta");

    companion object {
        fun porRuta(ruta: String?): SeccionConfig? = entries.firstOrNull { it.ruta == ruta }
    }
}

private fun SeccionConfig.icono() = when (this) {
    SeccionConfig.HORARIOS -> Icons.Default.Schedule
    SeccionConfig.OBRAS_SOCIALES -> Icons.Default.HealthAndSafety
    SeccionConfig.RESERVAS -> Icons.Default.EventAvailable
    SeccionConfig.CUPO -> Icons.Default.PersonAdd
    SeccionConfig.MODULOS -> Icons.Default.Tune
    SeccionConfig.MENSAJES -> Icons.Default.Campaign
    SeccionConfig.HISTORIA_CLINICA -> Icons.Default.Checklist
}

/**
 * Menú de configuración de la agenda del médico: una tarjeta por sección que navega con `onAbrir`.
 * `secciones` permite ocultar las que no aplican (por ejemplo horarios si no hay consultorio).
 */
@Composable
fun ConfigAgendaScreen(
    medicoId: Id, onVolver: () -> Unit, onAbrir: (SeccionConfig) -> Unit,
    secciones: List<SeccionConfig> = SeccionConfig.entries,
    vm: ConfigAgendaViewModel = koinViewModel(key = "config-$medicoId") { parametersOf(medicoId) },
) {
    val ui by vm.ui.collectAsState()
    val estado by vm.estado.collectAsState()
    val u = ui
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Configuración")
        if (estado.cargando) Text("Actualizando desde turnosonlinebb…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        estado.error?.let { Text(it, color = Salud360Colors.Danger) }
        secciones.forEach { s ->
            val detalle = when (s) {
                SeccionConfig.RESERVAS -> u?.let { "${it.config.ventanaDias} días" + (if (it.config.valorConsulta > 0) " · $${it.config.valorConsulta}" else "") }
                SeccionConfig.CUPO -> u?.let { c -> c.config.cupoPrimerControl.values.sum().takeIf { it > 0 }?.let { "$it por semana" } }
                SeccionConfig.MODULOS -> u?.let { "${it.modulos.size} activos" }
                SeccionConfig.MENSAJES -> u?.let { m -> m.mensajes.count { it.activo }.takeIf { it > 0 }?.let { "$it activos" } }
                else -> null
            }
            NavCard(s.titulo, onClick = { onAbrir(s) }, icon = s.icono(), subtitle = detalle?.let { "${s.subtitulo} · $it" } ?: s.subtitulo)
        }
        BackButton(onClick = onVolver)
        Spacer(Modifier.height(24.dp))
    }
}

/** Una sección de configuración (reservas, cupo, módulos o mensajes). Horarios y obras sociales tienen su propia pantalla. */
@Composable
fun ConfigSeccionScreen(
    medicoId: Id, seccion: SeccionConfig, esAdmin: Boolean, onVolver: () -> Unit,
    vm: ConfigAgendaViewModel = koinViewModel(key = "config-$medicoId") { parametersOf(medicoId) },
) {
    val ui by vm.ui.collectAsState()
    val estado by vm.estado.collectAsState()
    val u = ui ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle(seccion.titulo, seccion.subtitulo)
        if (estado.cargando) Text("Actualizando desde turnosonlinebb…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        estado.error?.let { Text(it, color = Salud360Colors.Danger) }
        when (seccion) {
            SeccionConfig.RESERVAS -> ReservasSeccion(u, vm)
            SeccionConfig.CUPO -> CupoSeccion(u, vm)
            SeccionConfig.MODULOS -> ModulosSeccion(u, vm, esAdmin)
            SeccionConfig.MENSAJES -> MensajesSeccion(u, vm)
            SeccionConfig.HORARIOS, SeccionConfig.OBRAS_SOCIALES, SeccionConfig.HISTORIA_CLINICA -> Text("Esta sección tiene su propia pantalla.")
        }
        BackButton(onClick = onVolver)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReservasSeccion(u: ConfigUi, vm: ConfigAgendaViewModel) {
    var ventana by remember(u.config.ventanaDias) { mutableStateOf(u.config.ventanaDias.toString()) }
    var valor by remember(u.config.valorConsulta) { mutableStateOf(if (u.config.valorConsulta == 0.0) "" else u.config.valorConsulta.toString()) }
    var guardado by remember { mutableStateOf(false) }
    SectionCard("Reservas", collapsible = false) {
        NumberField("Ventana de días para reservar", ventana, { ventana = it; guardado = false }, Modifier.width(240.dp), suffix = "días")
        Text("Hasta cuántos días hacia adelante puede reservar un paciente.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NumberField("Valor de la consulta", valor, { valor = it; guardado = false }, Modifier.width(240.dp), suffix = "$")
        ActionRow {
            if (guardado) Text("Guardado", color = Salud360Colors.SuccessDark)
            AcceptButton("Guardar", onClick = {
                vm.guardarConfig(u.config.copy(ventanaDias = ventana.toIntOrNull() ?: 180, valorConsulta = valor.replace(',', '.').toDoubleOrNull() ?: 0.0)) { guardado = it == null }
            })
        }
    }
}

@Composable
private fun CupoSeccion(u: ConfigUi, vm: ConfigAgendaViewModel) {
    val cupos = remember(u.config.cupoPrimerControl) { mutableStateMapOf<DayOfWeek, String>().apply { DIAS.forEach { d -> put(d, u.config.cupoPrimerControl[d]?.toString() ?: "") } } }
    var guardado by remember { mutableStateOf(false) }
    SectionCard("Cupo de primeros controles por día", collapsible = false) {
        Text("Cantidad máxima de primeros controles que se pueden reservar cada día de la semana. Vacío o 0 significa sin cupo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DIAS.forEach { d -> NumberField(d.nombre(), cupos[d] ?: "", { cupos[d] = it; guardado = false }, Modifier.width(120.dp)) }
        }
        ActionRow {
            if (guardado) Text("Guardado", color = Salud360Colors.SuccessDark)
            AcceptButton("Guardar", onClick = {
                vm.guardarConfig(u.config.copy(cupoPrimerControl = DIAS.associateWith { d -> cupos[d]?.toIntOrNull() ?: 0 })) { guardado = it == null }
            })
        }
    }
}

@Composable
private fun ModulosSeccion(u: ConfigUi, vm: ConfigAgendaViewModel, esAdmin: Boolean) {
    SectionCard("Módulos habilitados", collapsible = false) {
        if (!esAdmin) Text("Solo el administrador puede cambiar los módulos.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModuloTurnos.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = m in u.modulos, onCheckedChange = { vm.setModulo(m, it) }, enabled = esAdmin)
                Spacer(Modifier.width(10.dp))
                Text("${m.codigo}. ${m.descripcion}", modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Mensajes para pacientes: lista con activar/desactivar y edición en diálogo, más alta de uno nuevo. Para médicos de turnosonlinebb se guardan en la web. */
@Composable
private fun MensajesSeccion(u: ConfigUi, vm: ConfigAgendaViewModel) {
    var titulo by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }
    var desde by remember { mutableStateOf<LocalDate?>(null) }
    var hasta by remember { mutableStateOf<LocalDate?>(null) }
    var editando by remember { mutableStateOf<MensajeEspecial?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    SectionCard("Mensajes cargados", collapsible = false) {
        if (u.mensajes.isEmpty()) Text("Todavía no hay mensajes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        u.mensajes.forEach { m ->
            Text(m.titulo, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Text(m.descripcion, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            Text(
                listOfNotNull(m.validoDesde?.let { "desde ${it.toDisplay()}" }, m.validoHasta?.let { "hasta ${it.toDisplay()}" }).joinToString(" ").ifBlank { "sin fecha de vigencia" },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = m.activo, onCheckedChange = { vm.guardarMensaje(m.copy(activo = it)) })
                Text(
                    if (m.activo) "Activo: los pacientes lo ven al reservar" else "Inactivo: no se muestra",
                    style = MaterialTheme.typography.bodyMedium, color = if (m.activo) Salud360Colors.SuccessDark else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                LinkButton("Editar", onClick = { editando = m })
            }
            HorizontalDivider()
        }
    }
    // Solo se puede cargar un mensaje nuevo cuando no hay ninguno; si ya existe, se edita.
    if (u.mensajes.isEmpty()) SectionCard("Nuevo mensaje", collapsible = false) {
        TextField("Título", titulo, { titulo = it }, Modifier.fillMaxWidth())
        TextAreaField("Mensaje", descripcion, { descripcion = it }, Modifier.fillMaxWidth(), minLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField("Válido desde", desde, { desde = it }, Modifier.width(200.dp))
            DateField("Válido hasta", hasta, { hasta = it }, Modifier.width(200.dp))
        }
        Text("Sin fechas el mensaje se muestra siempre que esté activo.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        aviso?.let { Text(it, color = Salud360Colors.Danger) }
        ActionRow {
            AcceptButton("Agregar mensaje", enabled = titulo.isNotBlank() && descripcion.isNotBlank(), onClick = {
                vm.nuevoMensaje(titulo, descripcion, desde, hasta) { error ->
                    aviso = error
                    if (error == null) { titulo = ""; descripcion = ""; desde = null; hasta = null }
                }
            })
        }
    }

    editando?.let { m ->
        MensajeDialog(
            m, onCerrar = { editando = null },
            onGuardar = { editado, listo -> vm.guardarMensaje(editado) { listo(it) } },
            onEliminar = { listo -> vm.eliminarMensaje(m) { listo(it) } },
        )
    }
}

/** Diálogo de edición de un mensaje: título, texto, vigencia y activo, con Guardar y Eliminar. */
@Composable
private fun MensajeDialog(
    m: MensajeEspecial, onCerrar: () -> Unit,
    onGuardar: (MensajeEspecial, (String?) -> Unit) -> Unit,
    onEliminar: ((String?) -> Unit) -> Unit,
) {
    var titulo by remember(m.id) { mutableStateOf(m.titulo) }
    var descripcion by remember(m.id) { mutableStateOf(m.descripcion) }
    var desde by remember(m.id) { mutableStateOf(m.validoDesde) }
    var hasta by remember(m.id) { mutableStateOf(m.validoHasta) }
    var activo by remember(m.id) { mutableStateOf(m.activo) }
    var error by remember(m.id) { mutableStateOf<String?>(null) }
    var confirmarBorrado by remember(m.id) { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Editar mensaje") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckboxField("Activo (se muestra a los pacientes)", activo, { activo = it })
                HorizontalDivider()
                TextField("Título", titulo, { titulo = it }, Modifier.fillMaxWidth())
                TextAreaField("Mensaje", descripcion, { descripcion = it }, Modifier.fillMaxWidth(), minLines = 3)
                DateField("Válido desde", desde, { desde = it })
                DateField("Válido hasta", hasta, { hasta = it })
                error?.let { Text(it, color = Salud360Colors.Danger) }
                if (confirmarBorrado) Text("¿Eliminar este mensaje? No se puede deshacer.", color = Salud360Colors.Danger, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            TextButton(
                enabled = titulo.isNotBlank() && descripcion.isNotBlank() && (desde == null || hasta == null || hasta!! >= desde!!),
                onClick = { onGuardar(m.copy(titulo = titulo.trim(), descripcion = descripcion.trim(), validoDesde = desde, validoHasta = hasta, activo = activo)) { e -> if (e == null) onCerrar() else error = e } },
            ) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCerrar) { Text("Cancelar") }
                TextButton(onClick = { if (!confirmarBorrado) confirmarBorrado = true else onEliminar { e -> if (e == null) onCerrar() else error = e } }) {
                    Text(if (confirmarBorrado) "Sí, eliminar" else "Eliminar", color = Salud360Colors.Danger)
                }
            }
        },
    )
}

/** Obras sociales: catálogo global + activación e importes por médico. */
@Composable
fun ObrasSocialesScreen(
    medicoId: Id?, onVolver: () -> Unit,
    vm: ObrasSocialesViewModel = koinViewModel(key = "os-$medicoId") { parametersOf(medicoId) },
) {
    val filas by vm.filas.collectAsState()
    val estado by vm.estado.collectAsState()
    var busqueda by remember { mutableStateOf("") }
    val visibles = remember(filas, busqueda) {
        val q = busqueda.trim()
        if (q.isEmpty()) filas else filas.filter { it.obraSocial.nombre.contains(q, ignoreCase = true) }
    }
    val activas = filas.count { it.vinculo?.activo == true }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Obras sociales", if (medicoId != null) "Activá las que atendés y definí importes · $activas de ${filas.size} activas" else "Catálogo")
        if (estado.cargando) Text("Trayendo las obras sociales de turnosonlinebb…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        estado.error?.let { Text(it, color = Salud360Colors.Danger) }
        TextField("Buscar obra social", busqueda, { busqueda = it }, Modifier.fillMaxWidth(), placeholder = "Escribí para filtrar")
        if (medicoId != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Activar todas", onClick = { vm.activarTodas(true) })
            PillButton("Desactivar todas", onClick = { vm.activarTodas(false) })
        }
        if (filas.isEmpty() && !estado.cargando) EmptyState("Todavía no hay obras sociales cargadas")
        else if (visibles.isEmpty()) EmptyState("Ninguna obra social coincide con \"${busqueda.trim()}\"")
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f, fill = false)) {
            items(visibles.size, key = { visibles[it].obraSocial.id }) { i ->
                val f = visibles[i]
                PlainCard {
                    Text(f.obraSocial.nombre, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    if (medicoId != null) {
                        var importe by remember(f.vinculo?.importe) { mutableStateOf(f.vinculo?.importe?.takeIf { it != 0.0 }?.toString() ?: "") }
                        var reserva by remember(f.vinculo?.importeReserva) { mutableStateOf(f.vinculo?.importeReserva?.takeIf { it != 0.0 }?.toString() ?: "") }
                        val importeNum = importe.replace(',', '.').toDoubleOrNull() ?: 0.0
                        val reservaNum = reserva.replace(',', '.').toDoubleOrNull() ?: 0.0
                        val cambiado = importeNum != (f.vinculo?.importe ?: 0.0) || reservaNum != (f.vinculo?.importeReserva ?: 0.0)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.width(170.dp)) {
                                NumberField("Diferencial $", importe, { importe = it }, Modifier.fillMaxWidth())
                                Text("Importe que ve el paciente al reservar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.width(190.dp)) {
                                NumberField("Reserva online $", reserva, { reserva = it }, Modifier.fillMaxWidth())
                                Text("Seña por Mercado Pago (solo con pagos activos)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val activa = f.vinculo?.activo == true
                            Switch(checked = activa, onCheckedChange = { vm.setActiva(f, it) })
                            Text(if (activa) "Activa: se atiende esta obra social" else "Inactiva: no se atiende", style = MaterialTheme.typography.bodyMedium, color = if (activa) Salud360Colors.SuccessDark else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (cambiado) LinkButton("Guardar importes", onClick = { vm.setImportes(f, importeNum, reservaNum) })
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
    /** Médico elegido y consultorio para la agenda (vacío si el médico no tiene agenda). */
    onElegido: (medico: Medico, consultorioId: Id) -> Unit,
    /** Avatar del médico (foto o iniciales); lo aporta la app porque la carga de fotos vive fuera de este módulo. */
    foto: @Composable (Medico) -> Unit = { m -> InitialsAvatar(m.nombreCompleto, size = 48) },
    medicoActualId: Id? = null,
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
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lista.size, key = { lista[it].id }) { i ->
                val m = lista[i]
                val elegido = m.id == medicoActualId
                PlainCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        foto(m)
                        Column(Modifier.weight(1f)) {
                            Text(m.nombreCompleto, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            val detalle = listOfNotNull(m.especialidadesHc.joinToString(", ").ifBlank { null }, if (m.tieneTurnos) "Con agenda" else "Sin agenda").joinToString(" · ")
                            Text(detalle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (elegido) StatusChip("Elegido", Salud360Colors.Success)
                        else AcceptButton("Elegir", onClick = { onElegido(m, m.consultorioId ?: consultorio ?: "") })
                    }
                }
            }
        }
    }
}
