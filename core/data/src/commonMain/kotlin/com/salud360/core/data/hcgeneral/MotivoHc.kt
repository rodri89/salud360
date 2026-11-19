package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

/**
 * El motivo por el que un **envío** a la HC general no salió o fue rechazado (spec 005, RF-6, RF-30): los códigos nuevos de la
 * escritura con su texto propio y, para todo lo que ya tenía motivo ([motivoHc], [motivoDeLaLectura]), el mismo texto de siempre.
 * Siempre nombra la HC con [nombreHc]. Un `422` se distingue por el `motivo` del cuerpo (`clave_en_uso`, `texto_demasiado_largo`),
 * no solo por el código.
 */
fun motivoDelEnvio(nombreHc: String, e: TobbException): MotivoHc {
    val propio = when {
        e.codigo == "fuera_de_cartera" -> "este paciente no está en tu cartera."
        e.codigo == "paciente_no_encontrado" -> "la ficha de este paciente no está disponible en la web."
        e.codigo == "sin_permiso" -> "tu usuario no puede cargar consultas en esta historia clínica. Avisale al administrador."
        e.codigo == "consulta_no_encontrada" -> "la consulta ya no figura en la web."
        e.codigo == "consulta_cerrada" -> "la consulta ya está cerrada o dada de baja en la web."
        e.codigo == "consulta_no_propia" -> "esta consulta no se puede modificar desde la app (la cargó la web u otro médico)."
        e.codigo == "datos_invalidos" || e.status == 422 -> textoDeDatosInvalidos(e)
        e.codigo == "tobb_caido" || e.codigo == "red" -> null
        e.status >= 500 -> "la web no pudo guardar los cambios. Se vuelve a intentar sola."
        (e.status == 0 && e.codigo == "respuesta") || (e.status in 200..299 && e.codigo == null) ->
            "la web respondió algo inesperado. Se vuelve a intentar sola."
        else -> null
    }
    return if (propio != null) MotivoHc(MotivoHc.Tipo.OTRO, "Historia clínica de $nombreHc: $propio") else motivoDeLaLectura(nombreHc, e)
}

private fun textoDeDatosInvalidos(e: TobbException): String = when (e.cuerpo?.get("motivo")?.jsonPrimitive?.contentOrNull) {
    "clave_en_uso" -> "el identificador de este envío ya se usó para otra cosa. Reintentar no lo arregla: avisale al administrador."
    "texto_demasiado_largo" -> "un texto es demasiado largo para la web. Acortalo y volvé a enviar."
    else -> "la web no aceptó los datos. ${e.message?.trim().orEmpty()}".trim()
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
