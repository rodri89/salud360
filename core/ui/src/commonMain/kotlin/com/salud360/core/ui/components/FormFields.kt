package com.salud360.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Campo de texto de una línea con etiqueta. */
@Composable
fun TextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    supportingText: String? = null,
    mostrarCopiar: Boolean = false,
    onCopiado: (() -> Unit)? = null,
) {
    val portapapeles = LocalClipboardManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        readOnly = readOnly,
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        trailingIcon = if (mostrarCopiar && value.isNotEmpty()) {
            { IconButton(onClick = { portapapeles.setText(AnnotatedString(value)); onCopiado?.invoke() }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copiar") } }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Campo numérico (peso, talla, valores de laboratorio). Acepta texto libre para permitir rangos como "12-14". */
@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    readOnly: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        readOnly = readOnly,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Área de texto multilínea (equivalente a los `<textarea rows=6>` de las secciones). */
@Composable
fun TextAreaField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 4,
    readOnly: Boolean = false,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        readOnly = readOnly,
        minLines = minLines,
        modifier = modifier.fillMaxWidth().heightIn(min = (minLines * 24).dp),
    )
}

/** Selector de fecha con calendario en español (reemplaza a bootstrap-datepicker). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: LocalDate?,
    onValueChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value?.toDisplay() ?: "",
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = { if (!readOnly) open = true }) {
                Icon(Icons.Default.CalendarMonth, contentDescription = "Elegir fecha")
            }
        },
        modifier = modifier.fillMaxWidth().clickable(enabled = !readOnly) { open = true },
    )
    // Calendario propio (ver CalendarioDialog): el DatePicker de Material 3 en web mostraba el mes corrido.
    if (open) CalendarioDialog(inicial = value, onElegida = onValueChange, onCerrar = { open = false })
}

/** Lista desplegable de opciones fijas (equivalente a `<select>`). */
@Composable
fun SelectField(
    label: String,
    value: String?,
    options: List<String>,
    onValueChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    allowEmpty: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedTextField(
            value = value ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                IconButton(onClick = { if (!readOnly) expanded = true }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth().clickable(enabled = !readOnly) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowEmpty) {
                DropdownMenuItem(text = { Text("—") }, onClick = { onValueChange(null); expanded = false })
            }
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false })
            }
        }
    }
}

/** Casilla con etiqueta. */
@Composable
fun CheckboxField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.clickable(enabled = enabled) { onCheckedChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Casilla con detalle: al marcarla aparece un área de texto (patrón "flag + detalle" de antecedentes). */
@Composable
fun CheckboxWithDetail(
    label: String,
    checked: Boolean,
    detail: String,
    onCheckedChange: (Boolean) -> Unit,
    onDetailChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    Column(modifier) {
        CheckboxField(label = label, checked = checked, onCheckedChange = onCheckedChange, enabled = !readOnly)
        if (checked) {
            TextAreaField(
                label = "Detalle de $label",
                value = detail,
                onValueChange = onDetailChange,
                minLines = 2,
                readOnly = readOnly,
                modifier = Modifier.padding(start = 40.dp),
            )
        }
    }
}

/** Grupo de radios horizontales (SI / NO, o cualquier lista corta). */
@Composable
fun RadioGroupField(
    label: String,
    value: String?,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(enabled = enabled) { onValueChange(option) },
                ) {
                    RadioButton(selected = value == option, onClick = { onValueChange(option) }, enabled = enabled)
                    Text(option)
                }
            }
        }
    }
}

@Composable
fun SiNoField(label: String, value: Boolean?, onValueChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    RadioGroupField(
        label = label,
        value = when (value) { true -> "SI"; false -> "NO"; null -> null },
        options = listOf("SI", "NO"),
        onValueChange = { onValueChange(it == "SI") },
        modifier = modifier,
        enabled = enabled,
    )
}

/** Fila de formulario que acomoda varios campos en una línea (en pantallas anchas) usando pesos iguales. */
@Composable
fun FormRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/**
 * Como [FormRow], pero en pantallas angostas (celular) apila los campos uno abajo del otro a ancho
 * completo en vez de ponerlos en una fila. [ancho] es el mismo corte que ya usa el shell principal
 * (>= 840dp) para elegir entre barra lateral y barra inferior.
 */
@Composable
fun FormRowResponsivo(ancho: Boolean, modifier: Modifier = Modifier, pesos: List<Float> = emptyList(), campos: List<@Composable () -> Unit>) {
    if (ancho) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            campos.forEachIndexed { i, campo -> Box(Modifier.weight(pesos.getOrElse(i) { 1f })) { campo() } }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            campos.forEach { campo -> campo() }
        }
    }
}

// ---- utilidades de fecha ----

fun LocalDate.toDisplay(): String {
    val d = day.toString().padStart(2, '0')
    val m = month.number.toString().padStart(2, '0')
    return "$d/$m/$year"
}

fun LocalDate.toEpochMillis(): Long = toEpochDays() * 86_400_000L

fun Long.toLocalDate(): LocalDate =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date
