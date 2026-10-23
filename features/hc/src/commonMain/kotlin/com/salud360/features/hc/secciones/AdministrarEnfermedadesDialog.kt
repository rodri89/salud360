package com.salud360.features.hc.secciones

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.salud360.core.data.hcgeneral.Enfermedad
import com.salud360.core.data.repos.CrearVisible
import com.salud360.core.data.repos.EnfermedadesControlador
import com.salud360.core.data.repos.PanelAdministrar
import com.salud360.core.data.repos.debeCerrarAdministrar
import com.salud360.core.data.repos.panelDeAdministrar
import com.salud360.core.ui.components.TextField
import com.salud360.core.ui.theme.Salud360Colors

/** Alto máximo de cada lista de la ventana (RF-38): más allá se desplaza dentro de la lista, sin paginar. */
private val ALTO_MAXIMO_LISTA = 240.dp

/**
 * Ventana "Administrar enfermedades" (spec 003, RF-37 a RF-47, RF-58): el buscador, la lista del médico con "Quitar" y
 * "Del catálogo" con "Agregar". **Modal**, como `VerTodoDialog`: con ella abierta no se toca nada de lo de atrás. Solo
 * dibuja y delega: qué se ve lo decide [panelDeAdministrar] y lo que pasa al tocar, el [controlador]. Al mostrarse pide
 * el estado de administrar (un solo pedido); "Cerrar" y cerrarla por fuera llaman a `cerrarAdministrar()` y a [onCerrar]. Se
 * cierra sola por el mismo camino si la sección deja de poder administrar ([debeCerrarAdministrar]).
 *
 * Solo quitar, agregar y crear (RF-47: nada más sobre el catálogo). Sin botón para volver a pedir: si falló al abrir, se
 * cierra y se vuelve a abrir.
 */
@Composable
fun AdministrarEnfermedadesDialog(controlador: EnfermedadesControlador, onCerrar: () -> Unit) {
    val ui by controlador.administrar.collectAsState()
    LaunchedEffect(Unit) { controlador.abrirAdministrar() }
    val cerrar = {
        controlador.cerrarAdministrar()
        onCerrar()
    }
    // Sin conexión o con un motivo de los lugares 1 a 5 se cierra sola, por el mismo camino que "Cerrar"; el aviso ya está
    // en la sección de atrás. Una consulta que se cierra no la cierra (RF-58). Qué cuenta lo decide la regla pura.
    val seccion by controlador.estado.collectAsState()
    val hayQueCerrar = debeCerrarAdministrar(seccion)
    LaunchedEffect(hayQueCerrar) { if (hayQueCerrar) cerrar() }
    AlertDialog(
        onDismissRequest = cerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f),
        title = { Text("Administrar enfermedades") },
        text = {
            // Desplazamiento propio de la ventana (RF-38). Las listas de adentro tienen alto acotado: una `LazyColumn` con
            // alto infinito dentro de un contenedor con scroll vertical hace caer la composición.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val panel = panelDeAdministrar(ui)) {
                    PanelAdministrar.Cargando -> Text("Cargando…")
                    is PanelAdministrar.SoloAviso -> Aviso(panel.texto, MaterialTheme.colorScheme.error)
                    is PanelAdministrar.Contenido -> Contenido(
                        panel = panel,
                        buscado = ui.buscado,
                        onBuscar = controlador::buscar,
                        onCrear = controlador::crear,
                        onQuitar = controlador::quitar,
                        onAgregar = controlador::agregar,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = cerrar) { Text("Cerrar") } },
    )
}

@Composable
private fun Contenido(
    panel: PanelAdministrar.Contenido,
    buscado: String,
    onBuscar: (String) -> Unit,
    onCrear: (String) -> Unit,
    onQuitar: (Long) -> Unit,
    onAgregar: (Long) -> Unit,
) {
    // Lo escrito no se limpia al crear ni ante un rechazo (P3-A, RF-46): lo guarda el controlador.
    TextField(
        label = "Buscar o crear una enfermedad",
        value = buscado,
        onValueChange = onBuscar,
        isError = panel.crear?.error != null,
    )
    panel.crear?.let { Crear(it, habilitado = panel.accionesHabilitadas, onCrear = { onCrear(it.nombre) }) }
    panel.aviso?.let { Aviso(it, MaterialTheme.colorScheme.onSurface) }

    Titulo("Tu lista")
    ListaAcotada(panel.lista, accion = "Quitar", habilitada = panel.accionesHabilitadas, onAccion = onQuitar)

    Titulo("Del catálogo")
    panel.textoCatalogo?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    ListaAcotada(panel.catalogo, accion = "Agregar", habilitada = panel.accionesHabilitadas, onAccion = onAgregar)
}

/**
 * "Crear «nombre»" (RF-42), o el error de RF-43 en rojo y sin botón apenas se escribe (P5-A). El texto del botón puede
 * ser largo (hasta 255 caracteres): el botón crece, no corta el nombre.
 */
@Composable
private fun Crear(crear: CrearVisible, habilitado: Boolean, onCrear: () -> Unit) {
    val error = crear.error
    if (error != null) {
        Aviso(error, MaterialTheme.colorScheme.error)
    } else {
        Boton("Crear «${crear.nombre}»", habilitado, onCrear, Modifier.fillMaxWidth())
    }
}

/**
 * Las enfermedades de una lista con su botón, en una `LazyColumn` de alto acotado ([ALTO_MAXIMO_LISTA]): "Del catálogo"
 * puede traer miles con una letra escrita, y así solo se componen las que se ven. Una lista que no coincide con lo
 * escrito queda vacía y no dice nada.
 */
@Composable
private fun ListaAcotada(enfermedades: List<Enfermedad>, accion: String, habilitada: Boolean, onAccion: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = ALTO_MAXIMO_LISTA), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(enfermedades, key = { it.id }) { e ->
            // Una sola parada para el lector: "Asma, Quitar" y no un "Quitar" suelto por cada fila. El teclado no cambia.
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(e.nombre, modifier = Modifier.weight(1f))
                Boton(accion, habilitada, { onAccion(e.id) })
            }
        }
    }
}

/** El botón de las filas, del estilo de "Elegir" (`ElegirFichaPaciente`): 48 dp como mínimo. */
@Composable
private fun Boton(texto: String, habilitado: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
        shape = CircleShape,
        border = BorderStroke(1.5.dp, Salud360Colors.Success),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Salud360Colors.SuccessDark),
    ) { Text(texto) }
}

/** El único aviso de la ventana, que el lector de pantalla anuncia cuando aparece o cambia (RF-46). */
@Composable
private fun Aviso(texto: String, color: Color) {
    Text(texto, color = color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}
