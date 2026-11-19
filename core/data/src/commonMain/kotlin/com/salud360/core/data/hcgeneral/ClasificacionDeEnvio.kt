package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.TobbException
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.CausaDeRechazo
import com.salud360.core.model.hc.CausaEnEspera
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// Qué hacer con un error del envío a la HC general (spec 005, RF-6, RF-19, RF-30). Puro: ni red, ni base, ni reloj. Dos familias
// que no se mezclan:
// - **Causa ajena al dato** → «En espera: <motivo>» (RF-6): sesión vencida, HC no habilitada, médico no vinculado, licencia
//   vencida, paciente fuera de la cartera o de baja, sin permiso, sin conexión, `429`, `5xx`, `503 tobb_caido` o una respuesta que no
//   se puede leer. No es un rechazo: se vuelve a intentar sola (RF-29) y nada se pierde ni se modifica.
// - **Causa del dato** → «No se pudo enviar: <motivo>» (RF-30): `422` (datos que la web no acepta), `404 consulta_no_encontrada`,
//   `409` (consulta cerrada o dada de baja) y `403 consulta_no_propia`. No se arregla reintentando: no se reintenta sola; el médico
//   la reintenta a mano o la descarta.
// El motivo siempre nombra la HC con el `nombreHc` que da el registro de especialidades (el mismo de la cartera y la licencia).

/** Textos de los estados (spec 005, tabla de textos). */
const val TEXTO_FALTA_ENVIAR = "Falta enviar"
const val TEXTO_DESCARTADO = "Descartado: no se envió a la web"

/**
 * Lo que se decide de un error del envío: el [estado] (siempre `EnEspera` o `NoSePudoEnviar`), si se vuelve a intentar sola
 * ([reintentoAutomatico]: solo «En espera»), si ese reintento va con espera y sin ráfaga ([conEspera]: `429`, `5xx`, sin red y
 * respuestas que no se leen; RF-29, RF-30) y si hay que poner «Ya no figura en la web» a la consulta ([marcaYaNoFigura], RF-19, RF-33).
 */
data class ClasificacionDeEnvio(
    val estado: EstadoEnvio,
    val reintentoAutomatico: Boolean,
    val conEspera: Boolean,
    val marcaYaNoFigura: Boolean,
)

/**
 * Clasifica [error]. [yaEnviada] dice si la consulta ya estaba creada en la web: solo entonces un `404 consulta_no_encontrada`
 * marca «Ya no figura en la web» (si nunca llegó, no hay nada que marcar; lo pendiente igual pasa a «No se pudo enviar»). Un error
 * que no es de la API se trata como una respuesta que no se pudo leer: queda «En espera» y no se pierde nada.
 */
