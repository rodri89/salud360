package com.salud360.features.hc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PregnantWoman
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sick
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.data.repos.TEXTO_REINTENTAR
import com.salud360.core.data.repos.AlLlegarConNuevaConsulta
import com.salud360.core.data.repos.alLlegarConNuevaConsulta
import com.salud360.core.data.repos.hoy
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.ui.components.BackButton
import com.salud360.core.ui.components.BrandPanel
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.InitialsAvatar
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.OptionCard
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Ícono para cada tipo de consulta a partir del nombre declarado por la especialidad. */
fun iconoTipoConsulta(nombre: String): ImageVector = when (nombre) {
    "control" -> Icons.Default.MedicalServices
    "enfermedad" -> Icons.Default.Sick
    "telemedicina" -> Icons.Default.VideoCall
    "foto" -> Icons.Default.CameraAlt
    "prenatal", "obstetrica" -> Icons.Default.PregnantWoman
    "lactancia" -> Icons.Default.ChildCare
    "escalas" -> Icons.Default.Psychology
    "estudio" -> Icons.Default.Science
    "cardio" -> Icons.Default.FavoriteBorder
    else -> Icons.Default.MedicalServices
}

/**
 * Historia clínica de un paciente en una especialidad: listado de consultas, pendientes y
 * elección del tipo de consulta nueva (las "tarjetas" de `nueva_consulta_opciones`).
 *
 * [iniciarNueva] es la señal del botón «Nueva consulta» de la ficha del paciente: cuando las consultas ya se leyeron se
 * decide una sola vez qué hacer (`alLlegarConNuevaConsulta`: elegir el tipo, crearla si hay un único tipo, o nada si ya
 * hay una en curso) y se avisa con [onNuevaConsultaAtendida] para que quien la dio la apague. No viaja en la ruta, para
 * que recargar la página no cree una consulta sola.
 */
