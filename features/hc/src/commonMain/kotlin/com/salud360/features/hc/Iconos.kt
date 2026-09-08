package com.salud360.features.hc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.PregnantWoman
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Visibility
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
    else -> Icons.Default.Description
}
