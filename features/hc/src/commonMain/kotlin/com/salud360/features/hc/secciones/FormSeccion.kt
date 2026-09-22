package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.salud360.core.model.especialidad.CampoDef
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.especialidad.TipoCampo
import com.salud360.core.ui.components.CheckboxField
import com.salud360.core.ui.components.CheckboxWithDetail
import com.salud360.core.ui.components.DateField
import com.salud360.core.ui.components.NumberField
import com.salud360.core.ui.components.RadioGroupField
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.SelectField
import com.salud360.core.ui.components.SiNoField
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.components.TextField
import com.salud360.core.ui.components.toDisplay
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.datetime.LocalDate

/**
 * Formulario genérico a partir de [CampoDef]. Los campos con el mismo `grupo` se acomodan en una fila.
 * Los valores se guardan como texto en la sección clave-valor de la consulta.
 */
@Composable
fun FormSeccion(s: SeccionDef, ctx: SeccionContext, valores: Map<String, String>) {
    val hayArchivos = s.conArchivos && hayArchivos(s.id, ctx)
    if (ctx.soloLectura && camposCompletos(s.campos, valores).isEmpty() && !hayArchivos) { SeccionVacia(); return }
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "form"), initiallyExpanded = s.inicialmenteExpandida) {
        CamposForm(s.campos, valores, ctx.soloLectura) { campo, valor -> ctx.setValor(s.id, campo, valor) }
        if (s.conArchivos) ArchivosInline(s.id, ctx)
    }
}

/** Si el valor guardado para el campo cuenta como "completado" (respeta las codificaciones de [Campo]). */
fun CampoDef.tieneValor(valor: String): Boolean = when (tipo) {
    TipoCampo.ETIQUETA -> false
    TipoCampo.CHECK -> valor == "1"
    TipoCampo.CHECK_DETALLE -> valor.substringBefore("|") == "1" || valor.substringAfter("|", "").isNotBlank()
    TipoCampo.SI_NO -> valor == "SI" || valor == "NO"
    TipoCampo.SI_NO_DETALLE -> valor.substringBefore("|") in setOf("SI", "NO") || valor.substringAfter("|", "").isNotBlank()
    else -> valor.isNotBlank()
}

/**
 * Campos con valor cargado (para el modo lectura). Una ETIQUETA se conserva solo si alguno de los campos
 * que le siguen, hasta la próxima etiqueta, tiene valor.
 */
fun camposCompletos(campos: List<CampoDef>, valores: Map<String, String>): List<CampoDef> {
    val resultado = mutableListOf<CampoDef>()
    var etiquetaPendiente: CampoDef? = null
    campos.forEach { c ->
        if (c.tipo == TipoCampo.ETIQUETA) etiquetaPendiente = c
        else if (c.tieneValor(valores[c.clave] ?: "")) {
            etiquetaPendiente?.let { resultado += it; etiquetaPendiente = null }
            resultado += c
        }
    }
    return resultado
}

/** Renderiza una lista de campos; reutilizado por registros repetibles y diálogos. En lectura solo pinta los completados. */
@Composable
fun CamposForm(campos: List<CampoDef>, valores: Map<String, String>, soloLectura: Boolean, onCambio: (String, String) -> Unit) {
    val visibles = if (soloLectura) camposCompletos(campos, valores) else campos
    val grupos = mutableListOf<List<CampoDef>>()
    var actual = mutableListOf<CampoDef>()
    var grupoActual: String? = null
    visibles.forEach { c ->
        if (c.grupo != null && c.grupo == grupoActual) actual += c
        else {
            if (actual.isNotEmpty()) grupos += actual
            actual = mutableListOf(c)
            grupoActual = c.grupo
        }
    }
    if (actual.isNotEmpty()) grupos += actual

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        grupos.forEach { grupo ->
            if (grupo.size == 1 || grupo.any { it.tipo == TipoCampo.TEXTO_LARGO || it.tipo == TipoCampo.CHECK_DETALLE || it.tipo == TipoCampo.SI_NO_DETALLE }) {
                grupo.forEach { c -> Campo(c, valores[c.clave] ?: "", soloLectura, Modifier.fillMaxWidth()) { onCambio(c.clave, it) } }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    grupo.forEach { c -> Campo(c, valores[c.clave] ?: "", soloLectura, Modifier.weight(c.peso)) { onCambio(c.clave, it) } }
                }
            }
        }
    }
}

