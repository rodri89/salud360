package com.salud360.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.ui.theme.LocalIsDarkTheme
import com.salud360.core.ui.theme.Salud360Colors

/**
 * Nota interna del paciente ("no cobrar, es familiar del médico"): un punto ámbar al lado del nombre que
 * despliega el texto debajo. El punto va dentro de una fila y el cartel en la columna de arriba, así que
 * el estado de abierto lo mantiene quien llama.
 *
 * Todas las funciones no dibujan nada si la nota está en blanco, para no tener que envolverlas en un `if`.
 * Reciben la nota como texto porque `core:ui` no depende de `core:model`.
 */
private val ColorNota = Salud360Colors.Warning

/** Punto clickeable que abre y cierra la nota. */
@Composable
fun PuntoNota(nota: String, abierta: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    if (nota.isBlank()) return
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = if (abierta) "Ocultar la nota del paciente" else "Ver la nota del paciente" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(if (abierta) 11.dp else 9.dp).clip(CircleShape).background(ColorNota))
    }
}

/** Cartel con la nota, desplegado bajo el nombre. */
@Composable
fun NotaInline(nota: String, abierta: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = abierta && nota.isNotBlank(), modifier = modifier) { CuerpoNota(nota) }
}

/** Nota siempre visible, sin punto (ficha del paciente). */
@Composable
fun NotaPacienteCard(nota: String, modifier: Modifier = Modifier) {
    if (nota.isBlank()) return
    CuerpoNota(nota, modifier)
}

@Composable
private fun CuerpoNota(nota: String, modifier: Modifier = Modifier) {
    val dark = LocalIsDarkTheme.current
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
        color = if (dark) Salud360Colors.NotaBgDark else Salud360Colors.NotaBg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, ColorNota.copy(alpha = if (dark) 0.5f else 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Nota", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = ColorNota)
            Text(nota, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
