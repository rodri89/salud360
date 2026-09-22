package com.salud360.core.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Paleta tomada de `rodri_style*.css` de los proyectos originales
 * (turnosonlinebb, hclinica, hcpediatria, hcgineco, hccardiologia, hcendocrinologia, hchematologia).
 */
object Salud360Colors {
    // Degradado de marca (.fondoNav, .rodri_button, .rodri_th, .nav_seleccionado)
    val TealStart = Color(0xFF2C7B90)
    val TealMid = Color(0xFF2A6C7E)
    val TealEnd = Color(0xFF004D45)

    // Degradado del header público (.fondoHeader)
    val SkyStart = Color(0xFF93CEDE)
    val SkyMid = Color(0xFF75BDD1)
    val SkyEnd = Color(0xFF009688)

    // Verde flúor de los bordes de botones (.rodri_button border) — usado como acento sutil
    val NeonGreen = Color(0xFF03F237)
    // Verde de éxito más legible (día disponible en el datepicker: #28a745)
    val Success = Color(0xFF28A745)
    val SuccessDark = Color(0xFF155724)

    // Fondo general y texto (body)
    val Background = Color(0xFFF8F7FA)
    val OnBackground = Color(0xFF333333)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFEFF3F5)

    // Títulos de sección (.background_panel_consulta_actual, .letraAzul)
    val Indigo = Color(0xFF303F9F)

    // Sombra de tarjetas (box-shadow #C9C9C9)
    val Shadow = Color(0xFFC9C9C9)

    // Fila resaltada (.tr_amarillo)
    val HighlightRow = Color(0xFFF8DF73)

    // Estados de turnos (.circulo, .circulo_ocupado, .circulo_fuera_ventana)
    val SlotFree = Color(0xFF91E842)
    val SlotBusy = Color(0xFFE53935)
    val SlotBlocked = Color(0xFF9E9E9E)
    val SlotOutOfWindow = Color(0xFFC9A227)

    // Colores de apoyo heredados de SB Admin 2
    val Danger = Color(0xFFE74A3B)
    val Warning = Color(0xFFF6C23E)
    val Info = Color(0xFF36B9CC)
    val Grey = Color(0xFF999999)
    val GreyLight = Color(0xFFD7D3CB)

    // Tipos de turno (chip en la tarjeta de la agenda): un color por tipo para distinguirlos de un vistazo
    val TipoConsulta = TealStart
    val TipoVideollamada = Indigo
    val TipoConsultaOnline = Info
    val TipoEcografia = Color(0xFF7E57C2)
    val TipoDeportologia = Color(0xFFEF6C00)
    val TipoConsultaEco = Color(0xFFC2185B)

    // Nota interna del paciente (cartel bajo el nombre) y horario libre en la agenda del día
    val NotaBg = Color(0xFFFDF3D8)
    val NotaBgDark = Color(0xFF33290F)
    val SlotLibreBg = Color(0xFFE6F6EA)
    val SlotLibreBgDark = Color(0xFF17302A)

    // Modo oscuro (mejora visual: no existía en los proyectos originales)
    val DarkBackground = Color(0xFF0F1B1E)
    val DarkSurface = Color(0xFF16262B)
    val DarkSurfaceVariant = Color(0xFF203438)
    val DarkOnBackground = Color(0xFFE6EDEF)

    /** Degradado de marca a 45° usado en barras, botones primarios y cabeceras de tabla. */
    val BrandGradient: Brush = Brush.linearGradient(
        colorStops = arrayOf(0f to TealStart, 0.16f to TealStart, 0.36f to TealMid, 1f to TealEnd),
    )

    /** Degradado claro del header público. */
    val SkyGradient: Brush = Brush.linearGradient(
        colorStops = arrayOf(0f to SkyStart, 0.36f to SkyMid, 0.82f to SkyEnd, 1f to SkyEnd),
    )

    /** Colores identificatorios por especialidad (mejora visual para la app unificada). */
    fun especialidad(codigo: String): Color = when (codigo) {
        "clinica" -> TealStart
        "pediatria" -> Color(0xFF00897B)
        "gineco" -> Color(0xFFAD1457)
        "cardiologia" -> Color(0xFFC62828)
        "endocrinologia" -> Color(0xFF6A1B9A)
        "hematologia" -> Color(0xFFB71C1C)
        "desarrollo_infantil" -> Color(0xFFF9A825)
        else -> Indigo
    }
}
