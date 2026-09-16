package com.salud360.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.salud360.core.ui.theme.Salud360Colors

/**
 * Botón primario "píldora" con el degradado de marca (equivalente a `.rodri_button`).
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (enabled) Salud360Colors.BrandGradient else androidx.compose.ui.graphics.SolidColor(Salud360Colors.Grey))
            .border(1.dp, if (enabled) Salud360Colors.NeonGreen.copy(alpha = 0.7f) else Color.Transparent, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Botón secundario blanco con borde verde (equivalente a `.rodri_button_aceptar`). */
@Composable
fun AcceptButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = CircleShape,
        border = BorderStroke(1.5.dp, Salud360Colors.Success),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Salud360Colors.SuccessDark),
    ) { Text(text) }
}

/** Botón de cancelar blanco con borde rojo (equivalente a `.rodri_button_cancelar`). */
@Composable
fun CancelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = CircleShape,
        border = BorderStroke(1.5.dp, Salud360Colors.Danger),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Salud360Colors.Danger),
    ) {
        if (icon != null) { Icon(icon, contentDescription = null); Spacer(Modifier.width(8.dp)) }
        Text(text)
    }
}

/** Botón "volver" gris (equivalente a `.rodri_button_volver`). */
@Composable
fun BackButton(text: String = "Volver", onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, Salud360Colors.Grey),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) { Text(text) }
}

/** Botón de texto plano para acciones secundarias dentro de tablas y tarjetas. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, color = color)
    }
}
