package com.salud360.core.data.sync

/**
 * Web: el evento `online` del navegador, que salta cuando el navegador pasa de sin red a con red (modo avión y vuelta, wifi que vuelve). No
 * salta al cargar la página, así que registrarse no dispara nada.
 *
 * El oyente se registra una sola vez y no se quita: [detener] solo suelta el aviso. Se usa `js()` como el resto del código de la web de este
 * repo (`kotlinx.browser` no está en el classpath de `core:data` y esta tarea no suma dependencias).
 */
actual class ObservadorDeConexionDelSistema : ObservadorDeConexion {
    private var aviso: (() -> Unit)? = null
    private var registrado = false

    actual override fun iniciar(alRecuperar: () -> Unit) {
        aviso = alRecuperar
        if (registrado) return
        registrado = true
        escucharOnline { aviso?.invoke() }
    }

    actual override fun detener() {
        aviso = null
    }
}

private fun escucharOnline(aviso: () -> Unit): Unit = js("{ window.addEventListener('online', () => aviso()); }")