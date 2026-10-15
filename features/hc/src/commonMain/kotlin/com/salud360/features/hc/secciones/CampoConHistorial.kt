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
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.toDisplay
import kotlinx.datetime.LocalDate

/** Una entrada del historial tal como se muestra: la fecha (si tiene) y el texto. */
data class EntradaHistorial(val fecha: LocalDate?, val texto: String)

/**
 * Campo de esta consulta con su historial, como los estudios de la web (RF-38 a RF-42) y el motivo de consulta
 * (RF-83 a RF-90): a la izquierda [campo], a la derecha "Consultas previas" del mismo alto y con desplazamiento propio;
 * con menos de 600 dp, debajo. "Ver todo" abre [estaConsulta] y las [previas] en una ventana grande. [campo] recibe el
 * `Modifier` que tiene que ponerse en su contenedor (de ahí sale el alto del recuadro).
 */
@Composable
fun CampoConHistorial(titulo: String, estaConsulta: EntradaHistorial, previas: List<EntradaHistorial>, campo: @Composable (Modifier) -> Unit) {
    var verTodo by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val ancho = maxWidth >= 600.dp
        var altoCampo by remember { mutableIntStateOf(0) }
        val densidad = LocalDensity.current
        if (ancho) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                campo(Modifier.weight(1f).onSizeChanged { altoCampo = it.height })
                val alto = with(densidad) { altoCampo.toDp() }.coerceAtLeast(200.dp)
                ConsultasPrevias(previas, { verTodo = true }, Modifier.weight(1f).height(alto), llenar = true)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                campo(Modifier.fillMaxWidth().onSizeChanged { altoCampo = it.height })
                ConsultasPrevias(previas, { verTodo = true }, Modifier.fillMaxWidth(), llenar = false)
            }
        }
    }

    if (verTodo) VerTodoDialog(titulo, estaConsulta, previas, onCerrar = { verTodo = false })
}

/**
 * Recuadro "Consultas previas": título, "Ver todo" y las entradas con su propio desplazamiento. Al lado del campo
 * ([llenar]) ocupa lo que queda del alto del campo; debajo, hasta un máximo.
 */
@Composable
private fun ConsultasPrevias(previas: List<EntradaHistorial>, onVerTodo: () -> Unit, modifier: Modifier, llenar: Boolean) {
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
            previas.forEach { Entrada(it) }
        }
    }
}

@Composable
private fun Entrada(e: EntradaHistorial) {
    Column {
        e.fecha?.let { Text(it.toDisplay(), fontWeight = FontWeight.SemiBold) }
        Text(e.texto, style = MaterialTheme.typography.bodyMedium)
    }
}

/** RF-40/RF-87: el historial completo en una ventana grande, primero "Esta consulta"; cerrarla no cambia nada. */
@Composable
private fun VerTodoDialog(titulo: String, estaConsulta: EntradaHistorial, previas: List<EntradaHistorial>, onCerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f),
        title = { Text(titulo) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Titulo("Esta consulta")
                if (estaConsulta.texto.isBlank()) Text("Sin datos en esta consulta", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Entrada(estaConsulta)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Titulo("Consultas previas")
                if (previas.isEmpty()) Text("Sin consultas previas", color = MaterialTheme.colorScheme.onSurfaceVariant)
                previas.forEach { Entrada(it) }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}
