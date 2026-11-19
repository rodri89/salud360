package com.salud360.features.hc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.data.hcgeneral.DefinicionResuelta
import com.salud360.core.data.hcgeneral.TEXTO_ANULAR_CONSULTA
import com.salud360.core.data.hcgeneral.avisoDeEnvioDeLaSeccion
import com.salud360.core.data.hcgeneral.confirmacionDeAnular
import com.salud360.core.data.hcgeneral.ETIQUETA_WEB
import com.salud360.core.data.hcgeneral.esDeLaWeb
import com.salud360.core.data.hcgeneral.etiquetaDeEstadoEnLectura
import com.salud360.core.data.hcgeneral.marcaDeSeccion
import com.salud360.core.data.hcgeneral.mensajeDeLaLecturaDeConsulta
import com.salud360.core.data.hcgeneral.pendientesDeLaWeb
import com.salud360.core.data.hcgeneral.puedeResolverse
import com.salud360.core.data.repos.debePreguntarSecciones
import com.salud360.core.data.repos.hayConfigDeSecciones
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.especialidad.TipoSeccion
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.BackButton
import com.salud360.core.ui.components.BrandPanel
import com.salud360.core.ui.components.ConfirmDialog
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.InitialsAvatar
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.LoadingIndicator
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.StatusChip
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.secciones.AntecedentesSeccion
import com.salud360.features.hc.secciones.ArchivosSeccion
import com.salud360.features.hc.secciones.AudioSeccion
import com.salud360.features.hc.secciones.DatosPacienteSeccion
import com.salud360.features.hc.secciones.DiagnosticosSeccion
import com.salud360.features.hc.secciones.EnfermedadesEnDatos
import com.salud360.features.hc.secciones.ExamenFisicoSeccion
import com.salud360.features.hc.secciones.FormSeccion
import com.salud360.features.hc.secciones.LaboratorioSeccion
import com.salud360.features.hc.secciones.LocalSeccionEnPestania
import com.salud360.features.hc.secciones.RegistrosSeccion
import com.salud360.features.hc.secciones.TextoSeccion
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Pantalla de una consulta: cabecera del paciente, pestañas superiores (datos, antecedentes) y
 * secciones en acordeón, renderizadas a partir de la definición de la especialidad.
 * Cuando la consulta está cerrada se muestra en modo lectura, con todas las secciones expandidas.
 */
