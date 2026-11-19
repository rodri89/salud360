package com.salud360.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salud360.core.ui.theme.ColoresSiNo
import com.salud360.core.ui.theme.LocalIsDarkTheme
import com.salud360.core.ui.theme.coloresSiNo

/**
 * Una casilla que se marca o se desmarca tocándola (la grilla de enfermedades; RF-20 a RF-22), de al menos 48 × 48 dp.
 * Mismo aspecto y mismos [ColoresSiNo] que [OpcionElegible]: la marcada va rellena y con tilde (no solo color), la otra
 * con borde, y el foco (2 dp) se dibuja por fuera. El [nombre] se ve completo, en las líneas que haga falta; [marca]
 * ("Guardando…") va debajo y es parte del mismo nodo semántico, así que el lector la anuncia con el nombre y el estado
 * ("marcada" / "sin marcar"). Tabulador y espacio la tocan (`toggleable`).
 *
 * [habilitada] = false solo ignora los toques: no atenúa los colores, porque el estado elegido tiene que seguir
 * leyéndose mientras el pedido está en camino. Para filas parejas, quien la usa le da `weight(1f)` y `fillMaxHeight()`
 * dentro de un `Row` con `IntrinsicSize.Min` (como la tabla de Child-Pugh).
 */
@Composable
fun CasillaElegible(
    nombre: String,
    marcada: Boolean,
    onCambio: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    habilitada: Boolean = true,
    marca: String? = null,
    colores: ColoresSiNo = coloresSiNo(LocalIsDarkTheme.current),
) {
    val interaccion = remember { MutableInteractionSource() }
    val foco by interaccion.collectIsFocusedAsState()
    val forma = RoundedCornerShape(8.dp)
    val textoColor = if (marcada) colores.textoRelleno else colores.texto
    Box(
        modifier
            .toggleable(
                value = marcada, enabled = habilitada, role = Role.Checkbox,
                interactionSource = interaccion, indication = null, onValueChange = onCambio,
            )
            .border(2.dp, if (foco) colores.bordeFoco else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(4.dp),
        // Con un alto mínimo de afuera (fillMaxHeight en una fila de la grilla), la casilla lo toma: celdas parejas.
        propagateMinConstraints = true,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).widthIn(min = 48.dp).clip(forma)
                .then(if (marcada) Modifier.background(colores.relleno) else Modifier.border(1.dp, colores.borde, forma))
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (marcada) Icon(Icons.Filled.Check, contentDescription = null, tint = colores.textoRelleno, modifier = Modifier.size(18.dp))
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(nombre, color = textoColor, fontWeight = FontWeight.SemiBold)
                if (marca != null) Text(marca, color = textoColor, fontSize = 12.sp)
            }
        }
    }
}
