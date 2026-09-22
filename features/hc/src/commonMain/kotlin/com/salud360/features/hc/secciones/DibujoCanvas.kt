package com.salud360.features.hc.secciones

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.CancelButton
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.components.TextField
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Trazo(val color: Long, val puntos: List<Float>, val referencia: String = "")

private val json = Json { ignoreUnknownKeys = true }

/**
 * Lienzo de dibujo sobre una imagen de fondo (esquema PAP de ginecología, silueta corporal de
 * hematología). Reemplaza al canvas + `signature_pad.js`; los trazos se guardan como JSON
 * (coordenadas normalizadas 0..1) en la sección, junto a una referencia por color y el resultado.
 */
@Composable
fun DibujoSeccion(s: SeccionDef, ctx: SeccionContext, fondo: Painter?, colorInicial: Color = Color(0xFFD100A4), campoResultado: String? = "resultado") {
    val valorTrazos = ctx.valor(s.id, "trazos")
    var trazos by remember(valorTrazos.hashCode()) { mutableStateOf(runCatching { json.decodeFromString<List<Trazo>>(valorTrazos) }.getOrDefault(emptyList())) }
    var color by remember { mutableStateOf(colorInicial) }
    var actual by remember { mutableStateOf<List<Float>>(emptyList()) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(trazos, dirty) { if (dirty) { delay(500); ctx.setValor(s.id, "trazos", json.encodeToString(trazos)); dirty = false } }
    val paleta = listOf(colorInicial, Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA), Color.Black)
    val resultado = campoResultado?.let { ctx.valor(s.id, it) } ?: ""

    if (ctx.soloLectura && trazos.isEmpty() && resultado.isBlank()) { SeccionVacia(); return }
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "dibujo"), initiallyExpanded = s.inicialmenteExpandida) {
        if (!ctx.soloLectura) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Color:", style = MaterialTheme.typography.labelLarge)
            paleta.forEach { c ->
                Box(Modifier.size(28.dp).clip(CircleShape).background(c).border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.onSurface else Color.Gray, CircleShape).clickable { color = c })
            }
            LinkButton("Deshacer", onClick = { trazos = trazos.dropLast(1); dirty = true })
            CancelButton("Borrar todo", onClick = { trazos = emptyList(); dirty = true })
        }
        Box(
            Modifier.size(320.dp, 380.dp).border(1.dp, MaterialTheme.colorScheme.outline).background(Color.White)
                .then(if (ctx.soloLectura) Modifier else Modifier.pointerInput(color) {
                    detectDragGestures(
                        onDragStart = { o -> actual = listOf(o.x / size.width, o.y / size.height) },
                        onDrag = { change, _ -> actual = actual + listOf(change.position.x / size.width, change.position.y / size.height) },
                        onDragEnd = { if (actual.size >= 4) { trazos = trazos + Trazo(color.value.toLong(), actual); dirty = true }; actual = emptyList() },
                    )
                }),
        ) {
            if (fondo != null) Image(fondo, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Canvas(Modifier.fillMaxSize()) {
                fun dibujar(puntos: List<Float>, c: Color) {
                    if (puntos.size < 4) return
                    val path = Path().apply {
                        moveTo(puntos[0] * size.width, puntos[1] * size.height)
                        for (i in 2 until puntos.size step 2) lineTo(puntos[i] * size.width, puntos[i + 1] * size.height)
                    }
                    drawPath(path, c, style = Stroke(width = 5f, cap = StrokeCap.Round))
                }
                trazos.forEach { t -> dibujar(t.puntos, Color(t.color.toULong())) }
                dibujar(actual, color)
            }
        }
        // referencias por color (como en el PAP original)
        trazos.map { it.color }.distinct().forEach { c ->
            var ref by remember(c, ctx.valor(s.id, "ref_$c")) { mutableStateOf(ctx.valor(s.id, "ref_$c")) }
            if (!ctx.soloLectura || ref.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color(c.toULong())))
                TextField("Referencia", ref, { ref = it; ctx.setValor(s.id, "ref_$c", it) }, Modifier.width(360.dp), readOnly = ctx.soloLectura)
            }
        }
        if (campoResultado != null && (!ctx.soloLectura || resultado.isNotBlank())) {
            TextAreaField("Descripción / resultado", resultado, { ctx.setValor(s.id, campoResultado, it) }, minLines = 3, readOnly = ctx.soloLectura)
        }
    }
}
