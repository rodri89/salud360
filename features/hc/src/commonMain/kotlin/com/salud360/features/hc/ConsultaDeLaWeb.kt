package com.salud360.features.hc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.salud360.core.data.hcgeneral.AvisosDeConsulta
import com.salud360.core.data.hcgeneral.MarcaDeSeccion
import com.salud360.core.data.hcgeneral.YA_NO_FIGURA_EN_LA_WEB
import com.salud360.core.data.repos.TEXTO_REINTENTAR
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.theme.Salud360Colors

// La consulta de la web en la pantalla (spec 002, entrega L: RF-43 a RF-45, RF-56, RF-58, RF-63). Solo dibuja: qué avisos
// lleva, qué se marca como «Ya no figura en la web» y si hay que mostrar un fallo de la lectura lo deciden funciones puras de
// `core/data` (`avisosDeLaPantallaDeConsulta`, `marcaDeSeccion`, `mensajeDeLaLecturaDeConsulta`, con tests). Nada de acá
// escribe: una consulta de la web se lee siempre, y las marcas muestran lo último que se trajo sin ocultarlo ni tacharlo.

/**
 * Los avisos de una consulta de la web, arriba de todo: el banner «Consulta de la web — solo lectura» (RF-45), quién la cargó,
 * «Ya no figura en la web» (RF-63), «Abierta en la web: …» (RF-44) y lo que la web tiene y esta versión no muestra (RF-43).
 * Cada uno es una región viva: el lector de pantalla lo anuncia cuando aparece o cambia.
 */
@Composable
internal fun AvisosDeLaConsulta(avisos: AvisosDeConsulta) {
    if (!avisos.soloLecturaWeb) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        avisos.banner?.let { AvisoDeLaConsulta(Icons.Default.Lock, it, MaterialTheme.colorScheme.onSurface) }
        avisos.cargadaPor?.let { AvisoDeLaConsulta(Icons.Default.Lock, it, MaterialTheme.colorScheme.onSurfaceVariant) }
        avisos.ausente?.let { AvisoDeLaConsulta(Icons.Default.Warning, it, Salud360Colors.Danger) }
        avisos.abiertaEnLaWeb?.let { AvisoDeLaConsulta(Icons.Default.Warning, it, MaterialTheme.colorScheme.onSurface) }
        avisos.noLeidas?.let { AvisoDeLaConsulta(Icons.Default.Warning, it, MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun AvisoDeLaConsulta(icono: ImageVector, texto: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icono, contentDescription = null, tint = color)
        Spacer(Modifier.width(8.dp))
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/**
 * El fallo de la lectura de la consulta, con el motivo que nombra la historia clínica (RF-56) y «Reintentar» a mano (RF-58): lo
 * ya traído se sigue viendo debajo. Mientras reintenta, el botón se apaga.
 */
@Composable
internal fun FalloDeLaLecturaDeLaConsulta(mensaje: String, leyendo: Boolean, onReintentar: () -> Unit) {
    SectionCard("No se pudo traer la historia clínica", icon = Icons.Default.CloudOff, collapsible = false) {
        Text(mensaje, color = Salud360Colors.Danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(
            "Lo que está cargado en este dispositivo se sigue viendo y nada se pierde.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BotonDeLaLectura(TEXTO_REINTENTAR, onClick = onReintentar, enabled = !leyendo)
    }
}

/** Mientras se trae la consulta de la web se ve lo que ya estaba en el dispositivo, y esta línea. */
@Composable
internal fun LeyendoLaConsulta() {
    Text(
        "Cargando…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** La marca «Ya no figura en la web» (RF-63) de una carga, un estudio, un pendiente o un antecedente: el dato sigue a la vista. */
@Composable
internal fun MarcaYaNoFigura(modifier: Modifier = Modifier) {
    StatusChip(YA_NO_FIGURA_EN_LA_WEB, Salud360Colors.Danger, modifier, pequeno = true)
}

/** La marca de una sección de la consulta: la etiqueta y, si se sabe, qué campos la web dejó de mandar. La sección se ve entera. */
@Composable
internal fun MarcaDeLaSeccion(marca: MarcaDeSeccion) {
    // FlowRow: con muchos campos nombrados, en una pantalla angosta el texto pasa a la línea de abajo en vez de recortarse.
    FlowRow(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        MarcaYaNoFigura()
        if (marca.campos.isNotEmpty()) {
            Text(marca.campos.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
