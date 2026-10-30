package com.salud360.features.hc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.data.hcgeneral.ConsultaDeLaLista
import com.salud360.core.data.hcgeneral.ETIQUETA_WEB
import com.salud360.core.data.hcgeneral.MARCA_SOLO_EN_ESTE_DISPOSITIVO
import com.salud360.core.data.hcgeneral.TEXTO_ACTUALIZAR
import com.salud360.core.data.hcgeneral.TEXTO_VER_MAS
import com.salud360.core.data.hcgeneral.TITULO_ABIERTAS_EN_LA_WEB
import com.salud360.core.data.hcgeneral.VistaDeLaHistoria
import com.salud360.core.data.hcgeneral.YA_NO_FIGURA_EN_LA_WEB
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.hc.Consulta
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.LocalIsDarkTheme
import com.salud360.core.ui.theme.Salud360Colors

// La historia de la HC general con lo que trae la web (spec 002, entrega L: RF-38, RF-44 a RF-46, RF-49, RF-63). Solo dibuja:
// qué grupos hay, qué etiquetas lleva cada consulta, cuántas se ven y qué avisos corresponden lo decide `vistaDeLaHistoria`
// (core/data, con tests). Ninguna acción de acá escribe sobre una consulta de la web: «Ver» la abre para leerla y solo la
// «Consulta en curso» del dispositivo tiene Continuar y Anular.

/** El alto mínimo de todo lo que se toca (48 dp), también cuando el texto de un botón ocupa dos líneas. */
private val ALTO_TOCABLE = 48.dp

/**
 * Los avisos de la historia (consultas que esta versión no muestra, cargas sin fecha, paciente sin vínculo). Cada uno es una
 * región viva: el lector de pantalla lo anuncia cuando aparece o cambia.
 */
@Composable
internal fun AvisosDeLaLectura(avisos: List<String>) {
    avisos.forEach { aviso ->
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(aviso, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Un botón de la lectura (del estilo de `AcceptButton`) de 48 dp como mínimo: si el texto ocupa dos líneas el botón crece y
 * no lo recorta.
 */
@Composable
internal fun BotonDeLaLectura(texto: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = ALTO_TOCABLE),
        shape = CircleShape,
        border = BorderStroke(1.5.dp, if (enabled) Salud360Colors.Success else Salud360Colors.Grey),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Salud360Colors.SuccessDark),
    ) { Text(texto) }
}

/** «Actualizar»: fuerza la lectura de la lista y el historial; mientras lee no se puede volver a tocar. */
@Composable
internal fun AccionesDeLaLectura(leyendo: Boolean, onActualizar: () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BotonDeLaLectura(if (leyendo) "Cargando…" else TEXTO_ACTUALIZAR, onClick = onActualizar, enabled = !leyendo)
    }
}

/** La «Consulta en curso» del dispositivo, con su marca «Solo en este dispositivo» (Q14); la única con Continuar y Anular. */
@Composable
internal fun ConsultaEnCursoLocal(item: ConsultaDeLaLista, def: EspecialidadDefinition, onContinuar: () -> Unit, onAnular: () -> Unit) {
    val c = item.consulta
    SectionCard("Consulta en curso", collapsible = false) {
        Text("${c.fecha.toDisplay()} · ${def.tipoConsulta(c.tipo)?.nombre ?: c.tipo}", fontWeight = FontWeight.SemiBold)
        Text(MARCA_SOLO_EN_ESTE_DISPOSITIVO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Continuar", onClick = onContinuar, modifier = Modifier.heightIn(min = ALTO_TOCABLE))
            LinkButton("Anular", onClick = onAnular, modifier = Modifier.heightIn(min = ALTO_TOCABLE))
        }
    }
}

/** «Abiertas en la web (solo lectura)»: solo si hay alguna (RF-44). Cada una se abre para leerla; no se edita ni se anula. */
@Composable
internal fun AbiertasEnLaWeb(vista: VistaDeLaHistoria, def: EspecialidadDefinition, color: Color, onAbrir: (Consulta) -> Unit) {
    if (vista.abiertasEnLaWeb.isEmpty()) return
    SectionCard(TITULO_ABIERTAS_EN_LA_WEB, collapsible = false) {
        vista.abiertasEnLaWeb.forEach { FilaDeConsulta(it, def, color, onAbrir) }
    }
}

/** «Consultas anteriores (N)»: las de la web y las del dispositivo juntas, cada una con su origen, y «Ver más» mientras queden. */
@Composable
internal fun AnterioresDeLaLectura(vista: VistaDeLaHistoria, def: EspecialidadDefinition, color: Color, onAbrir: (Consulta) -> Unit, onVerMas: () -> Unit) {
    SectionCard("Consultas anteriores (${vista.totalAnteriores})", collapsible = false) {
        if (vista.totalAnteriores == 0) Text("Todavía no hay consultas cerradas", color = MaterialTheme.colorScheme.onSurfaceVariant)
        vista.anteriores.forEach { FilaDeConsulta(it, def, color, onAbrir) }
        if (vista.hayMas) {
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                BotonDeLaLectura(TEXTO_VER_MAS, onClick = onVerMas)
            }
        }
    }
}

/**
 * Una fila de la lista: fecha, tipo y edad, y sus etiquetas (origen, «Ya no figura en la web» y «Cargada por …»). El texto
 * y las etiquetas se leen juntos; «Ver» es el único botón de la fila.
 */
@Composable
private fun FilaDeConsulta(item: ConsultaDeLaLista, def: EspecialidadDefinition, color: Color, onAbrir: (Consulta) -> Unit) {
    val c = item.consulta
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(iconoTipoConsulta(def.tipoConsulta(c.tipo)?.icono ?: ""), contentDescription = null, tint = color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.fecha.toDisplay(), fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(def.tipoConsulta(c.tipo)?.nombre ?: c.tipo, c.edadMostrar.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.etiquetas.forEach { Etiqueta(it) }
            }
        }
        LinkButton("Ver", onClick = { onAbrir(c) }, modifier = Modifier.heightIn(min = ALTO_TOCABLE))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

/** Una etiqueta de la fila: «Web», «Solo en este dispositivo», «Ya no figura en la web» o «Cargada por …». */
@Composable
private fun Etiqueta(texto: String) {
    val oscuro = LocalIsDarkTheme.current
    val color = when (texto) {
        ETIQUETA_WEB -> if (oscuro) MaterialTheme.colorScheme.secondary else Salud360Colors.Indigo
        YA_NO_FIGURA_EN_LA_WEB -> Salud360Colors.Danger
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    StatusChip(texto, color, pequeno = true)
}
