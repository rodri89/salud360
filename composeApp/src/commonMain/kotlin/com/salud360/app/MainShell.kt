package com.salud360.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Sesion
import com.salud360.core.ui.components.InitialsAvatar
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.admin.AdminScreen
import com.salud360.features.hc.ConsultaScreen
import com.salud360.features.hc.EspecialidadRegistry
import com.salud360.features.hc.HistoriaClinicaScreen
import com.salud360.features.pacientes.PacienteDetalleScreen
import com.salud360.features.pacientes.PacienteFormScreen
import com.salud360.features.pacientes.PacientesListScreen
import com.salud360.features.turnos.AgendaDiaScreen
import com.salud360.features.turnos.AgendaSemanaScreen
import com.salud360.features.turnos.AsignarTurnoScreen
import com.salud360.features.turnos.ConfigAgendaScreen
import com.salud360.features.turnos.HorariosScreen
import com.salud360.features.turnos.ObrasSocialesScreen
import com.salud360.features.turnos.RecetasScreen
import com.salud360.features.turnos.SelectorMedicoScreen
import org.koin.compose.koinInject

/** Destinos de navegación. */
private object Rutas {
    const val AGENDA = "agenda"
    const val AGENDA_SEMANA = "agenda/semana"
    const val AGENDA_BLOQUEAR = "agenda/bloquear"
    const val ASIGNAR = "agenda/asignar"
    const val PACIENTES = "pacientes"
    const val PACIENTE_NUEVO = "pacientes/nuevo"
    const val PACIENTE = "pacientes/{id}"
    const val PACIENTE_EDITAR = "pacientes/{id}/editar"
    const val HC = "hc/{pacienteId}/{especialidad}"
    const val CONSULTA = "consulta/{id}"
    const val HORARIOS = "config/horarios"
    const val CONFIG_AGENDA = "config/agenda"
    const val OBRAS_SOCIALES = "config/obras-sociales"
    const val RECETAS = "recetas"
    const val ADMIN = "admin"
    const val ADMIN_CONFIG_MEDICO = "admin/medico/{id}/config"
    const val ADMIN_HORARIOS_MEDICO = "admin/medico/{id}/horarios/{consultorioId}"
    const val SELECTOR = "selector"
}

private data class ItemNav(val ruta: String, val titulo: String, val icono: ImageVector)

/** Médico que está siendo gestionado (el propio, o el elegido por la secretaria). */
data class ContextoMedico(val medicoId: String, val consultorioId: String?, val nombre: String, val especialidades: List<String>, val tieneTurnos: Boolean)

/**
 * Shell principal con navegación adaptativa: barra lateral (con el degradado de marca) en
 * pantallas anchas, barra inferior en teléfonos. El menú depende del rol y de lo que tiene
 * habilitado el médico (agenda y/o historias clínicas).
 */
