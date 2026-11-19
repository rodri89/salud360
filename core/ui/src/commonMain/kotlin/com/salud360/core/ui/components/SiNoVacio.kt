package com.salud360.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                OpcionElegible(opcion, "$etiqueta, $opcion", valor == opcion, { onCambio(alternarSiNo(valor, opcion)) }, Modifier.weight(1f), colores)
            }
        }
    }
}
