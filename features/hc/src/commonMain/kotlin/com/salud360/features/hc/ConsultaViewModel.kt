package com.salud360.features.hc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.data.files.mimeDe
import com.salud360.core.data.repos.HcRepository
import com.salud360.core.data.repos.PacientesRepository
import com.salud360.core.data.repos.hoy
import com.salud360.core.data.sync.SyncEngine
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.EspecialidadDefinition
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.TipoConsultaDef
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.Diagnostico
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.VacunaAplicada
import com.salud360.core.model.newId
import com.salud360.core.model.pacientes.Paciente
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class ConsultaUi(
    val consulta: Consulta? = null,
    val paciente: Paciente? = null,
    val definicion: EspecialidadDefinition? = null,
    val tipo: TipoConsultaDef? = null,
    val examen: ExamenFisico? = null,
    val antecedentes: List<Antecedente> = emptyList(),
    val diagnosticosCatalogo: List<Diagnostico> = emptyList(),
    val diagnosticosConsulta: Set<Id> = emptySet(),
    val pendientes: List<Pendiente> = emptyList(),
    val vacunas: List<VacunaAplicada> = emptyList(),
    val preferencias: Map<String, String> = emptyMap(),
) {
    val soloLectura: Boolean get() = consulta?.estado != EstadoConsulta.ABIERTA
    val edadMeses: Int? get() {
        val nac = paciente?.fechaNacimiento ?: return null
        val fecha = consulta?.fecha ?: return null
        return com.salud360.core.model.pacientes.Edad.entre(nac, fecha).totalMeses
    }
}

