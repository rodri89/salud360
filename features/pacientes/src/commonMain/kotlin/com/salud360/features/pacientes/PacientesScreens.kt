package com.salud360.features.pacientes

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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.salud360.core.ui.components.linkWhatsApp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.salud360.core.data.repos.hoy
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.Sexo
import com.salud360.core.model.turnos.Turno
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.BackButton
import com.salud360.core.ui.components.BrandPanel
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.FormRow
import com.salud360.core.ui.components.FormRowResponsivo
import com.salud360.core.ui.components.InitialsAvatar
import com.salud360.core.ui.components.LabelValue
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.LoadingIndicator
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.PlainCard
import com.salud360.core.ui.components.RadioGroupField
import com.salud360.core.ui.components.ScreenTitle
import com.salud360.core.ui.components.SearchBar
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.SelectField
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.components.TextField
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Listado con búsqueda (reemplaza a las DataTables de "Buscar paciente"). */
@Composable
fun PacientesListScreen(
    medicoId: Id?,
    onAbrir: (Paciente) -> Unit,
    onNuevo: () -> Unit,
    titulo: String = "Pacientes",
    vm: PacientesListViewModel = koinViewModel(key = "pacientes-$medicoId") { parametersOf(medicoId) },
) {
    val lista by vm.lista.collectAsState()
    val busqueda by vm.busqueda.collectAsState()
    val cargandoCartera by vm.cargandoCartera.collectAsState()
    val total by vm.total.collectAsState()
    val hayMas by vm.hayMas.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Paginado: al acercarse al final de lo cargado se pide la página siguiente.
    LaunchedEffect(listState, lista.size, hayMas) {
        if (!hayMas) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { ultimo -> if (ultimo >= lista.size - 10) vm.cargarMas() }
    }
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onNuevo, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Nuevo paciente", tint = Color.White)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            ScreenTitle(titulo, if (hayMas) "$total pacientes · mostrando ${lista.size}" else "$total pacientes")
            SearchBar(value = busqueda, onValueChange = { vm.busqueda.value = it })
            Spacer(Modifier.height(12.dp))
            if (lista.isEmpty() && cargandoCartera) {
                LoadingIndicator(text = "Cargando pacientes...")
            } else if (lista.isEmpty()) {
                EmptyState(if (busqueda.isBlank()) "Todavía no hay pacientes cargados" else "No se encontraron pacientes", icon = Icons.Default.PersonSearch)
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lista, key = { it.id }) { p -> PacienteItem(p, onClick = { onAbrir(p) }) }
                    if (hayMas) item(key = "mas") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                            com.salud360.core.ui.components.LinkButton("Mostrar más", onClick = vm::cargarMas)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PacienteItem(p: Paciente, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(p.nombreCompleto, color = if (p.sexo == Sexo.F) Salud360Colors.SkyEnd else Salud360Colors.TealStart)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.nombreCompleto, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull("DNI ${p.dni}", p.edad(hoy())?.let { "${it.anios} años" }, p.obraSocial.ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!p.activo) StatusChip("Pendiente", Salud360Colors.Warning)
        }
    }
}

