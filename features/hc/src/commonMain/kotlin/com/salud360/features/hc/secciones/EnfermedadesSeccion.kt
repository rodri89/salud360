package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.data.hcgeneral.DURACION_GUARDADO
import com.salud360.core.data.hcgeneral.EstadoMarcado
import com.salud360.core.data.hcgeneral.MotivoEnfermedades
import com.salud360.core.data.hcgeneral.VistaSeccionEnfermedades
import com.salud360.core.data.hcgeneral.consultaEditable
import com.salud360.core.data.repos.PanelEnfermedades
import com.salud360.core.data.repos.TEXTO_ADMINISTRAR
import com.salud360.core.data.repos.TEXTO_GUARDADO
import com.salud360.core.data.repos.TEXTO_GUARDANDO
import com.salud360.core.data.repos.TEXTO_REINTENTAR
import com.salud360.core.data.repos.panelDeSeccion
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.AcceptButton
import com.salud360.core.ui.components.ActionRow
import com.salud360.core.ui.components.CasillaElegible
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.columnasPara
import com.salud360.features.hc.EnfermedadesViewModel
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Sección "Enfermedades" de la consulta (spec 003): una casilla por enfermedad de la lista del médico, que se marca o se
 * desmarca al tocarla. Solo dibuja y delega: qué se ve lo decide [panelDeSeccion] y lo que pasa al tocar, el
 * `EnfermedadesControlador` de [EnfermedadesViewModel]. Nada de esto pasa por `ctx` (ni por el autoguardado): cada toque
 * sale en el momento. Sin médico propio (secretaria, administrador) no dibuja nada (RF-4).
 */
