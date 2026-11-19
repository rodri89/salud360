package com.salud360.core.data.hcgeneral

import com.salud360.core.data.repos.PacientesHcRepository
import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.CodigosHc
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.pacientes.PacienteExtra

// La historia del paciente de la HC general tal como la pantalla la muestra (spec 002, entrega L: RF-38, RF-44 a RF-46, RF-49,
// RF-63, Q9). Puro: junta los grupos de E7.1 con el tope de «Ver más» y los avisos; la pantalla muestra lo que esto devuelve y
// no decide nada (constitución, punto 4). No escribe ni pide nada: «Ver más» es sobre lo que ya se trajo a la base.

/** Q9: cuántas consultas se ven por vez, y cuántas más con cada «Ver más». */
const val CONSULTAS_POR_VEZ = 50

/** Los botones de la historia, con el texto de la spec (RF-38, RF-57) y del plan («Pantalla»). */
const val TEXTO_VER_MAS = "Ver más"
const val TEXTO_ACTUALIZAR = "Actualizar"

/** Cuántas se ven después de tocar «Ver más» estando a la vista [visibles]. */
fun masConsultas(visibles: Int): Int = visibles + CONSULTAS_POR_VEZ

/**
 * Lo que muestra la historia. Los tres grupos ya están recortados a lo visible; [totalAnteriores] cuenta todas las cerradas
 * y [ocultas] las consultas (abiertas en la web o cerradas) que quedan detrás de «Ver más».
 */
data class VistaDeLaHistoria(
    val abiertasEnLaWeb: List<ConsultaDeLaLista>,
    /** Las abiertas del dispositivo: siempre a la vista, no cuentan para el tope. */
    val enCurso: List<ConsultaDeLaLista>,
    val anteriores: List<ConsultaDeLaLista>,
    val totalAnteriores: Int,
    val ocultas: Int,
    val avisos: AvisosDeLaHistoria,
) {
    /** «Ver más» solo mientras quede algo sin mostrar de lo ya traído. */
    val hayMas: Boolean get() = ocultas > 0
}

/**
 * La historia del paciente: [consultas] son las de la HC general que hay en la base, [nombresMedicos] los de
 * [nombresDeMedicosWeb] y [extras] las filas de `paciente_extra` del paciente (el vínculo, las cuentas de lo omitido y las
 * marcas «Ya no figura en la web»). Se ven las [visibles] más recientes entre las abiertas en la web y las cerradas (por
 * fecha, de la más nueva a la más vieja); la consulta en curso del dispositivo se ve siempre. Nada se oculta salvo detrás de
 * «Ver más»: una consulta con la marca de RF-63 sigue en su grupo.
 */
fun vistaDeLaHistoria(
    consultas: List<Consulta>,
    medicoSesionId: Id,
    nombresMedicos: Map<Id, String>,
    extras: List<PacienteExtra>,
    visibles: Int = CONSULTAS_POR_VEZ,
): VistaDeLaHistoria {
    val lectura = extrasDeLectura(extras)
    val grupos = agruparConsultas(consultas, medicoSesionId, nombresMedicos, lectura.ausencias)
    // El orden entre grupos es por fecha; en un empate, el que ya traían (primero las abiertas en la web).
    val aVer = (grupos.abiertasEnLaWeb + grupos.anteriores).sortedByDescending { it.consulta.fecha }
    val ids = aVer.take(visibles.coerceAtLeast(0)).map { it.consulta.id }.toSet()
    return VistaDeLaHistoria(
        abiertasEnLaWeb = grupos.abiertasEnLaWeb.filter { it.consulta.id in ids },
        enCurso = grupos.enCurso,
        anteriores = grupos.anteriores.filter { it.consulta.id in ids },
        totalAnteriores = grupos.anteriores.size,
        ocultas = aVer.size - ids.size,
        avisos = avisosDeLaHistoria(vinculadoConLaWeb(extras), lectura),
    )
}

/** RF-49: el paciente tiene la ficha de la HC general en la web (`hc_paciente_id` con un número) en [extras]. */
fun vinculadoConLaWeb(extras: List<PacienteExtra>): Boolean =
    extras.any {
        it.especialidad == CodigosHc.GENERAL && it.clave == PacientesHcRepository.EXTRA_HC_PACIENTE_ID &&
            (it.valor.toLongOrNull() ?: 0L) > 0L
    }
