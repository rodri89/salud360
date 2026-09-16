package com.salud360.features.turnos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.repos.AdminRepository
import com.salud360.core.data.repos.TurnosRepository
import com.salud360.core.data.repos.hoy
import com.salud360.core.model.Id
import com.salud360.core.model.newId
import com.salud360.core.model.turnos.ConfigAgenda
import com.salud360.core.model.turnos.EstadoReceta
import com.salud360.core.model.turnos.FechaAgregada
import com.salud360.core.model.turnos.HorarioMedico
import com.salud360.core.model.turnos.MensajeEspecial
import com.salud360.core.model.turnos.ModuloTurnos
import com.salud360.core.model.turnos.ObraSocial
import com.salud360.core.model.turnos.ObraSocialMedico
import com.salud360.core.model.turnos.Receta
import com.salud360.core.model.turnos.TipoTurno
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Estado de la carga de horarios desde turnosonlinebb y del último error de escritura. */
data class EstadoHorarios(val cargando: Boolean = false, val error: String? = null)

/**
 * Horarios fijos semanales + fechas agregadas (`admin_horarios_fijos` + `admin_horarios`).
 * Al abrir, si el médico es de turnosonlinebb, trae su plantilla a la caché local; las altas/bajas van a la web.
 */
class HorariosViewModel(private val turnos: TurnosRepository, val medicoId: Id, val consultorioId: Id) : ViewModel() {
    val horarios: StateFlow<List<HorarioMedico>> = turnos.observarHorarios(medicoId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val fechasAgregadas: StateFlow<List<FechaAgregada>> = turnos.observarFechasAgregadas(medicoId, hoy()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _estado = MutableStateFlow(EstadoHorarios())
    val estado: StateFlow<EstadoHorarios> = _estado

    init { refrescar() }

    fun refrescar() = viewModelScope.launch {
        if (!turnos.esRemota(medicoId)) return@launch
        _estado.value = EstadoHorarios(cargando = true)
        _estado.value = try {
            turnos.sincronizarHorarios(medicoId, consultorioId); EstadoHorarios()
        } catch (e: CancellationException) {
        throw e // cancelación normal (se cerró la pantalla), no es un error
    } catch (e: Exception) {
            EstadoHorarios(error = "No se pudieron traer los horarios de turnosonlinebb: ${e.message ?: "error de red"}")
        }
    }

    /** Ejecuta una operación de escritura; si falla deja el mensaje en [estado] y devuelve false. */
    private suspend fun intentar(bloque: suspend () -> Unit): Boolean = try {
        bloque(); _estado.value = _estado.value.copy(error = null); true
    } catch (e: CancellationException) {
        throw e // cancelación normal (se cerró la pantalla), no es un error
    } catch (e: Exception) {
        _estado.value = _estado.value.copy(error = e.message ?: "turnosonlinebb rechazó la operación"); false
    }

    /**
     * Alta de un horario. Sin `validoDesde` rige desde hoy (para los quincenales se fija hoy como semana ancla);
     * sin `validoHasta` rige para siempre. `onResultado` recibe null si se agregó o el motivo si no.
     */
    fun agregar(
        dia: DayOfWeek, hora: LocalTime, tipo: TipoTurno, doble: Boolean, quincenal: Boolean,
        validoDesde: LocalDate? = null, validoHasta: LocalDate? = null, onResultado: (String?) -> Unit,
    ) = viewModelScope.launch {
        var agregado = false
        val ok = intentar {
            val h = HorarioMedico(newId(), medicoId, consultorioId, dia, hora, doble, tipo, validoDesde = anclaDesde(validoDesde, quincenal), validoHasta = validoHasta, quincenal = quincenal)
            agregado = turnos.agregarHorario(h)
        }
        onResultado(when { !ok -> _estado.value.error; !agregado -> "Ese horario ya existe"; else -> null })
    }
    fun generar(
        dia: DayOfWeek, desde: LocalTime, hasta: LocalTime, intervalo: Int, tipo: TipoTurno, quincenal: Boolean,
        validoDesde: LocalDate? = null, validoHasta: LocalDate? = null, onResultado: (Int) -> Unit,
    ) = viewModelScope.launch {
        var creados = 0
        intentar { creados = turnos.generarHorarios(medicoId, consultorioId, dia, desde, hasta, intervalo, tipo, quincenal, anclaDesde(validoDesde, quincenal), validoHasta) }
        onResultado(creados)
    }

    /** Un horario quincenal necesita una fecha ancla: si no se cargó "válido desde" se usa hoy. */
    private fun anclaDesde(validoDesde: LocalDate?, quincenal: Boolean): LocalDate? = validoDesde ?: if (quincenal) hoy() else null
    fun eliminar(h: HorarioMedico) = viewModelScope.launch { intentar { turnos.eliminarHorario(h) } }
    fun vigencia(h: HorarioMedico, desde: LocalDate?, hasta: LocalDate?) = viewModelScope.launch { intentar { turnos.guardarHorario(h.copy(validoDesde = desde, validoHasta = hasta)) } }
    fun doble(h: HorarioMedico, valor: Boolean) = viewModelScope.launch { intentar { turnos.guardarHorario(h.copy(doble = valor)) } }
    fun quincenal(h: HorarioMedico, valor: Boolean) = viewModelScope.launch { intentar { turnos.guardarHorario(h.copy(quincenal = valor)) } }
    fun agregarFecha(fecha: LocalDate, horarios: List<LocalTime>) = viewModelScope.launch { intentar { turnos.guardarFechaAgregada(FechaAgregada(newId(), medicoId, consultorioId, fecha, horarios)) } }
    fun eliminarFecha(f: FechaAgregada) = viewModelScope.launch { intentar { turnos.eliminarFechaAgregada(f) } }
}

data class ConfigUi(
    val config: ConfigAgenda,
    val modulos: Set<ModuloTurnos>,
    val mensajes: List<MensajeEspecial>,
)

/**
 * Configuración del médico: ventana de días, valor de consulta, cupo de primer control, módulos y mensajes especiales.
 * Si el médico es de turnosonlinebb, al abrir se refrescan cupo, ventana y mensajes desde la web; cupo y ventana se guardan allí.
 */
class ConfigAgendaViewModel(private val turnos: TurnosRepository, val medicoId: Id) : ViewModel() {
    val ui: StateFlow<ConfigUi?> = combine(turnos.observarConfig(medicoId), turnos.observarModulos(medicoId), turnos.observarMensajes(medicoId)) { c, m, msj -> ConfigUi(c, m, msj) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** true si la agenda vive en turnosonlinebb (los mensajes se administran desde la web). */
    val remota: Boolean = turnos.esRemota(medicoId)
    private val _estado = MutableStateFlow(EstadoHorarios())
    val estado: StateFlow<EstadoHorarios> = _estado

    init { refrescar() }

    fun refrescar() = viewModelScope.launch {
        if (!remota) return@launch
        _estado.value = EstadoHorarios(cargando = true)
        _estado.value = try {
            turnos.sincronizarConfig(medicoId); EstadoHorarios()
        } catch (e: CancellationException) {
        throw e // cancelación normal (se cerró la pantalla), no es un error
    } catch (e: Exception) {
            EstadoHorarios(error = "No se pudo traer la configuración de turnosonlinebb: ${e.message ?: "error de red"}")
        }
    }

    /** Guarda la configuración. `onResultado` recibe null si salió bien o el mensaje de error. */
    fun guardarConfig(c: ConfigAgenda, onResultado: (String?) -> Unit = {}) = viewModelScope.launch {
        try {
            turnos.guardarConfig(c); _estado.value = _estado.value.copy(error = null); onResultado(null)
        } catch (e: CancellationException) {
        throw e // cancelación normal (se cerró la pantalla), no es un error
    } catch (e: Exception) {
            val msg = e.message ?: "turnosonlinebb rechazó la operación"
            _estado.value = _estado.value.copy(error = msg); onResultado(msg)
        }
    }
    fun setModulo(m: ModuloTurnos, activo: Boolean) = viewModelScope.launch { turnos.guardarModulo(medicoId, m, activo) }

    /** Guarda (o crea) un mensaje. `onResultado` recibe null si salió bien o el mensaje de error. */
    fun guardarMensaje(m: MensajeEspecial, onResultado: (String?) -> Unit = {}) = viewModelScope.launch {
        try {
            turnos.guardarMensaje(m); _estado.value = _estado.value.copy(error = null); onResultado(null)
        } catch (e: CancellationException) {
        throw e // cancelación normal (se cerró la pantalla), no es un error
    } catch (e: Exception) {
            val msg = e.message ?: "turnosonlinebb rechazó la operación"
            _estado.value = _estado.value.copy(error = msg); onResultado(msg)
        }
    }
    fun eliminarMensaje(m: MensajeEspecial, onResultado: (String?) -> Unit = {}) = viewModelScope.launch {
        try {
            turnos.eliminarMensaje(m); _estado.value = _estado.value.copy(error = null); onResultado(null)
        } catch (e: CancellationException) {
        throw e // cancelación normal (se cerró la pantalla), no es un error
    } catch (e: Exception) {
            val msg = e.message ?: "turnosonlinebb rechazó la operación"
            _estado.value = _estado.value.copy(error = msg); onResultado(msg)
        }
    }
    fun nuevoMensaje(titulo: String, descripcion: String, desde: LocalDate?, hasta: LocalDate?, onResultado: (String?) -> Unit = {}) =
        guardarMensaje(MensajeEspecial(newId(), medicoId, titulo.trim(), descripcion.trim(), desde, hasta), onResultado)
}

data class ObraSocialFila(val obraSocial: ObraSocial, val vinculo: ObraSocialMedico?)

/**
 * Obras sociales del médico: activar/desactivar, importe diferencial e importe de reserva.
 * Al abrir, si el médico es de turnosonlinebb, trae el catálogo y sus vínculos; los cambios van a la web.
 */
class ObrasSocialesViewModel(private val turnos: TurnosRepository, val medicoId: Id?) : ViewModel() {
    val filas: StateFlow<List<ObraSocialFila>> = combine(
        turnos.observarObrasSociales(),
        medicoId?.let { turnos.observarObrasSocialesDeMedico(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()),
    ) { todas, delMedico -> todas.map { os -> ObraSocialFila(os, delMedico.firstOrNull { it.obraSocialId == os.id }) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _estado = MutableStateFlow(EstadoHorarios())
    val estado: StateFlow<EstadoHorarios> = _estado

    init { refrescar() }

    fun refrescar() = viewModelScope.launch {
        val m = medicoId ?: return@launch
        if (!turnos.esRemota(m)) return@launch
        _estado.value = EstadoHorarios(cargando = true)
        _estado.value = try {
            turnos.sincronizarObrasSociales(m); EstadoHorarios()
        } catch (e: CancellationException) {
        throw e // cancelación normal (se cerró la pantalla), no es un error
    } catch (e: Exception) {
            EstadoHorarios(error = "No se pudieron traer las obras sociales de turnosonlinebb: ${e.message ?: "error de red"}")
        }
    }

    private suspend fun intentar(bloque: suspend () -> Unit) = try {
        bloque(); _estado.value = _estado.value.copy(error = null)
    } catch (e: CancellationException) {
        throw e // cancelación normal (se cerró la pantalla), no es un error
    } catch (e: Exception) {
        _estado.value = _estado.value.copy(error = e.message ?: "turnosonlinebb rechazó la operación")
    }

    fun setActiva(fila: ObraSocialFila, activa: Boolean) = viewModelScope.launch {
        val m = medicoId ?: return@launch
        intentar { turnos.guardarObraSocialMedico((fila.vinculo ?: ObraSocialMedico(newId(), m, fila.obraSocial.id)).copy(activo = activa)) }
    }
    fun setImportes(fila: ObraSocialFila, importe: Double, reserva: Double) = viewModelScope.launch {
        val m = medicoId ?: return@launch
        intentar { turnos.guardarObraSocialMedico((fila.vinculo ?: ObraSocialMedico(newId(), m, fila.obraSocial.id)).copy(importe = importe, importeReserva = reserva)) }
    }
    fun activarTodas(activar: Boolean) = viewModelScope.launch {
        val m = medicoId ?: return@launch
        intentar { filas.value.forEach { turnos.guardarObraSocialMedico((it.vinculo ?: ObraSocialMedico(newId(), m, it.obraSocial.id)).copy(activo = activar)) } }
    }
}

/** Recetas del médico (o de los médicos de la secretaria). */
class RecetasViewModel(private val turnos: TurnosRepository, medicoIds: List<Id>) : ViewModel() {
    val recetas: StateFlow<List<Receta>> = turnos.observarRecetasDeMedicos(medicoIds).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun cambiarEstado(r: Receta, estado: EstadoReceta) = viewModelScope.launch { turnos.cambiarEstadoReceta(r.id, estado) }
    fun comentario(r: Receta, texto: String) = viewModelScope.launch { turnos.guardarReceta(r.copy(comentario = texto)) }
}

/** Selección de consultorio y médico para la secretaria. */
class SelectorMedicoViewModel(private val admin: AdminRepository, private val turnos: TurnosRepository, val consultorioIds: List<Id>, val medicoIds: List<Id>) : ViewModel() {
    val consultorios = kotlinx.coroutines.flow.flow { emit(turnos.consultorios(consultorioIds)) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val medicos = kotlinx.coroutines.flow.flow { emit(admin.medicos(medicoIds)) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
