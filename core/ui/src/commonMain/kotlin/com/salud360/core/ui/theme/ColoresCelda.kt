package com.salud360.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * Colores de la celda editable de las tablas (laboratorio, RF-50 a RF-53). Tomados de la paleta existente para que
 * el borde llegue a 3:1 contra la celda y contra la fila (blanca o gris), y el texto y la ayuda a 4,5:1 (WCAG 2.1).
 * `outline` no sirve para el borde: contra blanco no llega a 3:1.
 */
data class ColoresCelda(
    val fondo: Color,
    val borde: Color,
    /** Borde con foco (2 dp) y cursor. */
    val bordeFoco: Color,
    val texto: Color,
    /** Texto de ayuda de la celda vacía ("Valor"). */
    val ayuda: Color,
)

fun coloresCelda(oscuro: Boolean): ColoresCelda = if (oscuro) {
    ColoresCelda(
        // Más hundido que la tarjeta (`DarkSurface`).
        fondo = Salud360Colors.DarkBackground,
        borde = Color(0xFFB8C6CA), // onSurfaceVariant oscuro
        bordeFoco = Color(0xFF6EC1D6), // primary oscuro
        texto = Salud360Colors.DarkOnBackground,
        ayuda = Color(0xFFB8C6CA),
    )
} else {
    ColoresCelda(
        fondo = Color(0xFFD5EBF1), // primaryContainer claro
        borde = Color(0xFF4F5B5E), // onSurfaceVariant claro
        bordeFoco = Salud360Colors.TealStart,
        texto = Salud360Colors.OnBackground,
        ayuda = Color(0xFF4F5B5E),
    )
}

/** Relación de contraste WCAG 2.1 entre dos colores opacos: de 1 (iguales) a 21 (negro y blanco). */
fun contraste(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}