/** Alta / edición de la ficha (campos unificados de las siete apps). */
@Composable
fun PacienteFormScreen(
    pacienteId: Id?,
    vincularA: Id?,
    onGuardado: (Paciente) -> Unit,
    onVolver: () -> Unit,
    vm: PacienteFormViewModel = koinViewModel(key = "paciente-form-$pacienteId") { parametersOf(pacienteId, vincularA) },
) {
    val state by vm.state.collectAsState()
    val p = state.paciente
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onCopiado: () -> Unit = { scope.launch { snackbarHostState.showSnackbar("Copiado") } }
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(padding)) {
        val ancho = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle(if (pacienteId == null) "Nuevo paciente" else "Actualizar paciente")

            state.existente?.let { e ->
                PlainCard {
                    Text("Ya existe un paciente con DNI ${e.dni}: ${e.nombreCompleto}", fontWeight = FontWeight.SemiBold)
                    Text("Podés usar su ficha para no duplicarlo.", style = MaterialTheme.typography.bodyMedium)
                    ActionRow { AcceptButton("Usar ficha existente", onClick = vm::usarExistente) }
                }
            }

            SectionCard("Datos personales", collapsible = false) {
                FormRow {
                    TextField("DNI *", p.dni, { v -> vm.actualizar { it.copy(dni = v.filter { c -> c.isDigit() }) } }, Modifier.weight(1f),
                        keyboardType = KeyboardType.Number, isError = "dni" in state.errores, supportingText = state.errores["dni"])
                    DateField("Fecha de nacimiento", p.fechaNacimiento, { v -> vm.actualizar { it.copy(fechaNacimiento = v) } }, Modifier.weight(1f))
                }
                FormRow {
                    TextField("Apellido *", p.apellido, { v -> vm.actualizar { it.copy(apellido = v) } }, Modifier.weight(1f), isError = "apellido" in state.errores, supportingText = state.errores["apellido"])
                    TextField("Nombre *", p.nombre, { v -> vm.actualizar { it.copy(nombre = v) } }, Modifier.weight(1f), isError = "nombre" in state.errores, supportingText = state.errores["nombre"])
                }
                RadioGroupField("Sexo", p.sexo?.etiqueta, Sexo.entries.map { it.etiqueta }, { e -> vm.actualizar { it.copy(sexo = Sexo.entries.first { s -> s.etiqueta == e }) } })
                FormRowResponsivo(ancho, campos = listOf(
                    { TextField("Teléfono", p.telefono, { v -> vm.actualizar { it.copy(telefono = v) } }, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Phone) },
                    { TextField("Mail", p.mail, { v -> vm.actualizar { it.copy(mail = v) } }, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Email) },
                ))
                FormRowResponsivo(ancho, campos = listOf(
                    { TextField("Domicilio", p.domicilio, { v -> vm.actualizar { it.copy(domicilio = v) } }, Modifier.fillMaxWidth()) },
                    { TextField("Localidad", p.localidad, { v -> vm.actualizar { it.copy(localidad = v) } }, Modifier.fillMaxWidth()) },
                ))
                TextField("Nacionalidad", p.nacionalidad, { v -> vm.actualizar { it.copy(nacionalidad = v) } })
            }

            SectionCard("Obra social") {
                FormRowResponsivo(ancho, pesos = listOf(1.4f, 1f, 0.8f), campos = listOf(
                    { TextField("Obra social", p.obraSocial, { v -> vm.actualizar { it.copy(obraSocial = v) } }, Modifier.fillMaxWidth(), mostrarCopiar = true, onCopiado = onCopiado) },
                    { TextField("N° afiliado", p.numeroAfiliado, { v -> vm.actualizar { it.copy(numeroAfiliado = v) } }, Modifier.fillMaxWidth(), mostrarCopiar = true, onCopiado = onCopiado) },
                    { TextField("Plan", p.obraSocialPlan, { v -> vm.actualizar { it.copy(obraSocialPlan = v) } }, Modifier.fillMaxWidth(), mostrarCopiar = true, onCopiado = onCopiado) },
                ))
                FormRowResponsivo(ancho, pesos = listOf(1.4f, 1f, 0.8f), campos = listOf(
                    { TextField("Obra social opcional", p.obraSocialOpcional, { v -> vm.actualizar { it.copy(obraSocialOpcional = v) } }, Modifier.fillMaxWidth(), mostrarCopiar = true, onCopiado = onCopiado) },
                    { TextField("N° afiliado", p.numeroAfiliadoOpcional, { v -> vm.actualizar { it.copy(numeroAfiliadoOpcional = v) } }, Modifier.fillMaxWidth(), mostrarCopiar = true, onCopiado = onCopiado) },
                    { TextField("Plan", p.obraSocialPlanOpcional, { v -> vm.actualizar { it.copy(obraSocialPlanOpcional = v) } }, Modifier.fillMaxWidth(), mostrarCopiar = true, onCopiado = onCopiado) },
                ))
            }

            SectionCard("Familia y contacto", initiallyExpanded = false) {
                FormRowResponsivo(ancho, campos = listOf(
                    { TextField("Nombre de la madre", p.nombreMadre, { v -> vm.actualizar { it.copy(nombreMadre = v) } }, Modifier.fillMaxWidth()) },
                    { TextField("Teléfono madre", p.telefonoMadre, { v -> vm.actualizar { it.copy(telefonoMadre = v) } }, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Phone) },
                ))
                FormRowResponsivo(ancho, campos = listOf(
                    { TextField("Nombre del padre", p.nombrePadre, { v -> vm.actualizar { it.copy(nombrePadre = v) } }, Modifier.fillMaxWidth()) },
                    { TextField("Teléfono padre", p.telefonoPadre, { v -> vm.actualizar { it.copy(telefonoPadre = v) } }, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Phone) },
                ))
                FormRowResponsivo(ancho, campos = listOf(
                    { SelectField("Hermanos", p.cantidadHermanos?.toString(), (0..9).map { it.toString() }, { v -> vm.actualizar { it.copy(cantidadHermanos = v?.toIntOrNull()) } }, Modifier.fillMaxWidth()) },
                    { TextField("Familiar de contacto", p.nombreFamiliar, { v -> vm.actualizar { it.copy(nombreFamiliar = v) } }, Modifier.fillMaxWidth()) },
                    { TextField("Teléfono familiar", p.telefonoFamiliar, { v -> vm.actualizar { it.copy(telefonoFamiliar = v) } }, Modifier.fillMaxWidth(), keyboardType = KeyboardType.Phone) },
                ))
            }

            ActionRow {
                BackButton(onClick = onVolver)
                PillButton(if (state.guardando) "Guardando..." else "Guardar", onClick = { vm.guardar(onGuardado) }, enabled = !state.guardando)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

/** Link de WhatsApp para un teléfono (se le agrega el código de país si no lo tiene). */
/** Ficha del paciente + accesos a sus historias clínicas y turnos. */
@Composable
fun PacienteDetalleScreen(
    pacienteId: Id,
    especialidadesDisponibles: List<Pair<String, String>>,
    onEditar: () -> Unit,
    onAbrirHc: (especialidad: String) -> Unit,
    onVerConsulta: (Consulta) -> Unit,
    onNuevoTurno: (() -> Unit)?,
    onVolver: () -> Unit,
    vm: PacienteDetalleViewModel = koinViewModel(key = "paciente-$pacienteId") { parametersOf(pacienteId) },
) {
    val paciente by vm.paciente.collectAsState()
    val consultas by vm.consultas.collectAsState()
    val turnos by vm.turnos.collectAsState()
    val p = paciente ?: return
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onCopiado: () -> Unit = { scope.launch { snackbarHostState.showSnackbar("Copiado") } }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialsAvatar(p.nombreCompleto, size = 56, color = Color.White.copy(alpha = 0.25f))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.nombreCompleto, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text(
                        listOfNotNull("DNI ${p.dni}", p.edad(hoy())?.toString(), p.sexo?.etiqueta).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
                LinkButton("Editar", onClick = onEditar, color = Color.White)
            }
        }

        SectionCard("Datos", icon = Icons.Default.Edit, initiallyExpanded = false) {
            FormRow {
                LabelValue("Fecha de nacimiento", p.fechaNacimiento?.toDisplay() ?: "", Modifier.weight(1f))
                LabelValue("Teléfono", p.telefono, Modifier.weight(1f), onClick = if (p.telefono.isNotBlank()) ({ uriHandler.openUri(linkWhatsApp(p.telefono)) }) else null)
            }
            LabelValue("Mail", p.mail, Modifier.fillMaxWidth())
            FormRow {
                LabelValue("Domicilio", p.domicilio, Modifier.weight(1f))
                LabelValue("Localidad", p.localidad, Modifier.weight(1f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelValue("Obra social", p.obraSocial, Modifier.fillMaxWidth(), mostrarCopiar = true, onCopiado = onCopiado)
                LabelValue("N° afiliado", p.numeroAfiliado, Modifier.fillMaxWidth(), mostrarCopiar = true, onCopiado = onCopiado)
                LabelValue("Plan", p.obraSocialPlan, Modifier.fillMaxWidth(), mostrarCopiar = true, onCopiado = onCopiado)
            }
            if (p.nombreMadre.isNotBlank() || p.nombrePadre.isNotBlank() || p.nombreFamiliar.isNotBlank()) FormRow {
                LabelValue("Madre", listOf(p.nombreMadre, p.telefonoMadre).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
                LabelValue("Padre", listOf(p.nombrePadre, p.telefonoPadre).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
                LabelValue("Familiar", listOf(p.nombreFamiliar, p.telefonoFamiliar).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
            }
        }

        if (especialidadesDisponibles.isNotEmpty()) SectionCard("Historia clínica", icon = Icons.Default.FolderOpen, collapsible = false) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                especialidadesDisponibles.forEach { (codigo, nombre) -> PillButton(nombre, onClick = { onAbrirHc(codigo) }) }
            }
            if (consultas.isEmpty()) Text("Sin consultas registradas", color = MaterialTheme.colorScheme.onSurfaceVariant)
            consultas.take(10).forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.fecha.toDisplay(), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(100.dp))
                    Text("${c.especialidad} · ${c.tipo}", modifier = Modifier.weight(1f))
                    StatusChip(c.estado.name.lowercase().replaceFirstChar { it.uppercase() }, if (c.estado.name == "ABIERTA") Salud360Colors.Warning else Salud360Colors.Success)
                    LinkButton("Ver", onClick = { onVerConsulta(c) })
                }
            }
        }

        SectionCard("Turnos", icon = Icons.Default.CalendarMonth, initiallyExpanded = turnos.isNotEmpty()) {
            if (onNuevoTurno != null) ActionRow { AcceptButton("Asignar turno", onClick = onNuevoTurno) }
            if (turnos.isEmpty()) Text("Sin turnos", color = MaterialTheme.colorScheme.onSurfaceVariant)
            turnos.take(15).forEach { t -> TurnoLinea(t) }
        }

        ActionRow { BackButton(onClick = onVolver) }
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun TurnoLinea(t: Turno) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${t.fecha.toDisplay()} ${t.horario.hour.toString().padStart(2, '0')}:${t.horario.minute.toString().padStart(2, '0')}", modifier = Modifier.width(150.dp), fontWeight = FontWeight.SemiBold)
        Text(t.tipoTurno.etiqueta + if (t.sobreturno) " (sobreturno)" else "", modifier = Modifier.weight(1f))
        val (texto, color) = when {
            t.estado.name == "CANCELADO" -> "Cancelado" to Salud360Colors.Danger
            t.asistencia.name == "ASISTIO" -> "Asistió" to Salud360Colors.Success
            t.asistencia.name == "NO_ASISTIO" -> "No asistió" to Salud360Colors.Warning
            else -> "Activo" to Salud360Colors.TealStart
        }
        StatusChip(texto, color)
    }
}
