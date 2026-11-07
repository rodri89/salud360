package com.salud360.core.data.sync

/**
 * **iOS: vacío a propósito hasta la tarea T32** (spec 005, RF-57). El observador de verdad usa `NWPathMonitor` y solo se escribe y se
 * prueba en macOS; en Windows los targets iOS ni se compilan. Hasta entonces no avisa nunca, así que en iOS se envía al abrir la app, al
 * iniciar sesión y con los reintentos de la cola (cada 5 minutos), pero no apenas vuelve la conexión.
 */
actual class ObservadorDeConexionDelSistema : ObservadorDeConexion {
    actual override fun iniciar(alRecuperar: () -> Unit) = Unit

    actual override fun detener() = Unit
}