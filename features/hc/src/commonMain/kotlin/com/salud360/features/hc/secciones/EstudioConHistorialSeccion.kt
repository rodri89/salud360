package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.salud360.core.data.hcgeneral.entradaDeConsulta
import com.salud360.core.data.hcgeneral.historialEstudio
import com.salud360.core.data.hcgeneral.textoEstudio
import com.salud360.core.model.especialidad.RegistroDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextAreaField
import com.salud360.features.hc.ConsultaUi
import com.salud360.features.hc.MarcaYaNoFigura
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.datetime.LocalDate

/**
 * Estudio con historial (`ModoRegistro.UNO_POR_CONSULTA`, RF-38 a RF-42), como `biopsia_hepatica.blade.php` de la
 * web: la fecha y el texto de esta consulta con "Consultas previas" al lado ([CampoConHistorial]). Las previas no se
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

    if (ctx.soloLectura && texto.isBlank() && previas.isEmpty()) { SeccionVacia(); return }

    fun cambiar(f: LocalDate, t: String) {
        escrito = f to t
        ctx.vm.guardarEstudio(regDef.tipo, f, t)
    }

    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "registros"), initiallyExpanded = s.inicialmenteExpandida) {
        // RF-63: el estudio que la web ya no manda se sigue viendo entero, con la marca (el de esta consulta y los previos).
        val ausenteEnEstaConsulta = entrada?.let { ui.ausencias.fila(it.id) } == true
        CampoConHistorial(
            s.titulo, EntradaHistorial(fecha, texto, ausenteEnEstaConsulta),
            previas.map { EntradaHistorial(it.fecha, it.textoEstudio, ui.ausencias.fila(it.id)) },
        ) { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ausenteEnEstaConsulta) MarcaYaNoFigura()
                DateField("Fecha", fecha, { it?.let { f -> cambiar(f, texto) } }, readOnly = ctx.soloLectura)
                if (ctx.soloLectura && texto.isBlank()) Text("Sin datos en esta consulta", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else TextAreaField(s.titulo, texto, { cambiar(fecha, it) }, minLines = 10, readOnly = ctx.soloLectura)
            }
        }
    }
}
