package com.salud360.core.data.repos

/**
 * Algo que, al cerrar sesión, puede revocar el token en un servidor (turnosonlinebb o una HC con API, RF-59).
 *
 * Interfaz propia e inyectable para probar `AuthRepository.logout` sin red y sin dependencias de prueba nuevas (C3).
 * Recibe el token por parámetro: cuando corre, `logout` ya borró el de los clientes. Un error cualquiera (sin conexión,
 * 404 de una API vieja, lentitud) no importa: `AuthRepository` lo ignora y el cierre en el dispositivo no lo espera (C4).
 */
fun interface RevocaSesion {
    suspend fun revocar(token: String)
}
