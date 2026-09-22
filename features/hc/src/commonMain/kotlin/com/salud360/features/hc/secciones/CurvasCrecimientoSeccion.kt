package com.salud360.features.hc.secciones

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salud360.core.crecimiento.CalculadoraOms
import com.salud360.core.crecimiento.Medida
import com.salud360.core.crecimiento.SexoOms
import com.salud360.core.data.repos.ExamenFisicoFechado
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.pacientes.Sexo
import com.salud360.core.ui.components.EmptyState
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Una medición del paciente ubicada en la curva: edad en meses (decimal) y valor en la unidad de la medida. */
data class PuntoCrecimiento(val edadMeses: Double, val valor: Double)

/** Tramo de edad que muestra el gráfico. */
data class RangoEdad(val etiqueta: String, val desde: Int, val hasta: Int) {
    companion object {
        val todos = listOf(RangoEdad("0-2 años", 0, 24), RangoEdad("0-5 años", 0, 60), RangoEdad("5-19 años", 60, 228))
        fun paraEdad(meses: Int): RangoEdad = when { meses <= 24 -> todos[0]; meses <= 60 -> todos[1]; else -> todos[2] }
    }
}

/** Sexo del paciente en términos de la referencia OMS (solo M/F tienen curva). */
fun Sexo?.aSexoOms(): SexoOms? = when (this) { Sexo.M -> SexoOms.M; Sexo.F -> SexoOms.F; else -> null }

/** Talla cargada en metros (≤ 3) o centímetros, siempre en cm. */
fun tallaEnCm(talla: String): Double? = talla.replace(',', '.').toDoubleOrNull()?.let { if (it <= 3) it * 100 else it }?.takeIf { it > 0 }

/** Valor de la medida en un examen físico, en la unidad que usa la referencia OMS. */
fun ExamenFisico.valorDe(medida: Medida): Double? = when (medida) {
    Medida.PESO -> peso.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    Medida.TALLA -> tallaEnCm(talla)
    Medida.PERIMETRO_CEFALICO -> perimetroCefalico.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    Medida.IMC -> {
        val p = peso.replace(',', '.').toDoubleOrNull()
        val t = tallaEnCm(talla)
        if (p != null && t != null) CalculadoraOms.imc(p, t) else null
    }
}

/** Sección "Curvas de crecimiento" (renderKey `curvas_crecimiento`): lo mismo que se ve dentro del examen físico, como sección aparte. */
@Composable
fun CurvasCrecimientoSeccion(s: SeccionDef, ctx: SeccionContext) {
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "examen"), initiallyExpanded = s.inicialmenteExpandida) {
        CurvasCrecimiento(ctx)
    }
}

/**
 * Curvas OMS con los exámenes físicos históricos del paciente. Selector de medida y de tramo de edad.
 * Requiere fecha de nacimiento y sexo M/F; si faltan, lo indica.
 */
