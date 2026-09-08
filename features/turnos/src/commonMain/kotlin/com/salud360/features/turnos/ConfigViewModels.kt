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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Horarios fijos semanales + fechas agregadas (`admin_horarios_fijos` + `admin_horarios`). */
class HorariosViewModel(private val turnos: TurnosRepository, val medicoId: Id, val consultorioId: Id) : ViewModel() {
    val horarios: StateFlow<List<HorarioMedico>> = turnos.observarHorarios(medicoId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val fechasAgregadas: StateFlow<List<FechaAgregada>> = turnos.observarFechasAgregadas(medicoId, hoy()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun agregar(dia: DayOfWeek, hora: LocalTime, tipo: TipoTurno, doble: Boolean, onResultado: (Boolean) -> Unit) = viewModelScope.launch {
        onResultado(turnos.agregarHorario(HorarioMedico(newId(), medicoId, consultorioId, dia, hora, doble, tipo)))
    }
    fun generar(dia: DayOfWeek, desde: LocalTime, hasta: LocalTime, intervalo: Int, tipo: TipoTurno, onResultado: (Int) -> Unit) = viewModelScope.launch {
        onResultado(turnos.generarHorarios(medicoId, consultorioId, dia, desde, hasta, intervalo, tipo))
    }
    fun eliminar(h: HorarioMedico) = viewModelScope.launch { turnos.eliminarHorario(h) }
    fun vigencia(h: HorarioMedico, desde: LocalDate?, hasta: LocalDate?) = viewModelScope.launch { turnos.guardarHorario(h.copy(validoDesde = desde, validoHasta = hasta)) }
    fun doble(h: HorarioMedico, valor: Boolean) = viewModelScope.launch { turnos.guardarHorario(h.copy(doble = valor)) }
    fun agregarFecha(fecha: LocalDate, horarios: List<LocalTime>) = viewModelScope.launch { turnos.guardarFechaAgregada(FechaAgregada(newId(), medicoId, consultorioId, fecha, horarios)) }
    fun eliminarFecha(f: FechaAgregada) = viewModelScope.launch { turnos.eliminarFechaAgregada(f) }
}

data class ConfigUi(
    val config: ConfigAgenda,
    val modulos: Set<ModuloTurnos>,
    val mensajes: List<MensajeEspecial>,
)

/** Configuración del médico: ventana de días, valor de consulta, cupo de primer control, módulos y mensajes especiales. */
class ConfigAgendaViewModel(private val turnos: TurnosRepository, val medicoId: Id) : ViewModel() {
    val ui: StateFlow<ConfigUi?> = combine(turnos.observarConfig(medicoId), turnos.observarModulos(medicoId), turnos.observarMensajes(medicoId)) { c, m, msj -> ConfigUi(c, m, msj) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun guardarConfig(c: ConfigAgenda) = viewModelScope.launch { turnos.guardarConfig(c) }
    fun setModulo(m: ModuloTurnos, activo: Boolean) = viewModelScope.launch { turnos.guardarModulo(medicoId, m, activo) }
    fun guardarMensaje(m: MensajeEspecial) = viewModelScope.launch { turnos.guardarMensaje(m) }
    fun nuevoMensaje(titulo: String, descripcion: String, desde: LocalDate?, hasta: LocalDate?) = guardarMensaje(MensajeEspecial(newId(), medicoId, titulo, descripcion, desde, hasta))
}

data class ObraSocialFila(val obraSocial: ObraSocial, val vinculo: ObraSocialMedico?)

/** Obras sociales del médico: activar/desactivar, importe diferencial e importe de reserva. */
class ObrasSocialesViewModel(private val turnos: TurnosRepository, val medicoId: Id?) : ViewModel() {
    val filas: StateFlow<List<ObraSocialFila>> = combine(
        turnos.observarObrasSociales(),
        medicoId?.let { turnos.observarObrasSocialesDeMedico(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()),
    ) { todas, delMedico -> todas.map { os -> ObraSocialFila(os, delMedico.firstOrNull { it.obraSocialId == os.id }) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun altaObraSocial(nombre: String) = viewModelScope.launch { turnos.guardarObraSocial(ObraSocial(newId(), nombre)) }
    fun setActiva(fila: ObraSocialFila, activa: Boolean) = viewModelScope.launch {
        val m = medicoId ?: return@launch
        turnos.guardarObraSocialMedico((fila.vinculo ?: ObraSocialMedico(newId(), m, fila.obraSocial.id)).copy(activo = activa))
    }
    fun setImportes(fila: ObraSocialFila, importe: Double, reserva: Double) = viewModelScope.launch {
        val m = medicoId ?: return@launch
        turnos.guardarObraSocialMedico((fila.vinculo ?: ObraSocialMedico(newId(), m, fila.obraSocial.id)).copy(importe = importe, importeReserva = reserva))
    }
    fun activarTodas(activar: Boolean) = viewModelScope.launch { filas.value.forEach { setActiva(it, activar) } }
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
