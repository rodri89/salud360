package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.ui.components.LabelValue
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.toDisplay
import com.salud360.features.hc.SeccionContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Pestaña "Datos": ficha del paciente en solo lectura + campos extra de la especialidad (editables). */
@Composable
fun DatosPacienteSeccion(p: Paciente, def: EspecialidadDefinition, ctx: SeccionContext) {
    val pacientes = koinInject<com.salud360.core.data.repos.PacientesRepository>()
    val extras by pacientes.observarExtras(p.id, def.codigo).collectAsState(emptyMap())
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SectionCard("Datos del paciente", icon = Icons.Default.Person, collapsible = false) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue("DNI", p.dni, Modifier.weight(1f))
                LabelValue("Apellido", p.apellido, Modifier.weight(1f))
                LabelValue("Nombre", p.nombre, Modifier.weight(1f))
                LabelValue("Sexo", p.sexo?.etiqueta ?: "", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue("Fecha de nacimiento", p.fechaNacimiento?.toDisplay() ?: "", Modifier.weight(1f))
                LabelValue("Edad", ctx.vm.ui.value.consulta?.edadMostrar ?: "", Modifier.weight(1f))
                LabelValue("Teléfono", p.telefono, Modifier.weight(1f))
                LabelValue("Mail", p.mail, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue("Domicilio", p.domicilio, Modifier.weight(1f))
                LabelValue("Localidad", p.localidad, Modifier.weight(1f))
                LabelValue("Obra social", p.obraSocial, Modifier.weight(1f))
                LabelValue("N° afiliado / plan", listOf(p.numeroAfiliado, p.obraSocialPlan).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
            }
            if (p.obraSocialOpcional.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue("Obra social opcional", p.obraSocialOpcional, Modifier.weight(1f))
                LabelValue("N° afiliado / plan", listOf(p.numeroAfiliadoOpcional, p.obraSocialPlanOpcional).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
            }
            if (p.nombreMadre.isNotBlank() || p.nombrePadre.isNotBlank() || p.cantidadHermanos != null || p.nombreFamiliar.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue("Madre", listOf(p.nombreMadre, p.telefonoMadre).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
                LabelValue("Padre", listOf(p.nombrePadre, p.telefonoPadre).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
                LabelValue("Hermanos", p.cantidadHermanos?.toString() ?: "", Modifier.weight(0.6f))
                LabelValue("Familiar", listOf(p.nombreFamiliar, p.telefonoFamiliar).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
            }
            if (def.fichaExtra.isNotEmpty()) {
                CamposForm(def.fichaExtra, extras, ctx.soloLectura) { k, v -> scope.launch { pacientes.guardarExtra(p.id, def.codigo, k, v) } }
            }
        }
    }
}
