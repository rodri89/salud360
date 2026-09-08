package com.salud360.features.admin

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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.salud360.core.data.repos.hoy
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Secretaria
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.CheckboxField
import com.salud360.core.ui.components.DataTable
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.NumberField
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.PlainCard
import com.salud360.core.ui.components.RadioGroupField
import com.salud360.core.ui.components.ScreenTitle
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.SelectField
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.components.TableColumn
import com.salud360.core.ui.components.TextField
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import kotlinx.datetime.LocalDate
import org.koin.compose.viewmodel.koinViewModel

private val SOLAPAS = listOf("Usuarios", "Médicos", "Secretarias", "Consultorios", "Especialidades", "Feriados", "Licencias")

/** Panel de administración con solapas. */
@Composable
fun AdminScreen(onAbrirConfigMedico: (medicoId: String) -> Unit, onAbrirHorarios: (medicoId: String, consultorioId: String) -> Unit, vm: AdminViewModel = koinViewModel()) {
    var solapa by remember { mutableIntStateOf(0) }
    val mensaje by vm.mensaje.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Administración", modifier = Modifier.weight(1f))
            LinkButton("Sincronizar ahora", onClick = vm::sincronizar)
        }
        mensaje?.let { Text(it, color = Salud360Colors.Indigo, fontWeight = FontWeight.SemiBold) }
        ScrollableTabRow(selectedTabIndex = solapa, edgePadding = 0.dp, containerColor = Color.Transparent) {
            SOLAPAS.forEachIndexed { i, t -> Tab(selected = solapa == i, onClick = { solapa = i }, text = { Text(t) }) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (solapa) {
                0 -> UsuariosTab(vm)
                1 -> MedicosTab(vm, onAbrirConfigMedico, onAbrirHorarios)
                2 -> SecretariasTab(vm)
                3 -> ConsultoriosTab(vm)
                4 -> EspecialidadesTab(vm)
                5 -> FeriadosTab(vm)
                6 -> LicenciasTab(vm)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UsuariosTab(vm: AdminViewModel) {
    val usuarios by vm.usuarios.collectAsState()
    var nombre by remember { mutableStateOf("") }
    var apellido by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rol by remember { mutableStateOf(Rol.MEDICO) }
    SectionCard("Nuevo usuario", collapsible = false) {
        Text("El médico no elige especialidad al ingresar: se le asignan desde la solapa Médicos y la app la reconoce por su mail.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField("Nombre", nombre, { nombre = it }, Modifier.weight(1f))
            TextField("Apellido", apellido, { apellido = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField("Mail", email, { email = it }, Modifier.weight(1f), keyboardType = KeyboardType.Email)
            androidx.compose.material3.OutlinedTextField(password, { password = it }, label = { Text("Contraseña") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.weight(1f))
        }
        RadioGroupField("Rol", rol.etiqueta(), Rol.entries.map { it.etiqueta() }, { v -> rol = Rol.entries.first { it.etiqueta() == v } })
        ActionRow {
            PillButton("Crear usuario", enabled = nombre.isNotBlank() && apellido.isNotBlank() && email.contains('@') && password.length >= 6, onClick = {
                vm.crearUsuario(nombre, apellido, email, password, rol); nombre = ""; apellido = ""; email = ""; password = ""
            })
        }
    }
    DataTable(
        columns = listOf(TableColumn("Apellido y nombre", 240.dp), TableColumn("Mail", 260.dp), TableColumn("Rol", 110.dp), TableColumn("Activo", 80.dp)),
        rows = usuarios, cells = { u -> listOf(u.nombreCompleto, u.email, u.rol.etiqueta(), if (u.activo) "Sí" else "No") },
        trailing = { u -> LinkButton(if (u.activo) "Desactivar" else "Activar", onClick = { vm.guardarUsuario(u.copy(activo = !u.activo)) }) },
    )
}

fun Rol.etiqueta() = when (this) { Rol.ADMIN -> "Administrador"; Rol.MEDICO -> "Médico"; Rol.SECRETARIA -> "Secretaria" }

@Composable
private fun MedicosTab(vm: AdminViewModel, onConfig: (String) -> Unit, onHorarios: (String, String) -> Unit) {
    val medicos by vm.medicos.collectAsState()
    val consultorios by vm.consultorios.collectAsState()
    val especialidades by vm.especialidades.collectAsState()
    if (medicos.isEmpty()) EmptyState("Todavía no hay médicos. Creá un usuario con rol Médico.")
    medicos.forEach { m -> MedicoCard(m, vm, consultorios.map { it.id to it.nombre }, especialidades.map { it.id to it.nombre }, onConfig, onHorarios) }
}

@Composable
private fun MedicoCard(m: Medico, vm: AdminViewModel, consultorios: List<Pair<String, String>>, especialidades: List<Pair<String, String>>, onConfig: (String) -> Unit, onHorarios: (String, String) -> Unit) {
    SectionCard(m.nombreCompleto, initiallyExpanded = false, trailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (m.tieneTurnos) StatusChip("Turnos", Salud360Colors.TealStart)
            m.especialidadesHc.forEach { StatusChip(vm.especialidadesHc.firstOrNull { e -> e.first == it }?.second ?: it, Salud360Colors.especialidad(it)) }
        }
    }) {
        Text("Historias clínicas habilitadas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.especialidadesHc.forEach { (codigo, nombre) ->
                CheckboxField(nombre, codigo in m.especialidadesHc, { vm.setEspecialidadHc(m, codigo, it) })
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(checked = m.tieneTurnos, onCheckedChange = { vm.guardarMedico(m.copy(tieneTurnos = it)) })
            Text("Tiene agenda de turnos")
            Switch(checked = m.visibleEnTurnos, onCheckedChange = { vm.guardarMedico(m.copy(visibleEnTurnos = it)) })
            Text("Visible para pacientes en la web")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectField("Consultorio", consultorios.firstOrNull { it.first == m.consultorioId }?.second, consultorios.map { it.second },
                { v -> vm.guardarMedico(m.copy(consultorioId = consultorios.firstOrNull { it.second == v }?.first)) }, Modifier.weight(1f))
            SelectField("Especialidad (turnos)", especialidades.firstOrNull { it.first == m.especialidadId }?.second, especialidades.map { it.second },
                { v -> vm.guardarMedico(m.copy(especialidadId = especialidades.firstOrNull { it.second == v }?.first)) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField("Teléfono", m.telefono, { vm.guardarMedico(m.copy(telefono = it)) }, Modifier.weight(1f))
            TextField("Mail de contacto", m.mail, { vm.guardarMedico(m.copy(mail = it)) }, Modifier.weight(1f))
        }
        ActionRow {
            AcceptButton("Módulos y configuración", onClick = { onConfig(m.id) })
            if (m.consultorioId != null) AcceptButton("Horarios", onClick = { onHorarios(m.id, m.consultorioId!!) })
        }
    }
}

@Composable
private fun SecretariasTab(vm: AdminViewModel) {
    val secretarias by vm.secretarias.collectAsState()
    val medicos by vm.medicos.collectAsState()
    val consultorios by vm.consultorios.collectAsState()
    if (secretarias.isEmpty()) EmptyState("Todavía no hay secretarias. Creá un usuario con rol Secretaria.")
    secretarias.forEach { s ->
        SectionCard("${s.apellido}, ${s.nombre}", initiallyExpanded = false) {
            Text("Consultorios que administra", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                consultorios.forEach { c -> CheckboxField(c.nombre, c.id in s.consultorioIds, { on -> vm.guardarSecretaria(s.copy(consultorioIds = if (on) s.consultorioIds + c.id else s.consultorioIds - c.id)) }) }
            }
            Text("Médicos a los que asiste (historia clínica)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                medicos.forEach { m -> CheckboxField(m.nombreCompleto, m.id in s.medicoIds, { on -> vm.guardarSecretaria(s.copy(medicoIds = if (on) s.medicoIds + m.id else s.medicoIds - m.id)) }) }
            }
        }
    }
}

@Composable
private fun ConsultoriosTab(vm: AdminViewModel) {
    val consultorios by vm.consultorios.collectAsState()
    var nombre by remember { mutableStateOf("") }
    var direccion by remember { mutableStateOf("") }
    var telefono by remember { mutableStateOf("") }
    SectionCard("Nuevo consultorio", collapsible = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField("Nombre", nombre, { nombre = it }, Modifier.weight(1f))
            TextField("Dirección", direccion, { direccion = it }, Modifier.weight(1f))
            TextField("Teléfono", telefono, { telefono = it }, Modifier.weight(0.7f))
        }
        ActionRow { AcceptButton("Agregar", enabled = nombre.isNotBlank(), onClick = { vm.nuevoConsultorio(nombre, direccion, telefono); nombre = ""; direccion = ""; telefono = "" }) }
    }
    consultorios.forEach { c ->
        PlainCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(c.nombre, fontWeight = FontWeight.SemiBold); Text("${c.direccion} · ${c.telefono}", style = MaterialTheme.typography.bodyMedium) }
                Switch(checked = c.activo, onCheckedChange = { vm.guardarConsultorio(c.copy(activo = it)) })
            }
        }
    }
}

@Composable
private fun EspecialidadesTab(vm: AdminViewModel) {
    val especialidades by vm.especialidades.collectAsState()
    var nombre by remember { mutableStateOf("") }
    var codigoHc by remember { mutableStateOf<String?>(null) }
    SectionCard("Nueva especialidad (para la agenda de turnos)", collapsible = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField("Nombre", nombre, { nombre = it }, Modifier.weight(1f))
            SelectField("Historia clínica asociada", vm.especialidadesHc.firstOrNull { it.first == codigoHc }?.second, vm.especialidadesHc.map { it.second },
                { v -> codigoHc = vm.especialidadesHc.firstOrNull { it.second == v }?.first }, Modifier.weight(1f))
        }
        ActionRow { AcceptButton("Agregar", enabled = nombre.isNotBlank(), onClick = { vm.nuevaEspecialidad(nombre, codigoHc); nombre = "" }) }
    }
    DataTable(
        columns = listOf(TableColumn("Especialidad", 240.dp), TableColumn("Historia clínica", 200.dp), TableColumn("Activa", 80.dp)),
        rows = especialidades, cells = { e -> listOf(e.nombre, vm.especialidadesHc.firstOrNull { it.first == e.codigoHc }?.second ?: "—", if (e.activo) "Sí" else "No") },
        trailing = { e -> LinkButton(if (e.activo) "Desactivar" else "Activar", onClick = { vm.guardarEspecialidad(e.copy(activo = !e.activo)) }) },
    )
}

@Composable
private fun FeriadosTab(vm: AdminViewModel) {
    val feriados by vm.feriados.collectAsState()
    var fecha by remember { mutableStateOf<LocalDate?>(null) }
    var descripcion by remember { mutableStateOf("") }
    SectionCard("Nuevo feriado", collapsible = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DateField("Fecha", fecha, { fecha = it }, Modifier.width(200.dp))
            TextField("Descripción", descripcion, { descripcion = it }, Modifier.weight(1f))
            AcceptButton("Agregar", enabled = fecha != null, onClick = { fecha?.let { vm.agregarFeriado(it, descripcion) }; fecha = null; descripcion = "" })
        }
    }
    DataTable(
        columns = listOf(TableColumn("Fecha", 140.dp), TableColumn("Descripción", 300.dp)),
        rows = feriados.filter { it.fecha >= hoy().let { h -> LocalDate(h.year, 1, 1) } }, cells = { f -> listOf(f.fecha.toDisplay(), f.descripcion) },
        trailing = { f -> LinkButton("Quitar", onClick = { vm.eliminarFeriado(f) }) },
    )
}