@Composable
fun CurvasCrecimiento(ctx: SeccionContext) {
    val ui by ctx.vm.ui.collectAsState()
    val paciente = ui.paciente
    val nacimiento = paciente?.fechaNacimiento
    val sexo = paciente?.sexo.aSexoOms()
    if (nacimiento == null || sexo == null) {
        EmptyState("Para ver las curvas de crecimiento hace falta la fecha de nacimiento y el sexo del paciente (masculino o femenino).")
        return
    }
    val examenes by ctx.vm.examenesFisicos().collectAsState(emptyList())
    val edadActual = ctx.edadMeses ?: 0
    var medida by remember { mutableStateOf(Medida.PESO) }
    var rango by remember(edadActual) { mutableStateOf(RangoEdad.paraEdad(edadActual)) }
    val medidas = Medida.entries.filter { it != Medida.PERIMETRO_CEFALICO || edadActual <= CalculadoraOms.edadMaximaMeses(Medida.PERIMETRO_CEFALICO) }
    val rangoEfectivo = rango.let { r -> val max = CalculadoraOms.edadMaximaMeses(medida); if (r.desde >= max) RangoEdad.paraEdad(max) else r.copy(hasta = minOf(r.hasta, max)) }
    val puntos = remember(examenes, medida, nacimiento) { puntosDe(examenes, medida, nacimiento) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            medidas.forEach { m -> FilterChip(selected = medida == m, onClick = { medida = m }, label = { Text(m.etiqueta) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RangoEdad.todos.filter { it.desde < CalculadoraOms.edadMaximaMeses(medida) }.forEach { r ->
                FilterChip(selected = rango.etiqueta == r.etiqueta, onClick = { rango = r }, label = { Text(r.etiqueta) })
            }
        }
        GraficoCrecimiento(medida, sexo, rangoEfectivo, puntos, Salud360Colors.especialidad(ctx.especialidad))
        val ultimo = puntos.filter { it.edadMeses in rangoEfectivo.desde.toDouble()..rangoEfectivo.hasta.toDouble() }.maxByOrNull { it.edadMeses }
        val texto = ultimo?.let { p -> CalculadoraOms.zScoreMeses(medida, sexo, p.edadMeses, p.valor)?.let { "Última medición: ${formatear(p.valor)} ${medida.unidad} → ${it.etiqueta} (z ${formatear(it.z, 2)})" } }
        Text(texto ?: "Sin mediciones de ${medida.etiqueta.lowercase()} en este tramo. Referencia OMS 2006/2007, P3 · P15 · P50 · P85 · P97.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Convierte los exámenes físicos históricos en puntos (edad decimal en meses, valor). */
fun puntosDe(examenes: List<ExamenFisicoFechado>, medida: Medida, nacimiento: LocalDate): List<PuntoCrecimiento> =
    examenes.mapNotNull { e ->
        val valor = e.examen.valorDe(medida) ?: return@mapNotNull null
        val dias = nacimiento.daysUntil(e.fecha)
        if (dias < 0) null else PuntoCrecimiento(dias / CalculadoraOms.DIAS_POR_MES, valor)
    }.sortedBy { it.edadMeses }

private fun formatear(v: Double, decimales: Int = 1): String {
    var factor = 1.0
    repeat(decimales) { factor *= 10 }
    val r = (v * factor).roundToInt() / factor
    return if (decimales == 0) r.roundToInt().toString() else r.toString()
}

/**
 * Gráfico de una medida contra la edad: curvas de referencia OMS (P3, P15, P50, P85, P97) y los puntos del paciente.
 * Dibujado con Canvas, sin librerías externas, para que funcione igual en Android, iOS y Web.
 */
@Composable
fun GraficoCrecimiento(medida: Medida, sexo: SexoOms, rango: RangoEdad, puntos: List<PuntoCrecimiento>, colorPaciente: Color, modifier: Modifier = Modifier) {
    val medidor = rememberTextMeasurer()
    val colorTexto = MaterialTheme.colorScheme.onSurfaceVariant
    val colorGrilla = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val colorCurva = Salud360Colors.TealStart
    val estilo = TextStyle(fontSize = 10.sp, color = colorTexto)

    // Curvas: una muestra por mes dentro del rango.
    val curvas = remember(medida, sexo, rango) {
        CalculadoraOms.curvasReferencia.map { (nombre, z) ->
            nombre to (rango.desde..rango.hasta).mapNotNull { mes -> CalculadoraOms.valorEnZ(medida, sexo, mes.toDouble(), z)?.let { mes.toDouble() to it } }
        }
    }
    val visibles = puntos.filter { it.edadMeses >= rango.desde && it.edadMeses <= rango.hasta }
    val valores = curvas.flatMap { it.second.map { p -> p.second } } + visibles.map { it.valor }
    if (valores.isEmpty()) { EmptyState("Sin curva de referencia para este tramo"); return }
    val pasoY = pasoBonito((valores.max() - valores.min()) / 6)
    val yMin = floor(valores.min() / pasoY) * pasoY
    val yMax = ceil(valores.max() / pasoY) * pasoY
    val pasoX = when { rango.hasta - rango.desde <= 24 -> 2; rango.hasta - rango.desde <= 60 -> 6; else -> 12 }

    Canvas(modifier.fillMaxWidth().height(280.dp)) {
        val izq = 44.dp.toPx(); val der = 34.dp.toPx(); val arriba = 10.dp.toPx(); val abajo = 26.dp.toPx()
        val ancho = size.width - izq - der
        val alto = size.height - arriba - abajo
        fun x(meses: Double) = izq + ((meses - rango.desde) / (rango.hasta - rango.desde).toDouble()).toFloat() * ancho
        fun y(v: Double) = arriba + (1 - ((v - yMin) / (yMax - yMin)).toFloat()) * alto

        // grilla y ejes
        var v = yMin
        while (v <= yMax + 1e-9) {
            drawLine(colorGrilla, Offset(izq, y(v)), Offset(izq + ancho, y(v)), strokeWidth = 1f)
            drawText(medidor, formatear(v, if (pasoY < 1) 1 else 0), topLeft = Offset(2f, y(v) - 7.sp.toPx()), style = estilo)
            v += pasoY
        }
        var mes = rango.desde
        while (mes <= rango.hasta) {
            drawLine(colorGrilla, Offset(x(mes.toDouble()), arriba), Offset(x(mes.toDouble()), arriba + alto), strokeWidth = 1f)
            val etiqueta = if (rango.hasta > 60) "${mes / 12}a" else if (mes % 12 == 0 && rango.hasta > 24) "${mes / 12}a" else "$mes"
            drawText(medidor, etiqueta, topLeft = Offset(x(mes.toDouble()) - 6f, arriba + alto + 4f), style = estilo)
            mes += pasoX
        }
        drawLine(colorTexto, Offset(izq, arriba), Offset(izq, arriba + alto), strokeWidth = 1.5f)
        drawLine(colorTexto, Offset(izq, arriba + alto), Offset(izq + ancho, arriba + alto), strokeWidth = 1.5f)
        drawText(medidor, if (rango.hasta > 24) "edad (años)" else "edad (meses)", topLeft = Offset(izq + ancho - 60f, arriba + alto + 14.sp.toPx()), style = estilo)

        // curvas de referencia
        curvas.forEach { (nombre, pts) ->
            if (pts.size < 2) return@forEach
            val path = Path()
            pts.forEachIndexed { i, (m, valor) -> if (i == 0) path.moveTo(x(m), y(valor)) else path.lineTo(x(m), y(valor)) }
            val esMediana = nombre == "P50"
            val extremo = nombre == "P3" || nombre == "P97"
            drawPath(path, colorCurva.copy(alpha = if (esMediana) 0.95f else 0.6f), style = Stroke(width = if (esMediana) 2.5f else 1.5f, pathEffect = if (extremo) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null))
            val (um, uv) = pts.last()
            drawText(medidor, nombre, topLeft = Offset(x(um) + 3f, y(uv) - 6.sp.toPx()), style = estilo.copy(color = colorCurva))
        }

        // paciente
        if (visibles.size >= 2) {
            val path = Path()
            visibles.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.edadMeses), y(p.valor)) else path.lineTo(x(p.edadMeses), y(p.valor)) }
            drawPath(path, colorPaciente, style = Stroke(width = 2f))
        }
        visibles.forEach { p ->
            drawCircle(Color.White, radius = 5f, center = Offset(x(p.edadMeses), y(p.valor)))
            drawCircle(colorPaciente, radius = 4f, center = Offset(x(p.edadMeses), y(p.valor)))
        }
    }
}

/** Paso de grilla "redondo" (1, 2, 5 × 10^n) cercano al pedido. */
private fun pasoBonito(aprox: Double): Double {
    if (aprox <= 0) return 1.0
    var magnitud = 1.0
    while (magnitud * 10 <= aprox) magnitud *= 10
    while (magnitud > aprox) magnitud /= 10
    val r = aprox / magnitud
    return magnitud * when { r <= 1 -> 1.0; r <= 2 -> 2.0; r <= 5 -> 5.0; else -> 10.0 }
}
