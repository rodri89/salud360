package com.salud360.core.data.repos

import com.salud360.core.model.Id

/**
 * Capacidad opcional de un [HcBackend] que lee de la web: **listar al abrir** los documentos de la sección `documentos` y dejarlos en el
 * dispositivo (spec 005, T106; RF-45). `HcRepository` la usa solo con los backends que la declaran (hoy la HC general); [HcBackend] no
 * cambia y pediatría y las demás historias clínicas siguen exactamente igual.
 */
interface ListaDeAdjuntos {
    /**
     * Lista los documentos de la consulta [consultaLocalId] que se acaba de abrir, con la regla de Q-E4-3: si es una consulta **nueva y en
     * curso** (una propia abierta, la que se está cargando), **todos** los documentos del paciente (`GET pacientes/{id}/adjuntos`); si es una
     * **previa** (cerrada o del histórico), **solo** los de esa consulta (`GET consultas/{id}/adjuntos`). Nunca desde el autoguardado. Es un
     * solo pedido; si falla lanza el error de la API, sin reintento propio (quien llama decide que la pantalla no se rompa por esto).
     */
    suspend fun traerAdjuntos(consultaLocalId: Id)
}
