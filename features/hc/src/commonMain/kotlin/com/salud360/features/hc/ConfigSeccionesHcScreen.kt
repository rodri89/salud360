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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.repos.HcRepository
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.EspecialidadDefinition
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

/** Ids de las secciones ocultas de una historia clínica según las preferencias del médico (sin preferencia: ninguna). */
fun seccionesOcultasDe(preferencias: Map<String, String>, codigoEspecialidad: String): Set<String> =
    preferencias[clavePreferenciaSeccionesOcultas(codigoEspecialidad)]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

/** Secciones del cuerpo de la consulta de una especialidad (unión de todos los tipos, sin las pestañas), en orden. */
fun seccionesConfigurables(def: EspecialidadDefinition?): List<SeccionDef> {
    def ?: return emptyList()
    val pestanias = def.tiposConsulta.flatMap { it.pestanias }.toSet()
    return def.tiposConsulta.flatMap { it.secciones }.distinct().filter { it !in pestanias }.mapNotNull { def.seccion(it) }
}

/** Una pill por sección, tildada = visible, con el contador de visibles. La usan la pantalla de configuración y el modal de la primera consulta. */
@Composable
fun SelectorSeccionesHc(secciones: List<SeccionDef>, ocultas: Set<String>, onCambiar: (seccionId: String, visible: Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${secciones.count { it.id !in ocultas }} de ${secciones.size} secciones visibles", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            secciones.forEach { s ->
                val visible = s.id !in ocultas
                FilterChip(selected = visible, onClick = { onCambiar(s.id, !visible) }, label = { Text(s.titulo) })
            }
        }
    }
}

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

    fun secciones(codigo: String): List<SeccionDef> = seccionesConfigurables(registry.definicion(codigo))

    fun ocultas(codigo: String): Set<String> = seccionesOcultasDe(preferencias.value, codigo)

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
            val ocultas = seccionesOcultasDe(preferencias, codigo)
            SectionCard(vm.nombre(codigo), icon = Icons.Default.Checklist, collapsible = false, trailing = { LinkButton("Mostrar todas", onClick = { vm.mostrarTodas(codigo) }) }) {
                SelectorSeccionesHc(vm.secciones(codigo), ocultas, onCambiar = { id, visible -> vm.setVisible(codigo, id, visible) })
            }
        }
        BackButton(onClick = onVolver)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Modal de la primera vez que el médico abre una consulta de esta historia clínica: mismo selector que la configuración.
 * No se cierra tocando afuera ni con «atrás»: solo con uno de los dos botones, que son los que dejan la elección guardada
 * (así no queda un «ya se le preguntó» ambiguo). [onGuardar] recibe las secciones que el médico dejó ocultas.
 */
@Composable
fun PreguntaSeccionesHcDialog(secciones: List<SeccionDef>, onGuardar: (ocultas: Set<String>) -> Unit, onUsarTodas: () -> Unit) {
    var ocultas by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("¿Qué secciones querés ver en esta historia clínica?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Podés cambiarlo cuando quieras desde Configuración → Secciones de la historia clínica. Las pestañas (datos del paciente y antecedentes) siempre se muestran.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectorSeccionesHc(secciones, ocultas, onCambiar = { id, visible -> ocultas = if (visible) ocultas - id else ocultas + id })
            }
        },
        confirmButton = { TextButton(onClick = { onGuardar(ocultas) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onUsarTodas) { Text("Usar todas") } },
    )
}
