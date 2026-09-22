package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.CheckboxField
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextField
import com.salud360.features.hc.ConsultaUi
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion

/**
 * Diagnósticos / enfermedades del catálogo del médico como casillas (HC hematología y clínica).
 * El médico puede agregar nuevos ítems al catálogo desde acá.
 */
@Composable
fun DiagnosticosSeccion(s: SeccionDef, ctx: SeccionContext, ui: ConsultaUi) {
    var nuevo by remember { mutableStateOf("") }
    // En lectura solo se muestran los diagnósticos asignados a esta consulta.
    val catalogo = if (ctx.soloLectura) ui.diagnosticosCatalogo.filter { it.id in ui.diagnosticosConsulta } else ui.diagnosticosCatalogo
    if (ctx.soloLectura && catalogo.isEmpty()) { SeccionVacia(); return }
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "diagnostico"), initiallyExpanded = s.inicialmenteExpandida) {
        if (catalogo.isEmpty()) Text("No tenés diagnósticos en tu listado. Agregá el primero abajo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        catalogo.chunked(3).forEach { fila ->
            Row(Modifier.fillMaxWidth()) {
                fila.forEach { d ->
                    CheckboxField(d.nombre, d.id in ui.diagnosticosConsulta, { ctx.vm.toggleDiagnostico(d.id, it) }, Modifier.weight(1f), enabled = !ctx.soloLectura)
                }
                repeat(3 - fila.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }
        if (!ctx.soloLectura) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField("Nuevo diagnóstico", nuevo, { nuevo = it }, Modifier.width(320.dp))
            AcceptButton("Agregar", enabled = nuevo.isNotBlank(), onClick = { ctx.vm.nuevoDiagnostico(nuevo); nuevo = "" })
        }
    }
}
