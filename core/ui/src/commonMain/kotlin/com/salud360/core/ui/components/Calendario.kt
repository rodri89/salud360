package com.salud360.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

private val MESES = listOf("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre")
private val DIAS_CORTOS = listOf("L", "M", "M", "J", "V", "S", "D")

/**
 * Calendario mensual propio (lunes a domingo) para elegir una fecha. Se usa en lugar del DatePicker de Material 3
 * porque este último, en web, rotula los meses con la hora local del navegador y en zonas UTC negativas muestra el
 * mes anterior al que realmente está en la grilla (elegir "18 de octubre" devolvía el 18 de noviembre).
 * Trabaja solo con [LocalDate], sin milisegundos ni zonas horarias.
 */
@Composable
fun CalendarioDialog(
    inicial: LocalDate?,
    onElegida: (LocalDate?) -> Unit,
    onCerrar: () -> Unit,
    permitirBorrar: Boolean = true,
) {
    val hoy = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    var mesVisible by remember { mutableStateOf(LocalDate((inicial ?: hoy).year, (inicial ?: hoy).month, 1)) }
    var elegida by remember { mutableStateOf(inicial) }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(elegida?.toDisplay() ?: "Elegí una fecha") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { mesVisible = mesVisible.minus(1, DateTimeUnit.MONTH) }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Mes anterior") }
                    Text(
                        "${MESES[mesVisible.month.number - 1]} ${mesVisible.year}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { mesVisible = mesVisible.plus(1, DateTimeUnit.MONTH) }) { Icon(Icons.Default.ChevronRight, contentDescription = "Mes siguiente") }
                }
                Row(Modifier.fillMaxWidth()) {
                    DIAS_CORTOS.forEach { d ->
                        Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Celdas: huecos hasta el día de la semana del 1 (lunes = 0), luego los días del mes, en filas de 7.
                val primerDia = mesVisible.dayOfWeek.isoDayNumber - 1
                val diasDelMes = mesVisible.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
                val celdas: List<LocalDate?> = List(primerDia) { null } + (1..diasDelMes).map { LocalDate(mesVisible.year, mesVisible.month, it) }
                celdas.chunked(7).forEach { fila ->
                    Row(Modifier.fillMaxWidth()) {
                        fila.forEach { f -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (f != null) DiaCelda(f, f == elegida, f == hoy) { elegida = f } } }
                        repeat(7 - fila.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onElegida(elegida); onCerrar() }, enabled = elegida != null) { Text("Aceptar") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCerrar) { Text("Cancelar") }
                TextButton(onClick = { elegida = hoy; mesVisible = LocalDate(hoy.year, hoy.month, 1) }) { Text("Hoy") }
                if (permitirBorrar && inicial != null) TextButton(onClick = { onElegida(null); onCerrar() }) { Text("Borrar") }
            }
        },
    )
}

@Composable
private fun DiaCelda(f: LocalDate, elegida: Boolean, esHoy: Boolean, onClick: () -> Unit) {
    val fondo = when {
        elegida -> MaterialTheme.colorScheme.primary
        esHoy -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surface
    }
    val texto = if (elegida) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(fondo).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(f.day.toString(), color = texto, style = MaterialTheme.typography.bodyMedium, fontWeight = if (esHoy || elegida) FontWeight.SemiBold else FontWeight.Normal)
    }
    Spacer(Modifier.height(2.dp))
}

/** true si la fecha es lunes a viernes (para resaltar fines de semana si se quisiera). */
fun LocalDate.esDiaHabil(): Boolean = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY
