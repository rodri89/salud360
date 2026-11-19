package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio

// Lo que la pantalla de una consulta propia de la HC general dice y ofrece según su estado de envío (spec 005, T37; RF-4, RF-24, RF-25, RF-26,
// RF-30, RF-55). Puro: sin base, red ni reloj; la pantalla (Compose) dibuja lo que esto decide y no decide nada (constitución, punto 4).
// Es la tabla de textos de la spec en un solo lugar: la pantalla no lleva literales propios.

/** Textos de las acciones y de la confirmación de anular (los de los estados están en `ClasificacionDeEnvio` y `EstadoDeLaConsulta`). */
const val TEXTO_DESCARTAR = "Descartar"
const val TEXTO_ANULAR_CONSULTA = "Anular consulta"
const val TEXTO_CONFIRMAR_ANULAR = "Anular"

/** El aviso base de la confirmación y los que se suman según el estado de la consulta (T28, decisión (f); RF-26). */
const val AVISO_ANULAR_BASE = "La consulta queda anulada: ya no se puede editar ni reabrir."
const val AVISO_ANULAR_YA_EN_LA_WEB = "Ya está creada en la web: se la da de baja allí (no se borra) y deja de verse como abierta."
const val AVISO_ANULAR_DATOS_DEL_PACIENTE =
    "Lo que ya se envió del paciente (laboratorio, peso, estudios, exámenes, interconsultas y antecedentes) no se borra: sigue en su historial."
const val AVISO_ANULAR_CIERRE_PENDIENTE = "Está cerrada en este dispositivo, pero el cierre todavía no llegó a la web: al anularla se pierde ese cierre."
const val AVISO_ANULAR_PENDIENTE = "Lo que todavía no se envió no se manda: queda guardado solo en este dispositivo."

/** Qué tipo de franja de estado se muestra arriba de la consulta. */
enum class TipoDeBanner { ENVIANDO_EL_CIERRE, NO_SE_PUDO_ENVIAR, EN_ESPERA, FALTA_ENVIAR, DESCARTADO }

/** Una franja de estado de envío: el [texto] de la tabla de la spec y las acciones que ofrece (a mano; nada se reintenta por acá solo). */
data class BannerDeEnvio(val tipo: TipoDeBanner, val texto: String, val puedeReintentar: Boolean = false, val puedeDescartar: Boolean = false)

/**
 * Las franjas de estado de [estado], de la más importante a la menos; ninguna si no hay estado (histórico, lo de la web, otras historias)
 * o si no falta nada. **«Enviando el cierre.»** manda sobre todo y no ofrece acciones (RF-55). Si no, la franja de lo pendiente: «Falta
 * enviar» (RF-24, sin acciones), «En espera: <motivo>» (RF-6: se reintenta sola y el médico puede **Reintentar**; no se descarta una espera,
 * se perdería lo que falta mandar) o «No se pudo enviar: <motivo>» (RF-30: **Reintentar** o **Descartar**). Y, aparte, «Descartado: no se
 * envió a la web» mientras haya partes descartadas (siguen solo en el dispositivo).
 */
fun bannersDeEnvio(estado: EstadoDeLaConsulta?): List<BannerDeEnvio> {
    if (estado == null) return emptyList()
    if (estado.enviandoElCierre) return listOf(BannerDeEnvio(TipoDeBanner.ENVIANDO_EL_CIERRE, TEXTO_ENVIANDO_EL_CIERRE))
    val pendiente = when (val e = estado.envio) {
        null, EstadoEnvio.Descartado -> null
        EstadoEnvio.Pendiente -> BannerDeEnvio(TipoDeBanner.FALTA_ENVIAR, textoDelEstado(e))
        is EstadoEnvio.EnEspera -> BannerDeEnvio(TipoDeBanner.EN_ESPERA, textoDelEstado(e), puedeReintentar = true)
        is EstadoEnvio.NoSePudoEnviar -> BannerDeEnvio(TipoDeBanner.NO_SE_PUDO_ENVIAR, textoDelEstado(e), puedeReintentar = true, puedeDescartar = true)
    }
    val descartado = BannerDeEnvio(TipoDeBanner.DESCARTADO, TEXTO_DESCARTADO).takeIf { estado.partesDescartadas.isNotEmpty() }
    return listOfNotNull(pendiente, descartado)
}

/** La confirmación de anular una consulta propia: el título, los avisos (uno por línea) y el texto del botón. */
data class ConfirmacionDeAnular(val titulo: String, val avisos: List<String>, val textoConfirmar: String) {
    val mensaje: String get() = avisos.joinToString("\n\n")
}

/**
 * Qué avisa la confirmación de anular [consulta] (RF-26; T28, decisión (f)): lo básico; que una ya creada se da de baja en la web -no se
 * borra- y que lo ya enviado del paciente queda en su historial; que una **cerrada cuyo cierre todavía no llegó a la web se queda sin ese
 * cierre**; y que lo pendiente no se manda. La pantalla solo ofrece «Anular» si `EstadoDeLaConsulta.puedeAnular`.
 */
fun confirmacionDeAnular(consulta: Consulta, estado: EstadoDeLaConsulta?): ConfirmacionDeAnular {
    val avisos = buildList {
        add(AVISO_ANULAR_BASE)
        if (consulta.remotoId.isNotBlank()) {
            add(AVISO_ANULAR_YA_EN_LA_WEB)
            add(AVISO_ANULAR_DATOS_DEL_PACIENTE)
        }
        if (consulta.estado == EstadoConsulta.CERRADA && estado?.cierreEnviado != true) add(AVISO_ANULAR_CIERRE_PENDIENTE)
        if (estado?.envio != null) add(AVISO_ANULAR_PENDIENTE)
    }
    return ConfirmacionDeAnular(TEXTO_ANULAR_CONSULTA, avisos, TEXTO_CONFIRMAR_ANULAR)
}