package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.PerfilHc

/** Configuración del médico guardada en el dispositivo (una fila de `hc_config`). [perfil] null = sin perfil (RF-19). */
data class ConfigGuardada(val perfil: PerfilHc?)

/** Lo que se obtuvo al pedir `GET config` a hclinica. */
sealed interface ConfigRemota {
    /** Respuesta buena; [perfil] null = el médico no tiene perfil asignado. */
    data class Recibida(val perfil: PerfilHc?) : ConfigRemota

    /** No se pudo traer: [codigo] y [mensaje] como en `TobbException` (`red` = sin conexión). */
    data class Fallida(val codigo: String?, val mensaje: String) : ConfigRemota
}

/** Con qué configuración arma la app la HC general del médico. */
sealed interface EstadoConfigHc {
    data class ConPerfil(val perfil: PerfilHc) : EstadoConfigHc

    /** Tiene la HC general pero no un perfil (RF-19). */
    data object SinPerfil : EstadoConfigHc

    /**
     * Nunca se recibió la configuración y no se pudo traer (RF-26). [codigo] y [mensaje] dicen por qué (sin conexión,
     * no vinculado, licencia vencida, HC no habilitada), para el aviso, como el de la cartera de pacientes.
     */
    data class PrimeraVezSinConexion(val codigo: String?, val mensaje: String) : EstadoConfigHc
}

/**
 * Estado de la configuración (RF-23, RF-25, RF-26): la [remota] buena manda; si falló, vale la [guardada] sin
 * vencimiento. [guardada] null = nunca se recibió (sin fila), distinto de una guardada "sin perfil".
 */
fun estadoConfig(guardada: ConfigGuardada?, remota: ConfigRemota): EstadoConfigHc {
    val perfil = when (remota) {
        is ConfigRemota.Recibida -> remota.perfil
        is ConfigRemota.Fallida -> {
            if (guardada == null) return EstadoConfigHc.PrimeraVezSinConexion(remota.codigo, remota.mensaje)
            guardada.perfil
        }
    }
    return if (perfil == null) EstadoConfigHc.SinPerfil else EstadoConfigHc.ConPerfil(perfil)
}
