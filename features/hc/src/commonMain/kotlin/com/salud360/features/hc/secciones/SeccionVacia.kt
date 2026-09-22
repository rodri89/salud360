package com.salud360.features.hc.secciones

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** true cuando la sección se renderiza como contenido de una pestaña superior (no como tarjeta del acordeón). */
val LocalSeccionEnPestania = compositionLocalOf { false }

/**
 * Lo que se muestra en modo lectura cuando la sección no tiene nada cargado: nada en el acordeón
 * (la tarjeta desaparece), un aviso chico cuando la sección es el contenido de una pestaña.
 */
@Composable
fun SeccionVacia() {
    if (LocalSeccionEnPestania.current) {
        Text("Sin datos cargados", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
