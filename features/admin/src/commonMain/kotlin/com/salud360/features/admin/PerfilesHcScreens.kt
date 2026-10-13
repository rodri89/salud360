package com.salud360.features.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.ResumenPerfil
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.PlainCard
import com.salud360.core.ui.theme.Salud360Colors
import org.koin.compose.viewmodel.koinViewModel

/** Perfiles de la HC general (spec 001): lista con Duplicar y Borrar. El editor llega en la T24. */
@Composable
internal fun PerfilesHcTab(vm: PerfilesHcViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    var aBorrar by remember { mutableStateOf<ResumenPerfil?>(null) }
    val habilitado = !state.cargando && !state.sinConexion
    Text("Cada médico con la historia clínica general ve las pestañas y secciones de su perfil, con los nombres que se le pongan acá.", style = MaterialTheme.typography.bodyMedium)
    if (state.sinConexion) {
        // La lista que quedó se sigue viendo, pero sin conexión no se puede cambiar nada (RF-14).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CloudOff, contentDescription = null, tint = Salud360Colors.Warning)
            Text("Sin conexión con la historia clínica general: los perfiles no se pueden cambiar.", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
    state.mensaje?.let { Text(it, color = Salud360Colors.Indigo, fontWeight = FontWeight.SemiBold) }
    if (state.sinConexion || (state.perfiles.isEmpty() && !state.cargando)) {
        TextButton(onClick = vm::reintentar, enabled = !state.cargando) { Text("Reintentar") }
    }
    if (state.cargando) Text("Cargando perfiles...", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.perfiles.isEmpty() && !state.cargando && state.mensaje == null) EmptyState("Todavía no hay perfiles.")
    state.perfiles.forEach { p ->
        PlainCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.nombre, fontWeight = FontWeight.SemiBold)
                    Text(medicosAsignados(p.medicos), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = {}, enabled = false) { Text("Editar") }
                TextButton(onClick = { vm.duplicar(p.id) }, enabled = habilitado) { Text("Duplicar") }
                // Con médicos asignados no se puede borrar (RF-11): primero hay que pasarlos a otro perfil.
                TextButton(onClick = { aBorrar = p }, enabled = habilitado && p.medicos == 0) { Text("Borrar") }
            }
        }
    }
    aBorrar?.let { p ->
        ConfirmDialog(
            title = "Borrar perfil",
            message = "¿Borrar \"${p.nombre}\"? No se puede deshacer.",
            onConfirm = { vm.borrar(p.id); aBorrar = null },
            onDismiss = { aBorrar = null },
            confirmText = "Borrar",
            destructive = true,
        )
    }
}

private fun medicosAsignados(n: Int) = when (n) {
    0 -> "Sin médicos asignados"
    1 -> "1 médico asignado"
    else -> "$n médicos asignados"
}
