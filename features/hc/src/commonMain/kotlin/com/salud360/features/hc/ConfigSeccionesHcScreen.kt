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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.hcgeneral.DefinicionResuelta
import com.salud360.core.data.hcgeneral.mostrarTodas
import com.salud360.core.data.hcgeneral.seccionesDelPerfil
import com.salud360.core.data.repos.HcRepository
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.BackButton
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.ScreenTitle
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.theme.Salud360Colors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Clave de preferencia con las secciones ocultas de una historia clínica (csv de ids), la lee `ConsultaViewModel.seccionesVisibles`. */
fun clavePreferenciaSeccionesOcultas(codigoEspecialidad: String) = "secciones_ocultas_$codigoEspecialidad"

/**
 * Qué secciones ve cada médico en cada historia clínica. La preferencia es por especialidad y aplica a todos los
 * tipos de consulta; las pestañas (datos del paciente, antecedentes) siempre se muestran.
 *
 * La HC general lista las secciones del perfil del médico con sus nombres (RF-21, RF-28); las ocultas que quedaron
 * fuera del perfil nunca se borran de la preferencia, así vuelven ocultas si el administrador las repone.
 */
class ConfigSeccionesHcViewModel(
    private val hc: HcRepository,
    private val registry: EspecialidadRegistry,
    private val definiciones: DefinicionesHc,
    private val medicoId: Id,
    val especialidades: List<String>,
) : ViewModel() {
    val preferencias: StateFlow<Map<String, String>> =
        hc.observarPreferencias(medicoId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Definición de cada especialidad; la HC general queda en null hasta resolverse con el perfil (RF-24 a RF-26). */
    private val _resueltas = MutableStateFlow(especialidades.associateWith { definiciones.estatica(it) })
    val resueltas: StateFlow<Map<String, DefinicionResuelta?>> = _resueltas

    init {
        especialidades.filter { definiciones.catalogo(it) != null }.forEach { codigo ->
            viewModelScope.launch {
                val r = definiciones.resolver(codigo, medicoId)
                _resueltas.update { it + (codigo to r) }
            }
        }
    }

    fun nombre(codigo: String) = registry.nombre(codigo)

    /** La HC general mientras espera la configuración del médico. */
    fun cargando(codigo: String) = _resueltas.value[codigo] == null && definiciones.catalogo(codigo) != null

    /**
     * Secciones del cuerpo de la consulta de una especialidad, en orden: las del perfil en la HC general; en las demás,
     * la unión de todos los tipos sin las pestañas, como siempre (RF-33).
     */
    fun secciones(codigo: String): List<SeccionDef> {
        val r = _resueltas.value[codigo] ?: return emptyList()
        val def = r.definicion
        if (r.porPerfil) return seccionesDelPerfil(def).mapNotNull { def.seccion(it) }
        val pestanias = def.tiposConsulta.flatMap { it.pestanias }.toSet()
        return def.tiposConsulta.flatMap { it.secciones }.distinct().filter { it !in pestanias }.mapNotNull { def.seccion(it) }
    }

    fun ocultas(codigo: String): Set<String> =
        preferencias.value[clavePreferenciaSeccionesOcultas(codigo)]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun setVisible(codigo: String, seccionId: String, visible: Boolean) {
        val nuevas = if (visible) ocultas(codigo) - seccionId else ocultas(codigo) + seccionId
        viewModelScope.launch { hc.guardarPreferencia(medicoId, clavePreferenciaSeccionesOcultas(codigo), nuevas.joinToString(",")) }
    }

    /** En la HC general solo muestra las del perfil y conserva las de afuera (RF-28); en las demás, vacía la preferencia. */
    fun mostrarTodas(codigo: String) = viewModelScope.launch {
        val r = _resueltas.value[codigo]
        val nuevas = if (r?.porPerfil == true) mostrarTodas(ocultas(codigo), seccionesDelPerfil(r.definicion)).joinToString(",") else ""
        hc.guardarPreferencia(medicoId, clavePreferenciaSeccionesOcultas(codigo), nuevas)
    }
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
    val resueltas by vm.resueltas.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Secciones de la historia clínica", "Elegí qué secciones ves en cada consulta. Las pestañas (datos del paciente y antecedentes) siempre se muestran.")
        if (vm.especialidades.isEmpty()) EmptyState("No tenés historias clínicas habilitadas", icon = Icons.Default.Checklist)
        vm.especialidades.forEach { codigo ->
            val r = resueltas[codigo]
            // HC general sin la configuración del perfil: el aviso en vez de una lista vacía (RF-19, RF-26).
            if (vm.cargando(codigo)) {
                SectionCard(vm.nombre(codigo), icon = Icons.Default.Checklist, collapsible = false) {
                    Text("Cargando la configuración de tu historia clínica…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@forEach
            }
            if (r != null && r.sinPerfil) {
                SectionCard(vm.nombre(codigo), icon = Icons.Default.Checklist, collapsible = false) {
                    Text("Falta que el administrador te asigne un perfil", fontWeight = FontWeight.SemiBold)
                    Text("Cuando lo tengas, acá vas a poder elegir cuáles de sus secciones ves.")
                }
                return@forEach
            }
            r?.primeraVezSinConexion?.let { p ->
                SectionCard(vm.nombre(codigo), icon = Icons.Default.CloudOff, collapsible = false) {
                    Text("La primera vez hace falta conexión", fontWeight = FontWeight.SemiBold)
                    Text(p.mensaje, color = Salud360Colors.Danger)
                    Text(
                        "Para elegir las secciones, la app tiene que recibir la configuración de tu historia clínica al menos una vez.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@forEach
            }
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
