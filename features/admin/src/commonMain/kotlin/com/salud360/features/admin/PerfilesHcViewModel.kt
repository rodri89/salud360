package com.salud360.features.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.hcgeneral.ErrorPerfil
import com.salud360.core.data.hcgeneral.ErroresPorCampo
import com.salud360.core.data.hcgeneral.SeccionCatalogoAdmin
import com.salud360.core.data.hcgeneral.agregarPestania
import com.salud360.core.data.hcgeneral.agregarSeccion
import com.salud360.core.data.hcgeneral.catalogoParaAdmin
import com.salud360.core.data.hcgeneral.duplicarPerfil
import com.salud360.core.data.hcgeneral.marcarTipo
import com.salud360.core.data.hcgeneral.moverPestania
import com.salud360.core.data.hcgeneral.moverSeccion
import com.salud360.core.data.hcgeneral.moverSeccionAPestania
import com.salud360.core.data.hcgeneral.quitarPestania
import com.salud360.core.data.hcgeneral.quitarSeccion
import com.salud360.core.data.hcgeneral.renombrarPestania
import com.salud360.core.data.hcgeneral.renombrarSeccion
import com.salud360.core.data.hcgeneral.seccionesParaAgregar
import com.salud360.core.data.hcgeneral.ubicarErrores
import com.salud360.core.data.hcgeneral.validarPerfil
import com.salud360.core.data.network.TobbException
import com.salud360.core.data.repos.PerfilesHcRepository
import com.salud360.core.model.especialidad.AsignacionPerfil
import com.salud360.core.model.especialidad.CatalogoHc
import com.salud360.core.model.especialidad.PerfilHc
import com.salud360.core.model.especialidad.ResumenPerfil
import com.salud360.core.model.especialidad.SeccionCatalogoRemota
import com.salud360.core.model.especialidad.TipoConsultaHc
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
    /** Perfil abierto en el editor; null = se ve la lista. */
    val editor: EditorPerfilUi? = null,
    /** Hay un pedido en curso: las acciones esperan a que termine. */
    val cargando: Boolean = false,
    /** Resultado de la última acción o motivo del último fallo, tal como lo manda la API (RF-13). */
    val mensaje: String? = null,
    /** El último pedido falló por falta de conexión: no se puede tocar nada hasta reintentar (RF-14). */
    val sinConexion: Boolean = false,
)

