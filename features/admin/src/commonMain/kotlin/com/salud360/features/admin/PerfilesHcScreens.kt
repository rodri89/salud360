package com.salud360.features.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.salud360.core.data.hcgeneral.SeccionCatalogoAdmin
import com.salud360.core.data.hcgeneral.seccionesParaAgregar
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.ResumenPerfil
import com.salud360.core.model.especialidad.SeccionPerfil
import com.salud360.core.model.especialidad.TipoConsultaHc
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.CancelButton
import com.salud360.core.ui.components.CheckboxField
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.PlainCard
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.components.TextField
import com.salud360.core.ui.theme.Salud360Colors
import org.koin.compose.viewmodel.koinViewModel

/** Perfiles de la HC general (spec 001): lista con Editar, Duplicar y Borrar, y el editor de un perfil. */
@Composable
internal fun PerfilesHcTab(vm: PerfilesHcViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    if (state.editor != null) {
        EditorPerfil(state, vm)
        return
    }
    var aBorrar by remember { mutableStateOf<ResumenPerfil?>(null) }
    val habilitado = !state.cargando && !state.sinConexion
    Text("Cada médico con la historia clínica general ve las pestañas y secciones de su perfil, con los nombres que se le pongan acá.", style = MaterialTheme.typography.bodyMedium)
    if (state.sinConexion) AvisoSinConexion("Sin conexión con la historia clínica general: los perfiles no se pueden cambiar.")
    state.mensaje?.let { Text(it, color = Salud360Colors.Indigo, fontWeight = FontWeight.SemiBold) }
    if (state.sinConexion || (state.perfiles.isEmpty() && !state.cargando)) {
        TextButton(onClick = vm::reintentar, enabled = !state.cargando) { Text("Reintentar") }
    }
    if (state.cargando) Text("Cargando perfiles...", color = MaterialTheme.colorScheme.onSurfaceVariant)
    ActionRow {
        // Sin catálogo no hay secciones para elegir: se espera a que cargue.
        PillButton("Nuevo perfil", onClick = vm::nuevoPerfil, enabled = habilitado && state.catalogo.isNotEmpty(), icon = Icons.Default.Add)
    }
    if (state.perfiles.isEmpty() && !state.cargando && state.mensaje == null) EmptyState("Todavía no hay perfiles.")
    state.perfiles.forEach { p ->
        PlainCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(p.nombre, fontWeight = FontWeight.SemiBold)
                    Text(medicosAsignados(p.medicos), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.editar(p.id) }, enabled = habilitado) { Text("Editar") }
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

/**
 * Editor de un perfil (RF-3 a RF-9): nombre, pestañas y sus secciones, con cada error junto a su campo. Sin conexión
 * se ve pero no se edita (RF-14); Guardar sigue disponible para reintentar con el mismo borrador (RF-13).
 */
@Composable
private fun EditorPerfil(state: PerfilesHcUi, vm: PerfilesHcViewModel) {
    val editor = state.editor ?: return
    val perfil = editor.borrador
    val errores = editor.errores
    val editable = !state.cargando && !state.sinConexion
    var confirmarSalida by remember { mutableStateOf(false) }

    Text(if (editor.nuevo) "Nuevo perfil" else "Editar perfil", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (state.sinConexion) {
        AvisoSinConexion("Sin conexión con la historia clínica general: no se puede editar. Lo que está en pantalla se conserva; Guardar lo vuelve a intentar.")
        TextButton(onClick = vm::reintentar, enabled = !state.cargando) { Text("Reintentar") }
    }
    if (state.cargando) Text("Guardando...", color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.mensaje?.let { Text(it, color = Salud360Colors.Indigo, fontWeight = FontWeight.SemiBold) }
    Errores(errores.generales)

    TextField(
        "Nombre del perfil", perfil.nombre, vm::renombrarPerfil, readOnly = !editable,
        isError = errores.nombre.isNotEmpty(), supportingText = errores.nombre.unidos(),
    )

    perfil.pestanias.forEachIndexed { i, pestania ->
        SectionCard(pestania.nombre.ifBlank { "Pestaña sin nombre" }, collapsible = false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    "Nombre de la pestaña", pestania.nombre, { vm.renombrarPestania(i, it) }, Modifier.weight(1f), readOnly = !editable,
                    isError = errores.pestania(i).isNotEmpty(), supportingText = errores.pestania(i).unidos(),
                )
                Flechas(i, perfil.pestanias.size, editable, "pestaña") { vm.moverPestania(i, it) }
                IconButton(onClick = { vm.quitarPestania(i) }, enabled = editable) { Icon(Icons.Default.Delete, contentDescription = "Quitar pestaña") }
            }
            if (pestania.secciones.isEmpty()) {
                Text("Sin secciones.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pestania.secciones.forEachIndexed { j, seccion ->
                HorizontalDivider()
                SeccionDelPerfil(perfil, i, j, seccion, state.catalogo, editor, editable, vm)
            }
            AgregarSeccion(seccionesParaAgregar(state.catalogo, perfil), editable) { vm.agregarSeccion(i, it) }
        }
    }
    ActionRow {
        PillButton("Agregar pestaña", onClick = vm::agregarPestania, enabled = editable, icon = Icons.Default.Add)
    }

    CatalogoSecciones(state.catalogo, perfil)

    ActionRow {
        CancelButton("Volver", onClick = { if (editor.hayCambios) confirmarSalida = true else vm.cerrarEditor() }, enabled = !state.cargando)
        AcceptButton("Guardar", onClick = vm::guardar, enabled = !state.cargando)
    }

    if (confirmarSalida) {
        ConfirmDialog(
            title = "Cambios sin guardar",
            message = "¿Salir sin guardar? Se pierden los cambios de este perfil.",
            onConfirm = { confirmarSalida = false; vm.cerrarEditor() },
            onDismiss = { confirmarSalida = false },
            confirmText = "Salir sin guardar",
            destructive = true,
        )
    }
}

/** Una sección dentro de su pestaña: nombre, tipos, orden, mover a otra pestaña y quitar. */
@Composable
private fun SeccionDelPerfil(
    perfil: PerfilHc,
    i: Int,
    j: Int,
    seccion: SeccionPerfil,
    catalogo: List<SeccionCatalogoAdmin>,
    editor: EditorPerfilUi,
    editable: Boolean,
    vm: PerfilesHcViewModel,
) {
    val delCatalogo = catalogo.firstOrNull { it.id == seccion.seccion }
    val errores = editor.errores
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                "Nombre que se muestra", seccion.nombre, { vm.renombrarSeccion(i, j, it) }, Modifier.weight(1f), readOnly = !editable,
                isError = errores.nombreSeccion(i, j).isNotEmpty(), supportingText = errores.nombreSeccion(i, j).unidos(),
            )
            Flechas(j, perfil.pestanias[i].secciones.size, editable, "sección") { vm.moverSeccion(i, j, it) }
            IconButton(onClick = { vm.quitarSeccion(i, j) }, enabled = editable) { Icon(Icons.Default.Delete, contentDescription = "Quitar sección") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            Text(
                "Del catálogo: ${delCatalogo?.nombre ?: seccion.seccion}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            // Las que esta versión no sabe mostrar se conservan tal cual al guardar (RF-16).
            when {
                delCatalogo == null -> StatusChip("No está en el catálogo", Salud360Colors.Danger, pequeno = true)
                !delCatalogo.soportada -> StatusChip("Requiere actualizar la app", Salud360Colors.Warning, pequeno = true)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TipoConsultaHc.entries.forEach { tipo ->
                CheckboxField(tipo.etiqueta(), tipo in seccion.tipos, { vm.marcarTipo(i, j, tipo, it) }, enabled = editable)
            }
            MoverAPestania(perfil, i, editable) { vm.moverSeccionAPestania(i, j, it) }
        }
        Errores(errores.seccion(i, j))
    }
}

/** Subir y bajar un elemento de [cantidad]; la primera no sube y la última no baja. */
@Composable
private fun Flechas(indice: Int, cantidad: Int, editable: Boolean, que: String, onMover: (Int) -> Unit) {
    IconButton(onClick = { onMover(-1) }, enabled = editable && indice > 0) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir $que") }
    IconButton(onClick = { onMover(1) }, enabled = editable && indice < cantidad - 1) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Bajar $que") }
}

@Composable
private fun MoverAPestania(perfil: PerfilHc, actual: Int, editable: Boolean, onMover: (Int) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { abierto = true }, enabled = editable && perfil.pestanias.size > 1) { Text("Mover a otra pestaña") }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            perfil.pestanias.forEachIndexed { k, p ->
                if (k != actual) {
                    // Con el número adelante: dos pestañas pueden tener el mismo nombre mientras no se guarde.
                    DropdownMenuItem(text = { Text("${k + 1}. ${p.nombre.ifBlank { "Pestaña sin nombre" }}") }, onClick = { abierto = false; onMover(k) })
                }
            }
        }
    }
}

