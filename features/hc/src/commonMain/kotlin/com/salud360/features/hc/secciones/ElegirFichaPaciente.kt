package com.salud360.features.hc.secciones

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salud360.core.data.hcgeneral.lineaDeCandidata
import com.salud360.core.data.network.hc.HcPacienteCandidato
import com.salud360.core.ui.components.LabelValue
import com.salud360.core.ui.theme.Salud360Colors

/**
 * Elegir cuál de varias fichas de la web es la del paciente (spec 003, RF-9; la 002 puede reutilizarla). Una tarjeta por
 * candidata, con lo que arma [lineaDeCandidata] (nombre, documento y, si la web la tiene, fecha de nacimiento), y un
 * botón "Elegir" que llama a [onElegir] con el id de la ficha. [habilitado] apaga todos los botones (p. ej. mientras
 * se carga la elegida). [titulo] llega de quien la usa: acá no hay texto de ninguna sección.
 */
@Composable
fun ElegirFichaPaciente(titulo: String, candidatas: List<HcPacienteCandidato>, habilitado: Boolean, onElegir: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        candidatas.forEach { c ->
            val linea = lineaDeCandidata(c)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(linea.nombre, fontWeight = FontWeight.SemiBold)
                        LabelValue("DNI", linea.documento)
                        linea.nacimiento?.let { LabelValue("Fecha de nacimiento", it) }
                    }
                    OutlinedButton(
                        onClick = { onElegir(c.id) },
                        enabled = habilitado,
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
                        shape = CircleShape,
                        border = BorderStroke(1.5.dp, Salud360Colors.Success),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Salud360Colors.SuccessDark),
                    ) { Text("Elegir") }
                }
            }
        }
    }
}
