package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.salud360.core.data.repos.ValorHistorico
import com.salud360.core.data.repos.consultasPrevias
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

/** Por debajo de este ancho, la última consulta previa va debajo del área de escritura y no a su derecha. */
private val AnchoMinimoDosColumnas = 600.dp

/** Alto máximo del panel de la última consulta previa: si el texto es más largo, se desplaza dentro del panel. */
private val AltoMaximoPanelPrevia = 180.dp

/**
 * Texto libre de una sección (guardado en `seccion_valor`) con el área de escritura del médico y, a su derecha, un panel
 * de solo lectura con la última consulta previa y un botón que abre todas. Reutilizable desde secciones a medida
 * (ej. vacunas en modo texto). Sin previas, o en lectura, se muestra solo el texto de esta consulta.
 */
@Composable
fun TextoLibreConPrevias(seccionId: String, campo: String, etiqueta: String, ctx: SeccionContext, valor: String = ctx.valor(seccionId, campo), minLines: Int = 5) {
    val historial by ctx.vm.historial(seccionId, campo).collectAsState(emptyList())
    val previas = if (ctx.soloLectura) emptyList() else consultasPrevias(historial, ctx.consultaId)
    val escritura: @Composable (Modifier) -> Unit = { modificador ->
        TextAreaField(etiqueta, valor, { ctx.setValor(seccionId, campo, it) }, modificador, minLines = minLines, readOnly = ctx.soloLectura)
    }
    if (previas.isEmpty()) { escritura(Modifier); return }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= AnchoMinimoDosColumnas) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                escritura(Modifier.weight(0.62f))
                PanelUltimaPrevia(previas, etiqueta, minLines, Modifier.weight(0.38f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                escritura(Modifier)
                PanelUltimaPrevia(previas, etiqueta, minLines, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Última consulta previa (fecha y texto) como campo de solo lectura de alto acotado, más el acceso a todas debajo.
 * Es un `OutlinedTextField` igual al del área de escritura: así el borde superior (que Material baja media altura de
 * la etiqueta para que ésta quede montada sobre él) coincide con el del textarea de al lado.
 */
@Composable
private fun PanelUltimaPrevia(previas: List<ValorHistorico>, titulo: String, minLines: Int, modifier: Modifier = Modifier) {
    var verTodas by remember { mutableStateOf(false) }
    val ultima = previas.first()
    val altoMinimo = (minLines * 24).dp
    val fondo = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    Column(modifier, horizontalAlignment = Alignment.End) {
        OutlinedTextField(
            value = ultima.valor,
            onValueChange = {},
            readOnly = true,
            label = { Text("Consultas previas · ${ultima.fecha.toDisplay()}") },
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = fondo, unfocusedContainerColor = fondo),
            modifier = Modifier.fillMaxWidth().heightIn(min = altoMinimo, max = maxOf(AltoMaximoPanelPrevia, altoMinimo)),
        )
        if (previas.size > 1) TextButton(onClick = { verTodas = true }) { Text("Ver todas (${previas.size})") }
    }
    if (verTodas) TodasLasPreviasDialog(titulo, previas) { verTodas = false }
}

/** Todas las consultas previas de un texto, de la más reciente a la más antigua, en una lista que se desplaza. */
@Composable
private fun TodasLasPreviasDialog(titulo: String, previas: List<ValorHistorico>, onCerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Consultas previas · $titulo") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                previas.forEachIndexed { i, h ->
                    if (i > 0) HorizontalDivider()
                    Text(h.fecha.toDisplay(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(h.valor, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}
