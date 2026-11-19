package com.salud360.especialidades.clinica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.OpcionElegible
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.theme.ColoresSiNo
import com.salud360.core.ui.theme.LocalIsDarkTheme
import com.salud360.core.ui.theme.coloresSiNo
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import com.salud360.features.hc.secciones.SeccionVacia

/**
 * Score Child-Pugh-Turcotte como la web (RF-74 a RF-82): desde 600 dp una tabla Parámetro | 1 | 2 | 3 con celdas que
 * se tocan; más angosto, un bloque por parámetro con las tres opciones apiladas. Tocar la elegida la deja sin cargar.
 * Total y clase se calculan en cada recomposición, no se guardan. En solo lectura, todo como texto.
 */
@Composable
fun ChildPughSeccion(s: SeccionDef, ctx: SeccionContext) {
    // Suscripto al flujo, no `ctx.valor`: esa lectura no se observa y, con los mismos `s` y `ctx`, Compose saltea
    // esta función al cambiar un valor (la celda no se pintaba ni cambiaba el total).
    val todos by ctx.vm.valores.collectAsState()
    val valores = parametrosChildPugh.associate { it.clave to todos[s.id]?.get(it.clave).orEmpty() }
    if (ctx.soloLectura && valores.values.all { it.isBlank() }) {
        SeccionVacia()
        return
    }
    val total = totalChildPugh(valores)
    val clase = claseChildPugh(total, completosChildPugh(valores))
    val colores = coloresSiNo(LocalIsDarkTheme.current)
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "form"), initiallyExpanded = s.inicialmenteExpandida) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(colores.fondoTarjeta)) {
            val ancho = maxWidth >= 600.dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Encabezado(total, clase, ancho, colores)
                when {
                    ctx.soloLectura -> parametrosChildPugh.forEach { p -> FilaLectura(p, valores.getValue(p.clave), colores) }
                    ancho -> Tabla(valores, colores) { clave, valor -> ctx.setValor(s.id, clave, valor) }
                    else -> parametrosChildPugh.forEach { p ->
                        Bloque(p, valores.getValue(p.clave), colores) { ctx.setValor(s.id, p.clave, it) }
                    }
                }
            }
        }
    }
}

/** "1 punto", "2 puntos". */
private fun puntosTexto(n: Int) = if (n == 1) "1 punto" else "$n puntos"

/** El lector anuncia el total nuevo al cambiar (RF-80). */
@Composable
private fun Encabezado(total: Int, clase: String, ancho: Boolean, colores: ColoresSiNo) {
    val totalTexto = @Composable {
        Text(
            "Total: $total · Clase $clase", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = colores.textoTotal, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val leyenda = @Composable { Text("A: ≤ 6 · B: 7-9 · C: ≥ 10", style = MaterialTheme.typography.labelMedium, color = colores.texto) }
    if (ancho) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            totalTexto()
            leyenda()
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            totalTexto()
            leyenda()
        }
    }
}

/** Accesibilidad de una fila o bloque: grupo de opciones con su estado (RF-80). */
private fun Modifier.grupoParametro(puntos: Int?) =
    selectableGroup().semantics { stateDescription = if (puntos == null) "sin cargar" else puntosTexto(puntos) }

/** Desde 600 dp (RF-74): "Parámetro" y "Puntos" con 1 | 2 | 3 debajo; una fila por parámetro con celdas parejas. */
@Composable
private fun Tabla(valores: Map<String, String>, colores: ColoresSiNo, onCambio: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("Parámetro", Modifier.weight(1.4f), color = colores.texto, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(3f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Puntos", color = colores.texto, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth()) {
                    (1..3).forEach {
                        Text("$it", Modifier.weight(1f), color = colores.texto, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        parametrosChildPugh.forEach { p ->
            val valor = valores.getValue(p.clave)
            val puntos = puntosDe(valor)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).grupoParametro(puntos), verticalAlignment = Alignment.CenterVertically) {
                Nombre(p, valor, colores, Modifier.weight(1.4f).padding(end = 8.dp))
                p.criterios.forEachIndexed { i, criterio ->
                    val n = i + 1
                    OpcionElegible(
                        criterio, "${p.nombre}, ${puntosTexto(n)}, $criterio", puntos == n,
                        { onCambio(p.clave, alternarPuntos(valor, "$n")) }, Modifier.weight(1f).fillMaxHeight(), colores,
                    )
                }
            }
        }
    }
}

/** Menos de 600 dp (RF-75): el nombre y las tres opciones a todo el ancho, una debajo de la otra. */
@Composable
private fun Bloque(p: ParametroChildPugh, valor: String, colores: ColoresSiNo, onCambio: (String) -> Unit) {
    val puntos = puntosDe(valor)
    Column(Modifier.fillMaxWidth().grupoParametro(puntos)) {
        Nombre(p, valor, colores, Modifier.padding(bottom = 2.dp))
        p.criterios.forEachIndexed { i, criterio ->
            val n = i + 1
            OpcionElegible(
                "${puntosTexto(n)} · $criterio", "${p.nombre}, ${puntosTexto(n)}, $criterio", puntos == n,
                { onCambio(alternarPuntos(valor, "$n")) }, Modifier.fillMaxWidth(), colores,
            )
        }
    }
}

/** Nombre del parámetro (no se toca: RF-76) y, si lo guardado no es 1, 2 ni 3, ese valor chico debajo. */
@Composable
private fun Nombre(p: ParametroChildPugh, valor: String, colores: ColoresSiNo, modifier: Modifier) {
    Column(modifier) {
        Text(p.nombre, color = colores.texto, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (valor.isNotBlank() && puntosDe(valor) == null) {
            Text(valor, color = colores.texto, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Solo lectura (RF-81): "nombre" y "n puntos · criterio", el valor desconocido tal cual o "—". */
@Composable
private fun FilaLectura(p: ParametroChildPugh, valor: String, colores: ColoresSiNo) {
    val puntos = puntosDe(valor)
    val texto = when {
        puntos != null -> "${puntosTexto(puntos)} · ${p.criterios[puntos - 1]}"
        valor.isNotBlank() -> valor
        else -> "—"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(p.nombre, Modifier.weight(1f), color = colores.texto, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(texto, Modifier.weight(1f), color = colores.texto, style = MaterialTheme.typography.bodyMedium)
    }
}