fun clasificarErrorDeEnvio(error: Throwable, nombreHc: String, yaEnviada: Boolean = false): ClasificacionDeEnvio {
    val e = error as? TobbException ?: TobbException(0, "respuesta", error.message?.trim()?.ifEmpty { null } ?: "error")
    val motivo = motivoDelEnvio(nombreHc, e).texto
    val codigo = e.codigo

    fun espera(causa: CausaEnEspera, conEspera: Boolean = false) =
        ClasificacionDeEnvio(EstadoEnvio.EnEspera(causa, motivo), reintentoAutomatico = true, conEspera = conEspera, marcaYaNoFigura = false)

    fun rechazo(causa: CausaDeRechazo, marca: Boolean = false) =
        ClasificacionDeEnvio(EstadoEnvio.NoSePudoEnviar(causa, motivo), reintentoAutomatico = false, conEspera = false, marcaYaNoFigura = marca)

    return when {
        codigo == "red" -> espera(CausaEnEspera.SIN_CONEXION, conEspera = true)
        codigo == "sin_token" || codigo == "token" || e.status == 401 -> espera(CausaEnEspera.SESION_VENCIDA)
        codigo == "hc_no_habilitada" -> espera(CausaEnEspera.HC_NO_HABILITADA)
        codigo == "medico_no_vinculado" -> espera(CausaEnEspera.MEDICO_NO_VINCULADO)
        codigo == "licencia_vencida" -> espera(CausaEnEspera.LICENCIA_VENCIDA)
        codigo == "sin_permiso" -> espera(CausaEnEspera.SIN_PERMISO)
        codigo == "fuera_de_cartera" -> espera(CausaEnEspera.FUERA_DE_CARTERA)
        codigo == "paciente_no_encontrado" -> espera(CausaEnEspera.PACIENTE_DE_BAJA)
        codigo == "consulta_no_encontrada" -> rechazo(CausaDeRechazo.CONSULTA_DE_BAJA, marca = yaEnviada)
        codigo == "consulta_cerrada" -> rechazo(CausaDeRechazo.CONSULTA_CERRADA)
        codigo == "consulta_no_propia" -> rechazo(CausaDeRechazo.CONSULTA_NO_PROPIA)
        // El `422` se lee por el `motivo` del cuerpo y no solo por el código: `clave_en_uso` no es un código sino el motivo de un `datos_invalidos`.
        codigo == "datos_invalidos" || e.status == 422 ->
            rechazo(if (e.cuerpo?.get("motivo")?.jsonPrimitive?.contentOrNull == "clave_en_uso") CausaDeRechazo.CLAVE_EN_USO else CausaDeRechazo.DATOS_INVALIDOS)
        e.status == 429 -> espera(CausaEnEspera.DEMASIADOS_PEDIDOS, conEspera = true)
        codigo == "tobb_caido" || e.status >= 500 -> espera(CausaEnEspera.SERVIDOR, conEspera = true)
        e.status == 0 || e.status in 200..299 -> espera(CausaEnEspera.SERVIDOR, conEspera = true)
        e.status == 409 -> rechazo(CausaDeRechazo.CONSULTA_CERRADA)
        else -> rechazo(CausaDeRechazo.OTRO)
    }
}

/**
 * De varias clasificaciones de una misma consulta, la que manda (RF-19): primero el paciente de baja o inexistente («En espera»,
 * porque una ficha se puede reactivar); después la consulta de baja o inexistente; después una causa ajena cualquiera (un rechazo
 * volverá a salir al reintentar); y por último cualquier otro rechazo. A igual lugar, la primera. `null` si no hay ninguna.
 */
fun masPrioritaria(clasificaciones: List<ClasificacionDeEnvio>): ClasificacionDeEnvio? = clasificaciones.minByOrNull { it.rango() }

private fun ClasificacionDeEnvio.rango(): Int = when (val e = estado) {
    is EstadoEnvio.EnEspera -> if (e.causa == CausaEnEspera.PACIENTE_DE_BAJA) 0 else 2
    is EstadoEnvio.NoSePudoEnviar -> if (e.causa == CausaDeRechazo.CONSULTA_DE_BAJA) 1 else 3
    EstadoEnvio.Pendiente, EstadoEnvio.Descartado -> 4
}

/** El texto del estado para la pantalla: «Falta enviar», «En espera: <motivo>», «No se pudo enviar: <motivo>» o «Descartado: no se envió a la web». */
fun textoDelEstado(estado: EstadoEnvio): String = when (estado) {
    EstadoEnvio.Pendiente -> TEXTO_FALTA_ENVIAR
    is EstadoEnvio.EnEspera -> "En espera: ${estado.motivo}"
    is EstadoEnvio.NoSePudoEnviar -> "No se pudo enviar: ${estado.motivo}"
    EstadoEnvio.Descartado -> TEXTO_DESCARTADO
}

// ---------- Cómo se guarda el estado (plan, «Estados persistentes») ----------
// En `paciente_extra` del paciente, especialidad `clinica`, sin columnas nuevas ni migración: una fila `hce_estado_<consulta>:<parte>`
// por parte de la consulta (`crear`, `secciones`, `examen`, `cierre`, `baja`, ...) con `<estado>|<desde>|<causa>|<motivo>`. Es un dato
// local: `dirty = 0` siempre. «Falta enviar» no se guarda: es que la fila no existe (lo dice `dirty` de lo que se editó).

