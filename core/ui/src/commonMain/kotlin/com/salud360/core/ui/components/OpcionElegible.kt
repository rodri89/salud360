package com.salud360.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.salud360.core.ui.theme.ColoresSiNo
import com.salud360.core.ui.theme.LocalIsDarkTheme
import com.salud360.core.ui.theme.coloresSiNo

/**
 * Una opción que se elige tocándola (los SI / NO de serologías, las celdas de Child-Pugh; RF-69 a RF-71, RF-79,
 * RF-80), de al menos 48 × 48 dp: la elegida va rellena y con tilde, las otras con borde; el texto, centrado, puede
 * ocupar varias líneas. El foco (2 dp) se dibuja por fuera, con ~2 dp de margen, así lo que tiene al lado es siempre
 * la tarjeta ("Decidido" C5-4). Enter o espacio la tocan (`selectable`). [descripcion] es lo que oye el lector de
 * pantalla; el "elegido" lo pone `selected`.
 */
@Composable
fun OpcionElegible(
    texto: String,
    descripcion: String,
    elegido: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colores: ColoresSiNo = coloresSiNo(LocalIsDarkTheme.current),
) {
    val interaccion = remember { MutableInteractionSource() }
    val foco by interaccion.collectIsFocusedAsState()
    val forma = RoundedCornerShape(8.dp)
    Box(
        modifier
            .selectable(selected = elegido, interactionSource = interaccion, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = descripcion }
            .border(2.dp, if (foco) colores.bordeFoco else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(4.dp),
        // Con un alto mínimo de afuera (fillMaxHeight en una fila de la tabla), el botón lo toma: celdas parejas.
        propagateMinConstraints = true,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).widthIn(min = 48.dp).clip(forma)
                .then(if (elegido) Modifier.background(colores.relleno) else Modifier.border(1.dp, colores.borde, forma))
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (elegido) Icon(Icons.Filled.Check, contentDescription = null, tint = colores.textoRelleno, modifier = Modifier.size(18.dp).padding(end = 2.dp))
            Text(
                texto, color = if (elegido) colores.textoRelleno else colores.texto, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}
