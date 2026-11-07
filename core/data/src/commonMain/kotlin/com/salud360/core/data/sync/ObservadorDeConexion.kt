package com.salud360.core.data.sync

/**
 * Avisa cuando vuelve la conexión (spec 005, RF-57). La implementa cada plataforma (`ObservadorDeConexionDelSistema`, un `expect class`):
 * la web con el evento `online` del navegador y Android con `ConnectivityManager`. En iOS queda un `actual` vacío hasta T32 (`NWPathMonitor`,
 * que solo se escribe y se prueba en macOS); en la JVM (tests) no hace nada.
 *
 * Solo informa: no envía nada ni sabe qué hay pendiente. Quien lo usa es [HcClinicaEnvio.observar], que pide un envío con la misma espera
 * de 4 s de siempre de lo propio del médico de la sesión.
 */
interface ObservadorDeConexion {
    /**
     * Empieza a observar y llama a [alRecuperar] **cada vez que la conexión vuelve** (pasa de sin red a con red). **No** la llama al
     * registrarse, aunque ya haya red: abrir la app es otro disparador (`HcClinicaEnvio.alAbrirOIniciar`). Volver a llamar a [iniciar]
     * reemplaza al aviso anterior. [alRecuperar] puede llegar desde cualquier hilo y debe ser liviano.
     */
    fun iniciar(alRecuperar: () -> Unit)

    /** Deja de observar. No pasa nada si no estaba observando. */
    fun detener()
}