@Composable
fun EnfermedadesSeccion(s: SeccionDef, ctx: SeccionContext) {
    val vm = koinViewModel<EnfermedadesViewModel>(key = "enfermedades-${ctx.consultaId}") { parametersOf(ctx.pacienteId) }
    val controlador = vm.controlador ?: return
    val consultaUi by ctx.vm.ui.collectAsState()
    // Abierta y propia (RF-35). `editable` no es observable: el controlador lo lee del ViewModel, que lo recibe acá.
    val editable = consultaUi.consulta?.let { consultaEditable(ctx.soloLectura, it.medicoId, vm.medicoId) } ?: false
    SideEffect { vm.editable = editable }
    LaunchedEffect(Unit) { controlador.abrir() }

    // Un solo cálculo en la misma composición que el estado y `editable` (también "Administrar", RF-37).
    val ui by controlador.estado.collectAsState()
    val panel = panelDeSeccion(ui, editable)

    // RF-24: una confirmación nueva reinicia los 3 s; cambiar de pestaña no la repite (`guardadoVisto` es del ViewModel).
    val guardado = (panel as? PanelEnfermedades.Contenido)?.guardado ?: 0
    var guardadoVisible by remember { mutableStateOf(false) }
    LaunchedEffect(guardado) {
        if (guardado > vm.guardadoVisto) {
            vm.guardadoVisto = guardado
            guardadoVisible = true
            // Si otra confirmación toma el relevo, ella lo deja visible; si no, se oculta al cumplirse o al cancelarse.
            try { delay(DURACION_GUARDADO) } finally { if (vm.guardadoVisto == guardado) guardadoVisible = false }
        }
    }

    // La ventana (modal) se abre con "Administrar enfermedades" y se cierra por su "Cerrar", o sola si se pierde la conexión o
    // aparece un motivo de los lugares 1 a 5 (`debeCerrarAdministrar`). Sigue abierta si solo se cierra la consulta: RF-58
    // pide que "Crear" siga funcionando.
    var administrarAbierto by rememberSaveable { mutableStateOf(false) }
    val onAdministrar: () -> Unit = { administrarAbierto = true }
    SectionCard(
        s.titulo,
        icon = iconoSeccion(s.icono ?: "enfermedades"),
        initiallyExpanded = s.inicialmenteExpandida,
        trailing = {
            if (guardadoVisible) {
                Text(
                    TEXTO_GUARDADO, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        },
    ) {
        when (panel) {
            PanelEnfermedades.Cargando -> Text("Cargando…")
            is PanelEnfermedades.ElegirFicha -> {
                ElegirFichaPaciente(
                    // El mismo texto que la spec da al motivo (RF-9): sale del motivo, no se repite acá.
                    titulo = MotivoEnfermedades.ElegirFicha(panel.candidatas).texto,
                    candidatas = panel.candidatas,
                    habilitado = panel.habilitado,
                    onElegir = controlador::elegirFicha,
                )
                Acciones(reintentar = false, administrar = panel.administrar, onReintentar = {}, onAdministrar = onAdministrar)
            }
            is PanelEnfermedades.Motivo -> {
                Text(panel.texto)
                Acciones(panel.reintentar, panel.administrar, controlador::reintentar, onAdministrar)
            }
            is PanelEnfermedades.SinCopia -> {
                Text(panel.texto)
                Acciones(panel.reintentar, administrar = false, onReintentar = controlador::reintentar, onAdministrar = onAdministrar)
            }
            is PanelEnfermedades.Contenido -> {
                panel.aviso?.let { Aviso(it, MaterialTheme.colorScheme.error) }
                panel.avisoSoloLectura?.let { Aviso(it, MaterialTheme.colorScheme.onSurface) }
                Vista(panel.vista, ui.marcado, controlador::tocar)
                Acciones(panel.reintentar, panel.administrar, controlador::reintentar, onAdministrar)
            }
        }
    }
    if (administrarAbierto) AdministrarEnfermedadesDialog(controlador, onCerrar = { administrarAbierto = false })
}

/** El único aviso de la sección (RF-29), que el lector de pantalla anuncia cuando aparece o cambia. */
@Composable
private fun Aviso(texto: String, color: Color) {
    Text(texto, color = color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** "Reintentar" y "Administrar enfermedades", solo los que el panel pide. */
@Composable
private fun Acciones(reintentar: Boolean, administrar: Boolean, onReintentar: () -> Unit, onAdministrar: () -> Unit) {
    if (!reintentar && !administrar) return
    ActionRow {
        if (reintentar) AcceptButton(TEXTO_REINTENTAR, onClick = onReintentar)
        if (administrar) AcceptButton(TEXTO_ADMINISTRAR, onClick = onAdministrar)
    }
}

@Composable
private fun Vista(vista: VistaSeccionEnfermedades, marcado: EstadoMarcado, onTocar: (Long, Boolean) -> Unit) {
    when (vista) {
        is VistaSeccionEnfermedades.Grilla -> {
            // Sin casillas (RF-18) se dice por qué; en cualquier caso siguen las "Otras" (RF-19), solo para leer.
            vista.sinMarcables?.let { Text(it) } ?: Grilla(vista, marcado, onTocar)
            vista.tituloOtras?.let { titulo ->
                Titulo(titulo)
                vista.otras.forEach { Text(it.nombre) }
            }
        }
        is VistaSeccionEnfermedades.EstadoActual -> {
            Titulo(vista.leyenda)
            vista.vacio?.let { Text(it) } ?: vista.enfermedades.forEach { Text(it.nombre) }
        }
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

/**
 * Casillas en filas parejas (RF-17): `columnasPara` según el ancho de la sección, cada fila del alto de su casilla más
 * alta y la última completada con huecos del mismo ancho. Una casilla no se toca con su pedido en camino (RF-22).
 */
@Composable
private fun Grilla(vista: VistaSeccionEnfermedades.Grilla, marcado: EstadoMarcado, onTocar: (Long, Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnas = columnasPara(maxWidth.value)
        Column(Modifier.fillMaxWidth()) {
            vista.marcables.chunked(columnas).forEach { fila ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    fila.forEach { m ->
                        CasillaElegible(
                            nombre = m.nombre,
                            marcada = m.tiene,
                            onCambio = { onTocar(m.id, it) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            habilitada = marcado.puedeTocar(m.id),
                            marca = TEXTO_GUARDANDO.takeIf { marcado.guardando(m.id) },
                        )
                    }
                    repeat(columnas - fila.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
