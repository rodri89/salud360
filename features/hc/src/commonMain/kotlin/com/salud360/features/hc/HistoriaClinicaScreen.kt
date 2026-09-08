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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MedicalServices
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
 */
@Composable
fun HistoriaClinicaScreen(
    pacienteId: Id,
    especialidad: String,
    medicoId: Id,
    onAbrirConsulta: (Consulta) -> Unit,
    onVolver: () -> Unit,
    vm: HistoriaClinicaViewModel = koinViewModel(key = "hc-$pacienteId-$especialidad") { parametersOf(pacienteId, especialidad, medicoId) },
) {
    val paciente by vm.paciente.collectAsState()
    val consultas by vm.consultas.collectAsState()
    val pendientes by vm.pendientes.collectAsState()
    val def = vm.definicion
    var elegirTipo by remember { mutableStateOf(false) }
    var anular by remember { mutableStateOf<Consulta?>(null) }
    val color = Salud360Colors.especialidad(especialidad)

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
                    StatusChip(def?.nombre ?: especialidad, Color.White)
                }
            }
        }

        if (pendientes.isNotEmpty()) SectionCard("Pendientes", icon = Icons.Default.NotificationsActive) {
            pendientes.forEach { p -> Text("• ${p.texto}", color = Salud360Colors.Danger) }
        }

        if (def == null) {
            EmptyState("La especialidad \"$especialidad\" no está disponible en esta versión de la app.")
        } else {
            val abierta = consultas.firstOrNull { it.estado == EstadoConsulta.ABIERTA }
            if (abierta != null) {
                SectionCard("Consulta en curso", collapsible = false) {
                    Text("${abierta.fecha.toDisplay()} · ${def.tipoConsulta(abierta.tipo)?.nombre ?: abierta.tipo}", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Continuar", onClick = { onAbrirConsulta(abierta) })
                        LinkButton("Anular", onClick = { anular = abierta })
                    }
                }
            } else if (!elegirTipo) {
                PillButton("Nueva consulta", icon = Icons.Default.Add, onClick = {
                    if (def.tiposConsulta.size == 1) vm.nuevaConsulta(def.tiposConsulta.first().codigo, onAbrirConsulta) else elegirTipo = true
                })
            }
            if (elegirTipo) SectionCard("¿Qué tipo de consulta querés cargar?", collapsible = false, trailing = { TextButton(onClick = { elegirTipo = false }) { Text("Cancelar") } }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    def.tiposConsulta.forEach { t ->
                        OptionCard(t.nombre, iconoTipoConsulta(t.icono), subtitle = t.descripcion.ifBlank { null }, tint = color,
                            onClick = { elegirTipo = false; vm.nuevaConsulta(t.codigo, onAbrirConsulta) }, modifier = Modifier.weight(1f).widthIn(max = 220.dp))
                    }
                }
            }

            SectionCard("Consultas anteriores (${consultas.count { it.estado == EstadoConsulta.CERRADA }})", collapsible = false) {
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
