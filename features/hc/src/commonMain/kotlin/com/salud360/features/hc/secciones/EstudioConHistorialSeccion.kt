package com.salud360.features.hc.secciones

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.salud360.core.data.hcgeneral.entradaDeConsulta
import com.salud360.core.data.hcgeneral.historialEstudio
import com.salud360.core.data.hcgeneral.textoEstudio
import com.salud360.core.model.especialidad.RegistroDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.components.toDisplay
import com.salud360.features.hc.ConsultaUi
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.datetime.LocalDate

/**
 * Estudio con historial (`ModoRegistro.UNO_POR_CONSULTA`, RF-38 a RF-42), como `biopsia_hepatica.blade.php` de la
 * web: a la izquierda la fecha y el texto de esta consulta, a la derecha "Consultas previas" del mismo alto y con
 * desplazamiento propio; con menos de 600 dp, debajo. "Ver todo" abre el historial completo. Las previas no se
 * editan desde acá. Qué se edita y qué va a la derecha lo deciden `entradaDeConsulta`/`historialEstudio`.
 */
@Composable
fun EstudioConHistorialSeccion(s: SeccionDef, regDef: RegistroDef, ctx: SeccionContext, ui: ConsultaUi) {
    // Recordado: con cada tecla se recompone y un flujo nuevo volvería a consultar la base.
    val registros by remember(regDef.tipo, ctx.consultaId) { ctx.vm.registrosDelPaciente(regDef.tipo) }.collectAsState(emptyList())
    val entrada = entradaDeConsulta(registros, ctx.consultaId)
    val previas = historialEstudio(registros, entrada)
    // Lo que el médico escribió manda sobre la base, que se pone al día recién con el autoguardado.
    var escrito by remember(regDef.tipo) { mutableStateOf(ctx.vm.estudioPendiente(regDef.tipo)) }
    val fecha = escrito?.first ?: entrada?.fecha ?: fechaConsulta(ui)
    val texto = escrito?.second ?: entrada?.textoEstudio.orEmpty()
    var verTodo by remember { mutableStateOf(false) }

    if (ctx.soloLectura && texto.isBlank() && previas.isEmpty()) { SeccionVacia(); return }

    fun cambiar(f: LocalDate, t: String) {
        escrito = f to t
        ctx.vm.guardarEstudio(regDef.tipo, f, t)
    }

    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "registros"), initiallyExpanded = s.inicialmenteExpandida) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val ancho = maxWidth >= 600.dp
            var altoCampo by remember { mutableIntStateOf(0) }
            val densidad = LocalDensity.current
            val campo = @Composable { modifier: Modifier ->
                Column(modifier.onSizeChanged { altoCampo = it.height }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField("Fecha", fecha, { it?.let { f -> cambiar(f, texto) } }, readOnly = ctx.soloLectura)
                    if (ctx.soloLectura && texto.isBlank()) Text("Sin datos en esta consulta", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else TextAreaField(s.titulo, texto, { cambiar(fecha, it) }, minLines = 10, readOnly = ctx.soloLectura)
                }
            }
            if (ancho) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    campo(Modifier.weight(1f))
                    val alto = with(densidad) { altoCampo.toDp() }.coerceAtLeast(200.dp)
                    ConsultasPrevias(previas, { verTodo = true }, Modifier.weight(1f).height(alto), llenar = true)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    campo(Modifier.fillMaxWidth())
                    ConsultasPrevias(previas, { verTodo = true }, Modifier.fillMaxWidth(), llenar = false)
                }
            }
        }
    }

    if (verTodo) VerTodoDialog(s.titulo, fecha.takeIf { texto.isNotBlank() }, texto, previas, onCerrar = { verTodo = false })
}

/**
 * Recuadro "Consultas previas": título, "Ver todo" y las entradas con su propio desplazamiento. Al lado del campo
 * ([llenar]) ocupa lo que queda del alto del campo; debajo, hasta un máximo.
 */
@Composable
private fun ConsultasPrevias(previas: List<RegistroClinico>, onVerTodo: () -> Unit, modifier: Modifier, llenar: Boolean) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Consultas previas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            LinkButton("Ver todo", onVerTodo)
        }
        Column(
            (if (llenar) Modifier.weight(1f) else Modifier.heightIn(max = 280.dp)).fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (previas.isEmpty()) Text("Sin consultas previas", color = MaterialTheme.colorScheme.onSurfaceVariant)
            previas.forEach { EntradaEstudio(it.fecha, it.textoEstudio) }
        }
    }
}

@Composable
private fun EntradaEstudio(fecha: LocalDate?, texto: String) {
    Column {
        fecha?.let { Text(it.toDisplay(), fontWeight = FontWeight.SemiBold) }
        Text(texto, style = MaterialTheme.typography.bodyMedium)
    }
}

/** RF-40: el historial completo en una ventana grande, primero "Esta consulta"; cerrarla no cambia nada. */
@Composable
private fun VerTodoDialog(titulo: String, fecha: LocalDate?, texto: String, previas: List<RegistroClinico>, onCerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f),
        title = { Text(titulo) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Titulo("Esta consulta")
                if (texto.isBlank()) Text("Sin datos en esta consulta", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else EntradaEstudio(fecha, texto)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Titulo("Consultas previas")
                if (previas.isEmpty()) Text("Sin consultas previas", color = MaterialTheme.colorScheme.onSurfaceVariant)
                previas.forEach { EntradaEstudio(it.fecha, it.textoEstudio) }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}
