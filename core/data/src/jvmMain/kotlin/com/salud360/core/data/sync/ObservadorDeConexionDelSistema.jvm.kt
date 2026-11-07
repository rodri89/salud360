package com.salud360.core.data.sync

/** En la JVM (los tests) no hay conexión que observar: no avisa nunca. */
actual class ObservadorDeConexionDelSistema : ObservadorDeConexion {
    actual override fun iniciar(alRecuperar: () -> Unit) = Unit

    actual override fun detener() = Unit
}