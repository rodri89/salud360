package com.salud360.core.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import co.touchlab.kermit.Logger

/**
 * Android: `ConnectivityManager.registerDefaultNetworkCallback` (API 24, la mínima de la app; necesita el permiso `ACCESS_NETWORK_STATE`).
 * «Hay red» es que la red por omisión tenga internet **validado**. Avisa solo en la transición de sin red a con red: el aviso que el
 * sistema manda al registrarse, con la red ya andando, no cuenta (abrir la app es otro disparador).
 */
actual class ObservadorDeConexionDelSistema(context: Context) : ObservadorDeConexion {
    private val conectividad = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val log = Logger.withTag("Conexion")
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var hayRed = false

    actual override fun iniciar(alRecuperar: () -> Unit) {
        detener()
        val manager = conectividad ?: return
        hayRed = conRed(manager.getNetworkCapabilities(manager.activeNetwork))
        val nuevo = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capacidades: NetworkCapabilities) {
                val conRed = conRed(capacidades)
                if (conRed && !hayRed) {
                    hayRed = true
                    alRecuperar()
                } else if (!conRed) {
                    hayRed = false
                }
            }

            override fun onLost(network: Network) {
                hayRed = false
            }
        }
        try {
            manager.registerDefaultNetworkCallback(nuevo)
            callback = nuevo
        } catch (e: RuntimeException) {
            // Sin el permiso o sin servicio: no se avisa, pero la app sigue (se envía al abrir y con los reintentos de la cola).
            log.w(e) { "no se pudo observar la conexión" }
        }
    }

    actual override fun detener() {
        val actual = callback ?: return
        callback = null
        try {
            conectividad?.unregisterNetworkCallback(actual)
        } catch (e: RuntimeException) {
            log.w(e) { "no se pudo dejar de observar la conexión" }
        }
    }

    private fun conRed(capacidades: NetworkCapabilities?): Boolean =
        capacidades != null &&
            capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}