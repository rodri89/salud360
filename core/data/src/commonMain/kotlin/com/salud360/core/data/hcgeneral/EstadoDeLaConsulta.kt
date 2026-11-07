package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.EstadoEnvio

// Cómo se ve una consulta propia de la HC general por lo que falta enviar, y qué se puede hacer con ella (spec 005, RF-24, RF-25, RF-26,
// RF-55, D13, D19(a)). Puro: sin base, red ni reloj; quien lee la base y pregunta a la cola es `HcClinicaEnvio.estadoDeLaConsulta`, y las
// mismas reglas gobiernan a `HcRepository.reabrirConsulta` y `anularConsulta` (una sola fuente de verdad: la pantalla ofrece lo que el
// repositorio va a permitir). Solo para consultas propias: el histórico no se envía y lo de la web solo se lee.

/** El texto mientras el cierre viaja (RF-55): no se puede reabrir ni anular hasta saber el resultado. */
const val TEXTO_ENVIANDO_EL_CIERRE = "Enviando el cierre."

/**
 * El estado de envío de una consulta propia, para su tarjeta y su banner. [envio] es lo que falta mandar y cómo está —«Falta enviar»
 * ([EstadoEnvio.Pendiente]), «En espera» o «No se pudo enviar»— o `null` si no falta nada. [cierreEnviado]: la web ya la tiene cerrada.
 */
data class EstadoDeLaConsulta(
    val envio: EstadoEnvio?,
    val enviandoElCierre: Boolean,
    val cierreEnviado: Boolean,
    val puedeReabrir: Boolean,
    val puedeAnular: Boolean,
) {
    /** La marca de la tarjeta: «Enviando el cierre.», «Falta enviar», «En espera: <motivo>» o «No se pudo enviar: <motivo>»; `null` si no hay. */
    val marca: String? get() = if (enviandoElCierre) TEXTO_ENVIANDO_EL_CIERRE else envio?.let { textoDelEstado(it) }
}

/**
 * La web ya tiene cerrada la [consulta] (RF-25): está cerrada, ya se creó allá y su fila **no** tiene pendiente de envío —el cierre es lo
 * último que se limpia (T25)—. Un cierre «Descartado» ([estadoDelCierre]) no se envió aunque la fila esté limpia: el médico decidió no
 * mandarlo y la web sigue abierta. [filaSucia] es el `dirty` de la fila de la consulta.
 */
fun cierreEnviado(consulta: Consulta, filaSucia: Boolean, estadoDelCierre: EstadoEnvio?): Boolean =
    consulta.estado == EstadoConsulta.CERRADA && consulta.remotoId.isNotBlank() && !filaSucia && estadoDelCierre != EstadoEnvio.Descartado

/**
 * Qué se puede hacer con la [consulta] (RF-25, RF-26, RF-55). **Reabrir**: solo una cerrada cuyo cierre no se envió y no está viajando.
 * **Anular**: una abierta, o una cerrada cuyo cierre todavía no se envió (la web la sigue viendo abierta y se da de baja); nunca una con el
 * cierre ya enviado, ni mientras el cierre viaja. Una anulada no se reabre ni se vuelve a anular. [envio] es lo que falta mandar
 * (`null` = nada) y [enviandoElCierre] viene de la cola.
 */
fun estadoDeLaConsulta(
    consulta: Consulta,
    filaSucia: Boolean,
    estadoDelCierre: EstadoEnvio?,
    envio: EstadoEnvio?,
    enviandoElCierre: Boolean,
): EstadoDeLaConsulta {
    val enviado = cierreEnviado(consulta, filaSucia, estadoDelCierre)
    val cerradaSinEnviar = consulta.estado == EstadoConsulta.CERRADA && !enviado
    return EstadoDeLaConsulta(
        envio = envio,
        enviandoElCierre = enviandoElCierre,
        cierreEnviado = enviado,
        puedeReabrir = cerradaSinEnviar && !enviandoElCierre,
        puedeAnular = (consulta.estado == EstadoConsulta.ABIERTA || cerradaSinEnviar) && !enviandoElCierre,
    )
}