@Composable
private fun LicenciasTab(vm: AdminViewModel) {
    val medicos by vm.medicos.collectAsState()
    val licencias by vm.licencias.collectAsState()
    Text("La licencia controla el acceso del médico a la historia clínica (vencida = no puede ingresar; aviso = se le muestra un recordatorio).", style = MaterialTheme.typography.bodyMedium)
    medicos.forEach { m ->
        val l = licencias.firstOrNull { it.medicoId == m.id } ?: Licencia(m.id, hoy().toString(), hoy().toString())
        PlainCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(m.nombreCompleto, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(220.dp))
                DateField("Vence", runCatching { LocalDate.parse(l.fechaExpiracion) }.getOrNull(), { it?.let { d -> vm.guardarLicencia(l.copy(fechaExpiracion = d.toString())) } }, Modifier.width(180.dp))
                DateField("Aviso desde", runCatching { LocalDate.parse(l.fechaAviso) }.getOrNull(), { it?.let { d -> vm.guardarLicencia(l.copy(fechaAviso = d.toString())) } }, Modifier.width(180.dp))
                var importe by remember(l.importe) { mutableStateOf(if (l.importe == 0.0) "" else l.importe.toString()) }
                NumberField("Importe", importe, { importe = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> vm.guardarLicencia(l.copy(importe = v)) } }, Modifier.width(140.dp), suffix = "$")
                Switch(checked = l.activo, onCheckedChange = { vm.guardarLicencia(l.copy(activo = it)) })
                val vencida = runCatching { LocalDate.parse(l.fechaExpiracion) < hoy() }.getOrDefault(false)
                StatusChip(if (!l.activo || vencida) "Vencida" else "Vigente", if (!l.activo || vencida) Salud360Colors.Danger else Salud360Colors.Success)
            }
        }
    }
}
