package com.salud360.features.hc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ChildFriendly
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PregnantWoman
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector

/** Ícono Material para el nombre declarado en la definición de la sección. */
fun iconoSeccion(nombre: String?): ImageVector? = when (nombre) {
    null -> null
    "motivo" -> Icons.Default.Assignment
    "antecedentes" -> Icons.Default.History
    "familia" -> Icons.Default.FamilyRestroom
    "examen" -> Icons.Default.MonitorWeight
    "laboratorio" -> Icons.Default.Science
    "estudios" -> Icons.Default.Biotech
    "registros" -> Icons.Default.Description
    "medicacion" -> Icons.Default.Medication
    "vacunas" -> Icons.Default.Vaccines
    "alimentacion" -> Icons.Default.Restaurant
    "escolaridad" -> Icons.Default.School
    "desarrollo" -> Icons.Default.ChildCare
    "cardio" -> Icons.Default.MonitorHeart
    "corazon" -> Icons.Default.Favorite
    "embarazo" -> Icons.Default.PregnantWoman
    "psico" -> Icons.Default.Psychology
    "diagnostico" -> Icons.Default.Visibility
    "archivos" -> Icons.Default.AttachFile
    "texto" -> Icons.Default.Notes
    "form" -> Icons.Default.ListAlt
    "diuresis" -> Icons.Default.WaterDrop
    "sueno" -> Icons.Default.Bedtime
    "actividades" -> Icons.Default.SportsSoccer
    "pantallas" -> Icons.Default.Devices
    "habitos" -> Icons.Default.SelfImprovement
    "menarca" -> Icons.Default.Female
    "conductas" -> Icons.Default.Checklist
    "observaciones" -> Icons.Default.StickyNote2
    "nota" -> Icons.Default.EditNote
    "subjetivo" -> Icons.Default.RecordVoiceOver
    "objetivo" -> Icons.Default.Straighten
    "internacion" -> Icons.Default.LocalHospital
    "screening" -> Icons.Default.FactCheck
    "interconsulta" -> Icons.Default.Groups
    "curvas" -> Icons.Default.ShowChart
    "lactancia" -> Icons.Default.ChildFriendly
    "dibujo" -> Icons.Default.Brush
    "plan" -> Icons.Default.EventNote
    "resumen" -> Icons.Default.Summarize
    else -> Icons.Default.Description
}
