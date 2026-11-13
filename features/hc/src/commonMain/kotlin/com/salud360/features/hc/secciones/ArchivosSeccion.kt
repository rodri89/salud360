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
import com.salud360.core.data.files.mimeDe
import com.salud360.core.data.hcgeneral.SECCION_DOCUMENTOS
import com.salud360.core.data.hcgeneral.TIPOS_MIME_DE_ADJUNTO
import com.salud360.core.data.hcgeneral.TEXTO_FALTA_ENVIAR
import com.salud360.core.data.hcgeneral.TipoDeAdjunto
import com.salud360.core.data.hcgeneral.avisoDelTipoDeAdjunto
import com.salud360.core.data.hcgeneral.tipoDeAdjunto
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

// Spec 005, T107 (RF-44, RF-45, RF-4): la sección «Documentos» de una consulta propia de la HC general lleva **PDF, JPG, JPEG y PNG**
// (Q-E4-1 revisada), dice el tope antes de subir (`limiteExcedido`, T103) y abre los documentos de la web con la sesión (`bytesDe`, T106).
// La lista sigue Q-E4-3 (`Consulta.documentosDeTodoElPaciente`) y las fotos de los exámenes no se muestran (T105, fuera de alcance). Las
// demás secciones de adjuntos (la HC digitalizada, el familigrama, lo de cada estudio) siguen como estaban.

/** Tipos MIME que ofrece el selector de las secciones que no son «Documentos»: cualquier imagen o un PDF, como siempre. */
private val TIPOS_MIME_DE_SIEMPRE = listOf("image/*", "application/pdf")

/** Sección de adjuntos (fotos de estudios, HC digitalizada, PDF). */
@Composable
fun ArchivosSeccion(s: SeccionDef, ctx: SeccionContext) {
    val esDocumentos = s.id == SECCION_DOCUMENTOS
    val porPaciente = s.id == "familigrama"
    // Q-E4-3 (T106, T107): la sección «Documentos» de una consulta nueva y en curso lista los de todo el paciente; una previa, solo los suyos.
    val lecturaPorPaciente = if (esDocumentos) ctx.vm.documentosDeTodoElPaciente() else porPaciente
    if (ctx.soloLectura && !hayArchivos(s.id, ctx, lecturaPorPaciente)) { SeccionVacia(); return }
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "archivos"), initiallyExpanded = s.inicialmenteExpandida) {
        ArchivosInline(s.id, ctx, porPaciente = porPaciente, lecturaPorPaciente = lecturaPorPaciente)
    }
}

/** Si la sección tiene adjuntos (para decidir si se muestra en modo lectura sin pintar la galería). */
@Composable
fun hayArchivos(seccion: String, ctx: SeccionContext, porPaciente: Boolean = false): Boolean {
    val archivos by ctx.vm.archivos(seccion, porPaciente).collectAsState(emptyList())
    return archivos.isNotEmpty()
}

/**
 * Galería + botón «Agregar» reutilizable dentro de cualquier sección. [porPaciente] es dónde se guarda (una foto del paciente) y
 * [lecturaPorPaciente], dónde se leen: la sección «Documentos» de una consulta nueva los muestra de todo el paciente (T107).
 */
