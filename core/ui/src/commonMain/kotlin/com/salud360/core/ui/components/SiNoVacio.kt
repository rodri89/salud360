package com.salud360.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.ui.theme.ColoresSiNo
import com.salud360.core.ui.theme.LocalIsDarkTheme
import com.salud360.core.ui.theme.coloresSiNo

/**
 * Valor de un marcador SI / NO al tocar [tocado] ("SI" o "NO") con [actual] cargado: tocar el elegido lo deja sin
 * cargar (`""`); si no, queda lo tocado, también sobre un valor desconocido (RF-67, RF-68).
 */
fun alternarSiNo(actual: String, tocado: String): String = if (tocado == actual) "" else tocado

/** Tarjetas por fila de la grilla SI / NO según el ancho de la sección: 2 hasta 600 dp, 4 desde ahí (RF-66). */
fun columnasPara(anchoDp: Float): Int = if (anchoDp < 600f) 2 else 4

/**
 * Tarjeta de un marcador SI / NO con "sin cargar" (serologías, RF-65 a RF-72): el nombre arriba y dos botones; el
 * elegido va relleno y con tilde, el otro con borde, y tocar el elegido lo deja en `""`. Un valor que no es "SI",
 * "NO" ni vacío se muestra como texto, sin botón elegido. En [soloLectura], el nombre y el valor, sin botones.
 */
@Composable
fun SiNoVacio(
    etiqueta: String,
    valor: String,
    onCambio: (String) -> Unit,
    modifier: Modifier = Modifier,
    soloLectura: Boolean = false,
) {
    val colores = coloresSiNo(LocalIsDarkTheme.current)
    val forma = MaterialTheme.shapes.small
    Column(
        modifier.clip(forma).background(colores.fondoTarjeta)
            .border(1.dp, MaterialTheme.colorScheme.outline, forma)
            .semantics(mergeDescendants = false) { stateDescription = valor.ifBlank { "sin cargar" } }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(etiqueta, color = colores.texto, style = MaterialTheme.typography.labelLarge, maxLines = 2)
        if (soloLectura) {
            Text(
                valor, color = colores.textoRelleno, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(forma).background(colores.relleno).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            return@Column
        }
        if (valor.isNotBlank() && valor != "SI" && valor != "NO") {
            Text(valor, color = colores.texto, style = MaterialTheme.typography.bodySmall)
        }
        // En la grilla las tarjetas de una fila tienen la misma altura: los botones quedan alineados abajo.
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            listOf("SI", "NO").forEach { opcion ->
                BotonSiNo(etiqueta, opcion, valor == opcion, colores, Modifier.weight(1f)) { onCambio(alternarSiNo(valor, opcion)) }
            }
        }
    }
}

/**
 * Un botón de [SiNoVacio], de al menos 48 × 48 dp. El foco (2 dp) se dibuja por fuera, con ~2 dp de margen, así lo
 * que tiene al lado es siempre la tarjeta ("Decidido" C5-4; RF-71). Enter o espacio lo tocan (`selectable`).
 */
@Composable
private fun BotonSiNo(etiqueta: String, opcion: String, elegido: Boolean, colores: ColoresSiNo, modifier: Modifier, onClick: () -> Unit) {
    val interaccion = remember { MutableInteractionSource() }
    val foco by interaccion.collectIsFocusedAsState()
    val forma = RoundedCornerShape(8.dp)
    Box(
        modifier
            .selectable(selected = elegido, interactionSource = interaccion, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "$etiqueta, $opcion" }
            .border(2.dp, if (foco) colores.bordeFoco else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).widthIn(min = 48.dp).clip(forma)
                .then(if (elegido) Modifier.background(colores.relleno) else Modifier.border(1.dp, colores.borde, forma))
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (elegido) Icon(Icons.Filled.Check, contentDescription = null, tint = colores.textoRelleno, modifier = Modifier.size(18.dp).padding(end = 2.dp))
            Text(opcion, color = if (elegido) colores.textoRelleno else colores.texto, fontWeight = FontWeight.SemiBold)
        }
    }
}
