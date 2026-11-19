package com.salud360.core.data.hcgeneral

import com.salud360.core.model.especialidad.CampoExamenFisico
import com.salud360.core.model.especialidad.EspecialidadDefinition

/** Cómo se pinta un campo común del examen físico. */
enum class ModoCampoExamen { OCULTO, LECTURA, EDITABLE }

/**
 * Modo de [campo] con [valor] en una consulta de [def]. Los de [EspecialidadDefinition.examenFisicoCampos] se editan
 * y, con la consulta en lectura, se ven solo si tienen valor. Los de [EspecialidadDefinition.examenFisicoSoloLectura]
 * (HC general: FC y CA) se ven solo para leer y solo si tienen valor, aunque la consulta esté abierta ("Decidido" 6
 * del plan). Las especialidades sin estos últimos quedan como siempre (RF-33).
 */
fun modoCampoExamen(def: EspecialidadDefinition, campo: CampoExamenFisico, valor: String, soloLectura: Boolean): ModoCampoExamen = when {
    campo in def.examenFisicoCampos -> when {
        !soloLectura -> ModoCampoExamen.EDITABLE
        valor.isNotBlank() -> ModoCampoExamen.LECTURA
        else -> ModoCampoExamen.OCULTO
    }
    campo in def.examenFisicoSoloLectura && valor.isNotBlank() -> ModoCampoExamen.LECTURA
    else -> ModoCampoExamen.OCULTO
}
