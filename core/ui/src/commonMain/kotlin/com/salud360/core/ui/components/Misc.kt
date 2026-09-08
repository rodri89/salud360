package com.salud360.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.salud360.core.ui.theme.Salud360Colors

/** Barra de búsqueda (DNI, apellido o nombre). */
@Composable
fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Buscar por DNI, apellido o nombre",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = CircleShape,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Chip de estado con color de fondo suave. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = 0.15f),
        contentColor = color,
        shape = CircleShape,
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

enum class SlotState { LIBRE, OCUPADO, BLOQUEADO, FUERA_VENTANA, SOBRETURNO }

/**
 * Círculo de horario de la agenda (equivalente a `.circulo` / `.circulo_ocupado` / `.circulo_fuera_ventana`).
 */
@Composable
fun TimeSlotCircle(
    horario: String,
    state: SlotState,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val border = when (state) {
        SlotState.LIBRE -> Salud360Colors.SlotFree
        SlotState.OCUPADO -> Salud360Colors.SlotBusy
        SlotState.BLOQUEADO -> Salud360Colors.SlotBlocked
        SlotState.FUERA_VENTANA -> Salud360Colors.SlotOutOfWindow
        SlotState.SOBRETURNO -> Salud360Colors.Warning
    }
    Column(
        modifier = modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(3.dp, border, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(horario, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

/** Avatar circular con iniciales (reemplaza `medico_sin_foto.png`). */
@Composable
fun InitialsAvatar(name: String, modifier: Modifier = Modifier, size: Int = 40, color: Color = Salud360Colors.TealStart) {
    val initials = name.split(" ", ",").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier = modifier.size(size.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier, text: String = "Cargando...") {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Diálogo de confirmación genérico (reemplaza a los modales de Bootstrap). */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Aceptar",
    dismissText: String = "Cancelar",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (destructive) Salud360Colors.Danger else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}

/** Título de pantalla con subtítulo opcional (equivalente a `#static_header` del topbar). */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Par etiqueta/valor de solo lectura (ficha del paciente). */
@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, color = valueColor)
    }
}

/** Fila de acciones alineadas a la derecha. */
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
