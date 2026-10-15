package com.salud360.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colores de las tarjetas SI / NO con "sin cargar" (serologías, RF-69 a RF-71). Relleno y borde a 3:1 contra la
 * tarjeta, textos a 4,5:1 contra su fondo (WCAG 2.1). El foco va con el color del texto y por fuera del botón, sobre
 * la tarjeta ("Decidido" C5-4): en oscuro ningún color llega a 3:1 a la vez contra la tarjeta y contra el relleno.
 */
data class ColoresSiNo(
    val fondoTarjeta: Color,
    /** Fondo del botón elegido (el mismo para SI y NO). */
    val relleno: Color,
    /** Texto y tilde del botón elegido. */
    val textoRelleno: Color,
    /** Borde (1 dp) del botón no elegido. */
    val borde: Color,
    /** Texto del botón no elegido y nombre del marcador. */
    val texto: Color,
    /** Borde de foco (2 dp), por fuera del botón. */
    val bordeFoco: Color,
)

fun coloresSiNo(oscuro: Boolean): ColoresSiNo = if (oscuro) {
    ColoresSiNo(
        fondoTarjeta = Salud360Colors.DarkSurface,
        relleno = Color(0xFF6EC1D6), // primary oscuro
        textoRelleno = Salud360Colors.DarkBackground,
        borde = Color(0xFFB8C6CA), // onSurfaceVariant oscuro
        texto = Salud360Colors.DarkOnBackground,
        bordeFoco = Salud360Colors.DarkOnBackground,
    )
} else {
    ColoresSiNo(
        fondoTarjeta = Salud360Colors.Surface,
        relleno = Salud360Colors.TealStart,
        textoRelleno = Color.White,
        borde = Color(0xFF4F5B5E), // onSurfaceVariant claro
        texto = Salud360Colors.OnBackground,
        bordeFoco = Salud360Colors.OnBackground,
    )
}
