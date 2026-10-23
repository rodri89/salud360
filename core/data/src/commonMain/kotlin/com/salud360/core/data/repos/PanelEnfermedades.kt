package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.AvisoEnfermedades
import com.salud360.core.data.hcgeneral.MotivoEnfermedades
import com.salud360.core.data.hcgeneral.VistaSeccionEnfermedades
import com.salud360.core.data.hcgeneral.puedeAdministrar
import com.salud360.core.data.hcgeneral.vistaSeccion
import com.salud360.core.data.network.hc.HcPacienteCandidato

/** RF-21: la marca de una casilla con un pedido en camino. */
const val TEXTO_GUARDANDO = "Guardando…"

/** RF-24: junto al título de la sección, 3 segundos después de cada confirmación. */
const val TEXTO_GUARDADO = "Guardado"

/** RF-30: vuelve a pedir el estado de la sección. */
const val TEXTO_REINTENTAR = "Reintentar"

/** RF-18, RF-37: abre la ventana para administrar la lista del médico. */
const val TEXTO_ADMINISTRAR = "Administrar enfermedades"

/**
 * Qué dibuja la sección "Enfermedades" para un [SeccionEnfermedadesUi] (spec 003, RF-9 a RF-14, RF-18, RF-19, RF-30,
 * RF-31, RF-35, RF-37). La pantalla solo dibuja el caso que toca; la decisión es de [panelDeSeccion].
 */
sealed interface PanelEnfermedades {

    /** Todavía no hay nada que mostrar: "Cargando…". */
    data object Cargando : PanelEnfermedades

    /**
     * RF-9: varias fichas posibles, sin casillas. [habilitado] = se puede tocar "Elegir" (no mientras se carga);
     * [administrar] = el botón de RF-37.
     */
    data class ElegirFicha(
        val candidatas: List<HcPacienteCandidato>,
        val habilitado: Boolean,
        val administrar: Boolean,
    ) : PanelEnfermedades

    /**
     * Cualquier otro motivo, sin casillas (RF-10 a RF-13, también el rechazo de un toque con motivo, RF-27). [reintentar]
     * muestra [TEXTO_REINTENTAR] (P2-B); [administrar] muestra [TEXTO_ADMINISTRAR] (RF-37).
     */
    data class Motivo(val texto: String, val reintentar: Boolean, val administrar: Boolean) : PanelEnfermedades

    /** RF-31: sin conexión (o un error de la web) y sin copia de esa ficha: solo el [texto], sin grilla ni "Administrar". */
    data class SinCopia(val texto: String, val reintentar: Boolean) : PanelEnfermedades

    /**
     * La [vista] de la sección (grilla con "Otras", o "Estado actual del paciente", RF-18, RF-19, RF-35).
     *
     * @param aviso el último rechazo (RF-29); los motivos son el caso [Motivo].
     * @param avisoSoloLectura el aviso de RF-30 con la copia o tras RF-28; con él las casillas no se tocan
     *   (`EstadoMarcado.puedeTocar`) y la grilla sigue a la vista (P8-A).
     * @param guardado el contador de confirmaciones (RF-24): la pantalla muestra [TEXTO_GUARDADO] cuando sube.
     */
    data class Contenido(
        val vista: VistaSeccionEnfermedades,
        val aviso: String?,
        val avisoSoloLectura: String?,
        val reintentar: Boolean,
        val administrar: Boolean,
        val guardado: Int,
    ) : PanelEnfermedades
}

/**
 * Qué mostrar según [ui] y si la consulta se puede [editable] (abierta y propia, RF-35), en el orden de RF-14.
 *
 * - "Reintentar" nunca con [SeccionEnfermedadesUi.cargando] (el pedido ya está en vuelo). Con motivo, solo con los
 *   lugares 7, 8 y 9 y con el 5 si llegara acá; no con el 6 (ya tiene "Elegir") ni con 1 a 4, que reintentar no arregla
 *   (P2-B). Sin copia y en solo lectura, siempre (RF-30, RF-31).
 * - "Administrar" como [puedeAdministrar] (RF-37), con la misma conexión que el controlador ([SeccionEnfermedadesUi.conConexion]):
 *   sí con los motivos 6 a 9; no con 1 a 5, sin conexión, sin copia, cargando ni con la consulta cerrada.
 * - Los motivos 1 a 4 que rechaza la ventana de administrar no pasan por acá: el controlador no los pone en [ui], así
 *   que la sección de atrás queda como estaba (P1-A, RF-46).
 */
fun panelDeSeccion(ui: SeccionEnfermedadesUi, editable: Boolean): PanelEnfermedades {
    val motivo = ui.motivoMostrado
    val vista = ui.vista
    // Sin nada para dibujar: la primera carga (y, por defensa, un estado sin vista, motivo ni texto, que no debería darse).
    if (motivo == null && ui.textoSinCopia == null && vista == null) return PanelEnfermedades.Cargando

    val reintentable = !ui.cargando
    val administrar = puedeAdministrar(editable, propia = true, conConexion = ui.conConexion, motivo = motivo)
    return when {
        motivo is MotivoEnfermedades.ElegirFicha ->
            PanelEnfermedades.ElegirFicha(motivo.candidatas, habilitado = reintentable, administrar = administrar)
        motivo != null -> PanelEnfermedades.Motivo(
            texto = motivo.texto,
            reintentar = reintentable && motivo.prioridad in LUGARES_CON_REINTENTAR,
            administrar = administrar,
        )
        ui.textoSinCopia != null -> PanelEnfermedades.SinCopia(ui.textoSinCopia, reintentar = reintentable)
        else -> PanelEnfermedades.Contenido(
            vista = vistaSeccion(vista!!, editable),
            aviso = (ui.marcado.aviso as? AvisoEnfermedades.Rechazo)?.texto,
            avisoSoloLectura = ui.avisoSoloLectura,
            reintentar = ui.avisoSoloLectura != null && reintentable,
            administrar = administrar,
            guardado = ui.marcado.guardado,
        )
    }
}

/** Lugares de RF-14 con "Reintentar" (P2-B): el 5 (si llegara como motivo), sin ficha, ficha no disponible y fuera de la cartera. */
private val LUGARES_CON_REINTENTAR = setOf(5, 7, 8, 9)