/**
 * Estado de una consulta abierta o en lectura. Los valores de sección se mantienen en memoria y
 * se guardan con un pequeño retardo (autoguardado), como en las apps originales pero sin botón.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsultaViewModel(
    private val hc: HcRepository,
    private val pacientes: PacientesRepository,
    private val archivos: ArchivoStore,
    private val sync: SyncEngine,
    private val registry: EspecialidadRegistry,
    val consultaId: Id,
    private val medicoId: Id,
) : ViewModel() {

    private val consultaFlow = hc.observarConsulta(consultaId)

    val ui: StateFlow<ConsultaUi> = consultaFlow.flatMapLatest { c ->
        if (c == null) flowOf(ConsultaUi()) else {
            val def = registry.definicion(c.especialidad)
            combine(
                pacientes.observar(c.pacienteId),
                hc.observarExamenFisico(c.id),
                hc.observarAntecedentes(c.pacienteId, c.especialidad),
                hc.observarDiagnosticos(medicoId),
                hc.observarDiagnosticosDeConsulta(c.id),
                hc.observarPendientes(c.pacienteId, medicoId),
                hc.observarVacunas(c.pacienteId),
                hc.observarPreferencias(medicoId),
            ) { arr ->
                @Suppress("UNCHECKED_CAST")
                ConsultaUi(
                    consulta = c, paciente = arr[0] as Paciente?, definicion = def, tipo = def?.tipoConsulta(c.tipo),
                    examen = arr[1] as ExamenFisico?, antecedentes = arr[2] as List<Antecedente>,
                    diagnosticosCatalogo = arr[3] as List<Diagnostico>, diagnosticosConsulta = (arr[4] as List<Diagnostico>).map { it.id }.toSet(),
                    pendientes = arr[5] as List<Pendiente>, vacunas = arr[6] as List<VacunaAplicada>, preferencias = arr[7] as Map<String, String>,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConsultaUi())

    // ---- valores de secciones (clave-valor) con autoguardado ----

    private val _valores = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val valores: StateFlow<Map<String, Map<String, String>>> = _valores
    private val pendientesGuardar = mutableMapOf<String, MutableMap<String, String>>()
    private var jobGuardar: Job? = null
    private val _guardadoEn = MutableStateFlow<Long?>(null)
    val guardadoEn: StateFlow<Long?> = _guardadoEn

    init {
        viewModelScope.launch { hc.observarValores(consultaId).collect { remoto -> _valores.value = mezclar(remoto) } }
    }

    private fun mezclar(remoto: Map<String, Map<String, String>>): Map<String, Map<String, String>> {
        if (pendientesGuardar.isEmpty()) return remoto
        val r = remoto.toMutableMap()
        pendientesGuardar.forEach { (s, campos) -> r[s] = (r[s] ?: emptyMap()) + campos }
        return r
    }

    fun valor(seccion: String, campo: String): String = _valores.value[seccion]?.get(campo) ?: ""

    fun setValor(seccion: String, campo: String, valor: String) {
        _valores.value = _valores.value + (seccion to ((_valores.value[seccion] ?: emptyMap()) + (campo to valor)))
        pendientesGuardar.getOrPut(seccion) { mutableMapOf() }[campo] = valor
        jobGuardar?.cancel()
        jobGuardar = viewModelScope.launch { delay(600); guardarPendientes() }
    }

    suspend fun guardarPendientes() {
        if (pendientesGuardar.isEmpty()) return
        val copia = pendientesGuardar.mapValues { it.value.toMap() }
        pendientesGuardar.clear()
        copia.forEach { (s, campos) -> hc.guardarSeccion(consultaId, s, campos) }
        _guardadoEn.value = com.salud360.core.data.ahoraMillis()
    }

    // ---- examen físico ----

    fun guardarExamen(examen: ExamenFisico) = viewModelScope.launch {
        val imc = examen.calcularImc() ?: examen.imc
        hc.guardarExamenFisico(examen.copy(consultaId = consultaId, imc = imc))
        _guardadoEn.value = com.salud360.core.data.ahoraMillis()
    }

    // ---- antecedentes ----

    fun guardarAntecedente(categoria: String, clave: String, flag: Boolean, detalle: String) = viewModelScope.launch {
        val c = ui.value.consulta ?: return@launch
        hc.guardarAntecedente(c.pacienteId, c.especialidad, categoria, clave, flag, detalle, consultaId)
    }

    // ---- registros repetibles ----

    fun registros(tipo: String, porPaciente: Boolean) = ui.value.consulta?.let { c ->
        if (porPaciente) hc.observarRegistros(c.pacienteId, c.especialidad, tipo) else hc.observarRegistrosDeConsulta(c.id, tipo)
    } ?: flowOf(emptyList())

    fun registrosDelPaciente(tipo: String) = ui.value.consulta?.let { c -> hc.observarRegistros(c.pacienteId, c.especialidad, tipo) } ?: flowOf(emptyList())

    fun guardarRegistro(tipo: String, id: Id?, fecha: LocalDate?, campos: Map<String, String>, porPaciente: Boolean) = viewModelScope.launch {
        val c = ui.value.consulta ?: return@launch
        hc.guardarRegistro(RegistroClinico(id ?: newId(), c.pacienteId, if (porPaciente) null else c.id, c.especialidad, tipo, fecha, campos))
    }

    fun eliminarRegistro(id: Id) = viewModelScope.launch { hc.eliminarRegistro(id) }

    // ---- laboratorios ----

    fun laboratorios(tipo: String) = ui.value.consulta?.let { c -> hc.observarLaboratorios(c.pacienteId, c.especialidad, tipo) } ?: flowOf(emptyList())

    fun guardarLaboratorio(tipo: String, id: Id?, fecha: LocalDate, valores: Map<String, String>) = viewModelScope.launch {
        val c = ui.value.consulta ?: return@launch
        hc.guardarLaboratorio(Laboratorio(id ?: newId(), c.pacienteId, c.id, c.especialidad, tipo, fecha, valores.filterValues { it.isNotBlank() }))
    }

    fun eliminarLaboratorio(id: Id) = viewModelScope.launch { hc.eliminarLaboratorio(id) }

    // ---- archivos ----

    fun archivos(seccion: String, porPaciente: Boolean = false) = ui.value.consulta?.let { c ->
        if (porPaciente) hc.observarArchivos(c.pacienteId, seccion) else hc.observarArchivosDeConsulta(c.id, seccion)
    } ?: flowOf(emptyList())

    fun archivosDeRegistro(registroId: Id) = hc.observarArchivosDeRegistro(registroId)

    fun agregarArchivo(seccion: String, nombre: String, bytes: ByteArray, registroId: Id? = null, duracionMs: Long = 0, porPaciente: Boolean = false) = viewModelScope.launch {
        val c = ui.value.consulta ?: return@launch
        val id = newId()
        val ruta = archivos.guardar(id, nombre, bytes)
        hc.guardarArchivo(Archivo(id, c.pacienteId, if (porPaciente) null else c.id, registroId, seccion, nombre, mimeDe(nombre), bytes.size.toLong(), duracionMs, ruta))
        launch { sync.sincronizar() }
    }

    suspend fun bytesDe(archivo: Archivo): ByteArray? {
        archivo.rutaLocal?.let { archivos.leer(it) }?.let { return it }
        val ruta = sync.descargarArchivo(archivo.id) ?: return null
        return archivos.leer(ruta)
    }

    fun eliminarArchivo(id: Id) = viewModelScope.launch { hc.eliminarArchivo(id) }

    // ---- diagnósticos ----

    fun toggleDiagnostico(diagnosticoId: Id, asignado: Boolean) = viewModelScope.launch { hc.asignarDiagnostico(consultaId, diagnosticoId, asignado) }
    fun nuevoDiagnostico(nombre: String) = viewModelScope.launch { hc.guardarDiagnostico(Diagnostico(newId(), medicoId, nombre.trim().uppercase())) }

    // ---- vacunas ----

    fun guardarVacuna(v: VacunaAplicada) = viewModelScope.launch { hc.guardarVacuna(v.copy(consultaId = v.consultaId ?: consultaId)) }

    // ---- pendientes ----

    fun agregarPendiente(texto: String) = viewModelScope.launch {
        val c = ui.value.consulta ?: return@launch
        hc.guardarPendiente(Pendiente(newId(), c.pacienteId, medicoId, texto, consultaId))
    }

    fun resolverPendiente(p: Pendiente) = viewModelScope.launch { hc.guardarPendiente(p.copy(resuelto = true)) }

    // ---- ciclo de vida de la consulta ----

    fun cambiarFecha(fecha: LocalDate) = viewModelScope.launch {
        val c = ui.value.consulta ?: return@launch
        val edad = ui.value.paciente?.edad(fecha)?.toString() ?: c.edadMostrar
        hc.guardarConsulta(c.copy(fecha = fecha, edadMostrar = edad))
    }

    fun cerrar(onCerrada: () -> Unit) = viewModelScope.launch {
        guardarPendientes()
        hc.cerrarConsulta(consultaId)
        launch { sync.sincronizar() }
        onCerrada()
    }

    fun reabrir() = viewModelScope.launch { hc.reabrirConsulta(consultaId) }

    fun guardarPreferencia(clave: String, valor: String) = viewModelScope.launch { hc.guardarPreferencia(medicoId, clave, valor) }

    /** Historial de un campo en consultas anteriores ("consultas previas"). */
    fun historial(seccion: String, campo: String) = ui.value.consulta?.let { c -> hc.observarHistorial(c.pacienteId, c.especialidad, seccion, campo) } ?: flowOf(emptyList())

    fun examenesFisicos() = ui.value.consulta?.let { c -> hc.observarExamenesFisicos(c.pacienteId) } ?: flowOf(emptyList())

    /** Secciones visibles para la consulta según el tipo, la condición y las preferencias del médico. */
    fun seccionesVisibles(ui: ConsultaUi): List<SeccionDef> {
        val def = ui.definicion ?: return emptyList()
        val tipo = ui.tipo ?: return emptyList()
        val ocultas = ui.preferencias["secciones_ocultas_${def.codigo}"]?.split(',')?.toSet() ?: emptySet()
        return tipo.secciones.mapNotNull { def.seccion(it) }.filter { s -> s.id !in ocultas && cumpleCondicion(s, ui) }
    }

    private fun cumpleCondicion(s: SeccionDef, ui: ConsultaUi): Boolean = when (val c = s.condicion) {
        null -> true
        is com.salud360.core.model.especialidad.CondicionSeccion.SoloFemenino -> ui.paciente?.sexo?.name == "F"
        is com.salud360.core.model.especialidad.CondicionSeccion.SoloMasculino -> ui.paciente?.sexo?.name == "M"
        is com.salud360.core.model.especialidad.CondicionSeccion.EdadMaximaMeses -> (ui.edadMeses ?: 0) <= c.meses
        is com.salud360.core.model.especialidad.CondicionSeccion.EdadMinimaMeses -> (ui.edadMeses ?: Int.MAX_VALUE) >= c.meses
    }

    override fun onCleared() { viewModelScope.launch { guardarPendientes() } }
}

