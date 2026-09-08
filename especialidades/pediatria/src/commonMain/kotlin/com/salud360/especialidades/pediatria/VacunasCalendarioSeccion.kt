package com.salud360.especialidades.pediatria

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.salud360.core.data.repos.hoy
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.hc.VacunaAplicada
import com.salud360.core.model.newId
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.NumberField
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.secciones.DebouncedText

/** Calendario Nacional de Vacunación (Argentina) tal como estaba en `vacunas.blade.php`. */
object CalendarioVacunas {
    data class Vacuna(val codigo: String, val nombre: String, val edades: Set<Int>)

    /** Filas del calendario: edad en meses (0 = recién nacido; 60 = 5-6 años; 132 = 11 años; 180 = 15 años). */
    val edades: List<Pair<Int, String>> = listOf(
        0 to "Recién nacido", 2 to "2 meses", 3 to "3 meses", 4 to "4 meses", 5 to "5 meses", 6 to "6 meses", 12 to "12 meses", 15 to "15 meses",
        16 to "15-18 meses", 18 to "18 meses", 24 to "24 meses", 60 to "5-6 años", 132 to "11 años", 180 to "15 años",
    )

    val vacunas: List<Vacuna> = listOf(
        Vacuna("bcg", "BCG", setOf(0)),
        Vacuna("hep_b", "Hepatitis B", setOf(0, 2, 4, 6, 132)),
        Vacuna("neumococo", "Neumococo conjugada", setOf(2, 4, 12)),
        Vacuna("quintuple", "Quíntuple (DTP-HB-Hib)", setOf(2, 4, 6)),
        Vacuna("polio_ipv", "Polio IPV", setOf(2, 4, 6, 60)),
        Vacuna("polio_opv", "Polio OPV", setOf(6, 60)),
        Vacuna("rotavirus", "Rotavirus", setOf(2, 4)),
        Vacuna("meningococo", "Meningococo", setOf(3, 5, 15, 132)),
        Vacuna("hep_a", "Hepatitis A", setOf(12)),
        Vacuna("triple_viral", "Triple viral (SRP)", setOf(12, 60)),
        Vacuna("varicela", "Varicela", setOf(15, 60)),
        Vacuna("cuadruple", "Cuádruple / Quíntuple (DTP-Hib)", setOf(16, 18)),
        Vacuna("triple_bacteriana", "Triple bacteriana celular (DTP)", setOf(60)),
        Vacuna("dtpa", "Triple bacteriana acelular (dTpa)", setOf(132)),
        Vacuna("vph", "VPH", setOf(132)),
        Vacuna("doble_bacteriana", "Doble bacteriana (dT)", setOf(180)),
        Vacuna("doble_viral", "Doble viral (SR) / Triple viral", setOf(132, 180)),
        Vacuna("fiebre_amarilla", "Fiebre amarilla", setOf(18, 132)),
        Vacuna("fha", "Fiebre hemorrágica argentina", setOf(180)),
    )
}

/**
 * Calendario de vacunas: filas por edad, columnas por vacuna, celda = casilla; más "Otras" (texto)
 * y el historial de antigripales con fecha y dosis.
 */
@Composable
fun VacunasCalendarioSeccion(s: SeccionDef, ctx: SeccionContext) {
    val ui by ctx.vm.ui.collectAsState()
    val aplicadas = ui.vacunas
    val scroll = rememberScrollState()
    val ro = ctx.soloLectura
    var fechaAntigripal by remember { mutableStateOf(hoy()) }
    var dosis by remember { mutableStateOf("1") }

    fun estado(codigo: String, edad: Int) = aplicadas.firstOrNull { it.vacuna == codigo && it.edadMeses == edad && it.aplicada }

    SectionCard(s.titulo, initiallyExpanded = s.inicialmenteExpandida) {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Column {
                Row(Modifier.clip(MaterialTheme.shapes.small).background(Salud360Colors.BrandGradient).padding(vertical = 6.dp)) {
                    Text("Edad", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp).padding(horizontal = 6.dp))
                    CalendarioVacunas.vacunas.forEach { v ->
                        Text(v.nombre, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(92.dp).padding(horizontal = 2.dp))
                    }
                }
                CalendarioVacunas.edades.forEachIndexed { i, (meses, etiqueta) ->
                    Row(Modifier.background(if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(etiqueta, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp).padding(horizontal = 6.dp))
                        CalendarioVacunas.vacunas.forEach { v ->
                            Box(Modifier.width(92.dp), contentAlignment = Alignment.Center) {
                                if (meses in v.edades) {
                                    val actual = estado(v.codigo, meses)
                                    Checkbox(
                                        checked = actual != null, enabled = !ro,
                                        onCheckedChange = { on ->
                                            val existente = aplicadas.firstOrNull { it.vacuna == v.codigo && it.edadMeses == meses }
                                            ctx.vm.guardarVacuna((existente ?: VacunaAplicada(newId(), ctx.pacienteId, v.codigo, meses)).copy(aplicada = on, fecha = if (on) (existente?.fecha ?: ui.consulta?.fecha) else null))
                                        },
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
        DebouncedText("Otras vacunas", aplicadas.firstOrNull { it.vacuna == "otras" }?.detalle ?: "", ro, multiline = true) { texto ->
            val existente = aplicadas.firstOrNull { it.vacuna == "otras" }
            ctx.vm.guardarVacuna((existente ?: VacunaAplicada(newId(), ctx.pacienteId, "otras")).copy(detalle = texto))
        }
        Text("Antigripal", style = MaterialTheme.typography.titleSmall, color = Salud360Colors.Indigo)
        val antigripales = aplicadas.filter { it.vacuna == "antigripal" && it.aplicada }.sortedByDescending { it.fecha }
        antigripales.forEach { a -> Text("• ${a.fecha?.toDisplay() ?: ""} — dosis ${a.dosis ?: 1}", style = MaterialTheme.typography.bodyMedium) }
        if (!ro) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DateField("Fecha", fechaAntigripal, { it?.let { f -> fechaAntigripal = f } }, Modifier.width(190.dp))
            NumberField("Dosis", dosis, { dosis = it }, Modifier.width(100.dp))
            AcceptButton("Agregar antigripal", onClick = { ctx.vm.guardarVacuna(VacunaAplicada(newId(), ctx.pacienteId, "antigripal", null, true, fechaAntigripal, dosis.toIntOrNull() ?: 1)) })
        }
    }
}
