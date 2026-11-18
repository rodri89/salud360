package com.salud360.features.hc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.files.ArchivoStore
import com.salud360.core.data.files.mimeDe
import com.salud360.core.data.hcgeneral.AusenciasEnLaWeb
import com.salud360.core.data.hcgeneral.AvisosDeConsulta
import com.salud360.core.data.hcgeneral.CONSULTAS_POR_VEZ
import com.salud360.core.data.hcgeneral.CambioEstudio
import com.salud360.core.data.hcgeneral.DatosConsulta
import com.salud360.core.data.hcgeneral.DefinicionResuelta
import com.salud360.core.data.hcgeneral.EdicionDeConsulta
import com.salud360.core.data.hcgeneral.EntradaTexto
import com.salud360.core.data.hcgeneral.ExtrasDeLectura
import com.salud360.core.data.hcgeneral.LayoutConsulta
import com.salud360.core.data.hcgeneral.LecturaDeLaConsulta
import com.salud360.core.data.hcgeneral.SoloLecturaConsulta
import com.salud360.core.data.hcgeneral.SECCION_DOCUMENTOS
import com.salud360.core.data.hcgeneral.VistaDeLaHistoria
import com.salud360.core.data.hcgeneral.avisosDeLaPantallaDeConsulta
import com.salud360.core.data.hcgeneral.cambioDePendientes
import com.salud360.core.data.hcgeneral.cambioEstudio
import com.salud360.core.data.hcgeneral.camposEstudio
import com.salud360.core.data.hcgeneral.copiaDiagnosticos
import com.salud360.core.data.hcgeneral.documentosDeTodoElPaciente
import com.salud360.core.data.hcgeneral.edicionDeConsulta
import com.salud360.core.data.hcgeneral.enfermedadesEnDatosDeConsulta
import com.salud360.core.data.hcgeneral.entradaDeConsulta
import com.salud360.core.data.hcgeneral.BannerDeEnvio
import com.salud360.core.data.hcgeneral.EstadoDeLaConsulta
import com.salud360.core.data.hcgeneral.esDeLaWeb
import com.salud360.core.data.hcgeneral.esIdDeLaWeb
import com.salud360.core.data.hcgeneral.bannersDeEnvio
import com.salud360.core.data.hcgeneral.esSoloLecturaWeb
import com.salud360.core.data.hcgeneral.seEnviaALaWeb
import com.salud360.core.data.hcgeneral.extrasDeLectura
import com.salud360.core.data.hcgeneral.layoutConsulta
import com.salud360.core.data.hcgeneral.limiteExcedido
import com.salud360.core.data.hcgeneral.masConsultas
import com.salud360.core.data.hcgeneral.medicoParaEnfermedades
import com.salud360.core.data.hcgeneral.motivoDeLaLectura
import com.salud360.core.data.hcgeneral.puedeResolverse
import com.salud360.core.data.hcgeneral.soloLecturaConsulta
import com.salud360.core.data.hcgeneral.tipoDelArchivo
import com.salud360.core.data.hcgeneral.vistaDeLaHistoria
import com.salud360.core.data.hcgeneral.textoDePendientesLocales
import com.salud360.core.data.hcgeneral.visibleParaSesion
import com.salud360.core.data.network.hc.RespuestaLimitesDeAdjuntosDto
import com.salud360.core.data.repos.AuthRepository
import com.salud360.core.data.repos.HcRepository
import com.salud360.core.data.repos.PacientesRepository
import com.salud360.core.data.repos.hoy
import com.salud360.core.data.sync.HcApiSync
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
import com.salud360.core.model.pacientes.PacienteExtra
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Con qué se abrió la consulta: la definición y, en la HC general, si falta perfil o la configuración (RF-19, RF-26). */
    val resuelta: DefinicionResuelta? = null,
    /** HC general: lo cargado fuera del perfil, solo para leer (RF-22 al final, RF-34 en "Datos"); vacío en las demás. */
    val fueraDelPerfil: SoloLecturaConsulta = SoloLecturaConsulta(),
    /**
     * HC general, spec 002 (entrega L): la consulta vino de la web y se lee siempre, abierta o cerrada allá (RF-45): sin
     * editar, sin cerrar, sin reabrir y sin anular. Lo decide `esSoloLecturaWeb`; false en las demás.
     */
    val soloLecturaWeb: Boolean = false,
    /** Los avisos de la consulta de la web (banner, abierta en la web, «Ya no figura en la web», `no_leidas`); ninguno si no es de la web. */
    val avisos: AvisosDeConsulta = AvisosDeConsulta.NINGUNO,
    /** Lo que la web dejó de mandar (RF-63), para las marcas de las secciones, cargas, estudios, pendientes y antecedentes. */
    val ausencias: AusenciasEnLaWeb = AusenciasEnLaWeb.NINGUNA,
    /**
     * Spec 005, T36: el estado de envío de una consulta **propia** de la HC general (RF-24, RF-25, RF-55): lo que falta mandar y cómo está
     * («Falta enviar», «En espera», «No se pudo enviar»), si el cierre viaja, si la web ya la tiene cerrada y si se puede reabrir o anular.
     * `null` en las demás (pediatría y las otras historias, el histórico, lo de la web). Se actualiza solo cuando cambia; la pantalla (T37) lo pinta.
     */
    val envio: EstadoDeLaConsulta? = null,
) {
    /** La consulta se envía a la web con la cola de la HC general (propia): solo ahí valen las franjas de estado y los avisos de lo que no se envía (T37). */
    val seEnviaALaWeb: Boolean get() = consulta?.seEnviaALaWeb() == true

    /** Las franjas de estado de envío de la consulta (RF-24, RF-30, RF-55); ninguna si no se envía o no falta nada. */
    val banners: List<BannerDeEnvio> get() = if (seEnviaALaWeb) bannersDeEnvio(envio) else emptyList()
    /** "HC digitalizada" en la HC general: se lee aunque haya quedado abierta ("Decidido" 5 del plan). */
    val soloLecturaPorTipo: Boolean get() = consulta?.let { resuelta?.soloLecturaPorTipo(it.tipo) } == true

    /** Si se edita, se cierra o se reabre: lo decide `edicionDeConsulta` (una consulta de la web no hace nada de eso). */
    val edicion: EdicionDeConsulta
        get() = edicionDeConsulta(
            estado = consulta?.estado ?: EstadoConsulta.CERRADA,
            soloLecturaPorTipo = soloLecturaPorTipo,
            puedeReabrirPorTipo = consulta?.let { resuelta?.puedeReabrir(it.tipo) } != false,
            soloLecturaWeb = soloLecturaWeb,
            cierreEnviadoOViajando = envio?.let { it.cierreEnviado || it.enviandoElCierre } == true,
        )
    val soloLectura: Boolean get() = edicion.soloLectura
    val puedeReabrir: Boolean get() = edicion.puedeReabrir
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
    private val definiciones: DefinicionesHc,
    /** Envío diferido a la API de la especialidad, cuando la tiene (hoy pediatría). */
    private val hcApi: HcApiSync,
    /** Solo para saber si la sesión es de un médico y filtrar "Enfermedades" (RF-4); el `medicoId` de abajo no cambia. */
    private val auth: AuthRepository,
    val consultaId: Id,
    private val medicoId: Id,
) : ViewModel() {

    /** Consultas con cambios guardados en el dispositivo que todavía no llegaron al servidor. */
    val pendientesDeEnviar: StateFlow<Int> = hcApi.pendientes

    /** Ámbito para el último guardado, el de `onCleared`, cuando `viewModelScope` ya no sirve. */
    private val salida = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val consultaFlow = hc.observarConsulta(consultaId)

    /**
     * La consulta vino de la web y se lee siempre (RF-45): esta pantalla no escribe, no marca nada para enviar ni llama a
     * `HcApiSync`. Arranca por el id (`hcc-…`) y, ya cargada la consulta, se confirma con `esSoloLecturaWeb` (que además mira
     * el `remoto_id` de un backend de solo lectura). Se asigna y se lee en el hilo principal.
     */
    private var soloLecturaWeb: Boolean = esIdDeLaWeb(consultaId)

    /**
     * La pantalla no escribe nada de esta consulta: viene de la web (RF-45) o es una propia de la HC general con el cierre ya enviado o viajando
     * (RF-25, D13; spec 005, T36). El repositorio la rechaza igual (`HcRepository.cierreEnviadoOViajando`): esto evita siquiera intentarlo.
     */
    private val noSeEscribe: Boolean get() = soloLecturaWeb || ui.value.envio?.let { it.cierreEnviado || it.enviandoElCierre } == true

    private val _lecturaWeb = MutableStateFlow(LecturaDeLaConsulta())

    /** Cómo va la lectura de la consulta de la web al abrirla (RF-56, RF-58); la pantalla muestra el motivo y «Reintentar». */
    val lecturaWeb: StateFlow<LecturaDeLaConsulta> = _lecturaWeb

    // Este init tiene que ir *después* de `consultaFlow`: `viewModelScope` usa Dispatchers.Main.immediate,
    // así que el cuerpo del launch arranca acá mismo y no en el siguiente turno del bucle de eventos.
    // Con el init más arriba, `consultaFlow` todavía valía null y la app se caía al abrir la consulta.
    init {
        // Al abrir la consulta se trae lo que haya en el servidor de la especialidad, sin pisar lo que el
        // médico escribió y todavía no se envió. Además se reintenta lo que haya quedado pendiente.
        viewModelScope.launch {
            val c = consultaFlow.first { it != null } ?: return@launch
            soloLecturaWeb = hc.esConsultaDeLaWeb(c)
            if (!hc.tieneApi(c.especialidad)) return@launch
            // Una consulta de la web: un solo pedido (RF-57), a mano si falla (RF-58), y nada que enviar ni reintentar.
            if (soloLecturaWeb) { leerDeLaWeb(c); return@launch }
            runCatching { hc.traerConsultaRemota(c) }
            // Spec 005 (T106, RF-45): los documentos de la sección «Documentos» se listan **al abrir** la consulta, nunca desde el
            // autoguardado. Si falla, la pantalla no se rompe: se vuelve a intentar al abrirla de nuevo.
            listarAdjuntos(c)
            hcApi.empujarTodo()
        }
    }

    /**
     * Trae el contenido de la consulta de la web (`traerConsulta`: un pedido) y deja el resultado en [lecturaWeb]. Si falla,
     * lo ya traído se queda y se ve (RF-56); no reintenta sola. Un `404 consulta_no_encontrada` ya dejó su marca «Ya no
     * figura en la web» (E6.4b) y la pantalla no lo cuenta como error.
     */
    private fun leerDeLaWeb(c: Consulta) {
        if (_lecturaWeb.value.leyendo) return
        _lecturaWeb.value = LecturaDeLaConsulta(leyendo = true)
        viewModelScope.launch {
            _lecturaWeb.value = try {
                hc.traerConsultaRemota(c)
                listarAdjuntos(c)
                LecturaDeLaConsulta()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LecturaDeLaConsulta(error = e)
            }
        }
    }

    /**
     * Los documentos de la sección «Documentos» de la consulta que se está abriendo (spec 005, T106; RF-45): una consulta nueva y en curso
     * lista los de todo el paciente y una previa solo los suyos (lo decide el backend). Nunca desde el autoguardado y nunca frena la consulta:
     * si falla, lo ya traído se queda y se vuelve a intentar al abrirla de nuevo o con «Reintentar» (RF-58).
     */
    private suspend fun listarAdjuntos(c: Consulta) {
        try {
            hc.traerAdjuntosRemotos(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // La lista de documentos no puede impedir que la consulta se abra ni que se lea su contenido.
        }
    }

    /** «Reintentar» después de un fallo de la lectura: a mano, nunca solo (RF-58). Solo para una consulta de la web. */
    fun reintentarLectura() {
        if (!soloLecturaWeb) return
        val c = ui.value.consulta ?: return
        leerDeLaWeb(c)
    }

    /**
     * HC general: la definición se resuelve una sola vez, al abrir, y queda fija para esta pantalla (RF-23): un cambio
     * de perfil con la consulta abierta se ve la próxima vez y no se pierde lo cargado. Nunca desde el autoguardado.
     */
    private val generalResuelta = MutableStateFlow<DefinicionResuelta?>(null)

    init {
        viewModelScope.launch {
            val c = consultaFlow.first { it != null } ?: return@launch
            if (definiciones.estatica(c.especialidad) == null) generalResuelta.value = definiciones.resolver(c.especialidad, medicoId)
        }
    }

    val ui: StateFlow<ConsultaUi> = consultaFlow.combine(generalResuelta) { c, g -> c to g }.flatMapLatest { (c, general) ->
        if (c == null) flowOf(ConsultaUi()) else {
            // Las especialidades de siempre, la registrada, como hasta ahora (RF-33).
            val resuelta = definiciones.estatica(c.especialidad) ?: general
            val def = resuelta?.definicion
            // Solo la HC general mira lo cargado fuera del perfil; las demás no piden nada más (RF-33).
            val catalogo = if (resuelta?.porPerfil == true) definiciones.catalogo(c.especialidad) else null
            // Spec 002 (entrega L): con un backend de solo lectura (HC general) además se leen las marcas, los avisos y los
            // nombres de los médicos de la web; las demás HC no piden nada más.
            val backendSoloLectura = hc.leeDeLaWeb(c.especialidad) // la HC se lee de la web, escriba o no el backend (spec 005, T23)
            val esDeLaWeb = hc.esConsultaDeLaWeb(c)
            val leeLaWeb = esDeLaWeb || backendSoloLectura
            combine(
                pacientes.observar(c.pacienteId),
                hc.observarExamenFisico(c.id),
                hc.observarAntecedentes(c.pacienteId, c.especialidad),
                hc.observarDiagnosticos(medicoId),
                hc.observarDiagnosticosDeConsulta(c.id),
                hc.observarPendientes(c.pacienteId, medicoId),
                hc.observarVacunas(c.pacienteId),
                hc.observarPreferencias(medicoId),
                if (catalogo != null) hc.observarDatosConsulta(c.id) else flowOf(DatosConsulta()),
                if (leeLaWeb) pacientes.observarFilasExtra(c.pacienteId, c.especialidad) else flowOf(emptyList<PacienteExtra>()),
                if (leeLaWeb) hc.observarNombresDeMedicosWeb() else flowOf(emptyMap<Id, String>()),
                hc.observarEstadoDeEnvio(c), // solo una propia de la HC general con cola; las demás, null
            ) { arr ->
                @Suppress("UNCHECKED_CAST")
                val antecedentes = arr[2] as List<Antecedente>
                @Suppress("UNCHECKED_CAST")
                val preferencias = arr[7] as Map<String, String>
                @Suppress("UNCHECKED_CAST")
                val lectura = if (leeLaWeb) extrasDeLectura(arr[9] as List<PacienteExtra>) else ExtrasDeLectura.VACIO
                @Suppress("UNCHECKED_CAST")
                val nombresMedicos = arr[10] as Map<Id, String>
                @Suppress("UNCHECKED_CAST")
                ConsultaUi(
                    consulta = c, paciente = arr[0] as Paciente?, definicion = def, tipo = def?.tipoConsulta(c.tipo),
                    examen = arr[1] as ExamenFisico?, antecedentes = antecedentes,
                    diagnosticosCatalogo = arr[3] as List<Diagnostico>, diagnosticosConsulta = (arr[4] as List<Diagnostico>).map { it.id }.toSet(),
                    pendientes = arr[5] as List<Pendiente>, vacunas = arr[6] as List<VacunaAplicada>, preferencias = preferencias,
                    resuelta = resuelta,
                    fueraDelPerfil = if (catalogo == null || def == null) SoloLecturaConsulta()
                    else soloLecturaConsulta(catalogo, def, c.tipo, arr[8] as DatosConsulta, antecedentes, ocultasDe(def, preferencias)),
                    soloLecturaWeb = esDeLaWeb,
                    avisos = avisosDeLaPantallaDeConsulta(c, backendSoloLectura, medicoId, nombresMedicos, lectura),
                    ausencias = lectura.ausencias,
                    envio = arr[11] as EstadoDeLaConsulta?,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConsultaUi())

    /**
     * El autoguardado terminó de guardar un cambio en el dispositivo (el que dispara cada 600 ms): avisa al envío diferido de la especialidad
     * (`HcApiSync`: pediatría, como siempre) y a la cola de la HC general (`HcRepository.avisarCambio`, spec 005, T36). **Ninguno hace un
     * pedido a la web ahora**: los dos programan un envío para 4 s después del último cambio. En una consulta de la web no avisa a nadie.
     */
    private suspend fun marcarSucia() {
        hcApi.marcarSucia(consultaId)
        hc.consulta(consultaId)?.let { hc.avisarCambio(it) }
    }

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
        // Una consulta de la web no se escribe: ni en memoria ni con el autoguardado (RF-45, RF-47).
        if (noSeEscribe) return
        _valores.value = _valores.value + (seccion to ((_valores.value[seccion] ?: emptyMap()) + (campo to valor)))
        pendientesGuardar.getOrPut(seccion) { mutableMapOf() }[campo] = valor
        jobGuardar?.cancel()
        jobGuardar = viewModelScope.launch { delay(600); flushValores() }
    }

    /** Escribe en la base los valores de sección que todavía están en memoria (autoguardado). */
    suspend fun flushValores() {
        if (noSeEscribe || pendientesGuardar.isEmpty()) return
        val copia = pendientesGuardar.mapValues { it.value.toMap() }
        pendientesGuardar.clear()
        copia.forEach { (s, campos) -> hc.guardarSeccion(consultaId, s, campos) }
        _guardadoEn.value = com.salud360.core.data.ahoraMillis()
        // El envío a la API va aparte y con su propia espera: acá se guarda en el dispositivo, que es
        // lo que no puede fallar, y el motor junta los cambios para mandarlos en un solo pedido.
        marcarSucia()
    }

    // ---- examen físico ----

    fun guardarExamen(examen: ExamenFisico) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        val imc = examen.calcularImc() ?: examen.imc
        if (!hc.guardarExamenFisico(examen.copy(consultaId = consultaId, imc = imc))) return@launch
        marcarSucia()
        _guardadoEn.value = com.salud360.core.data.ahoraMillis()
    }

    // ---- antecedentes ----

    fun guardarAntecedente(categoria: String, clave: String, flag: Boolean, detalle: String) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        val c = ui.value.consulta ?: return@launch
        // Un antecedente que vino de la web no se cambia desde una consulta del dispositivo (el repositorio lo rechaza): sin marca de envío.
        if (!hc.guardarAntecedente(c.pacienteId, c.especialidad, categoria, clave, flag, detalle, consultaId)) return@launch
        marcarSucia()
    }

    // ---- registros repetibles ----

    fun registros(tipo: String, porPaciente: Boolean) = ui.value.consulta?.let { c ->
        if (porPaciente) hc.observarRegistros(c.pacienteId, c.especialidad, tipo) else hc.observarRegistrosDeConsulta(c.id, tipo)
    } ?: flowOf(emptyList())

    fun registrosDelPaciente(tipo: String) = ui.value.consulta?.let { c -> hc.observarRegistros(c.pacienteId, c.especialidad, tipo) } ?: flowOf(emptyList())

    fun guardarRegistro(tipo: String, id: Id?, fecha: LocalDate?, campos: Map<String, String>, porPaciente: Boolean) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        val c = ui.value.consulta ?: return@launch
        // Al editar se conserva la consulta de origen. Un examen pedido en otra consulta se puede
        // completar desde esta, y moverlo acá lo haría pasar por pedido hoy: pediatría anota la consulta
        // donde se cargó la respuesta en una columna aparte, y el pedido queda donde estaba.
        val consultaOriginal = id?.let { hc.registro(it)?.consultaId }
        val deLaConsulta = if (porPaciente) null else consultaOriginal ?: c.id
        // Un registro que vino de la web no se edita desde acá (RF-62): el repositorio lo rechaza y no queda nada por enviar.
        if (!hc.guardarRegistro(RegistroClinico(id ?: newId(), c.pacienteId, deLaConsulta, c.especialidad, tipo, fecha, campos), enConsulta = consultaId)) return@launch
        marcarSucia()
    }

    fun eliminarRegistro(id: Id) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        if (!hc.eliminarRegistro(id)) return@launch
        marcarSucia()
    }

    // ---- estudios con historial (una entrada por consulta, RF-39) ----

    /** Fecha y texto escritos que todavía no están en la base, por tipo de registro. */
    private val pendientesEstudio = mutableMapOf<String, Pair<LocalDate, String>>()
    private var jobEstudios: Job? = null
    private val candadoEstudios = Mutex()

    /** Lo escrito y no guardado todavía de [tipo], para no mostrar lo viejo si la sección se vuelve a dibujar. */
    fun estudioPendiente(tipo: String): Pair<LocalDate, String>? = pendientesEstudio[tipo]

    /** Como el autoguardado de valores: en el dispositivo, con la misma espera; nunca la API. */
    fun guardarEstudio(tipo: String, fecha: LocalDate, texto: String) {
        if (noSeEscribe) return
        pendientesEstudio[tipo] = fecha to texto
        jobEstudios?.cancel()
        jobEstudios = viewModelScope.launch { delay(600); flushEstudios() }
    }

    /**
     * Aplica lo pendiente contra la entrada que tiene la consulta en la base en ese momento. De a un volcado por vez:
     * dos seguidos que leyeran "sin entrada" crearían dos. Lo pendiente se suelta recién guardado (y si no se volvió
     * a escribir), así un volcado cortado al salir lo deja para el de `onCleared`.
     */
    suspend fun flushEstudios() = candadoEstudios.withLock {
        if (noSeEscribe || pendientesEstudio.isEmpty()) return@withLock
        val c = ui.value.consulta ?: return@withLock
        pendientesEstudio.toMap().forEach { (tipo, cambio) ->
            val actual = entradaDeConsulta(hc.observarRegistrosDeConsulta(consultaId, tipo).first(), consultaId)
            when (val accion = cambioEstudio(actual, cambio.first, cambio.second)) {
                is CambioEstudio.Crear -> hc.guardarRegistro(RegistroClinico(newId(), c.pacienteId, consultaId, c.especialidad, tipo, accion.fecha, camposEstudio(accion.texto)), enConsulta = consultaId)
                is CambioEstudio.Actualizar -> hc.guardarRegistro(accion.registro, enConsulta = consultaId)
                is CambioEstudio.Borrar -> hc.eliminarRegistro(accion.registro.id)
                CambioEstudio.Nada -> Unit
            }
            if (pendientesEstudio[tipo] == cambio) pendientesEstudio.remove(tipo)
        }
        _guardadoEn.value = com.salud360.core.data.ahoraMillis()
        marcarSucia()
    }

    // ---- laboratorios ----

    fun laboratorios(tipo: String) = ui.value.consulta?.let { c -> hc.observarLaboratorios(c.pacienteId, c.especialidad, tipo) } ?: flowOf(emptyList())

    fun guardarLaboratorio(tipo: String, id: Id?, fecha: LocalDate, valores: Map<String, String>) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        val c = ui.value.consulta ?: return@launch
        // Al editar se conserva la consulta de origen: una carga del histórico que se completa desde esta consulta propia la reasigna
        // el repositorio, y solo si hay un cambio real (RF-68). Guardar lo mismo no marca nada.
        val consultaOriginal = id?.let { hc.laboratorio(it)?.consultaId }
        // Una carga que vino de la web no se crea ni se edita desde acá (RF-62): el repositorio la rechaza y nada queda por enviar.
        if (!hc.guardarLaboratorio(Laboratorio(id ?: newId(), c.pacienteId, consultaOriginal ?: c.id, c.especialidad, tipo, fecha, valores.filterValues { it.isNotBlank() }), enConsulta = consultaId)) return@launch
        marcarSucia()
    }

    /** Una carga que vino de la web no se borra (RF-62): sale sin hacer nada, y el repositorio la rechaza igual. */
    fun eliminarLaboratorio(id: Id) = viewModelScope.launch {
        if (noSeEscribe || esIdDeLaWeb(id)) return@launch
        if (!hc.eliminarLaboratorio(id)) return@launch
        marcarSucia()
    }

    // ---- archivos ----

    /** La sección «Documentos» de esta consulta lista los de **todo** el paciente si es nueva y en curso (Q-E4-3, T106); una previa, los suyos. */
    fun documentosDeTodoElPaciente(): Boolean = ui.value.consulta?.documentosDeTodoElPaciente() == true

    /** La consulta se envía a la web (propia de la HC general): solo ahí los adjuntos dicen si les falta viajar (T37, T107). */
    fun seEnviaALaWeb(): Boolean = ui.value.seEnviaALaWeb

    /** El tope efectivo de los adjuntos de esta pantalla, preguntado una sola vez (T107); `null` hasta que se sepa o si no hay API. */
    private var topeDeLosAdjuntos: RespuestaLimitesDeAdjuntosDto? = null

    /**
     * El aviso del tope **antes** de guardar un documento (spec 005, T107; RF-44): el texto de `limiteExcedido` con el máximo efectivo que
     * informa la web, o `null` si el archivo entra o si no se pudo preguntar. No frena la consulta ni marca nada: la cola lo vuelve a comparar
     * al subir (T104), y sin señal el archivo se guarda igual y se sube después.
     */
    suspend fun avisoDelTope(bytes: ByteArray): String? {
        val c = ui.value.consulta ?: return null
        val limites = preguntarTopeDeLosAdjuntos(c.especialidad) ?: return null
        return limiteExcedido(bytes.size.toLong(), limites)
    }

    /** `GET adjuntos/limites` una sola vez por pantalla; si falla no se reintenta solo y el archivo no se frena (RF-44, T104). */
    private suspend fun preguntarTopeDeLosAdjuntos(especialidad: String): RespuestaLimitesDeAdjuntosDto? {
        topeDeLosAdjuntos?.let { return it }
        return try {
            hc.limiteDeAdjuntos(especialidad)?.also { topeDeLosAdjuntos = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    fun archivos(seccion: String, porPaciente: Boolean = false) = ui.value.consulta?.let { c ->
        if (porPaciente) hc.observarArchivos(c.pacienteId, seccion) else hc.observarArchivosDeConsulta(c.id, seccion)
    } ?: flowOf(emptyList())

    fun archivosDeRegistro(registroId: Id) = hc.observarArchivosDeRegistro(registroId)

    fun agregarArchivo(seccion: String, nombre: String, bytes: ByteArray, registroId: Id? = null, duracionMs: Long = 0, porPaciente: Boolean = false) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        val c = ui.value.consulta ?: return@launch
        // Un adjunto de un registro de la web tampoco se agrega desde acá (RF-62).
        if (registroId != null && esIdDeLaWeb(registroId)) return@launch
        val id = newId()
        val ruta = archivos.guardar(id, nombre, bytes)
        // RF-44 (segunda vuelta del reviewer): en «Documentos» el tipo lo decide el nombre o el `Content-Type` y, si no lo definen, el **contenido**;
        // con ese MIME se guarda la fila, así el plan de envío (T104) también la reconoce.
        val mime = mimeDe(nombre)
        val mimeGuardado = if (seccion == SECCION_DOCUMENTOS) tipoDelArchivo(nombre, mime, bytes)?.mime ?: mime else mime
        hc.guardarArchivo(Archivo(id, c.pacienteId, if (porPaciente) null else c.id, registroId, seccion, nombre, mimeGuardado, bytes.size.toLong(), duracionMs, ruta))
        launch { sync.sincronizar() }
        marcarSucia()
    }

    suspend fun bytesDe(archivo: Archivo): ByteArray? {
        archivo.rutaLocal?.let { archivos.leer(it) }?.let { return it }
        // Lo que subió la web u otro dispositivo llega sin archivo: se baja la primera vez que hay que mostrarlo
        // (con la sesión) y queda guardado acá, así no se vuelve a pedir en cada recomposición (spec 005, T106).
        val consulta = ui.value.consulta
        if (consulta != null && archivo.remotoId.isNotBlank()) {
            hc.bytesDeArchivo(consulta, archivo, archivos)?.let { return it }
        }
        val ruta = sync.descargarArchivo(archivo.id) ?: return null
        return archivos.leer(ruta)
    }

    fun eliminarArchivo(id: Id) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        if (!hc.eliminarArchivo(id)) return@launch
        marcarSucia()
    }

    // ---- diagnósticos ----

    fun toggleDiagnostico(diagnosticoId: Id, asignado: Boolean) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        hc.asignarDiagnostico(consultaId, diagnosticoId, asignado)
    }

    fun nuevoDiagnostico(nombre: String) = viewModelScope.launch {
        if (soloLecturaWeb) return@launch
        hc.guardarDiagnostico(Diagnostico(newId(), medicoId, nombre.trim().uppercase()))
    }

    // ---- vacunas ----

    fun guardarVacuna(v: VacunaAplicada) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        hc.guardarVacuna(v.copy(consultaId = v.consultaId ?: consultaId))
    }

    // ---- pendientes ----

    /** Un pendiente que vino de la web no se resuelve desde acá: el cambio no llegaría (RF-62). */
    fun resolverPendiente(p: Pendiente) = viewModelScope.launch {
        if (noSeEscribe || !p.puedeResolverse()) return@launch
        hc.guardarPendiente(p.copy(resuelto = true))
    }

    /** Texto actual de los pendientes locales del paciente (los no resueltos, uno por línea), para prellenar el cierre. */
    fun textoPendientes(): String = textoDePendientesLocales(ui.value.pendientes)

    // ---- ciclo de vida de la consulta ----

    fun cambiarFecha(fecha: LocalDate) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        val c = ui.value.consulta ?: return@launch
        val edad = ui.value.paciente?.edad(fecha)?.toString() ?: c.edadMostrar
        hc.guardarConsulta(c.copy(fecha = fecha, edadMostrar = edad))
    }

    /**
     * Cierra la consulta dejando `texto` como pendientes para la próxima (modelo de la web: un solo texto por
     * paciente). Si el texto no cambió se conserva; si cambió, se resuelven los pendientes locales anteriores y, si no
     * está vacío, se crea uno nuevo asociado a esta consulta. Los pendientes de la web no se resuelven (RF-62).
     */
    fun cerrarConPendientes(texto: String, onCerrada: () -> Unit) = viewModelScope.launch {
        // Una consulta de la web no se cierra (RF-45): ni se crea ni se resuelve ningún pendiente, ni se manda nada.
        if (noSeEscribe) return@launch
        val c = ui.value.consulta ?: return@launch
        val cambio = cambioDePendientes(ui.value.pendientes, texto)
        cambio.resolver.forEach { hc.guardarPendiente(it) }
        cambio.crear?.let { hc.guardarPendiente(Pendiente(newId(), c.pacienteId, medicoId, it, consultaId)) }
        flushValores()
        flushEstudios()
        hc.cerrarConsulta(consultaId)
        // Al cerrar se manda ya, sin esperar: es el momento en que el médico da la consulta por terminada.
        hcApi.empujarYa(consultaId)
        launch { sync.sincronizar() }
        onCerrada()
    }

    /** «Reintentar» (RF-6, RF-30): le pide a la cola que mande ya lo pendiente y vuelva a incluir lo rechazado. La cola decide; acá no hay lógica. */
    fun reintentarEnvio() {
        if (noSeEscribe) return
        hc.reintentarEnvio(consultaId)
    }

    /** «Descartar» (RF-30): lo que la web rechazó queda «Descartado: no se envió a la web» y se conserva en el dispositivo. */
    fun descartarEnvio() {
        if (noSeEscribe) return
        hc.descartarEnvio(consultaId)
    }

    /**
     * «Anular consulta» (RF-26), tras la confirmación de la pantalla: una nunca creada en la web queda anulada solo acá; una creada se da de
     * baja en la web. Avisa con [onAnulada] solo si el repositorio la anuló (no lo hace con una cerrada cuyo cierre ya se envió ni mientras viaja).
     */
    fun anularConsulta(onAnulada: () -> Unit) = viewModelScope.launch {
        if (noSeEscribe) return@launch
        if (hc.anularConsulta(consultaId)) onAnulada()
    }

    fun reabrir() = viewModelScope.launch {
        if (noSeEscribe) return@launch
        hc.reabrirConsulta(consultaId)
    }

    fun guardarPreferencia(clave: String, valor: String) = viewModelScope.launch {
        // Una preferencia queda con pendiente de envío: desde una consulta de la web no se guarda nada (RF-47).
        if (soloLecturaWeb) return@launch
        hc.guardarPreferencia(medicoId, clave, valor)
    }

    /** Historial de un campo en consultas anteriores ("consultas previas"). */
    fun historial(seccion: String, campo: String) = ui.value.consulta?.let { c -> hc.observarHistorial(c.pacienteId, c.especialidad, seccion, campo) } ?: flowOf(emptyList())

    /** Texto de un campo en las demás consultas no anuladas del paciente, con la fecha de cada una (RF-83 a RF-85). Solo lee. */
    fun historialTexto(seccion: String, campo: String): Flow<List<EntradaTexto>> = ui.value.consulta?.let { c ->
        combine(hc.observarConsultas(c.pacienteId, c.especialidad), hc.observarValoresDeCampo(c.pacienteId, c.especialidad, seccion, campo)) { consultas, valores ->
            com.salud360.core.data.hcgeneral.historialTexto(consultas, valores, consultaId)
        }
    } ?: flowOf(emptyList())

    /** Último valor de cada campo de la sección en las otras consultas del paciente (lo que se arrastra entre consultas). */
    fun valoresPrevios(seccion: String) = ui.value.consulta?.let { c -> hc.observarUltimosValores(c.pacienteId, c.especialidad, seccion, consultaId) } ?: flowOf(emptyMap())

    fun examenesFisicos() = ui.value.consulta?.let { c -> hc.observarExamenesFisicos(c.pacienteId) } ?: flowOf(emptyList())

    /** Secciones visibles para la consulta según el tipo, la condición y las preferencias del médico. */
    fun seccionesVisibles(ui: ConsultaUi): List<SeccionDef> {
        val def = ui.definicion ?: return emptyList()
        val tipo = ui.tipo ?: return emptyList()
        // Agrupada (HC general): las de sus pestañas, nunca algo fuera del perfil (RF-21, RF-28).
        if (tipo.grupos.isNotEmpty()) {
            val layout = pestanias(ui)
            return (layout.pestanias.flatMap { it.secciones } + layout.cuerpo).mapNotNull { def.seccion(it) }
        }
        val ocultas = ocultas(ui)
        return tipo.secciones.mapNotNull { def.seccion(it) }.filter { s -> s.id !in ocultas && cumpleCondicion(s, ui) }
    }

    /** Pestañas y cuerpo de una consulta agrupada: "Datos", las del perfil con algo visible y "Consulta" debajo; vacío = la pantalla de siempre. */
    fun pestanias(ui: ConsultaUi): LayoutConsulta {
        val vacio = LayoutConsulta(emptyList(), emptyList())
        val def = ui.definicion ?: return vacio
        val tipo = ui.tipo ?: return vacio
        // RF-4: la secretaria y el administrador no ven la pestaña ni la sección de enfermedades, aunque el perfil la tenga.
        val medicoPropio = auth.sesion.value?.let(::medicoParaEnfermedades)
        return layoutConsulta(tipo, ocultas(ui)) { id -> visibleParaSesion(id, medicoPropio) && def.seccion(id)?.let { cumpleCondicion(it, ui) } == true }
    }

    /**
     * Si "Datos" muestra las enfermedades del paciente (RF-4, RF-6, RF-7, RF-57): solo la HC general y solo el médico de la
     * sesión; pediatría y las demás HC no pasan por ahí.
     */
    fun enfermedadesEnDatos(ui: ConsultaUi): Boolean {
        val def = ui.definicion ?: return false
        val medicoPropio = auth.sesion.value?.let(::medicoParaEnfermedades)
        return enfermedadesEnDatosDeConsulta(def, ocultas(ui), ui.resuelta?.porPerfil == true, medicoPropio)
    }

    private fun ocultas(ui: ConsultaUi): Set<String> = ui.definicion?.let { ocultasDe(it, ui.preferencias) } ?: emptySet()

    private fun ocultasDe(def: EspecialidadDefinition, preferencias: Map<String, String>): Set<String> =
        preferencias["secciones_ocultas_${def.codigo}"]?.split(',')?.toSet() ?: emptySet()

    fun cumpleCondicion(s: SeccionDef, ui: ConsultaUi): Boolean = when (val c = s.condicion) {
        null -> true
        is com.salud360.core.model.especialidad.CondicionSeccion.SoloFemenino -> ui.paciente?.sexo?.name == "F"
        is com.salud360.core.model.especialidad.CondicionSeccion.SoloMasculino -> ui.paciente?.sexo?.name == "M"
        is com.salud360.core.model.especialidad.CondicionSeccion.EdadMaximaMeses -> (ui.edadMeses ?: 0) <= c.meses
        is com.salud360.core.model.especialidad.CondicionSeccion.EdadMinimaMeses -> (ui.edadMeses ?: Int.MAX_VALUE) >= c.meses
    }

    // Al salir de la pantalla se vuelca lo que quedó en memoria y se manda sin esperar el debounce.
    //
    // No puede ir en `viewModelScope`: para cuando corre `onCleared` ese ámbito ya está cancelado (se
    // cierra antes, junto con los demás recursos del modelo de vista), así que el `launch` no haría
    // nada y se perdería lo tecleado en los últimos 600 ms, incluso en el dispositivo. Por eso un
    // ámbito propio, que nadie cancela.
    override fun onCleared() {
        // Una consulta de la web no vuelca ni manda nada al salir (RF-47); además el candado de E5.1 lo frena en el motor.
        if (noSeEscribe) return
        salida.launch {
            flushValores()
            flushEstudios()
            hcApi.empujarYa(consultaId)
        }
    }
}

