package com.salud360.features.hc

import com.salud360.core.data.hcgeneral.DefinicionResuelta
import com.salud360.core.data.hcgeneral.resolverDefinicion
import com.salud360.core.data.repos.ConfigHcRepository
import com.salud360.core.model.Id

/**
 * Definición con la que se muestra la historia clínica de un médico. Las especialidades de siempre usan la registrada
 * (RF-33); la HC general la arma con el perfil del médico, que se pide a hclinica esperando a lo sumo 5 s y, si no
 * llega, con el guardado (RF-24 a RF-26, "Decidido" C9).
 */
class DefinicionesHc(private val registry: EspecialidadRegistry, private val config: ConfigHcRepository) {

    /** Sin esperar: la de una especialidad sin perfiles; null para la HC general (hay que [resolver]) o si no existe. */
    fun estatica(codigo: String): DefinicionResuelta? =
        if (registry.catalogo(codigo) != null) null else registry.definicion(codigo)?.let { DefinicionResuelta.estatica(it) }

    /** null si la especialidad no está en esta versión de la app. */
    suspend fun resolver(codigo: String, medicoId: Id): DefinicionResuelta? {
        val catalogo = registry.catalogo(codigo) ?: return estatica(codigo)
        return resolverDefinicion(catalogo, config.estadoConEspera(medicoId))
    }
}

/** Recuerda en memoria si ya se avisó en esta sesión que el perfil tiene secciones que esta versión no sabe mostrar (RF-27). */
class AvisosHcGeneral {
    private var avisado = false

    /** true solo la primera vez en la sesión: la pantalla muestra el aviso y no se repite hasta volver a abrir la app. */
    fun avisarDesconocidas(): Boolean {
        if (avisado) return false
        avisado = true
        return true
    }
}
