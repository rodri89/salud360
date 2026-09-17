package com.salud360.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.salud360.core.ui.theme.Salud360Colors
import kotlinx.coroutines.delay

/**
 * Dato en línea que se copia al portapapeles al tocarlo (DNI, número de afiliado, teléfono...). Muestra "Copiado"
 * un instante como confirmación. `valor` es lo que se copia; por defecto, el mismo texto que se muestra.
 */
@Composable
fun DatoCopiable(
    texto: String,
    valor: String = texto,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icono: ImageVector = Icons.Default.ContentCopy,
) {
    val portapapeles = LocalClipboardManager.current
    var copiado by remember { mutableStateOf(false) }
    LaunchedEffect(copiado) { if (copiado) { delay(1500); copiado = false } }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { portapapeles.setText(AnnotatedString(valor)); copiado = true }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(if (copiado) "Copiado" else texto, style = MaterialTheme.typography.bodyMedium, color = if (copiado) Salud360Colors.SuccessDark else color)
        Icon(icono, contentDescription = "Copiar", modifier = Modifier.size(14.dp), tint = color)
    }
}

/**
 * Enlace de WhatsApp para un teléfono argentino tal como se carga en la ficha (con o sin 0 / 15 / código de país).
 * `wa.me` necesita el número internacional sin "+": 54 + 9 (celular) + característica + número.
 */
fun linkWhatsApp(telefono: String): String {
    var d = telefono.filter { it.isDigit() }.removePrefix("00")
    if (!d.startsWith("54")) {
        d = d.removePrefix("0")
        // "291 15 4241493" → "291 4241493": el 15 local no va en el formato internacional.
        if (d.length >= 12 && d.substring(2, 4) == "15") d = d.substring(0, 2) + d.substring(4)
        else if (d.length >= 12 && d.substring(3, 5) == "15") d = d.substring(0, 3) + d.substring(5)
        else if (d.length >= 12 && d.substring(4, 6) == "15") d = d.substring(0, 4) + d.substring(6)
        d = "549$d"
    } else if (d.length == 12) {
        // "54 291 4241493" sin el 9 de celular.
        d = "549" + d.substring(2)
    }
    return "https://wa.me/$d"
}