@Composable
fun MainShell(sesion: Sesion, onLogout: () -> Unit) {
    val nav = rememberNavController()
    val registry = koinInject<EspecialidadRegistry>()
    val sync = koinInject<SyncEngine>()
    val estadoSync by sync.estado.collectAsState()
    val rol = sesion.usuario.rol
    val operador = sesion.usuario.nombreCompleto

    // Para el médico el contexto es él mismo; la secretaria lo elige en el selector.
    var contexto by remember(sesion) {
        mutableStateOf(sesion.medico?.let { m -> ContextoMedico(m.medicoId, m.consultorioId, sesion.usuario.nombreCompleto, m.especialidades.filter { registry.existe(it) }, m.tieneTurnos) })
    }

    val items = remember(rol, contexto) {
        buildList {
            when (rol) {
                Rol.MEDICO -> {
                    if (contexto?.tieneTurnos == true && contexto?.consultorioId != null) add(ItemNav(Rutas.AGENDA, "Agenda", Icons.Default.CalendarMonth))
                    add(ItemNav(Rutas.PACIENTES, "Pacientes", Icons.Default.People))
                    if (contexto?.tieneTurnos == true) { add(ItemNav(Rutas.RECETAS, "Recetas", Icons.Default.Receipt)); add(ItemNav(Rutas.CONFIG_AGENDA, "Configuración", Icons.Default.Settings)) }
                }
                Rol.SECRETARIA -> {
                    add(ItemNav(Rutas.SELECTOR, "Médico", Icons.Default.SwapHoriz))
                    if (contexto != null) {
                        if (contexto?.consultorioId != null) add(ItemNav(Rutas.AGENDA, "Agenda", Icons.Default.CalendarMonth))
                        add(ItemNav(Rutas.PACIENTES, "Pacientes", Icons.Default.People))
                        add(ItemNav(Rutas.RECETAS, "Recetas", Icons.Default.Receipt))
                        add(ItemNav(Rutas.OBRAS_SOCIALES, "Obras sociales", Icons.Default.HealthAndSafety))
                    }
                }
                Rol.ADMIN -> { add(ItemNav(Rutas.ADMIN, "Administración", Icons.Default.AdminPanelSettings)); add(ItemNav(Rutas.PACIENTES, "Pacientes", Icons.Default.People)) }
            }
        }
    }
    val inicio = items.firstOrNull()?.ruta ?: Rutas.PACIENTES
    val backStack by nav.currentBackStackEntryAsState()
    val rutaActual = backStack?.destination?.route

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ancho = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (ancho) BarraLateral(items, rutaActual, sesion, contexto, estadoSync.online, estadoSync.pendientes, onNavegar = { nav.navigate(it) { launchSingleTop = true } }, onLogout = onLogout)
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    if (!ancho) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        items.forEach { it -> NavigationBarItem(selected = rutaActual?.startsWith(it.ruta) == true, onClick = { nav.navigate(it.ruta) { launchSingleTop = true } }, icon = { Icon(it.icono, contentDescription = it.titulo) }, label = { Text(it.titulo) }) }
                        NavigationBarItem(selected = false, onClick = onLogout, icon = { Icon(Icons.Default.Logout, contentDescription = "Salir") }, label = { Text("Salir") })
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    NavHost(navController = nav, startDestination = inicio) {
                        composable(Rutas.SELECTOR) {
                            val s = sesion.secretaria
                            if (s == null) Text("Sin perfil de secretaria") else SelectorMedicoScreen(s.consultorioIds, s.medicoIds, onElegido = { medicoId, consultorioId, nombre ->
                                contexto = ContextoMedico(medicoId, consultorioId.ifBlank { null }, nombre, emptyList(), consultorioId.isNotBlank())
                                nav.navigate(if (consultorioId.isNotBlank()) Rutas.AGENDA else Rutas.PACIENTES) { launchSingleTop = true }
                            })
                        }
                        composable(Rutas.AGENDA) {
                            val c = contexto
                            if (c?.consultorioId == null) Text("Elegí un médico con agenda", Modifier.padding(16.dp))
                            else AgendaDiaScreen(c.medicoId, c.consultorioId, operador, tituloMedico = if (rol == Rol.SECRETARIA) c.nombre else null,
                                onAbrirPaciente = { nav.navigate("pacientes/$it") }, onVerSemana = { nav.navigate(Rutas.AGENDA_SEMANA) }, onAsignar = { nav.navigate(Rutas.ASIGNAR) })
                        }
                        composable(Rutas.AGENDA_SEMANA) { contexto?.let { c -> c.consultorioId?.let { AgendaSemanaScreen(c.medicoId, it, operador, onVolver = { nav.popBackStack() }) } } }
                        composable(Rutas.AGENDA_BLOQUEAR) { contexto?.let { c -> c.consultorioId?.let { AgendaSemanaScreen(c.medicoId, it, operador, modoBloqueo = true, onVolver = { nav.popBackStack() }) } } }
                        composable(Rutas.ASIGNAR) { contexto?.let { c -> c.consultorioId?.let { AsignarTurnoScreen(c.medicoId, it, operador, onVolver = { nav.popBackStack() }) } } }

                        composable(Rutas.PACIENTES) {
                            PacientesListScreen(medicoId = if (rol == Rol.ADMIN) null else contexto?.medicoId, onAbrir = { nav.navigate("pacientes/${it.id}") }, onNuevo = { nav.navigate(Rutas.PACIENTE_NUEVO) },
                                titulo = if (rol == Rol.SECRETARIA && contexto != null) "Pacientes de ${contexto!!.nombre}" else "Pacientes")
                        }
                        composable(Rutas.PACIENTE_NUEVO) { PacienteFormScreen(null, contexto?.medicoId, onGuardado = { nav.popBackStack(); nav.navigate("pacientes/${it.id}") }, onVolver = { nav.popBackStack() }) }
                        composable(Rutas.PACIENTE) { entry ->
                            val id = entry.savedStateHandle.get<String>("id") ?: return@composable
                            val esp = contexto?.especialidades?.map { it to registry.nombre(it) } ?: emptyList()
                            PacienteDetalleScreen(id, esp, onEditar = { nav.navigate("pacientes/$id/editar") }, onAbrirHc = { nav.navigate("hc/$id/$it") },
                                onVerConsulta = { nav.navigate("consulta/${it.id}") },
                                onNuevoTurno = if (contexto?.consultorioId != null) ({ nav.navigate(Rutas.ASIGNAR) }) else null, onVolver = { nav.popBackStack() })
                        }
                        composable(Rutas.PACIENTE_EDITAR) { entry ->
                            val id = entry.savedStateHandle.get<String>("id") ?: return@composable
                            PacienteFormScreen(id, contexto?.medicoId, onGuardado = { nav.popBackStack() }, onVolver = { nav.popBackStack() })
                        }
                        composable(Rutas.HC) { entry ->
                            val pacienteId = entry.savedStateHandle.get<String>("pacienteId") ?: return@composable
                            val especialidad = entry.savedStateHandle.get<String>("especialidad") ?: return@composable
                            val medicoId = contexto?.medicoId ?: return@composable
                            HistoriaClinicaScreen(pacienteId, especialidad, medicoId, onAbrirConsulta = { nav.navigate("consulta/${it.id}") }, onVolver = { nav.popBackStack() })
                        }
                        composable(Rutas.CONSULTA) { entry ->
                            val id = entry.savedStateHandle.get<String>("id") ?: return@composable
                            val medicoId = contexto?.medicoId ?: sesion.usuario.id
                            ConsultaScreen(id, medicoId, onCerrada = { nav.popBackStack() }, onVolver = { nav.popBackStack() })
                        }
                        composable(Rutas.RECETAS) {
                            val ids = if (rol == Rol.SECRETARIA) sesion.secretaria?.medicoIds ?: emptyList() else listOfNotNull(contexto?.medicoId)
                            RecetasScreen(ids, onVolver = { nav.popBackStack() })
                        }
                        composable(Rutas.CONFIG_AGENDA) {
                            contexto?.let { c ->
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    ConfigAgendaScreen(c.medicoId, esAdmin = rol == Rol.ADMIN, onVolver = { nav.popBackStack() })
                                    Row(Modifier.padding(16.dp)) {
                                        if (c.consultorioId != null) com.salud360.core.ui.components.AcceptButton("Horarios de atención", onClick = { nav.navigate(Rutas.HORARIOS) })
                                        Spacer(Modifier.width(10.dp))
                                        com.salud360.core.ui.components.AcceptButton("Obras sociales", onClick = { nav.navigate(Rutas.OBRAS_SOCIALES) })
                                    }
                                }
                            }
                        }
                        composable(Rutas.HORARIOS) { contexto?.let { c -> c.consultorioId?.let { HorariosScreen(c.medicoId, it, onVolver = { nav.popBackStack() }) } } }
                        composable(Rutas.OBRAS_SOCIALES) { ObrasSocialesScreen(contexto?.medicoId, onVolver = { nav.popBackStack() }) }

                        composable(Rutas.ADMIN) {
                            AdminScreen(onAbrirConfigMedico = { nav.navigate("admin/medico/$it/config") }, onAbrirHorarios = { m, c -> nav.navigate("admin/medico/$m/horarios/$c") })
                        }
                        composable(Rutas.ADMIN_CONFIG_MEDICO) { entry ->
                            val id = entry.savedStateHandle.get<String>("id") ?: return@composable
                            ConfigAgendaScreen(id, esAdmin = true, onVolver = { nav.popBackStack() })
                        }
                        composable(Rutas.ADMIN_HORARIOS_MEDICO) { entry ->
                            val id = entry.savedStateHandle.get<String>("id") ?: return@composable
                            val c = entry.savedStateHandle.get<String>("consultorioId") ?: return@composable
                            HorariosScreen(id, c, onVolver = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarraLateral(
    items: List<ItemNav>, rutaActual: String?, sesion: Sesion, contexto: ContextoMedico?, online: Boolean, pendientes: Int,
    onNavegar: (String) -> Unit, onLogout: () -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().width(112.dp).background(Salud360Colors.BrandGradient),
        containerColor = Color.Transparent, contentColor = Color.White,
        header = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 12.dp)) {
                Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = Color.White, modifier = Modifier.height(32.dp))
                Text("Salud 360", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                InitialsAvatar(sesion.usuario.nombreCompleto, size = 40, color = Color.White.copy(alpha = 0.25f))
                Text(sesion.usuario.nombre, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                if (contexto != null && contexto.nombre != sesion.usuario.nombreCompleto) Text(contexto.nombre.substringBefore(","), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        },
    ) {
        items.forEach { it ->
            NavigationRailItem(
                selected = rutaActual?.startsWith(it.ruta) == true, onClick = { onNavegar(it.ruta) },
                icon = { Icon(it.icono, contentDescription = it.titulo) }, label = { Text(it.titulo, color = Color.White) },
                colors = NavigationRailItemDefaults.colors(selectedIconColor = Salud360Colors.TealEnd, unselectedIconColor = Color.White, indicatorColor = Color.White.copy(alpha = 0.9f), selectedTextColor = Color.White, unselectedTextColor = Color.White),
            )
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 12.dp)) {
            Icon(if (online) Icons.Default.CloudDone else Icons.Default.CloudOff, contentDescription = null, tint = if (online) Salud360Colors.NeonGreen else Salud360Colors.Warning)
            Text(if (pendientes > 0) "$pendientes por sincronizar" else if (online) "Sincronizado" else "Sin conexión", color = Color.White, style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = onLogout) { Icon(Icons.Default.Logout, contentDescription = "Cerrar sesión", tint = Color.White) }
        }
    }
}
