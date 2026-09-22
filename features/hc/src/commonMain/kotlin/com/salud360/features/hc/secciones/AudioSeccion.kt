package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.CancelButton
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.platform.abrirArchivo
import com.salud360.features.hc.platform.rememberAudioRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Grabación de audio de la consulta (reemplaza a `grabacion_audio.js`): grabar / pausar / detener,
 * cronómetro, corte automático a las 3 horas y lista de grabaciones con reproducción.
 * La transcripción se hace en el servidor (opcional, ver módulo `server`).
 */
@Composable
fun AudioSeccion(s: SeccionDef, ctx: SeccionContext) {
    val recorder = rememberAudioRecorder()
    val grabaciones by ctx.vm.archivos(s.id).collectAsState(emptyList())
    var grabando by remember { mutableStateOf(false) }
    var pausado by remember { mutableStateOf(false) }
    var segundos by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(grabando, pausado) {
        while (grabando && !pausado) {
            delay(1000); segundos++
            if (segundos >= 3 * 3600) { // corte de seguridad
                recorder.detener()?.let { ctx.vm.agregarArchivo(s.id, it.nombre, it.bytes, duracionMs = segundos * 1000L) }
                grabando = false; segundos = 0
            }
        }
    }

    if (ctx.soloLectura && grabaciones.isEmpty()) { SeccionVacia(); return }
    SectionCard(s.titulo, icon = Icons.Default.Mic, initiallyExpanded = s.inicialmenteExpandida) {
        if (!recorder.disponible) Text("La grabación de audio no está disponible en este dispositivo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (!ctx.soloLectura) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!grabando) PillButton("Grabar", icon = Icons.Default.Mic, onClick = { scope.launch { recorder.iniciar(); grabando = true; pausado = false; segundos = 0 } })
            else {
                if (!pausado) AcceptButton("Pausar", onClick = { recorder.pausar(); pausado = true })
                else AcceptButton("Reanudar", onClick = { recorder.reanudar(); pausado = false })
                CancelButton("Detener y guardar", onClick = {
                    scope.launch {
                        recorder.detener()?.let { ctx.vm.agregarArchivo(s.id, it.nombre, it.bytes, duracionMs = segundos * 1000L) }
                        grabando = false; segundos = 0
                    }
                })
                Text(formatoTiempo(segundos), style = MaterialTheme.typography.titleMedium, color = if (pausado) Salud360Colors.Warning else Salud360Colors.Danger)
            }
        }
        if (grabaciones.isEmpty()) Text("Sin grabaciones", color = MaterialTheme.colorScheme.onSurfaceVariant)
        grabaciones.forEach { a ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Audiotrack, contentDescription = null, tint = Salud360Colors.Indigo)
                Spacer(Modifier.width(8.dp))
                Text("${a.nombre} · ${formatoTiempo((a.duracionMs / 1000).toInt())}", modifier = Modifier.weight(1f))
                LinkButton("Reproducir", onClick = { scope.launch { ctx.vm.bytesDe(a)?.let { abrirArchivo(a.nombre, a.mime, it) } } })
                if (!ctx.soloLectura) LinkButton("Eliminar", onClick = { ctx.vm.eliminarArchivo(a.id) })
            }
        }
    }
}

fun formatoTiempo(segundos: Int): String {
    val h = segundos / 3600; val m = (segundos % 3600) / 60; val s = segundos % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}
