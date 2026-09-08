package com.salud360.features.hc.secciones

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.hc.Archivo
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import com.salud360.features.hc.platform.abrirArchivo
import com.salud360.features.hc.platform.decodificarImagen
import com.salud360.features.hc.platform.rememberFilePicker
import kotlinx.coroutines.launch

/** Sección de adjuntos (fotos de estudios, HC digitalizada, PDF). */
@Composable
fun ArchivosSeccion(s: SeccionDef, ctx: SeccionContext) {
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "archivos"), initiallyExpanded = s.inicialmenteExpandida) {
        ArchivosInline(s.id, ctx, porPaciente = s.id == "familigrama")
    }
}

/** Galería + botón "Agregar" reutilizable dentro de cualquier sección. */
@Composable
fun ArchivosInline(seccion: String, ctx: SeccionContext, registroId: Id? = null, soloLectura: Boolean = ctx.soloLectura, porPaciente: Boolean = false) {
    val archivos by (if (registroId != null) ctx.vm.archivosDeRegistro(registroId) else ctx.vm.archivos(seccion, porPaciente)).collectAsState(emptyList())
    val picker = rememberFilePicker(listOf("image/*", "application/pdf")) { ctx.vm.agregarArchivo(seccion, it.nombre, it.bytes, registroId, porPaciente = porPaciente) }
    var borrar by remember { mutableStateOf<Archivo?>(null) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (archivos.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(archivos, key = { it.id }) { a ->
                ArchivoMiniatura(a, ctx, onAbrir = { scope.launch { ctx.vm.bytesDe(a)?.let { abrirArchivo(a.nombre, a.mime, it) } } }, onBorrar = if (soloLectura) null else ({ borrar = a }))
            }
        }
        if (!soloLectura) ActionRow { AcceptButton("Agregar foto o PDF", onClick = picker) }
    }
    borrar?.let { a -> ConfirmDialog("Eliminar archivo", "¿Eliminar ${a.nombre}?", onConfirm = { ctx.vm.eliminarArchivo(a.id); borrar = null }, onDismiss = { borrar = null }, confirmText = "Eliminar", destructive = true) }
}

@Composable
fun ArchivoMiniatura(a: Archivo, ctx: SeccionContext, onAbrir: () -> Unit, onBorrar: (() -> Unit)?) {
    var bitmap by remember(a.id, a.rutaLocal) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(a.id, a.rutaLocal) { if (a.esImagen) bitmap = ctx.vm.bytesDe(a)?.let { decodificarImagen(it) } }
    Card(modifier = Modifier.width(150.dp).clickable(onClick = onAbrir)) {
        Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
            val bmp = bitmap
            when {
                bmp != null -> Image(bmp, contentDescription = a.nombre, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(110.dp))
                a.esPdf -> Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Salud360Colors.Danger, modifier = Modifier.size(48.dp))
                a.esAudio -> Icon(Icons.Default.Audiotrack, contentDescription = null, tint = Salud360Colors.Indigo, modifier = Modifier.size(48.dp))
                else -> Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (a.subido) Icons.Default.CloudDone else Icons.Default.CloudOff, contentDescription = null, tint = if (a.subido) Salud360Colors.Success else Salud360Colors.Warning, modifier = Modifier.size(16.dp))
            Text(a.nombre, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (onBorrar != null) IconButton(onClick = onBorrar, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Delete, contentDescription = "Eliminar", modifier = Modifier.size(16.dp)) }
        }
    }
}
