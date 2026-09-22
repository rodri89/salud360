package com.salud360.features.hc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.repos.HcRepository
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.BackButton
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.ScreenTitle
import com.salud360.core.ui.components.SectionCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Clave de preferencia con las secciones ocultas de una historia clínica (csv de ids), la lee `ConsultaViewModel.seccionesVisibles`. */
fun clavePreferenciaSeccionesOcultas(codigoEspecialidad: String) = "secciones_ocultas_$codigoEspecialidad"

/**
 * Qué secciones ve cada médico en cada historia clínica. La preferencia es por especialidad y aplica a todos los
 * tipos de consulta; las pestañas (datos del paciente, antecedentes) siempre se muestran.
 */
class ConfigSeccionesHcViewModel(
    private val hc: HcRepository,
    private val registry: EspecialidadRegistry,
    private val medicoId: Id,
    val especialidades: List<String>,
) : ViewModel() {
    val preferencias: StateFlow<Map<String, String>> =
        hc.observarPreferencias(medicoId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun nombre(codigo: String) = registry.nombre(codigo)

    /** Secciones del cuerpo de la consulta de una especialidad (unión de todos los tipos, sin las pestañas), en orden. */
    fun secciones(codigo: String): List<SeccionDef> {
        val def = registry.definicion(codigo) ?: return emptyList()
        val pestanias = def.tiposConsulta.flatMap { it.pestanias }.toSet()
        return def.tiposConsulta.flatMap { it.secciones }.distinct().filter { it !in pestanias }.mapNotNull { def.seccion(it) }
    }

    fun ocultas(codigo: String): Set<String> =
        preferencias.value[clavePreferenciaSeccionesOcultas(codigo)]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun setVisible(codigo: String, seccionId: String, visible: Boolean) {
        val nuevas = if (visible) ocultas(codigo) - seccionId else ocultas(codigo) + seccionId
        viewModelScope.launch { hc.guardarPreferencia(medicoId, clavePreferenciaSeccionesOcultas(codigo), nuevas.joinToString(",")) }
    }

    fun mostrarTodas(codigo: String) = viewModelScope.launch { hc.guardarPreferencia(medicoId, clavePreferenciaSeccionesOcultas(codigo), "") }
}

/** Configuración → Secciones de la historia clínica: una pill por sección, tildada = visible. */
@Composable
fun ConfigSeccionesHcScreen(
    medicoId: Id,
    especialidades: List<String>,
    onVolver: () -> Unit,
    vm: ConfigSeccionesHcViewModel = koinViewModel(key = "config-hc-$medicoId") { parametersOf(medicoId, especialidades) },
) {
    val preferencias by vm.preferencias.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Secciones de la historia clínica", "Elegí qué secciones ves en cada consulta. Las pestañas (datos del paciente y antecedentes) siempre se muestran.")
        if (vm.especialidades.isEmpty()) EmptyState("No tenés historias clínicas habilitadas", icon = Icons.Default.Checklist)
        vm.especialidades.forEach { codigo ->
            val ocultas = preferencias[clavePreferenciaSeccionesOcultas(codigo)]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            val secciones = vm.secciones(codigo)
            SectionCard(vm.nombre(codigo), icon = Icons.Default.Checklist, collapsible = false, trailing = { LinkButton("Mostrar todas", onClick = { vm.mostrarTodas(codigo) }) }) {
                Text("${secciones.size - ocultas.count { it in secciones.map { s -> s.id } }} de ${secciones.size} secciones visibles", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    secciones.forEach { s ->
                        val visible = s.id !in ocultas
                        FilterChip(selected = visible, onClick = { vm.setVisible(codigo, s.id, !visible) }, label = { Text(s.titulo) })
                    }
                }
            }
        }
        BackButton(onClick = onVolver)
        Spacer(Modifier.height(24.dp))
    }
}
