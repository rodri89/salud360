package com.salud360.especialidades.pediatria

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.CheckboxField
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.SeccionContext

/**
 * Hitos del desarrollo madurativo por edad (catálogo `desarrollo_madurativos`), en cuatro áreas.
 * El catálogo original se cargaba a mano en la base; acá se incluye una versión inicial basada en
 * las pautas habituales de control pediátrico, ampliable en este archivo.
 */
object HitosDesarrollo {
    data class Hito(val clave: String, val area: String, val descripcion: String)
    private fun h(area: String, vararg d: String) = d.map { Hito("${area.take(2).lowercase()}_${it.lowercase().replace(Regex("[^a-z0-9]+"), "_")}", area, it) }
    val areas = listOf("Motor grueso", "Motor fino", "Psicosocial", "Lenguaje")

    /** Hitos por tramo de edad (límite superior en meses). */
    val porEdad: List<Pair<IntRange, List<Hito>>> = listOf(
        0..0 to h("Motor grueso", "Reflejos de búsqueda y succión", "Postura en flexión") + h("Motor fino", "Prensión palmar refleja") + h("Psicosocial", "Fijación ocular") + h("Lenguaje", "Responde al sonido"),
        1..2 to h("Motor grueso", "Levanta la cabeza en prono") + h("Motor fino", "Sigue objetos con la mirada") + h("Psicosocial", "Sonrisa social") + h("Lenguaje", "Vocaliza"),
        3..4 to h("Motor grueso", "Sostén cefálico", "Se apoya en antebrazos") + h("Motor fino", "Junta las manos", "Toma objetos") + h("Psicosocial", "Ríe a carcajadas") + h("Lenguaje", "Balbucea"),
        5..6 to h("Motor grueso", "Rola", "Se sienta con apoyo") + h("Motor fino", "Pasa objetos de una mano a otra") + h("Psicosocial", "Reconoce extraños") + h("Lenguaje", "Silabeo"),
        7..8 to h("Motor grueso", "Se sienta sin apoyo") + h("Motor fino", "Pinza inferior") + h("Psicosocial", "Angustia del 8° mes") + h("Lenguaje", "Dice \"mamá\"/\"papá\" inespecífico"),
        9..11 to h("Motor grueso", "Gatea", "Se para con apoyo") + h("Motor fino", "Pinza superior") + h("Psicosocial", "Juega a las escondidas", "Saluda") + h("Lenguaje", "Comprende el \"no\""),
        12..14 to h("Motor grueso", "Camina con apoyo", "Primeros pasos") + h("Motor fino", "Mete objetos en un recipiente") + h("Psicosocial", "Señala con el dedo") + h("Lenguaje", "2-3 palabras con sentido"),
        15..17 to h("Motor grueso", "Camina solo") + h("Motor fino", "Garabatea", "Torre de 2 cubos") + h("Psicosocial", "Imita tareas del hogar") + h("Lenguaje", "5-10 palabras"),
        18..23 to h("Motor grueso", "Corre", "Sube escaleras con ayuda") + h("Motor fino", "Torre de 4 cubos") + h("Psicosocial", "Come solo con cuchara") + h("Lenguaje", "Frases de 2 palabras"),
        24..35 to h("Motor grueso", "Salta con ambos pies", "Patea la pelota") + h("Motor fino", "Torre de 6 cubos", "Copia línea") + h("Psicosocial", "Control de esfínteres diurno", "Juego paralelo") + h("Lenguaje", "Frases de 3 palabras", "Dice su nombre"),
        36..47 to h("Motor grueso", "Pedalea triciclo", "Se para en un pie") + h("Motor fino", "Copia círculo") + h("Psicosocial", "Se viste con ayuda", "Juego simbólico") + h("Lenguaje", "Conversa", "Pregunta \"por qué\""),
        48..72 to h("Motor grueso", "Salta en un pie", "Atrapa la pelota") + h("Motor fino", "Copia cruz y cuadrado", "Dibuja figura humana") + h("Psicosocial", "Juega con reglas", "Se viste solo") + h("Lenguaje", "Cuenta historias", "Conoce colores"),
    )

    fun hitosPara(meses: Int): List<Hito> = porEdad.firstOrNull { meses in it.first }?.second ?: porEdad.last().second
    fun tituloPara(meses: Int): String = when {
        meses < 1 -> "Menor de 1 mes"; meses == 1 -> "1 mes"; meses in 7..8 -> "7 y 8 meses"; meses in 9..11 -> "9, 10 y 11 meses"
        meses >= 24 -> "Mayor de 2 años"; else -> "$meses meses"
    }
}

@Composable
fun DesarrolloMadurativoSeccion(s: SeccionDef, ctx: SeccionContext) {
    val valores by ctx.vm.valores.collectAsState()
    val v = valores[s.id] ?: emptyMap()
    val meses = ctx.edadMeses ?: 0
    val hitos = HitosDesarrollo.hitosPara(meses)
    SectionCard("${s.titulo} · ${HitosDesarrollo.tituloPara(meses)}", initiallyExpanded = s.inicialmenteExpandida) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HitosDesarrollo.areas.forEach { area ->
                Column(Modifier.weight(1f)) {
                    Text(area, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Salud360Colors.Indigo)
                    hitos.filter { it.area == area }.forEach { h ->
                        CheckboxField(h.descripcion, v[h.clave] == "1", { ctx.setValor(s.id, h.clave, if (it) "1" else "0") }, enabled = !ctx.soloLectura)
                    }
                }
            }
        }
        TextAreaField("Observación", v["observacion"] ?: "", { ctx.setValor(s.id, "observacion", it) }, minLines = 3, readOnly = ctx.soloLectura)
    }
}