/** Perfil en edición. El [borrador] solo cambia con las acciones del administrador: un guardado fallido no lo toca (RF-13). */
data class EditorPerfilUi(
    val borrador: PerfilHc,
    /** Como se abrió, para avisar antes de salir con cambios sin guardar. */
    val original: PerfilHc,
    val errores: ErroresPorCampo = ErroresPorCampo.NINGUNO,
    /**
     * Ya se intentó guardar con errores: desde ahí cada cambio vuelve a validar, así los errores siguen al campo
     * aunque se muevan pestañas o secciones (los del servidor se reemplazan por los de [validarPerfil]).
     */
    val validarAlEditar: Boolean = false,
) {
    val nuevo: Boolean get() = original.id == null
    val hayCambios: Boolean get() = borrador != original
}

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

    // ---- Editor (RF-3 a RF-9) ----

    /** Perfil nuevo con una pestaña vacía. */
    fun nuevoPerfil() {
        if (!puedeTocar()) return
        val vacio = PerfilHc(nombre = "", pestanias = emptyList()).agregarPestania()
        _state.update { it.copy(editor = EditorPerfilUi(vacio, vacio), mensaje = null) }
    }

    /** Lo lee entero de hclinica: el que se abre es el último guardado, no una copia vieja (RF-15). */
    fun editar(id: Long) = ejecutar {
        val perfil = repo.perfil(id)
        _state.update { it.copy(editor = EditorPerfilUi(perfil, perfil)) }
        null
    }

    /** Vuelve a la lista descartando el borrador (la pantalla pide confirmación si hay cambios). */
    fun cerrarEditor() {
        if (_state.value.cargando) return
        _state.update { it.copy(editor = null, mensaje = null) }
    }

    fun renombrarPerfil(nombre: String) = cambiar { copy(nombre = nombre) }
    fun agregarPestania() = cambiar { agregarPestania() }
    fun renombrarPestania(i: Int, nombre: String) = cambiar { renombrarPestania(i, nombre) }
    fun moverPestania(i: Int, delta: Int) = cambiar { moverPestania(i, delta) }
    fun quitarPestania(i: Int) = cambiar { quitarPestania(i) }
    fun renombrarSeccion(i: Int, j: Int, nombre: String) = cambiar { renombrarSeccion(i, j, nombre) }
    fun marcarTipo(i: Int, j: Int, tipo: TipoConsultaHc, marcado: Boolean) = cambiar { marcarTipo(i, j, tipo, marcado) }
    fun moverSeccion(i: Int, j: Int, delta: Int) = cambiar { moverSeccion(i, j, delta) }
    fun moverSeccionAPestania(i: Int, j: Int, destino: Int) = cambiar { moverSeccionAPestania(i, j, destino) }
    fun quitarSeccion(i: Int, j: Int) = cambiar { quitarSeccion(i, j) }

    /** Solo las que ofrece [seccionesParaAgregar]: soportadas por la app y no usadas (RF-9, RF-16); entra con Consulta (RF-8). */
    fun agregarSeccion(i: Int, seccionId: String) = cambiar {
        val ofrecida = seccionesParaAgregar(_state.value.catalogo, this).firstOrNull { it.id == seccionId }
        if (ofrecida == null) this else agregarSeccion(i, seccionId, _state.value.catalogo.map { it.remota() })
    }

    /**
     * Valida en la app y, si pasa, manda el perfil entero (alta o reemplazo, RF-15). Los errores, de la app o del
     * 422, quedan junto a su campo; cualquier fallo deja el borrador como estaba para reintentar (RF-13). Bien
     * guardado, vuelve a la lista al día.
     */
    fun guardar() {
        val editor = _state.value.editor ?: return
        val errores = validar(editor.borrador)
        if (errores.isNotEmpty()) {
            _state.update {
                it.copy(
                    editor = editor.copy(errores = ubicarErrores(errores, editor.borrador), validarAlEditar = true),
                    mensaje = "Hay que corregir lo marcado antes de guardar.",
                )
            }
            return
        }
        ejecutar {
            val guardado = try {
                repo.guardar(editor.borrador)
            } catch (e: TobbException) {
                if (e.errores.isEmpty()) throw e
                _state.update { s ->
                    s.copy(editor = s.editor?.copy(errores = ubicarErrores(e.errores, s.editor.borrador), validarAlEditar = true))
                }
                return@ejecutar e.message
            }
            _state.update { it.copy(editor = null) }
            recargar()
            "Se guardó \"${guardado.nombre}\""
        }
    }

    private fun validar(perfil: PerfilHc): List<ErrorPerfil> {
        val s = _state.value
        val otros = s.perfiles.filter { it.id != perfil.id }.map { it.nombre }
        return validarPerfil(perfil, otros, s.catalogo.map { it.id })
    }

    /** Aplica un cambio al borrador; sin conexión o con un pedido en curso no se edita (RF-14). */
    private fun cambiar(cambio: PerfilHc.() -> PerfilHc) {
        if (!puedeTocar()) return
        val editor = _state.value.editor ?: return
        val borrador = editor.borrador.cambio()
        if (borrador == editor.borrador) return
        val errores = if (editor.validarAlEditar) ubicarErrores(validar(borrador), borrador) else editor.errores
        _state.update { it.copy(editor = editor.copy(borrador = borrador, errores = errores)) }
    }

    private fun puedeTocar() = _state.value.let { !it.cargando && !it.sinConexion }

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

private fun SeccionCatalogoAdmin.remota() = SeccionCatalogoRemota(id, nombre, descripcion)
