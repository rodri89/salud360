package com.salud360.core.data.sync

/**
 * El [ObservadorDeConexion] de cada plataforma (spec 005, T31, RF-57). Cada plataforma lo aporta al armar Koin, como `ArchivoStore`:
 * - **Web** (`wasmJsMain`): el evento `online` del navegador.
 * - **Android** (`androidMain`): `ConnectivityManager` (necesita el permiso `ACCESS_NETWORK_STATE` del manifiesto).
 * - **iOS** (`iosMain`): **todavía vacío**, no avisa nunca. Su observador real (`NWPathMonitor`) es la tarea T32 y solo se escribe y se
 *   prueba en macOS. Mientras tanto en iOS se envía al abrir la app, al iniciar sesión y por los reintentos de la cola, pero no al volver la red.
 * - **JVM** (`jvmMain`, tests): no hace nada.
 */
expect class ObservadorDeConexionDelSistema : ObservadorDeConexion {
    override fun iniciar(alRecuperar: () -> Unit)
    override fun detener()
}