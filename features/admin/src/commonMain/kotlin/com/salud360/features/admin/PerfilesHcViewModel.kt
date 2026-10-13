package com.salud360.features.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.hcgeneral.SeccionCatalogoAdmin
import com.salud360.core.data.hcgeneral.catalogoParaAdmin
import com.salud360.core.data.hcgeneral.duplicarPerfil
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.PerfilesHcRepository
import com.salud360.core.model.especialidad.AsignacionPerfil
import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.ResumenPerfil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PerfilesHcUi(
    val perfiles: List<ResumenPerfil> = emptyList(),
    val catalogo: List<SeccionCatalogoAdmin> = emptyList(),
    val asignaciones: List<AsignacionPerfil> = emptyList(),
    /** Hay un pedido en curso: las acciones esperan a que termine. */
    val cargando: Boolean = false,
    /** Resultado de la última acción o motivo del último fallo, tal como lo manda la API (RF-13). */
    val mensaje: String? = null,
    /** El último pedido falló por falta de conexión: no se puede tocar nada hasta reintentar (RF-14). */
    val sinConexion: Boolean = false,
)

/**
 * Solapa "Perfiles HC" del administrador. Todo vive en hclinica ([PerfilesHcRepository], nada local): la lista se
 * pide al abrir y después de cada cambio, y sin conexión no se ofrece ninguna acción.
 */
class PerfilesHcViewModel(
    private val repo: PerfilesHcRepository,
    /** Lo que esta versión de la app sabe mostrar, para marcar las secciones del catálogo remoto (RF-16). */
    private val catalogoApp: CatalogoHc,
) : ViewModel() {
    private val _state = MutableStateFlow(PerfilesHcUi())
    val state: StateFlow<PerfilesHcUi> = _state.asStateFlow()

    init {
        reintentar()
    }

    fun reintentar() = ejecutar { recargar(); null }

    /** Crea "Copia de X" con las mismas pestañas y secciones (RF-12); el nombre no choca con ninguno de la lista. */
    fun duplicar(id: Long) = ejecutar {
        val copia = repo.crear(duplicarPerfil(repo.perfil(id), _state.value.perfiles.map { it.nombre }))
        recargar()
        "Se creó \"${copia.nombre}\""
    }

    /**
     * La pantalla solo lo ofrece sin médicos asignados (RF-11), pero la lista puede estar vieja: si mientras tanto le
     * asignaron uno, la API contesta `409 perfil_con_medicos` y se muestra su mensaje con la lista al día.
     */
    fun borrar(id: Long) = ejecutar {
        try {
            repo.borrar(id)
        } catch (e: TobbException) {
            if (e.codigo != "perfil_con_medicos") throw e
            recargar()
            return@ejecutar e.message
        }
        recargar()
        "Perfil borrado"
    }

    private suspend fun recargar() {
        // El catálogo no cambia mientras la pantalla está abierta: se pide una sola vez.
        val catalogo = _state.value.catalogo.ifEmpty { catalogoParaAdmin(repo.catalogo(), catalogoApp) }
        val perfiles = repo.perfiles()
        val asignaciones = repo.asignaciones()
        _state.update { it.copy(catalogo = catalogo, perfiles = perfiles, asignaciones = asignaciones, sinConexion = false) }
    }

    /** Corre [accion] si no hay otra en curso; lo que devuelve (o el motivo del fallo) queda en [PerfilesHcUi.mensaje]. */
    private fun ejecutar(accion: suspend () -> String?) {
        if (_state.value.cargando) return
        _state.update { it.copy(cargando = true, mensaje = null) }
        viewModelScope.launch {
            try {
                val mensaje = accion()
                _state.update { it.copy(cargando = false, mensaje = mensaje) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val red = (e as? TobbException)?.codigo == "red"
                _state.update { it.copy(cargando = false, mensaje = e.message ?: "No se pudo completar la acción", sinConexion = red) }
            }
        }
    }
}