/** Prefijo de la clave de `paciente_extra` con el estado de envío de una parte de una consulta. */
const val PREFIJO_ESTADO_ENVIO = "hce_estado_"

/** El estado guardado y desde qué día está así. */
data class EstadoGuardado(val estado: EstadoEnvio, val desde: LocalDate)

private const val ESPERA = "espera"
private const val RECHAZADO = "rechazado"
private const val DESCARTADO = "descartado"
private const val SIN_CAUSA = "-"

/** `hce_estado_<consulta>:<parte>`. Ni el id local de la consulta ni la parte llevan `:`. */
fun claveDeEstadoDeEnvio(consultaId: Id, parte: String): String = "$PREFIJO_ESTADO_ENVIO$consultaId:$parte"

/** Lo que se guarda como valor de [estado], o `null` para [EstadoEnvio.Pendiente] (no se guarda: es la ausencia de un estado). */
fun estadoDeEnvioComoTexto(estado: EstadoEnvio, desde: LocalDate): String? = when (estado) {
    EstadoEnvio.Pendiente -> null
    is EstadoEnvio.EnEspera -> "$ESPERA|$desde|${estado.causa.name}|${estado.motivo}"
    is EstadoEnvio.NoSePudoEnviar -> "$RECHAZADO|$desde|${estado.causa.name}|${estado.motivo}"
    EstadoEnvio.Descartado -> "$DESCARTADO|$desde|$SIN_CAUSA|"
}

/** Lo que [estadoDeEnvioComoTexto] guardó; `null` si no hay texto o no se entiende (no se inventa un estado). El motivo puede llevar `|`. */
fun estadoDeEnvioDeTexto(texto: String?): EstadoGuardado? {
    val partes = texto?.split("|", limit = 4)?.takeIf { it.size == 4 } ?: return null
    val (tipo, fecha, causa, motivo) = partes
    val desde = runCatching { LocalDate.parse(fecha) }.getOrNull() ?: return null
    val estado = when (tipo) {
        ESPERA -> CausaEnEspera.entries.firstOrNull { it.name == causa }?.let { EstadoEnvio.EnEspera(it, motivo) }
        RECHAZADO -> CausaDeRechazo.entries.firstOrNull { it.name == causa }?.let { EstadoEnvio.NoSePudoEnviar(it, motivo) }
        DESCARTADO -> EstadoEnvio.Descartado.takeIf { causa == SIN_CAUSA }
        else -> null
    } ?: return null
    return EstadoGuardado(estado, desde)
}

/** La fila de `paciente_extra` que guarda [estado]; `null` para [EstadoEnvio.Pendiente]. */
fun filaDeEstadoDeEnvio(pacienteId: Id, consultaId: Id, parte: String, estado: EstadoEnvio, desde: LocalDate): PacienteExtra? =
    estadoDeEnvioComoTexto(estado, desde)?.let { PacienteExtra(pacienteId, CodigosHc.GENERAL, claveDeEstadoDeEnvio(consultaId, parte), it) }

/**
 * Los estados que hay en [extras] (las filas de `paciente_extra` del paciente), por consulta y parte. Lo que no es de la HC
 * general, no es un estado o no se entiende no cuenta.
 */
fun estadosDeEnvio(extras: List<PacienteExtra>): Map<Id, Map<String, EstadoGuardado>> =
    extras.asSequence()
        .filter { it.especialidad == CodigosHc.GENERAL && it.clave.startsWith(PREFIJO_ESTADO_ENVIO) }
        .mapNotNull { fila ->
            val resto = fila.clave.removePrefix(PREFIJO_ESTADO_ENVIO)
            val corte = resto.lastIndexOf(':')
            if (corte <= 0 || corte == resto.lastIndex) return@mapNotNull null
            val guardado = estadoDeEnvioDeTexto(fila.valor) ?: return@mapNotNull null
            Triple(resto.substring(0, corte), resto.substring(corte + 1), guardado)
        }
        .groupBy({ it.first }, { it.second to it.third })
        .mapValues { (_, partes) -> partes.toMap() }