/** Solo las del catálogo que la app sabe mostrar y que el perfil todavía no tiene (RF-2, RF-9, RF-16). */
@Composable
private fun AgregarSeccion(disponibles: List<SeccionCatalogoAdmin>, editable: Boolean, onAgregar: (String) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { abierto = true }, enabled = editable && disponibles.isNotEmpty()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(if (disponibles.isEmpty()) "No quedan secciones para agregar" else "Agregar sección")
        }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            disponibles.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.nombre, fontWeight = FontWeight.SemiBold)
                            if (s.descripcion.isNotBlank()) Text(s.descripcion, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { abierto = false; onAgregar(s.id) },
                )
            }
        }
    }
}

/** El catálogo completo con nombre y descripción (RF-1); las que la app no sabe mostrar, marcadas (RF-16). */
@Composable
private fun CatalogoSecciones(catalogo: List<SeccionCatalogoAdmin>, perfil: PerfilHc) {
    val enPerfil = perfil.pestanias.flatMap { p -> p.secciones.map { it.seccion } }.toSet()
    SectionCard("Catálogo de secciones", initiallyExpanded = false) {
        Text("Las secciones salen de este catálogo; en cada perfil se les puede poner otro nombre.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        catalogo.forEach { s ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(s.nombre, fontWeight = FontWeight.SemiBold)
                    if (s.descripcion.isNotBlank()) Text(s.descripcion, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!s.soportada) StatusChip("Requiere actualizar la app", Salud360Colors.Warning, pequeno = true)
                if (s.id in enPerfil) StatusChip("En el perfil", Salud360Colors.TealStart, pequeno = true)
            }
        }
    }
}

@Composable
private fun AvisoSinConexion(texto: String) {
    // Lo que quedó se sigue viendo, pero sin conexión no se puede cambiar nada (RF-14).
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.CloudOff, contentDescription = null, tint = Salud360Colors.Warning)
        Text(texto, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Errores(mensajes: List<String>) {
    mensajes.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

private fun List<String>.unidos(): String? = if (isEmpty()) null else joinToString("\n")

private fun TipoConsultaHc.etiqueta() = when (this) {
    TipoConsultaHc.CONSULTA -> "Consulta"
    TipoConsultaHc.TELEMEDICINA -> "Telemedicina"
}

private fun medicosAsignados(n: Int) = when (n) {
    0 -> "Sin médicos asignados"
    1 -> "1 médico asignado"
    else -> "$n médicos asignados"
}