/** Lista de consultas de un paciente en una especialidad + apertura de una nueva. */
class HistoriaClinicaViewModel(
    private val hc: HcRepository,
    pacientes: PacientesRepository,
    private val registry: EspecialidadRegistry,
    val pacienteId: Id,
    val especialidad: String,
    private val medicoId: Id,
) : ViewModel() {
    val definicion: EspecialidadDefinition? = registry.definicion(especialidad)
    val paciente: StateFlow<Paciente?> = pacientes.observar(pacienteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val consultas: StateFlow<List<Consulta>> = hc.observarConsultas(pacienteId, especialidad).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendientes: StateFlow<List<Pendiente>> = hc.observarPendientes(pacienteId, medicoId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun nuevaConsulta(tipo: String, onAbierta: (Consulta) -> Unit) = viewModelScope.launch {
        val p = paciente.first { it != null }!!
        val fecha = hoy()
        val c = hc.abrirConsulta(pacienteId, medicoId, especialidad, tipo, fecha, p.edad(fecha)?.toString() ?: "")
        if (definicion?.secciones?.any { it.tipo == com.salud360.core.model.especialidad.TipoSeccion.DIAGNOSTICOS } == true) {
            hc.copiarUltimosDiagnosticos(pacienteId, especialidad, c.id)
        }
        onAbierta(c)
    }

    fun anular(c: Consulta) = viewModelScope.launch { hc.anularConsulta(c.id) }
}
