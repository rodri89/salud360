package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException

/**
 * Por qué no se pudo usar una historia clínica (traer la cartera, vincular un paciente, abrir una sección), con el
 * [texto] para mostrar, que la nombra (spec 002 RF-9). Lo usan el resto de la 002 y la 003 (RF-13).
 */
data class MotivoHc(val tipo: Tipo, val texto: String) {
    /** En el orden en que la spec 003 (RF-14) elige cuál mostrar cuando aplica más de uno. */
    enum class Tipo { SESION_VENCIDA, HC_NO_HABILITADA, MEDICO_NO_VINCULADO, LICENCIA_VENCIDA, SIN_CONEXION, OTRO }
}

/**
 * El motivo de un error de la API de una historia clínica; [nombreHc] es su nombre visible ("Clínica", "Pediatría").
 * Los códigos de identidad son los del middleware de las APIs (`sin_token`, `token`, `tobb_caido`, `hc_no_habilitada`,
 * `medico_no_vinculado`, `licencia_vencida`) y `red` el de [com.salud360.core.data.network.hc.HcApiClient]; con
 * ellos el texto es propio (el de `red` trae el detalle técnico del fallo). Cualquier otro (también `ocupado` después
 * del reintento y el `500` sin código) muestra el mensaje de la web.
 */
fun motivoHc(nombreHc: String, e: TobbException): MotivoHc {
    val (tipo, texto) = when {
        e.codigo == "red" || e.codigo == "tobb_caido" || (e.status == 0 && e.codigo == null) ->
            MotivoHc.Tipo.SIN_CONEXION to "sin conexión. Intentá de nuevo en un momento."
        e.codigo == "token" || e.codigo == "sin_token" ->
            MotivoHc.Tipo.SESION_VENCIDA to "sesión vencida. Volvé a iniciar sesión."
        e.codigo == "hc_no_habilitada" ->
            MotivoHc.Tipo.HC_NO_HABILITADA to "no habilitada. Avisale al administrador."
        e.codigo == "medico_no_vinculado" ->
            MotivoHc.Tipo.MEDICO_NO_VINCULADO to "tu usuario no está vinculado con turnosonlinebb. Avisale al administrador."
        e.codigo == "licencia_vencida" ->
            MotivoHc.Tipo.LICENCIA_VENCIDA to "licencia vencida. Comunicate con el administrador para renovarla."
        else -> MotivoHc.Tipo.OTRO to (e.message?.trim()?.ifEmpty { null } ?: "respondió ${e.status}.")
    }
    return MotivoHc(tipo, "Historia clínica de $nombreHc: $texto")
}

/** RF-56: el `429` de Laravel (límite de pedidos por minuto), que llega sin código y con un texto en inglés. */
const val TEXTO_DEMASIADOS_PEDIDOS = "demasiados pedidos seguidos. Intentá de nuevo en un momento."

/**
 * El motivo por el que no se pudo leer la historia de la web (RF-56, RF-58): el de [motivoHc] y, para el `429`, «demasiados
 * pedidos» ([TEXTO_DEMASIADOS_PEDIDOS]) en vez del texto de Laravel. Un error que no es de la API dice su mensaje.
 */
fun motivoDeLaLectura(nombreHc: String, e: Throwable): MotivoHc {
    if (e !is TobbException) return motivoHc(nombreHc, TobbException(0, "respuesta", e.message?.trim()?.ifEmpty { null } ?: "error"))
    if (e.status == 429) return MotivoHc(MotivoHc.Tipo.OTRO, "Historia clínica de $nombreHc: $TEXTO_DEMASIADOS_PEDIDOS")
    return motivoHc(nombreHc, e)
}
