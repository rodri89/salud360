package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.ModoAntecedentes
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.ui.components.CheckboxWithDetail
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.components.TextField
import com.salud360.core.ui.components.toDisplay
import com.salud360.features.hc.ConsultaUi
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.coroutines.delay

/**
 * Antecedentes por categoría (personales, familiares, perinatales, ginecológicos...). Se guardan
 * a nivel paciente y se acumulan entre consultas. Dos modos: casillas con detalle o texto acumulativo.
 */
@Composable
fun AntecedentesSeccion(s: SeccionDef, ctx: SeccionContext, ui: ConsultaUi) {
    val def = ui.definicion ?: return
    val cat = def.antecedentes.firstOrNull { it.categoria == s.categoriaAntecedentes } ?: return
    val actuales = ui.antecedentes.filter { it.categoria == cat.categoria }.associateBy { it.clave }
    val ro = ctx.soloLectura
    // En lectura solo se listan los ítems marcados o con detalle.
    val items = if (ro) cat.items.filter { i -> actuales[i.clave]?.let { it.flag || it.detalle.isNotBlank() } == true } else cat.items
    val historico = actuales["texto"]?.detalle ?: ""
    val nota = actuales["nota"]?.detalle ?: ""
    val hayContenido = when (cat.modo) {
        ModoAntecedentes.CASILLAS -> items.isNotEmpty()
        ModoAntecedentes.TEXTO_ACUMULATIVO -> historico.isNotBlank()
    } || (cat.notaLibre && nota.isNotBlank())
    if (ro && !hayContenido) { SeccionVacia(); return }

    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "antecedentes"), initiallyExpanded = s.inicialmenteExpandida) {
        when (cat.modo) {
            ModoAntecedentes.CASILLAS -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item ->
                    val a = actuales[item.clave]
                    if (item.soloDetalle) {
                        DebouncedText(item.etiqueta, a?.detalle ?: "", ro, multiline = false) { ctx.vm.guardarAntecedente(cat.categoria, item.clave, it.isNotBlank(), it) }
                    } else {
                        CheckboxWithDetail(
                            label = item.etiqueta, checked = a?.flag == true, detail = a?.detalle ?: "",
                            onCheckedChange = { ctx.vm.guardarAntecedente(cat.categoria, item.clave, it, a?.detalle ?: "") },
                            onDetailChange = { ctx.vm.guardarAntecedente(cat.categoria, item.clave, true, it) },
                            readOnly = ro,
                        )
                    }
                }
            }
            ModoAntecedentes.TEXTO_ACUMULATIVO -> {
                // el histórico acumulado se muestra arriba, la nota nueva se agrega abajo (HC hematología)
                if (historico.isNotBlank()) TextAreaField("Histórico", historico, {}, minLines = 3, readOnly = true)
                if (!ro) {
                    var nuevo by remember { mutableStateOf("") }
                    TextAreaField("Nuevo antecedente", nuevo, { nuevo = it }, minLines = 3, placeholder = "Escribí acá el nuevo antecedente")
                    com.salud360.core.ui.components.ActionRow {
                        com.salud360.core.ui.components.AcceptButton("Agregar al histórico", enabled = nuevo.isNotBlank(), onClick = {
                            val fecha = ui.consulta?.fecha?.toDisplay() ?: ""
                            val texto = (if (historico.isBlank()) "" else "$historico\n") + "[$fecha] " + nuevo.trim()
                            ctx.vm.guardarAntecedente(cat.categoria, "texto", true, texto)
                            nuevo = ""
                        })
                    }
                }
            }
        }
        if (cat.notaLibre && (!ro || nota.isNotBlank())) {
            DebouncedText("Nota", nota, ro, multiline = true) { ctx.vm.guardarAntecedente(cat.categoria, "nota", it.isNotBlank(), it) }
        }
    }
}

/** Campo de texto que guarda con retardo para no escribir en cada tecla. */
@Composable
fun DebouncedText(label: String, valor: String, soloLectura: Boolean, multiline: Boolean, onGuardar: (String) -> Unit) {
    var texto by remember(valor) { mutableStateOf(valor) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(texto, dirty) { if (dirty) { delay(700); onGuardar(texto); dirty = false } }
    if (multiline) TextAreaField(label, texto, { texto = it; dirty = true }, Modifier.fillMaxWidth(), minLines = 3, readOnly = soloLectura)
    else TextField(label, texto, { texto = it; dirty = true }, Modifier.fillMaxWidth(), readOnly = soloLectura)
}