/** Lista de consultas de un paciente en una especialidad + apertura de una nueva. */
class HistoriaClinicaViewModel(
    private val hc: HcRepository,
    pacientes: PacientesRepository,
    registry: EspecialidadRegistry,
    private val definiciones: DefinicionesHc,
    val pacienteId: Id,
    val especialidad: String,
    private val medicoId: Id,
) : ViewModel() {
    /** false = la especialidad no está en esta versión de la app. */
    val existe: Boolean = registry.existe(especialidad)
    val nombre: String = registry.nombre(especialidad)

    // Las especialidades de siempre la tienen ya; la HC general la arma con el perfil del médico, que puede tardar.
    private val _definicion = MutableStateFlow(definiciones.estatica(especialidad))

    /** Con qué se muestra la HC; null mientras se arma la de la HC general. */
    val definicion: StateFlow<DefinicionResuelta?> = _definicion

    val paciente: StateFlow<Paciente?> = pacientes.observar(pacienteId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** `null` hasta que la base responde: «todavía no se leyó» no es lo mismo que «no hay consultas». */
    val consultas: StateFlow<List<Consulta>?> = hc.observarConsultas(pacienteId, especialidad).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pendientes: StateFlow<List<Pendiente>> = hc.observarPendientes(pacienteId, medicoId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _mensaje = MutableStateFlow<String?>(null)

    /** Aviso cuando no se pudo traer la historia clínica del servidor de la especialidad. */
    val mensaje: StateFlow<String?> = _mensaje

    /**
     * true si la HC solo lee de la web (HC general, spec 002 entrega L): la pantalla muestra los grupos de [vista] y las
     * consultas de la web son solo para leer. Pediatría y las demás siguen con la lista de siempre.
     */
    val soloLectura: Boolean = hc.leeDeLaWeb(especialidad)

    /** true si la pantalla ofrece «Actualizar» (RF-57): solo donde el backend sabe leer a pedido. */
    val puedeActualizar: Boolean = hc.puedeActualizar(especialidad)

    private val _visibles = MutableStateFlow(CONSULTAS_POR_VEZ)

    /**
     * La historia tal como se muestra (grupos, «Ver más» y avisos; todo lo decide `vistaDeLaHistoria`), o null mientras
     * se lee de la base y siempre que la HC no es de solo lectura.
     */
    val vista: StateFlow<VistaDeLaHistoria?> =
        if (soloLectura) {
            combine(
                hc.observarConsultas(pacienteId, especialidad),
                hc.observarNombresDeMedicosWeb(),
                pacientes.observarFilasExtra(pacienteId, especialidad),
                _visibles,
            ) { consultas, nombres, extras, visibles -> vistaDeLaHistoria(consultas, medicoId, nombres, extras, visibles) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        } else {
            MutableStateFlow(null)
        }

    private val _leyendo = MutableStateFlow(false)

    /** Hay una lectura de la web en curso (al abrir, «Actualizar» o «Reintentar»). */
    val leyendo: StateFlow<Boolean> = _leyendo

    init {
        // Si la especialidad tiene su propio sistema (pediatría, la HC general), se trae lo que haya allá. La lista se
        // observa de la base, así que aparece sola; sin conexión se sigue viendo lo que ya está en el dispositivo.
        if (hc.tieneApi(especialidad)) leer(forzar = false)
        if (existe && _definicion.value == null) {
            viewModelScope.launch { _definicion.value = definiciones.resolver(especialidad, medicoId) }
        }
    }

    /**
     * Una lectura de la web. Sin [forzar] respeta la espera de un minuto del backend; con [forzar] («Actualizar», RF-57) la
     * salta. Si falla no reintenta sola (RF-58): deja el motivo en [mensaje] y lo ya traído se sigue viendo.
     */
    private fun leer(forzar: Boolean) {
        if (_leyendo.value) return
        _leyendo.value = true
        viewModelScope.launch {
            try {
                if (forzar) hc.actualizarConsultasRemotas(pacienteId, medicoId, especialidad)
                else hc.traerConsultasRemotas(pacienteId, medicoId, especialidad)
                _mensaje.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _mensaje.value =
                    if (soloLectura) motivoDeLaLectura(nombre, e).texto
                    else "No se pudo traer la historia clínica del servidor: ${e.message}"
            } finally {
                _leyendo.value = false
            }
        }
    }

    /** «Actualizar»: vuelve a leer la lista y el historial de la web aunque no haya pasado el minuto. */
    fun actualizar() = leer(forzar = true)

    /** «Reintentar» después de un fallo: a mano, nunca solo. Repite lo que no se pudo (respeta el minuto de lo que sí salió). */
    fun reintentar() = leer(forzar = false)

    /** «Ver más» (Q9): 50 consultas más de las que ya están en el dispositivo; no pide nada a la web. */
    fun verMas() = _visibles.update(::masConsultas)

    fun nuevaConsulta(tipo: String, onAbierta: (Consulta) -> Unit) = viewModelScope.launch {
        // Sin perfil o sin configuración no se crean consultas (RF-19, RF-26); la pantalla ya no lo ofrece.
        val def = definicion.value?.takeIf { r -> r.tiposNuevos.any { it.codigo == tipo } }?.definicion ?: return@launch
        val p = paciente.first { it != null }!!
        val fecha = hoy()
        val c = hc.abrirConsulta(pacienteId, medicoId, especialidad, tipo, fecha, p.edad(fecha)?.toString() ?: "")
        if (copiaDiagnosticos(def)) {
            hc.copiarUltimosDiagnosticos(pacienteId, especialidad, c.id)
        }
        onAbierta(c)
    }

    fun anular(c: Consulta) = viewModelScope.launch {
        // Una consulta de la web no se anula desde acá (RF-45): solo la "en curso" del dispositivo tiene esa acción.
        if (soloLectura && c.esDeLaWeb()) return@launch
        hc.anularConsulta(c.id)
    }
}