@Composable
fun ConsultaScreen(
    consultaId: Id,
    medicoId: Id,
    onCerrada: () -> Unit,
    onVolver: () -> Unit,
    vm: ConsultaViewModel = koinViewModel(key = "consulta-$consultaId") { parametersOf(consultaId, medicoId) },
) {
    val ui by vm.ui.collectAsState()
    val valores by vm.valores.collectAsState()
    val guardadoEn by vm.guardadoEn.collectAsState()
    val lecturaWeb by vm.lecturaWeb.collectAsState()
    val registry = koinInject<EspecialidadRegistry>()
    val consulta = ui.consulta
    val def = ui.definicion
    val tipo = ui.tipo
    if (consulta == null || def == null || tipo == null || ui.paciente == null) { LoadingIndicator(); return }

    var pestania by remember { mutableIntStateOf(0) }
    var mostrarCierre by remember { mutableStateOf(false) }
    var mostrarAnular by remember { mutableStateOf(false) }
    // Una consulta de la web se lee siempre, abierta o cerrada allá: `ui.soloLectura` la incluye (RF-45).
    val soloLectura = ui.soloLectura
    val color = Salud360Colors.especialidad(consulta.especialidad)

    // Pendientes que dejaron consultas anteriores (los creados al cerrar esta misma consulta no cuentan).
    val pendientesPrevios = ui.pendientes.filter { it.consultaId != consulta.id }
    // Al abrir una consulta en curso con pendientes se avisa una sola vez, como el modal de la web al cargar.
    var avisoMostrado by remember(consulta.id) { mutableStateOf(false) }
    var mostrarAviso by remember(consulta.id) { mutableStateOf(false) }
    LaunchedEffect(pendientesPrevios.isNotEmpty(), soloLectura) {
        if (!soloLectura && pendientesPrevios.isNotEmpty() && !avisoMostrado) { avisoMostrado = true; mostrarAviso = true }
    }

    // Primera vez que el médico abre una consulta de esta historia clínica: se le pregunta qué secciones quiere ver. Las
    // preferencias llegan en la misma emisión que la consulta, así que acá ya están leídas. `preguntaResuelta` tapa el
    // instante entre tocar un botón y que la base devuelva la preferencia guardada.
    val seccionesParaElegir = remember(def) { seccionesConfigurables(def) }
    var preguntaResuelta by remember(consulta.id) { mutableStateOf(false) }
    val preguntarSecciones = !preguntaResuelta && debePreguntarSecciones(
        configGuardada = hayConfigDeSecciones(ui.preferencias, clavePreferenciaSeccionesOcultas(def.codigo)),
        soloLectura = soloLectura,
        haySeccionesParaElegir = seccionesParaElegir.isNotEmpty(),
    )

    val ctx = remember(consulta.id, soloLectura, ui.edadMeses, ui.paciente?.sexo) {
        object : SeccionContext {
            override val consultaId = consulta.id
            override val pacienteId = consulta.pacienteId
            override val especialidad = consulta.especialidad
            override val soloLectura = soloLectura
            override val edadMeses = ui.edadMeses
            override val sexo = ui.paciente?.sexo?.name
            override val vm = vm
            override fun valor(seccion: String, campo: String) = vm.valor(seccion, campo)
            override fun setValor(seccion: String, campo: String, valor: String) = vm.setValor(seccion, campo, valor)
        }
    }
    // Lo de fuera del perfil (RF-22, RF-34) se lee siempre, aunque la consulta esté abierta, y nunca escribe.
    val ctxLectura = remember(ctx) {
        object : SeccionContext by ctx {
            override val soloLectura = true
            override fun setValor(seccion: String, campo: String, valor: String) = Unit
        }
    }
    AvisoSeccionesDesconocidas(ui.resuelta)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- cabecera ----
        BrandPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialsAvatar(ui.paciente!!.nombreCompleto, size = 48, color = Color.White.copy(alpha = 0.25f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.paciente!!.nombreCompleto, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text("${def.nombre} · ${tipo.nombre} · ${consulta.edadMostrar}".trimEnd(' ', '·'), color = Color.White.copy(alpha = 0.9f))
                }
                if (soloLectura) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    StatusChip(etiquetaDeEstadoEnLectura(consulta.estado, ui.soloLecturaPorTipo, ui.soloLecturaWeb), Color.White)
                } else {
                    StatusChip(guardadoEn?.let { "Guardado" } ?: "En edición", Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("Fecha de consulta", consulta.fecha, { it?.let(vm::cambiarFecha) }, Modifier.width(220.dp), readOnly = soloLectura, sobreMarca = true)
                if (pendientesPrevios.isNotEmpty()) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Salud360Colors.Warning)
                    Text("Con pendientes", color = Color.White)
                }
            }
        }

        // Una consulta de la web: sus avisos (RF-43 a RF-45, RF-63) y, si la lectura falló, el motivo con «Reintentar» (RF-56, RF-58).
        // Lo que ya estaba en el dispositivo se ve igual debajo, mientras se lee y después de un fallo.
        AvisosDeLaConsulta(ui.avisos)
        if (ui.soloLecturaWeb) {
            val mensaje = lecturaWeb.error?.let { mensajeDeLaLecturaDeConsulta(def.nombre, it) }
            if (mensaje != null) FalloDeLaLecturaDeLaConsulta(mensaje, lecturaWeb.leyendo, vm::reintentarLectura)
            else if (lecturaWeb.leyendo) LeyendoLaConsulta()
        }

        // Spec 005, T37: el estado de envío de una consulta propia de la HC general (RF-24, RF-30, RF-55): «Falta enviar», «En espera: .»,
        // «No se pudo enviar: .» con Reintentar y Descartar, «Descartado: no se envió a la web» y «Enviando el cierre.». Las franjas y sus
        // acciones las decide `bannersDeEnvio`; el histórico, lo de la web, pediatría y las demás historias no las tienen.
        EstadoDeEnvioDeLaConsulta(ui.banners, onReintentar = vm::reintentarEnvio, onDescartar = vm::descartarEnvio)

        pendientesPrevios.takeIf { it.isNotEmpty() }?.let { lista ->
            SectionCard("Pendientes de la consulta anterior", icon = Icons.Default.NotificationsActive, initiallyExpanded = true) {
                lista.forEach { p ->
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            p.texto.lines().filter { it.isNotBlank() }.forEach { Text("• $it") }
                            // El de la web no se resuelve desde acá (RF-62) y, si la web ya no lo tiene, sigue a la vista con la marca (RF-63).
                            if (p.esDeLaWeb()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusChip(ETIQUETA_WEB, MaterialTheme.colorScheme.onSurfaceVariant, pequeno = true)
                                if (ui.ausencias.pendiente(p.id)) MarcaYaNoFigura()
                            }
                        }
                        if (!soloLectura && p.puedeResolverse()) LinkButton("Resuelto", onClick = { vm.resolverPendiente(p) })
                    }
                }
            }
        }

        // Sin perfil (RF-19) o sin la configuración la primera vez (RF-26): no hay secciones, solo "Datos" (C10).
        ui.resuelta?.let { r ->
            if (r.sinPerfil) SectionCard("Falta que el administrador te asigne un perfil", collapsible = false) {
                Text(if (soloLectura) "Las consultas anteriores se pueden leer." else "Podés seguir esta consulta en \"Datos\" y cerrarla, o anularla desde la historia clínica.")
            }
            r.primeraVezSinConexion?.let { p ->
                SectionCard("La primera vez hace falta conexión", icon = Icons.Default.CloudOff, collapsible = false) {
                    Text(p.mensaje, color = Salud360Colors.Danger)
                    Text(
                        "Hasta recibir la configuración de tu historia clínica solo se ve \"Datos\". Lo cargado en este dispositivo no se pierde.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- pestañas del perfil (HC general): "Datos" y las del perfil, abriendo en "Datos" ----
        val layout = vm.pestanias(ui)
        val agrupadas = layout.pestanias
        if (agrupadas.isNotEmpty()) {
            val i = pestania.coerceIn(0, agrupadas.lastIndex)
            ScrollableTabRow(selectedTabIndex = i, edgePadding = 0.dp, containerColor = Color.Transparent, contentColor = color) {
                agrupadas.forEachIndexed { j, p -> Tab(selected = i == j, onClick = { pestania = j }, text = { Text(p.titulo, fontWeight = FontWeight.SemiBold) }) }
            }
            val actual = agrupadas[i]
            if (actual.id == SeccionesComunes.DATOS_PACIENTE) {
                CompositionLocalProvider(LocalSeccionEnPestania provides true) { DatosPacienteSeccion(ui.paciente!!, def, ctx) }
                // Spec 003 (RF-6, RF-7): las enfermedades del paciente cuando la sección no se muestra, solo HC general y médico.
                if (vm.enfermedadesEnDatos(ui)) EnfermedadesEnDatos(ctx)
                SoloLecturaFueraDelPerfil(ui.fueraDelPerfil.enDatos, ctxLectura, registry, valores)
            } else {
                actual.secciones.mapNotNull { def.seccion(it) }.forEach { s -> RenderSeccion(s, ctx, registry, valores) }
            }
            // El cuerpo de la consulta (la pestaña "Consulta" del perfil): debajo y siempre visible, como las
            // secciones sueltas de pediatría (RF-35).
            layout.cuerpo.mapNotNull { def.seccion(it) }.forEach { s -> RenderSeccion(s, ctx, registry, valores) }
        }

        // ---- pestañas superiores ----
        val pestanias = if (agrupadas.isNotEmpty()) emptyList() else tipo.pestanias.mapNotNull { id -> if (id == SeccionesComunes.DATOS_PACIENTE) SeccionDef(id, "Datos", TipoSeccion.CUSTOM) else def.seccion(id) }
        if (pestanias.isNotEmpty()) {
            ScrollableTabRow(selectedTabIndex = pestania.coerceIn(0, pestanias.lastIndex), edgePadding = 0.dp, containerColor = Color.Transparent, contentColor = color) {
                pestanias.forEachIndexed { i, s -> Tab(selected = pestania == i, onClick = { pestania = i }, text = { Text(s.titulo, fontWeight = FontWeight.SemiBold) }) }
            }
            val actual = pestanias[pestania.coerceIn(0, pestanias.lastIndex)]
            // Dentro de una pestaña, una sección sin datos en modo lectura muestra un aviso en lugar de desaparecer.
            CompositionLocalProvider(LocalSeccionEnPestania provides true) {
                if (actual.id == SeccionesComunes.DATOS_PACIENTE) DatosPacienteSeccion(ui.paciente!!, def, ctx) else RenderSeccion(actual, ctx, registry, valores)
            }
            // HC general sin perfil o "HC digitalizada": la única pestaña es "Datos". Ahí también van las enfermedades del
            // paciente (RF-6: el médico sin perfil no tiene la sección), antes de lo de fuera del perfil.
            if (actual.id == SeccionesComunes.DATOS_PACIENTE) {
                if (vm.enfermedadesEnDatos(ui)) EnfermedadesEnDatos(ctx)
                SoloLecturaFueraDelPerfil(ui.fueraDelPerfil.enDatos, ctxLectura, registry, valores)
            }
        }

        // ---- secciones (agrupada: ya están en sus pestañas o en el cuerpo, no queda nada suelto) ----
        if (agrupadas.isEmpty()) vm.seccionesVisibles(ui).filter { it.id !in tipo.pestanias }.forEach { s -> RenderSeccion(s, ctx, registry, valores) }

        // ---- HC general: lo cargado en esta consulta fuera del perfil, al final con cualquier pestaña (RF-22, RF-37) ----
        SoloLecturaFueraDelPerfil(ui.fueraDelPerfil.alFinal, ctxLectura, registry, valores)

        // ---- acciones ----
        ActionRow {
            BackButton(onClick = onVolver)
            // Lo decide `edicionDeConsulta`: una consulta de la web no tiene ni «Guardar y cerrar» ni «Reabrir para editar» (RF-45).
            if (ui.edicion.puedeCerrar) {
                PillButton("Guardar y cerrar consulta", onClick = { mostrarCierre = true })
            } else if (ui.edicion.puedeReabrir) {
                // Sin perfil, sin configuración o "HC digitalizada" de la HC general: solo para leer.
                AcceptButton("Reabrir para editar", onClick = { vm.reabrir() })
            }
            // Spec 005, T37 (RF-26): «Anular consulta» solo si la cola lo permite (`puedeAnular`): no una cerrada con el cierre enviado ni mientras
            // el cierre viaja. Pide confirmación y avisa lo que se pierde.
            if (ui.seEnviaALaWeb && ui.envio?.puedeAnular == true) LinkButton(TEXTO_ANULAR_CONSULTA, onClick = { mostrarAnular = true })
        }
        Spacer(Modifier.height(32.dp))
    }

    if (mostrarCierre && ui.edicion.puedeCerrar) PendientesCierreDialog(
        inicial = vm.textoPendientes(),
        deLaWeb = pendientesDeLaWeb(ui.pendientes),
        onConfirmar = { mostrarCierre = false; vm.cerrarConPendientes(it, onCerrada) },
        onCancelar = { mostrarCierre = false },
    )
    if (mostrarAnular && ui.envio?.puedeAnular == true) {
        val confirmacion = confirmacionDeAnular(consulta, ui.envio)
        ConfirmDialog(
            title = confirmacion.titulo, message = confirmacion.mensaje, confirmText = confirmacion.textoConfirmar, destructive = true,
            onConfirm = { mostrarAnular = false; vm.anularConsulta(onVolver) }, onDismiss = { mostrarAnular = false },
        )
    }
    if (preguntarSecciones) PreguntaSeccionesHcDialog(
        secciones = seccionesParaElegir,
        onGuardar = { ocultas ->
            preguntaResuelta = true
            vm.guardarPreferencia(clavePreferenciaSeccionesOcultas(def.codigo), ocultas.joinToString(","))
        },
        onUsarTodas = {
            preguntaResuelta = true
            vm.guardarPreferencia(clavePreferenciaSeccionesOcultas(def.codigo), "")
        },
    )
    // Un diálogo por vez: el aviso de pendientes espera a que el médico responda lo de las secciones.
    if (mostrarAviso && !preguntarSecciones) androidx.compose.material3.AlertDialog(
        onDismissRequest = { mostrarAviso = false },
        title = { Text("Pendientes de la consulta anterior") },
        text = { Column { pendientesPrevios.forEach { p -> p.texto.lines().filter { it.isNotBlank() }.forEach { Text("• $it") } } } },
        confirmButton = { TextButton(onClick = { mostrarAviso = false }) { Text("Aceptar") } },
    )
}

/**
 * Al guardar la consulta se piden los pendientes para la próxima (como el modal de la web al tocar GUARDAR):
 * el texto viene prellenado con los pendientes actuales; vacío significa que no queda nada pendiente.
 */
@Composable
private fun PendientesCierreDialog(inicial: String, deLaWeb: List<Pendiente>, onConfirmar: (String) -> Unit, onCancelar: () -> Unit) {
    var texto by remember(inicial) { mutableStateOf(inicial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text("Pendientes para la próxima consulta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAreaField("Pendientes", texto, { texto = it }, minLines = 4, placeholder = "Qué hay que recordar en la próxima consulta")
                // El pendiente de la web no se edita ni se resuelve desde acá (RF-62): el campo de arriba es solo el de los locales,
                // y el de la web se muestra aparte, solo para leer, y sigue ahí después de cerrar.
                deLaWeb.forEach { p ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusChip(ETIQUETA_WEB, MaterialTheme.colorScheme.onSurfaceVariant, pequeno = true)
                        p.texto.lines().filter { it.isNotBlank() }.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                Text(
                    "Se muestran al abrir la próxima consulta. Dejalo vacío si no hay pendientes. Al confirmar, la consulta queda cerrada en la historia clínica; podés reabrirla si necesitás corregir algo.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirmar(texto) }) { Text("Cerrar consulta") } },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}

/**
 * Secciones de la HC general con datos fuera del perfil del médico, solo para leer y con el nombre por defecto
 * (RF-22, RF-34). [ctx] es el de lectura: aunque la consulta esté abierta, desde acá no se escribe nada.
 */
@Composable
private fun SoloLecturaFueraDelPerfil(secciones: List<SeccionDef>, ctx: SeccionContext, registry: EspecialidadRegistry, valores: Map<String, Map<String, String>>) {
    if (secciones.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("Fuera de tu perfil, solo para leer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    secciones.forEach { s -> RenderSeccion(s, ctx, registry, valores) }
}

/**
 * RF-27: si el perfil del médico tiene secciones que esta versión no sabe mostrar, se avisa una vez por sesión
 * ([AvisosHcGeneral]), en la historia clínica o en la consulta, la que se abra primero.
 */
@Composable
internal fun AvisoSeccionesDesconocidas(resuelta: DefinicionResuelta?) {
    val avisos = koinInject<AvisosHcGeneral>()
    var mostrar by remember { mutableStateOf(false) }
    LaunchedEffect(resuelta) {
        if (resuelta?.desconocidas?.isNotEmpty() == true && avisos.avisarDesconocidas()) mostrar = true
    }
    if (mostrar) androidx.compose.material3.AlertDialog(
        onDismissRequest = { mostrar = false },
        title = { Text("Hace falta actualizar la app") },
        text = { Text("Tu perfil tiene secciones que esta versión de la app no sabe mostrar. Se ve todo lo demás; para verlas, actualizá la app.") },
        confirmButton = { TextButton(onClick = { mostrar = false }) { Text("Aceptar") } },
    )
}

/** El mismo contexto, solo para leer: no se escribe nada desde acá (una sección que no se envía, T37). */
private fun SeccionContext.soloParaLeer(): SeccionContext = object : SeccionContext by this {
    override val soloLectura = true
    override fun setValor(seccion: String, campo: String, valor: String) = Unit
}

/** Despacha cada sección al renderizador que corresponde a su tipo y, debajo, sus [SeccionDef.subsecciones]. */
@Composable
fun RenderSeccion(s: SeccionDef, ctx: SeccionContext, registry: EspecialidadRegistry, valores: Map<String, Map<String, String>>) {
    val ui by ctx.vm.ui.collectAsState()
    RenderSeccionSola(s, ctx, registry, valores, ui)
    s.subsecciones.mapNotNull { ui.definicion?.seccion(it) }.filter { ctx.vm.cumpleCondicion(it, ui) }.forEach { sub ->
        RenderSeccionSola(sub, ctx, registry, valores, ui)
    }
}

@Composable
private fun RenderSeccionSola(definida: SeccionDef, ctx0: SeccionContext, registry: EspecialidadRegistry, valores: Map<String, Map<String, String>>, ui: ConsultaUi) {
    // RF-63: lo que la web dejó de mandar de esta sección (o de alguno de sus campos) se sigue viendo, con la marca arriba.
    if (ui.soloLecturaWeb) {
        marcaDeSeccion(ui.ausencias, ctx0.consultaId, definida.id, definida.campos.associate { it.clave to it.etiqueta })?.let { MarcaDeLaSeccion(it) }
    }
    // Spec 005, T37 (RF-3, RF-4): en una consulta que se envía a la web, una sección que la web no guarda o que esta versión todavía no envía no
    // se ofrece para cargar: se ve solo para leer, con lo que ya tenía cargado (nada se borra), y dice por qué. `avisoDeEnvioDeLaSeccion` lo decide.
    val aviso = avisoDeEnvioDeLaSeccion(definida.id, ui.seEnviaALaWeb)
    aviso?.let { AvisoDeEnvioDeLaSeccion(definida.titulo, it) }
    val ctx = remember(ctx0, aviso != null) { if (aviso == null) ctx0 else ctx0.soloParaLeer() }
    // Una consulta previa (cerrada) se abre con todas las secciones que se dibujan expandidas; la en curso respeta
    // el estado inicial que define cada especialidad. El usuario puede contraer/expandir a mano después.
    val s = if (ctx.soloLectura) definida.copy(inicialmenteExpandida = true) else definida
    when (s.tipo) {
        TipoSeccion.FORM -> FormSeccion(s, ctx, valores[s.id] ?: emptyMap())
        TipoSeccion.TEXTO -> TextoSeccion(s, ctx, ui, valores[s.id] ?: emptyMap())
        TipoSeccion.EXAMEN_FISICO -> ExamenFisicoSeccion(s, ctx, ui, valores[s.id] ?: emptyMap())
        TipoSeccion.LABORATORIO -> LaboratorioSeccion(s, ctx, ui)
        TipoSeccion.REGISTROS -> RegistrosSeccion(s, ctx, ui)
        TipoSeccion.ANTECEDENTES -> AntecedentesSeccion(s, ctx, ui)
        TipoSeccion.ARCHIVOS -> ArchivosSeccion(s, ctx)
        TipoSeccion.AUDIO -> AudioSeccion(s, ctx)
        TipoSeccion.DIAGNOSTICOS -> DiagnosticosSeccion(s, ctx, ui)
        TipoSeccion.FORM_PACIENTE -> com.salud360.features.hc.secciones.FormPacienteSeccion(s, ctx)
        TipoSeccion.CUSTOM -> {
            val r = s.renderKey?.let { registry.renderer(it) }
            if (r != null) r(s, ctx) else SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "form")) { Text("Sección no disponible: ${s.renderKey}", color = MaterialTheme.colorScheme.error) }
        }
    }
}
