package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextAreaField
import com.salud360.features.hc.ConsultaUi
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion

/**
 * Sección de texto con historial (`SeccionDef.conHistorial`, hoy solo el motivo de consulta; RF-83 a RF-90): el texto
 * de esta consulta con "Consultas previas" al lado ([CampoConHistorial]), cada una con la fecha de su consulta. Se
 * guarda como cualquier texto (`ctx.setValor`, autoguardado en el dispositivo); [texto] llega de los valores
 * observados, nunca de `ctx.valor`. En solo lectura no se edita, pero las previas y "Ver todo" siguen.
 */
@Composable
fun TextoConHistorialSeccion(s: SeccionDef, ctx: SeccionContext, ui: ConsultaUi, texto: String) {
    // Recordado: con cada tecla se recompone y un flujo nuevo volvería a consultar la base.
    val historial by remember(s.id, ctx.consultaId) { ctx.vm.historialTexto(s.id, "texto") }.collectAsState(emptyList())
    val previas = historial.map { EntradaHistorial(it.fecha, it.texto) }
    val hayArchivos = s.conArchivos && hayArchivos(s.id, ctx)

    if (ctx.soloLectura && texto.isBlank() && previas.isEmpty() && !hayArchivos) { SeccionVacia(); return }

    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "texto"), initiallyExpanded = s.inicialmenteExpandida) {
        CampoConHistorial(s.titulo, EntradaHistorial(fechaConsulta(ui), texto), previas) { modifier ->
            Column(modifier) {
                if (ctx.soloLectura && texto.isBlank()) Text("Sin datos en esta consulta", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else TextAreaField(s.titulo, texto, { ctx.setValor(s.id, "texto", it) }, minLines = 10, readOnly = ctx.soloLectura)
            }
        }
        if (s.conArchivos) ArchivosInline(s.id, ctx)
    }
}