@Composable
fun ArchivosInline(
    seccion: String,
    ctx: SeccionContext,
    registroId: Id? = null,
    soloLectura: Boolean = ctx.soloLectura,
    porPaciente: Boolean = false,
    lecturaPorPaciente: Boolean = porPaciente,
) {
    val esDocumentos = seccion == SECCION_DOCUMENTOS && registroId == null
    val archivos by (if (registroId != null) ctx.vm.archivosDeRegistro(registroId) else ctx.vm.archivos(seccion, lecturaPorPaciente)).collectAsState(emptyList())
    // Las fotos de los registros de examen no son documentos de la sección: no se muestran (T105, fuera de alcance).
    val visibles = if (esDocumentos) archivos.filter { it.registroId == null } else archivos
    var borrar by remember { mutableStateOf<Archivo?>(null) }
    /** El rechazo del archivo elegido (tipo o tope), antes de guardarlo: se queda a la vista hasta el próximo archivo (T107). */
    var avisoDeArchivo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // PDF, JPG, JPEG y PNG en «Documentos» (Q-E4-1 revisada); lo demás, como siempre.
    val picker = rememberFilePicker(if (esDocumentos) TIPOS_MIME_DE_ADJUNTO else TIPOS_MIME_DE_SIEMPRE) { elegido ->
        scope.launch {
            // Antes de guardarlo: que sea de los cuatro tipos y que entre en el tope que informa la web (T103, T107).
            val aviso = if (esDocumentos) {
                avisoDelTipoDeAdjunto(elegido.nombre, mimeDe(elegido.nombre)) ?: ctx.vm.avisoDelTope(elegido.bytes)
            } else {
                null
            }
            if (aviso != null) {
                avisoDeArchivo = aviso
            } else {
                avisoDeArchivo = null
                ctx.vm.agregarArchivo(seccion, elegido.nombre, elegido.bytes, registroId, porPaciente = porPaciente)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visibles.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visibles, key = { it.id }) { a ->
                ArchivoMiniatura(
                    a, ctx,
                    onAbrir = { scope.launch { ctx.vm.bytesDe(a)?.let { abrirArchivo(a.nombre, mimeDelArchivo(a), it) } } },
                    onBorrar = if (soloLectura) null else ({ borrar = a }),
                    // Spec 005, T37/T107: en una consulta que se envía, «Falta enviar» mientras un adjunto **de esta consulta** no esté en la web.
                    // Los documentos de las consultas anteriores de la lista no viajan con esta (RF-46): no se los marca.
                    faltaEnviar = ctx.vm.seEnviaALaWeb() && a.consultaId == ctx.consultaId && !a.subido && a.remotoId.isBlank(),
                )
            }
        }
        if (!soloLectura) {
            ActionRow { AcceptButton(if (esDocumentos) "Agregar PDF o imagen" else "Agregar foto o PDF", onClick = picker) }
            avisoDeArchivo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    borrar?.let { a -> ConfirmDialog("Eliminar archivo", "¿Eliminar ${a.nombre}?", onConfirm = { ctx.vm.eliminarArchivo(a.id); borrar = null }, onDismiss = { borrar = null }, confirmText = "Eliminar", destructive = true) }
}

/** El tipo con el que se abre un adjunto: el de su nombre o `Content-Type`, que para PDF, JPG, JPEG y PNG es el que manda la web (T106). */
private fun mimeDelArchivo(a: Archivo): String = tipoDeAdjunto(a.nombre, a.mime)?.mime ?: a.mime

@Composable
fun ArchivoMiniatura(a: Archivo, ctx: SeccionContext, onAbrir: () -> Unit, onBorrar: (() -> Unit)?, faltaEnviar: Boolean = false) {
    val tipo = tipoDeAdjunto(a.nombre, a.mime)
    // La miniatura, solo para PDF, JPG, JPEG y PNG (T107); lo demás sigue mirando el `mime` como siempre.
    val esImagen = tipo?.let { it != TipoDeAdjunto.PDF } ?: a.esImagen
    var bitmap by remember(a.id, a.rutaLocal) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(a.id, a.rutaLocal) { if (esImagen) bitmap = ctx.vm.bytesDe(a)?.let { decodificarImagen(it) } }
    Card(modifier = Modifier.width(150.dp).clickable(onClick = onAbrir)) {
        Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
            val bmp = bitmap
            when {
                bmp != null -> Image(bmp, contentDescription = a.nombre, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(110.dp))
                tipo == TipoDeAdjunto.PDF || a.esPdf -> Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Salud360Colors.Danger, modifier = Modifier.size(48.dp))
                a.esAudio -> Icon(Icons.Default.Audiotrack, contentDescription = null, tint = Salud360Colors.Indigo, modifier = Modifier.size(48.dp))
                else -> Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Está a salvo tanto si llegó al servidor propio como si llegó a la API de la especialidad.
            val guardado = a.subido || a.remotoId.isNotBlank()
            Icon(if (guardado) Icons.Default.CloudDone else Icons.Default.CloudOff, contentDescription = null, tint = if (guardado) Salud360Colors.Success else Salud360Colors.Warning, modifier = Modifier.size(16.dp))
            Text(a.nombre, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (onBorrar != null) IconButton(onClick = onBorrar, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Delete, contentDescription = "Eliminar", modifier = Modifier.size(16.dp)) }
        }
        if (faltaEnviar) Text(
            TEXTO_FALTA_ENVIAR,
            style = MaterialTheme.typography.labelSmall,
            color = Salud360Colors.Warning,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )
    }
}