@Composable
fun HistoriaClinicaScreen(
    pacienteId: Id,
    especialidad: String,
    medicoId: Id,
    onAbrirConsulta: (Consulta) -> Unit,
    onVolver: () -> Unit,
    iniciarNueva: Boolean = false,
    onNuevaConsultaAtendida: () -> Unit = {},
    vm: HistoriaClinicaViewModel = koinViewModel(key = "hc-$pacienteId-$especialidad") { parametersOf(pacienteId, especialidad, medicoId) },
) {
    val paciente by vm.paciente.collectAsState()
    val consultasLeidas by vm.consultas.collectAsState()
    val consultas = consultasLeidas.orEmpty()
    val pendientes by vm.pendientes.collectAsState()
    val mensaje by vm.mensaje.collectAsState()
    val resuelta by vm.definicion.collectAsState()
    // Solo la HC que lee de la web (clínica) usa estos dos; las demás siguen con la lista de siempre.
    val vista by vm.vista.collectAsState()
    val leyendo by vm.leyendo.collectAsState()
    var elegirTipo by remember { mutableStateOf(false) }
    var anular by remember { mutableStateOf<Consulta?>(null) }
    val color = Salud360Colors.especialidad(especialidad)
    AvisoSeccionesDesconocidas(resuelta)

    // Con la señal se espera a que la base responda: decidir con la lista vacía de antes de cargar crearía una consulta
    // aunque ya hubiera una abierta. La señal se apaga en cuanto se atiende, así que no se repite al recomponer; la
    // creación corre en el scope del ViewModel y no se cancela ni se relanza si este efecto se reinicia.
    LaunchedEffect(iniciarNueva, consultasLeidas != null) {
        if (!iniciarNueva) return@LaunchedEffect
        val definicion = resuelta?.definicion
        if (definicion == null) return@LaunchedEffect onNuevaConsultaAtendida()
        when (alLlegarConNuevaConsulta(consultasLeidas != null, consultas.any { it.estado == EstadoConsulta.ABIERTA }, definicion.tiposConsulta.size)) {
            AlLlegarConNuevaConsulta.ESPERAR -> return@LaunchedEffect
            AlLlegarConNuevaConsulta.ELEGIR_TIPO -> elegirTipo = true
            AlLlegarConNuevaConsulta.CREAR_DEL_UNICO_TIPO -> vm.nuevaConsulta(definicion.tiposConsulta.first().codigo, onAbrirConsulta)
            AlLlegarConNuevaConsulta.NADA -> Unit
        }
        onNuevaConsultaAtendida()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        paciente?.let { p ->
            BrandPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InitialsAvatar(p.nombreCompleto, size = 52, color = Color.White.copy(alpha = 0.25f))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.nombreCompleto, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(listOfNotNull("DNI ${p.dni}", p.edad(hoy())?.toString(), p.obraSocial.ifBlank { null }).joinToString(" · "), color = Color.White.copy(alpha = 0.9f))
                    }
                    StatusChip(vm.nombre, Color.White)
                }
            }
        }

        if (pendientes.isNotEmpty()) SectionCard("Pendientes", icon = Icons.Default.NotificationsActive) {
            pendientes.forEach { p -> p.texto.lines().filter { it.isNotBlank() }.forEach { Text("• $it", color = Salud360Colors.Danger) } }
        }

        // Si no se pudo traer la historia clínica del servidor de la especialidad hay que decirlo: si no,
        // la pantalla queda igual que la de un paciente sin consultas previas y el médico no distingue
        // "no tiene" de "no se pudo traer".
        mensaje?.let { m ->
            SectionCard("No se pudo traer la historia clínica", icon = Icons.Default.CloudOff, collapsible = false) {
                // En la HC de la web el aviso se anuncia al aparecer y el reintento es a mano: nunca se repite solo (RF-58).
                Text(
                    m, color = Salud360Colors.Danger,
                    modifier = if (vm.soloLectura) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                )
                Text(
                    "Lo que está cargado en este dispositivo se sigue viendo y nada se pierde.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (vm.soloLectura) BotonDeLaLectura(TEXTO_REINTENTAR, onClick = vm::reintentar, enabled = !leyendo)
            }
        }

        val r = resuelta
        if (!vm.existe) {
            EmptyState("La especialidad \"$especialidad\" no está disponible en esta versión de la app.")
        } else if (r == null || (vm.soloLectura && vista == null)) {
            // HC general: esperando la configuración del médico (a lo sumo unos segundos, después vale la guardada) o la
            // lectura de la base para armar los grupos.
            Text("Cargando la historia clínica…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val def = r.definicion
            val tipos = r.tiposNuevos
            // Con backend de solo lectura (HC general) los grupos, las etiquetas y los avisos salen de `vistaDeLaHistoria`.
            val lectura = vista.takeIf { vm.soloLectura }
            // Sin perfil (RF-19) o sin la configuración la primera vez (RF-26): no hay consulta nueva, lo guardado se lee.
            if (r.sinPerfil) SectionCard("Falta que el administrador te asigne un perfil", collapsible = false) {
                Text("Hasta entonces no podés cargar consultas nuevas. Las anteriores se pueden abrir para leerlas.")
            }
            r.primeraVezSinConexion?.let { p ->
                SectionCard("La primera vez hace falta conexión", icon = Icons.Default.CloudOff, collapsible = false) {
                    Text(p.mensaje, color = Salud360Colors.Danger)
                    Text(
                        "Para cargar consultas nuevas, la app tiene que recibir la configuración de tu historia clínica al menos una vez. Las anteriores se pueden abrir para leerlas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (lectura != null) {
                AvisosDeLaLectura(lectura.avisos.todos)
                if (vm.puedeActualizar) AccionesDeLaLectura(leyendo, onActualizar = vm::actualizar)
            }
            val abierta = consultas.firstOrNull { it.estado == EstadoConsulta.ABIERTA }
            // Una consulta de la web nunca es la "en curso" (RF-44, RF-46): con la lectura de la web, solo las del dispositivo.
            val hayEnCurso = if (lectura != null) lectura.enCurso.isNotEmpty() else abierta != null
            lectura?.enCurso?.forEach { item ->
                ConsultaEnCursoLocal(item, def, onContinuar = { onAbrirConsulta(item.consulta) }, onAnular = { anular = item.consulta })
            }
            if (hayEnCurso) {
                if (lectura == null && abierta != null) {
                    // Una consulta ya abierta se sigue y se anula aunque el médico haya quedado sin perfil (C10).
                    SectionCard("Consulta en curso", collapsible = false) {
                        Text("${abierta.fecha.toDisplay()} · ${def.tipoConsulta(abierta.tipo)?.nombre ?: abierta.tipo}", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("Continuar", onClick = { onAbrirConsulta(abierta) })
                            LinkButton("Anular", onClick = { anular = abierta })
                        }
                    }
                }
            } else if (!elegirTipo && !iniciarNueva && tipos.isNotEmpty()) {
                PillButton("Nueva consulta", icon = Icons.Default.Add, modifier = if (lectura != null) Modifier.heightIn(min = 48.dp) else Modifier, onClick = {
                    if (tipos.size == 1) vm.nuevaConsulta(tipos.first().codigo, onAbrirConsulta) else elegirTipo = true
                })
            }
            if (elegirTipo) SectionCard("¿Qué tipo de consulta querés cargar?", collapsible = false, trailing = { TextButton(onClick = { elegirTipo = false }) { Text("Cancelar") } }) {
                // FlowRow: en teléfono las tarjetas envuelven (pediatría tiene 6 tipos), en pantallas anchas quedan en una fila.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    tipos.forEach { t ->
                        OptionCard(t.nombre, iconoTipoConsulta(t.icono), subtitle = t.descripcion.ifBlank { null }, tint = color,
                            onClick = { elegirTipo = false; vm.nuevaConsulta(t.codigo, onAbrirConsulta) }, modifier = Modifier.widthIn(min = 150.dp, max = 220.dp).weight(1f, fill = false))
                    }
                }
            }

            if (lectura != null) {
                AbiertasEnLaWeb(lectura, def, color, onAbrirConsulta)
                AnterioresDeLaLectura(lectura, def, color, onAbrirConsulta, onVerMas = vm::verMas)
            } else SectionCard("Consultas anteriores (${consultas.count { it.estado == EstadoConsulta.CERRADA }})", collapsible = false) {
                val cerradas = consultas.filter { it.estado == EstadoConsulta.CERRADA }
                if (cerradas.isEmpty()) Text("Todavía no hay consultas cerradas", color = MaterialTheme.colorScheme.onSurfaceVariant)
                cerradas.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconoTipoConsulta(def.tipoConsulta(c.tipo)?.icono ?: ""), contentDescription = null, tint = color)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.fecha.toDisplay(), fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(def.tipoConsulta(c.tipo)?.nombre ?: c.tipo, c.edadMostrar.ifBlank { null }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LinkButton("Ver", onClick = { onAbrirConsulta(c) })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
        BackButton(onClick = onVolver)
        Spacer(Modifier.height(24.dp))
    }

    anular?.let { c ->
        ConfirmDialog("Anular consulta", "¿Querés anular la consulta en curso del ${c.fecha.toDisplay()}? No se borran los datos ya cargados en otras secciones.",
            onConfirm = { vm.anular(c); anular = null }, onDismiss = { anular = null }, confirmText = "Anular", destructive = true)
    }
}
