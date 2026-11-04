package com.salud360.core.data.repos

import com.salud360.core.model.Id
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Sesion

/**
 * Lo que contestó el servidor de la historia clínica sobre la licencia del médico. La licencia vive en la base de la
 * especialidad: con conexión decide esto, y la copia guardada en el dispositivo es solo respaldo para cuando no hay señal.
 */
sealed interface EstadoLicenciaRemoto {
    /** El servidor lo dejó entrar: [vence] y [avisoDesde] son sus fechas y [porVencer] si ya está en la ventana de aviso. */
    data class Vigente(val vence: String, val avisoDesde: String, val porVencer: Boolean) : EstadoLicenciaRemoto

    /** El servidor rechazó con `licencia_vencida` y una licencia cargada: vencida por fecha o dada de baja. */
    data object Vencida : EstadoLicenciaRemoto

    /**
     * El servidor rechazó con `licencia_vencida` y `licencia` nula: el médico no tiene ninguna fila. No bloquea el ingreso
     * (solo la HC de esa especialidad, que el servidor ya frena).
     */
    data object SinLicencia : EstadoLicenciaRemoto

    /** Sin respuesta concluyente (sin conexión, tiempo agotado, error del servidor, otro rechazo...): decide la copia local. */
    data object SinDato : EstadoLicenciaRemoto
}

/**
 * ¿Esta sesión queda afuera? Manda el servidor cuando fue concluyente (sin volver a comparar la fecha con el reloj del
 * dispositivo); sin respuesta decide la copia local con la regla de siempre ([licenciaBloqueada]).
 */
fun licenciaBloqueadaSegun(estado: EstadoLicenciaRemoto, sesion: Sesion): Boolean = when (estado) {
    is EstadoLicenciaRemoto.Vigente -> false
    EstadoLicenciaRemoto.Vencida -> true
    EstadoLicenciaRemoto.SinLicencia -> false
    EstadoLicenciaRemoto.SinDato -> sesion.licenciaBloqueada()
}

/**
 * Junta lo que contestaron varias HC con licencia (hoy solo pediatría) en una sola respuesta: una vencida bloquea, si no
 * manda la primera vigente, si no «sin licencia» y, si ninguna fue concluyente, [EstadoLicenciaRemoto.SinDato].
 */
fun combinarEstadosDeLicencia(estados: List<EstadoLicenciaRemoto>): EstadoLicenciaRemoto =
    estados.firstOrNull { it == EstadoLicenciaRemoto.Vencida }
        ?: estados.firstOrNull { it is EstadoLicenciaRemoto.Vigente }
        ?: estados.firstOrNull { it == EstadoLicenciaRemoto.SinLicencia }
        ?: EstadoLicenciaRemoto.SinDato

/**
 * La fila de la copia local que hay que escribir para reflejar lo que contestó el servidor, o `null` si no hay nada que
 * escribir. Nunca pisa con vacíos: el importe, que el servidor no informa en esta respuesta, y el aviso que no vino se
 * conservan de la copia previa; sin respuesta concluyente o sin cambios no se reescribe.
 */
fun copiaDeLicencia(estado: EstadoLicenciaRemoto, previa: Licencia?, medicoId: Id): Licencia? {
    val nueva = when (estado) {
        is EstadoLicenciaRemoto.Vigente -> {
            if (estado.vence.isBlank()) return null
            Licencia(
                medicoId = medicoId,
                fechaExpiracion = estado.vence,
                fechaAviso = estado.avisoDesde.ifBlank { previa?.fechaAviso.orEmpty() },
                importe = previa?.importe ?: 0.0,
                activo = true,
            )
        }
        // Sin copia no se inventa una fila: el cliente no sabe la fecha.
        EstadoLicenciaRemoto.Vencida -> previa?.takeIf { it.activo }?.copy(activo = false)
        EstadoLicenciaRemoto.SinLicencia, EstadoLicenciaRemoto.SinDato -> null
    }
    return nueva?.takeIf { it != previa }
}
