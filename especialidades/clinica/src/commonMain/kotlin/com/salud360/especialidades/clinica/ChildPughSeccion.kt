package com.salud360.especialidades.clinica

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.RadioGroupField
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.SeccionContext

private val PARAMETROS = listOf(
    "ascitis" to Triple("Ascitis", "Ausente (I)", listOf("Moderada (II)", "Severa (III) o refractaria")),
    "bilirrubina" to Triple("Bilirrubina total (mg/dl)", "≤ 2", listOf("2 - 3", "> 3")),
    "albumina" to Triple("Albúmina (g/dl)", "> 3,5", listOf("2,8 - 3,5", "< 2,8")),
    "tp" to Triple("TP (seg sobre control) / RIN", "1-3 / < 1,8", listOf("4-6 / 1,8-2,3", "> 6 / > 2,3")),
    "encefalopatia" to Triple("Encefalopatía", "No", listOf("Grado I/II", "Grado III/IV o refractaria")),
)

/** Score Child-Pugh-Turcotte: cada parámetro suma 1, 2 o 3 puntos; A ≤ 6, B 7-9, C ≥ 10. */
@Composable
fun ChildPughSeccion(s: SeccionDef, ctx: SeccionContext) {
    SectionCard(s.titulo, initiallyExpanded = s.inicialmenteExpandida) {
        var total = 0
        var completos = 0
        PARAMETROS.forEach { (clave, def) ->
            val (titulo, uno, resto) = def
            val opciones = listOf("$uno (1)", "${resto[0]} (2)", "${resto[1]} (3)")
            val valor = ctx.valor(s.id, clave)
            valor.toIntOrNull()?.let { total += it; completos++ }
            RadioGroupField(titulo, valor.toIntOrNull()?.let { opciones[it - 1] }, opciones, { sel -> ctx.setValor(s.id, clave, (opciones.indexOf(sel) + 1).toString()) }, enabled = !ctx.soloLectura)
        }
        val clase = when { completos < PARAMETROS.size -> "—"; total <= 6 -> "A"; total <= 9 -> "B"; else -> "C" }
        Text("Puntaje: $total · Clase $clase", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Salud360Colors.Indigo)
        Text("A: ≤ 6 · B: 7-9 · C: ≥ 10", style = MaterialTheme.typography.labelMedium)
    }
}
