package com.salud360.features.hc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.salud360.core.data.hcgeneral.BannerDeEnvio
import com.salud360.core.data.hcgeneral.TEXTO_DESCARTAR
import com.salud360.core.data.hcgeneral.TipoDeBanner
import com.salud360.core.data.repos.TEXTO_REINTENTAR
import com.salud360.core.ui.theme.Salud360Colors

// El estado de envío de una consulta propia de la HC general en la pantalla (spec 005, T37; RF-4, RF-24, RF-30, RF-55). Solo dibuja: qué franjas
// hay, con qué texto y qué acciones ofrecen lo decide `bannersDeEnvio` (core/data, con tests), y los avisos de cada sección,
// `avisoDeEnvioDeLaSeccion`. «Reintentar» y «Descartar» le piden a la cola de envío por el ViewModel; nada se reintenta ni se descarta solo.

/**
 * Las franjas de estado de la consulta, arriba de las secciones: «Enviando el cierre.», «Falta enviar», «En espera: <motivo>»,
 * «No se pudo enviar: <motivo>» (con Reintentar y Descartar) y «Descartado: no se envió a la web». Cada una es una región viva: el lector de
 * pantalla la anuncia cuando aparece o cambia. Sin franjas, no ocupa lugar.
 */
@Composable
internal fun EstadoDeEnvioDeLaConsulta(banners: List<BannerDeEnvio>, onReintentar: () -> Unit, onDescartar: () -> Unit) {
    if (banners.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        banners.forEach { FranjaDeEnvio(it, onReintentar, onDescartar) }
    }
}

@Composable
private fun FranjaDeEnvio(banner: BannerDeEnvio, onReintentar: () -> Unit, onDescartar: () -> Unit) {
    val color = when (banner.tipo) {
        TipoDeBanner.NO_SE_PUDO_ENVIAR -> Salud360Colors.Danger
        TipoDeBanner.EN_ESPERA -> Salud360Colors.Warning
        TipoDeBanner.ENVIANDO_EL_CIERRE -> MaterialTheme.colorScheme.primary
        TipoDeBanner.FALTA_ENVIAR, TipoDeBanner.DESCARTADO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconoDeLaFranja(banner.tipo, color)
                Spacer(Modifier.width(8.dp))
                Text(
                    banner.texto,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (banner.puedeReintentar || banner.puedeDescartar) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (banner.puedeReintentar) BotonDeLaLectura(TEXTO_REINTENTAR, onClick = onReintentar)
                    if (banner.puedeDescartar) BotonDeLaLectura(TEXTO_DESCARTAR, onClick = onDescartar)
                }
            }
        }
    }
}

@Composable
private fun IconoDeLaFranja(tipo: TipoDeBanner, color: Color) {
    when (tipo) {
        TipoDeBanner.ENVIANDO_EL_CIERRE -> CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
        TipoDeBanner.NO_SE_PUDO_ENVIAR -> Icon(Icons.Default.Warning, contentDescription = null, tint = color)
        TipoDeBanner.EN_ESPERA -> Icon(Icons.Default.CloudOff, contentDescription = null, tint = color)
        TipoDeBanner.FALTA_ENVIAR, TipoDeBanner.DESCARTADO -> Icon(Icons.Default.Lock, contentDescription = null, tint = color)
    }
}

/**
 * El aviso de una sección que no se envía (RF-3, RF-4): «<Sección>: Esta sección todavía no se envía a la web» o «<Sección>: No se envía a
 * la web». Va arriba de la sección, que se ve solo para leer con lo que ya tenía cargado (nada se borra).
 */
@Composable
internal fun AvisoDeEnvioDeLaSeccion(titulo: String, aviso: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("$titulo: $aviso", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Una línea chica bajo un campo o una casilla que la web no guarda: «No se envía a la web» (RF-3). */
@Composable
internal fun AvisoDeEnvioDelCampo(texto: String) {
    Text(texto, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}