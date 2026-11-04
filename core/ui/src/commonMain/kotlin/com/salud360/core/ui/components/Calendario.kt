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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
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
    var vista by remember { mutableStateOf(Vista.DIAS) }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(elegida?.toDisplay() ?: "Elegí una fecha") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Las flechas mueven el mes, y solo tienen sentido en la grilla de días.
                    if (vista == Vista.DIAS) {
                        IconButton(onClick = { mesVisible = mesVisible.minus(1, DateTimeUnit.MONTH) }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Mes anterior") }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                    // El título abre la elección de año. Una fecha de nacimiento está a veces cuarenta
                    // años atrás, y llegar mes por mes son cientos de toques.
                    Row(
                        Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                            .clickable { vista = if (vista == Vista.DIAS) Vista.ANIOS else Vista.DIAS },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when (vista) {
                                Vista.DIAS -> "${MESES[mesVisible.month.number - 1]} ${mesVisible.year}"
                                Vista.MESES -> "Mes de ${mesVisible.year}"
                                Vista.ANIOS -> "Elegí el año"
                            },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        )
                        Icon(if (vista == Vista.DIAS) Icons.Default.ArrowDropDown else Icons.Default.ArrowDropUp, contentDescription = "Elegir año")
                    }
                    if (vista == Vista.DIAS) {
                        IconButton(onClick = { mesVisible = mesVisible.plus(1, DateTimeUnit.MONTH) }) { Icon(Icons.Default.ChevronRight, contentDescription = "Mes siguiente") }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }

                when (vista) {
                    Vista.ANIOS -> GrillaAnios(mesVisible.year, hoy.year) { anio ->
                        mesVisible = LocalDate(anio, mesVisible.month, 1)
                        vista = Vista.MESES
                    }
                    Vista.MESES -> GrillaMeses(mesVisible.month.number) { mes ->
                        mesVisible = LocalDate(mesVisible.year, mes, 1)
                        vista = Vista.DIAS
                    }
                    Vista.DIAS -> {
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
                }
            }
        },
        confirmButton = { TextButton(onClick = { onElegida(elegida); onCerrar() }, enabled = elegida != null) { Text("Aceptar") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCerrar) { Text("Cancelar") }
                TextButton(onClick = { elegida = hoy; mesVisible = LocalDate(hoy.year, hoy.month, 1); vista = Vista.DIAS }) { Text("Hoy") }
                if (permitirBorrar && inicial != null) TextButton(onClick = { onElegida(null); onCerrar() }) { Text("Borrar") }
            }
        },
    )
}

/** Qué se está eligiendo en el calendario: el día, el mes o el año. */
private enum class Vista { DIAS, MESES, ANIOS }

/**
 * Años para elegir, del más reciente al más viejo, con el año en pantalla ya visible al abrir.
 *
 * El rango arranca 120 años atrás, que cubre a cualquier paciente vivo, y llega hasta cinco años
 * adelante, que alcanza para una fecha de consulta o un turno futuro.
 */
@Composable
private fun GrillaAnios(anioVisible: Int, anioHoy: Int, onElegido: (Int) -> Unit) {
    val anios = remember(anioHoy) { ((anioHoy + 5) downTo (anioHoy - 120)).toList() }
    val indice = remember(anioVisible, anios) { anios.indexOf(anioVisible).coerceAtLeast(0) }
    val estado = rememberLazyGridState(initialFirstVisibleItemIndex = (indice - 4).coerceAtLeast(0))
    LazyVerticalGrid(columns = GridCells.Fixed(4), state = estado, modifier = Modifier.fillMaxWidth().height(240.dp)) {
        items(anios) { a -> CeldaTexto(a.toString(), a == anioVisible) { onElegido(a) } }
    }
}

/** Los doce meses del año en pantalla, en una grilla de tres columnas. */
@Composable
private fun GrillaMeses(mesVisible: Int, onElegido: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().height(240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MESES.chunked(3).forEachIndexed { fila, nombres ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                nombres.forEachIndexed { columna, nombre ->
                    val mes = fila * 3 + columna + 1
                    Box(Modifier.weight(1f)) { CeldaTexto(nombre.take(3), mes == mesVisible) { onElegido(mes) } }
                }
            }
        }
    }
}

/** Celda de año o de mes, con el actual resaltado. */
@Composable
private fun CeldaTexto(texto: String, seleccionado: Boolean, onClick: () -> Unit) {
    val fondo = if (seleccionado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val color = if (seleccionado) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier.fillMaxWidth().height(44.dp).clip(MaterialTheme.shapes.small).background(fondo).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = if (seleccionado) FontWeight.SemiBold else FontWeight.Normal)
    }
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