@Composable
fun Campo(c: CampoDef, valor: String, soloLectura: Boolean, modifier: Modifier = Modifier, onCambio: (String) -> Unit) {
    val etiqueta = if (c.obligatorio) "${c.etiqueta} *" else c.etiqueta
    when (c.tipo) {
        TipoCampo.TEXTO -> TextField(etiqueta, valor, onCambio, modifier, readOnly = soloLectura, placeholder = c.placeholder)
        TipoCampo.TEXTO_LARGO -> TextAreaField(etiqueta, valor, onCambio, modifier, minLines = 3, readOnly = soloLectura, placeholder = c.placeholder)
        TipoCampo.NUMERO -> NumberField(etiqueta, valor, onCambio, modifier, suffix = c.unidad, readOnly = soloLectura)
        TipoCampo.FECHA -> DateField(etiqueta, valor.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, { onCambio(it?.toString() ?: "") }, modifier, readOnly = soloLectura)
        TipoCampo.HORA -> TextField(etiqueta, valor, onCambio, modifier, readOnly = soloLectura, placeholder = "HH:MM", keyboardType = KeyboardType.Number)
        TipoCampo.SELECT -> SelectField(etiqueta, valor.takeIf { it.isNotBlank() }, c.opciones, { onCambio(it ?: "") }, modifier, readOnly = soloLectura)
        TipoCampo.RADIO -> RadioGroupField(etiqueta, valor.takeIf { it.isNotBlank() }, c.opciones, onCambio, modifier, enabled = !soloLectura)
        TipoCampo.CHECK -> CheckboxField(etiqueta, valor == "1", { onCambio(if (it) "1" else "0") }, modifier, enabled = !soloLectura)
        TipoCampo.CHECK_DETALLE -> {
            val (flag, detalle) = valor.split("|", limit = 2).let { (it.getOrNull(0) == "1") to (it.getOrNull(1) ?: "") }
            CheckboxWithDetail(etiqueta, flag, detalle, { onCambio((if (it) "1" else "0") + "|" + detalle) }, { onCambio((if (flag) "1" else "0") + "|" + it) }, modifier, readOnly = soloLectura)
        }
        TipoCampo.SI_NO -> SiNoField(etiqueta, when (valor) { "SI" -> true; "NO" -> false; else -> null }, { onCambio(if (it) "SI" else "NO") }, modifier, enabled = !soloLectura)
        TipoCampo.SI_NO_DETALLE -> {
            val (opcion, detalle) = valor.split("|", limit = 2).let { it.getOrNull(0).orEmpty() to (it.getOrNull(1) ?: "") }
            Column(modifier) {
                SiNoField(etiqueta, when (opcion) { "SI" -> true; "NO" -> false; else -> null }, { onCambio((if (it) "SI" else "NO") + "|" + detalle) }, enabled = !soloLectura)
                if (opcion == "SI") TextAreaField("Detalle", detalle, { onCambio("SI|$it") }, Modifier.padding(start = 24.dp), minLines = 2, readOnly = soloLectura)
            }
        }
        TipoCampo.ETIQUETA -> Text(c.etiqueta, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Salud360Colors.Indigo, modifier = modifier)
    }
}

/** Sección de un único texto largo, con historial de consultas previas si existe. */
@Composable
fun TextoSeccion(s: SeccionDef, ctx: SeccionContext, valores: Map<String, String>) {
    val texto = valores["texto"] ?: ""
    val hayArchivos = s.conArchivos && hayArchivos(s.id, ctx)
    if (ctx.soloLectura && texto.isBlank() && !hayArchivos) { SeccionVacia(); return }
    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "texto"), initiallyExpanded = s.inicialmenteExpandida) {
        if (texto.isNotBlank() || !ctx.soloLectura) TextoLibreConPrevias(s.id, "texto", s.titulo, ctx, texto)
        if (s.conArchivos) ArchivosInline(s.id, ctx)
    }
}

/**
 * Texto libre de una sección (guardado en `seccion_valor`) precedido por lo cargado en consultas previas.
 * Reutilizable desde secciones a medida (ej. vacunas en modo texto). En lectura no se listan las previas:
 * se muestra solo lo cargado en esta consulta.
 */
@Composable
fun TextoLibreConPrevias(seccionId: String, campo: String, etiqueta: String, ctx: SeccionContext, valor: String = ctx.valor(seccionId, campo), minLines: Int = 5) {
    val historial by ctx.vm.historial(seccionId, campo).collectAsState(emptyList())
    val previas = if (ctx.soloLectura) emptyList() else historial.filter { it.consultaId != ctx.consultaId }
    if (previas.isNotEmpty()) {
        Text("Consultas previas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        previas.take(8).forEach { h ->
            Text("${h.fecha.toDisplay()}: ${h.valor}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        }
    }
    TextAreaField(etiqueta, valor, { ctx.setValor(seccionId, campo, it) }, minLines = minLines, readOnly = ctx.soloLectura)
}
