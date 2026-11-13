package com.salud360.core.data.repos

import com.salud360.core.data.network.hc.RespuestaLimitesDeAdjuntosDto

/**
 * Capacidad opcional de un [HcBackend] que sube adjuntos: **el tope antes de subir** (spec 005, T107; RF-44, D18). `HcRepository` la usa solo
 * con los backends que la declaran (hoy la HC general) para que la pantalla pregunte, con `limiteExcedido`, si el archivo que el médico eligió
 * entra **antes** de guardarlo; la cola lo vuelve a comparar al subir (T104). [HcBackend] no cambia y pediatría y las demás historias clínicas
 * siguen exactamente igual.
 */
interface LimitesDeAdjuntos {
    /**
     * El máximo **efectivo** que informa la web (`GET adjuntos/limites`): el menor entre el que acepta la web y el del hosting (D18).
     * `null` si esta instalación no tiene la API de adjuntos. Es un solo pedido, sin reintento propio: si falla, lanza el error de la API y
     * quien llama decide (la pantalla no frena el archivo por eso).
     */
    suspend fun limiteDeAdjuntos(): RespuestaLimitesDeAdjuntosDto?
}
