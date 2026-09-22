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
import com.salud360.core.crecimiento.CalculadoraOms
import com.salud360.core.crecimiento.Medida
import com.salud360.core.crecimiento.SexoOms
import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.SeccionDef
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.ui.components.LinkButton
import com.salud360.core.ui.components.NumberField
import com.salud360.core.ui.components.SectionCard
import com.salud360.core.ui.components.TextAreaField
import com.salud360.core.ui.components.TextField
import com.salud360.features.hc.ConsultaUi
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import kotlinx.coroutines.delay
import kotlinx.datetime.daysUntil

/**
 * Examen físico común (peso, talla, IMC calculado, TA, PC, CA, FC, temperatura, saturación, IPD, nota)
 * más los campos extra de la especialidad. Con `conPercentilos` muestra los percentilos de pediatría:
 * se calculan solos con las curvas OMS (si el paciente tiene fecha de nacimiento y sexo) y se pueden corregir a mano.
 */
@Composable
fun ExamenFisicoSeccion(s: SeccionDef, ctx: SeccionContext, ui: ConsultaUi, extras: Map<String, String>) {
    val def = ui.definicion ?: return
    val campos = def.examenFisicoCampos
    var ex by remember(ui.examen?.consultaId) { mutableStateOf(ui.examen ?: ExamenFisico(ctx.consultaId)) }
    var dirty by remember { mutableStateOf(false) }
    var verCurvas by remember { mutableStateOf(false) }
    LaunchedEffect(ex, dirty) { if (dirty) { delay(600); ctx.vm.guardarExamen(ex); dirty = false } }
    fun upd(t: (ExamenFisico) -> ExamenFisico) { ex = t(ex); dirty = true }
    val ro = ctx.soloLectura

    // Percentilos OMS: edad en días a la fecha de la consulta y sexo M/F. Sin eso, quedan manuales como en la web.
    val sexoOms = ui.paciente?.sexo.aSexoOms()
    val edadDias = ui.paciente?.fechaNacimiento?.let { nac -> ui.consulta?.fecha?.let { f -> nac.daysUntil(f) } }?.takeIf { it >= 0 }
    val calculaPercentilos = def.conPercentilos && sexoOms != null && edadDias != null
    fun updMedida(t: (ExamenFisico) -> ExamenFisico) = upd { e -> t(e).let { if (calculaPercentilos) it.conPercentilosOms(sexoOms!!, edadDias!!) else it } }

    // En lectura cada campo se pinta solo si tiene valor; `visible(campo, valor)` combina la definición de la
    // especialidad con esa regla, y cada fila se pinta solo si le queda algún campo.
    fun visible(campo: CampoExamenFisico, valor: String, conPercentilos: Boolean = false) =
        campo in campos && (!conPercentilos || def.conPercentilos) && (!ro || valor.isNotBlank())
    val imc = ex.calcularImc() ?: ex.imc
    val fila1 = visible(CampoExamenFisico.PESO, ex.peso) || visible(CampoExamenFisico.PESO, ex.pesoPercentil, true) ||
        visible(CampoExamenFisico.TALLA, ex.talla) || visible(CampoExamenFisico.TALLA, ex.tallaPercentil, true)
    val fila2 = visible(CampoExamenFisico.IMC, imc) || visible(CampoExamenFisico.IMC, ex.imcPercentil, true) ||
        visible(CampoExamenFisico.TENSION_ARTERIAL, ex.tensionArterial) || visible(CampoExamenFisico.FRECUENCIA_CARDIACA, ex.frecuenciaCardiaca)
    val fila3 = visible(CampoExamenFisico.PERIMETRO_CEFALICO, ex.perimetroCefalico) || visible(CampoExamenFisico.PERIMETRO_CEFALICO, ex.perimetroCefalicoPercentil, true) ||
        visible(CampoExamenFisico.IPD, ex.ipd) || visible(CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL, ex.circunferenciaAbdominal) ||
        visible(CampoExamenFisico.TEMPERATURA, ex.temperatura) || visible(CampoExamenFisico.SATURACION, ex.saturacion)
    val nota = visible(CampoExamenFisico.NOTA, ex.nota)
    val extrasVisibles = if (ro) camposCompletos(def.examenFisicoExtra, extras).isNotEmpty() else def.examenFisicoExtra.isNotEmpty()
    if (ro && !fila1 && !fila2 && !fila3 && !nota && !extrasVisibles) { SeccionVacia(); return }

    SectionCard(s.titulo, icon = iconoSeccion(s.icono ?: "examen"), initiallyExpanded = s.inicialmenteExpandida) {
        if (fila1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (visible(CampoExamenFisico.PESO, ex.peso)) NumberField("Peso (kg)", ex.peso, { v -> updMedida { it.copy(peso = v) } }, Modifier.weight(1f), suffix = "kg", readOnly = ro)
            if (visible(CampoExamenFisico.PESO, ex.pesoPercentil, true)) TextField("Pc peso", ex.pesoPercentil, { v -> upd { it.copy(pesoPercentil = v) } }, Modifier.weight(0.7f), readOnly = ro)
            if (visible(CampoExamenFisico.TALLA, ex.talla)) NumberField("Talla (m)", ex.talla, { v -> updMedida { it.copy(talla = v) } }, Modifier.weight(1f), suffix = "m", readOnly = ro)
            if (visible(CampoExamenFisico.TALLA, ex.tallaPercentil, true)) TextField("Pc talla", ex.tallaPercentil, { v -> upd { it.copy(tallaPercentil = v) } }, Modifier.weight(0.7f), readOnly = ro)
        }
        if (fila2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (visible(CampoExamenFisico.IMC, imc)) NumberField("IMC", imc, { v -> upd { it.copy(imc = v) } }, Modifier.weight(1f), readOnly = true)
            if (visible(CampoExamenFisico.IMC, ex.imcPercentil, true)) TextField("Pc IMC", ex.imcPercentil, { v -> upd { it.copy(imcPercentil = v) } }, Modifier.weight(0.7f), readOnly = ro)
            if (visible(CampoExamenFisico.TENSION_ARTERIAL, ex.tensionArterial)) TextField("TA (mm Hg)", ex.tensionArterial, { v -> upd { it.copy(tensionArterial = v) } }, Modifier.weight(1f), readOnly = ro, placeholder = "120/80")
            if (visible(CampoExamenFisico.FRECUENCIA_CARDIACA, ex.frecuenciaCardiaca)) NumberField("FC", ex.frecuenciaCardiaca, { v -> upd { it.copy(frecuenciaCardiaca = v) } }, Modifier.weight(0.8f), suffix = "lpm", readOnly = ro)
        }
        if (fila3) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (visible(CampoExamenFisico.PERIMETRO_CEFALICO, ex.perimetroCefalico)) NumberField("PC (cm)", ex.perimetroCefalico, { v -> updMedida { it.copy(perimetroCefalico = v) } }, Modifier.weight(1f), suffix = "cm", readOnly = ro)
            if (visible(CampoExamenFisico.PERIMETRO_CEFALICO, ex.perimetroCefalicoPercentil, true)) TextField("Pc PC", ex.perimetroCefalicoPercentil, { v -> upd { it.copy(perimetroCefalicoPercentil = v) } }, Modifier.weight(0.7f), readOnly = ro)
            if (visible(CampoExamenFisico.IPD, ex.ipd)) NumberField("IPD (g/día)", ex.ipd, { v -> upd { it.copy(ipd = v) } }, Modifier.weight(1f), suffix = "g/día", readOnly = ro)
            if (visible(CampoExamenFisico.CIRCUNFERENCIA_ABDOMINAL, ex.circunferenciaAbdominal)) NumberField("CA (cm)", ex.circunferenciaAbdominal, { v -> upd { it.copy(circunferenciaAbdominal = v) } }, Modifier.weight(1f), suffix = "cm", readOnly = ro)
            if (visible(CampoExamenFisico.TEMPERATURA, ex.temperatura)) NumberField("Temp.", ex.temperatura, { v -> upd { it.copy(temperatura = v) } }, Modifier.weight(0.8f), suffix = "°C", readOnly = ro)
            if (visible(CampoExamenFisico.SATURACION, ex.saturacion)) NumberField("Sat O2", ex.saturacion, { v -> upd { it.copy(saturacion = v) } }, Modifier.weight(0.8f), suffix = "%", readOnly = ro)
        }
        if (def.conPercentilos) {
            if (!ro) Text(
                if (calculaPercentilos) "Percentilos OMS calculados automáticamente al cargar peso, talla y PC; se pueden corregir a mano."
                else "Cargá la fecha de nacimiento y el sexo del paciente para calcular los percentilos OMS.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinkButton(if (verCurvas) "Ocultar curvas de crecimiento" else "Ver curvas de crecimiento", onClick = { verCurvas = !verCurvas })
            if (verCurvas) CurvasCrecimiento(ctx)
        }
        if (extrasVisibles) CamposForm(def.examenFisicoExtra, extras, ro) { campo, valor -> ctx.setValor(s.id, campo, valor) }
        if (nota) TextAreaField("Examen físico", ex.nota, { v -> upd { it.copy(nota = v) } }, minLines = 4, readOnly = ro)
        if (ex.peso.isNotBlank() && ex.talla.isNotBlank() && ex.calcularImc() == null) {
            Text("Revisá peso y talla: no se pudo calcular el IMC", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Completa los percentilos OMS (peso, talla, PC, IMC) a partir de las medidas cargadas; vacío si no se pueden calcular. */
fun ExamenFisico.conPercentilosOms(sexo: SexoOms, edadDias: Int): ExamenFisico {
    fun pc(medida: Medida): String = valorDe(medida)?.let { CalculadoraOms.zScore(medida, sexo, edadDias, it)?.etiqueta } ?: ""
    return copy(
        pesoPercentil = pc(Medida.PESO), tallaPercentil = pc(Medida.TALLA),
        perimetroCefalicoPercentil = pc(Medida.PERIMETRO_CEFALICO), imcPercentil = pc(Medida.IMC),
    )
}
