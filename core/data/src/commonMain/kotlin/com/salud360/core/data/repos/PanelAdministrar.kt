package com.salud360.core.data.repos

import com.salud360.core.data.hcgeneral.Enfermedad
import com.salud360.core.data.hcgeneral.errorDeNombreEnfermedad
import com.salud360.core.data.hcgeneral.nombreNormalizado

/**
 * Qué dibuja la ventana "Administrar enfermedades" para un [AdministrarEnfermedadesUi] (spec 003, RF-37 a RF-47, RF-58).
 * La pantalla solo dibuja el caso que toca; la decisión es de [panelDeAdministrar]. No hay ningún caso para renombrar,
 * dar de baja ni eliminar enfermedades del catálogo (RF-47).
 */
sealed interface PanelAdministrar {

    /** Todavía no hay lista ni catálogo que mostrar: "Cargando…". */
    data object Cargando : PanelAdministrar

    /**
     * Falló al abrir: solo el [texto] que ya arma el controlador, también con los motivos 1 a 5 (RF-46). Solo lo muestra
     * la ventana; la sección de atrás queda como estaba (P1-A). Sin "Reintentar": se cierra y se vuelve a abrir.
     */
    data class SoloAviso(val texto: String) : PanelAdministrar

    /**
     * La ventana con datos. [lista] y [catalogo] ya vienen filtrados por lo escrito y en orden alfabético (RF-39);
     * [textoCatalogo] reemplaza a lo de "Del catálogo" mientras no haya nada escrito; [crear] es la oferta de RF-42 o
     * su error (RF-43); [aviso] el último resultado o rechazo (RF-25 a RF-28, RF-44, RF-46, RF-58). [accionesHabilitadas]
     * apaga "Quitar", "Agregar" y "Crear" mientras haya un cambio de la lista en camino (RF-45).
     */
    data class Contenido(
        val lista: List<Enfermedad>,
        val catalogo: List<Enfermedad>,
        val textoCatalogo: String?,
        val crear: CrearVisible?,
        val aviso: String?,
        val accionesHabilitadas: Boolean,
    ) : PanelAdministrar
}

/**
 * "Crear «[nombre]»" (RF-42) con el nombre normalizado. Con [error] (RF-43) no hay botón: se ve el texto en rojo apenas
 * se escribe (P5-A), porque la app no lo enviaría igual.
 */
data class CrearVisible(val nombre: String, val error: String?)

/**
 * Qué mostrar según [ui], con el filtro y la oferta de `AdministrarEnfermedades.kt` (RF-39, RF-42).
 *
 * - Sin lista ni catálogo: [PanelAdministrar.Cargando] mientras hay un pedido, [PanelAdministrar.SoloAviso] si falló al
 *   abrir. Sin pedido ni aviso (el instante antes de que el controlador marque el pedido, o si no pudo pedir) también
 *   [PanelAdministrar.Cargando]: la ventana nunca queda vacía.
 * - Lo escrito se conserva tras crear y tras un rechazo (P3-A, RF-46): el panel solo lo refleja en el filtro.
 * - Solo espacios (RF-43): `ofrecerCrear` no ofrece nada con el nombre vacío, pero P5-A pide verlo apenas se escribe, así
 *   que el panel arma [CrearVisible] con el error de vacío; con el buscador sin tocar no hay error.
 */
fun panelDeAdministrar(ui: AdministrarEnfermedadesUi): PanelAdministrar {
    val busqueda = ui.busqueda
    if (busqueda == null) {
        return if (!ui.cargando && ui.aviso != null) PanelAdministrar.SoloAviso(ui.aviso) else PanelAdministrar.Cargando
    }
    val oferta = ui.oferta
    val crear = when {
        oferta != null -> CrearVisible(oferta.nombre, oferta.error)
        ui.buscado.isNotEmpty() && nombreNormalizado(ui.buscado).isEmpty() ->
            CrearVisible(nombre = "", error = errorDeNombreEnfermedad(""))
        else -> null
    }
    return PanelAdministrar.Contenido(
        lista = busqueda.lista,
        catalogo = busqueda.catalogo,
        textoCatalogo = busqueda.textoCatalogo,
        crear = crear,
        aviso = ui.aviso,
        accionesHabilitadas = !ui.enCamino,
    )
}
