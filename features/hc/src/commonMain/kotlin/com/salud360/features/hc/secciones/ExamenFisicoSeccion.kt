package com.salud360.features.hc.secciones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.ui.components.NumberField
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.components.TextField
import com.salud360.features.hc.ConsultaUi
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.coroutines.delay

/**
 * Examen físico común (peso, talla, IMC calculado, TA, PC, CA, FC, temperatura, saturación, IPD, nota)
 * más los campos extra de la especialidad. Con `conPercentilos` muestra los percentilos manuales de pediatría.
 */
@Composable
fun ExamenFisicoSeccion(s: SeccionDef, ctx: SeccionContext, ui: ConsultaUi, extras: Map<String, String>) {
    val def = ui.definicion ?: return
    val campos = def.examenFisicoCampos
    var ex by remember(ui.examen?.consultaId) { mutableStateOf(ui.examen ?: ExamenFisico(ctx.consultaId)) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(ex, dirty) { if (dirty) { delay(600); ctx.vm.guardarExamen(ex); dirty = false } }
    fun upd(t: (ExamenFisico) -> ExamenFisico) { ex = t(ex); dirty = true }
    val ro = ctx.soloLectura

    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "examen"), initiallyExpanded = s.inicialmenteExpandida) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (CampoExamenFisico.PESO in campos) NumberField("Peso", ex.peso, { v -> upd { it.copy(peso = v) } }, Modifier.weight(1f), suffix = "kg", readOnly = ro)
            if (def.conPercentilos && CampoExamenFisico.PESO in campos) TextField("Pc peso", ex.pesoPercentil, { v -> upd { it.copy(pesoPercentil = v) } }, Modifier.weight(0.7f), readOnly = ro)
            if (CampoExamenFisico.TALLA in campos) NumberField("Talla", ex.talla, { v -> upd { it.copy(talla = v) } }, Modifier.weight(1f), suffix = "m", readOnly = ro)
            if (def.conPercentilos && CampoExamenFisico.TALLA in campos) TextField("Pc talla", ex.tallaPercentil, { v -> upd { it.copy(tallaPercentil = v) } }, Modifier.weight(0.7f), readOnly = ro)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (CampoExamenFisico.IMC in campos) NumberField("IMC", ex.calcularImc() ?: ex.imc, { v -> upd { it.copy(imc = v) } }, Modifier.weight(1f), readOnly = true)
            if (def.conPercentilos && CampoExamenFisico.IMC in campos) TextField("Pc IMC", ex.imcPercentil, { v -> upd { it.copy(imcPercentil = v) } }, Modifier.weight(0.7f), readOnly = ro)
            if (CampoExamenFisico.TENSION_ARTERIAL in campos) TextField("TA", ex.tensionArterial, { v -> upd { it.copy(tensionArterial = v) } }, Modifier.weight(1f), readOnly = ro, placeholder = "120/80")
            if (CampoExamenFisico.FRECUENCIA_CARDIACA in campos) NumberField("FC", ex.frecuenciaCardiaca, { v -> upd { it.copy(frecuenciaCardiaca = v) } }, Modifier.weight(0.8f), suffix = "lpm", readOnly = ro)
        }
        if (CampoExamenFisico.PERIMETRO_CEFALICO in campos || CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL in campos || CampoExamenFisico.IPD in campos || CampoExamenFisico.TEMPERATURA in campos || CampoExamenFisico.SATURACION in campos) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (CampoExamenFisico.PERIMETRO_CEFALICO in campos) NumberField("PC", ex.perimetroCefalico, { v -> upd { it.copy(perimetroCefalico = v) } }, Modifier.weight(1f), suffix = "cm", readOnly = ro)
                if (def.conPercentilos && CampoExamenFisico.PERIMETRO_CEFALICO in campos) TextField("Pc PC", ex.perimetroCefalicoPercentil, { v -> upd { it.copy(perimetroCefalicoPercentil = v) } }, Modifier.weight(0.7f), readOnly = ro)
                if (CampoExamenFisico.IPD in campos) NumberField("IPD", ex.ipd, { v -> upd { it.copy(ipd = v) } }, Modifier.weight(1f), suffix = "g/día", readOnly = ro)
                if (CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL in campos) NumberField("CA", ex.circunferenciaAbdominal, { v -> upd { it.copy(circunferenciaAbdominal = v) } }, Modifier.weight(1f), suffix = "cm", readOnly = ro)
                if (CampoExamenFisico.TEMPERATURA in campos) NumberField("Temp.", ex.temperatura, { v -> upd { it.copy(temperatura = v) } }, Modifier.weight(0.8f), suffix = "°C", readOnly = ro)
                if (CampoExamenFisico.SATURACION in campos) NumberField("Sat O2", ex.saturacion, { v -> upd { it.copy(saturacion = v) } }, Modifier.weight(0.8f), suffix = "%", readOnly = ro)
            }
        }
        if (def.examenFisicoExtra.isNotEmpty()) CamposForm(def.examenFisicoExtra, extras, ro) { campo, valor -> ctx.setValor(s.id, campo, valor) }
        if (CampoExamenFisico.NOTA in campos) TextAreaField("Examen físico", ex.nota, { v -> upd { it.copy(nota = v) } }, minLines = 4, readOnly = ro)
        if (ex.peso.isNotBlank() && ex.talla.isNotBlank() && ex.calcularImc() == null) {
            Text("Revisá peso y talla: no se pudo calcular el IMC", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
    }
}
