package com.salud360.features.hc.secciones

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.salud360.core.data.repos.PacientesRepository
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.SectionCard
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Formulario cuyos valores pertenecen al paciente y no a la consulta
 * (antecedentes perinatales, ginecológicos, datos del embarazo/parto, etc.).
 * Se guardan en `paciente_extras` bajo la especialidad, con clave "seccion.campo".
 */
@Composable
fun FormPacienteSeccion(s: SeccionDef, ctx: SeccionContext) {
    val pacientes = koinInject<PacientesRepository>()
    val extras by pacientes.observarExtras(ctx.pacienteId, ctx.especialidad).collectAsState(emptyMap())
    val scope = rememberCoroutineScope()
    val prefijo = "${s.id}."
    val valores = extras.filterKeys { it.startsWith(prefijo) }.mapKeys { it.key.removePrefix(prefijo) }
    SectionCard(s.titulo, icon = iconoSeccion(s.icono), initiallyExpanded = s.inicialmenteExpandida) {
        CamposForm(s.campos, valores, ctx.soloLectura) { campo, valor ->
            scope.launch { pacientes.guardarExtra(ctx.pacienteId, ctx.especialidad, prefijo + campo, valor) }
        }
        if (s.conArchivos) ArchivosInline(s.id, ctx, porPaciente = true)
    }
}
