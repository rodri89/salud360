package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id
import com.salud360.core.model.TobbIds
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.especialidad.AsignacionPerfil
import com.salud360.core.model.especialidad.CodigosHc

/**
 * Médico al que el administrador le puede asignar un perfil (RF-17). [vinculado] false = hclinica todavía no tiene un
 * usuario con ese número de turnos: la asignación se guarda igual y vale cuando se vincule (RF-18).
 */
data class MedicoAsignable(
    val medicoId: Id,
    val medicoIdTobb: Long,
    val nombre: String,
    val perfilId: Long?,
    val vinculado: Boolean,
)

/**
 * Médicos con la HC general cruzados con [asignaciones] por número de turnos, como la solapa de licencias. En el orden
 * de [medicos]. Sin fila en `config/asignaciones` = sin perfil y no vinculado. Los que no vienen de turnos no tienen
 * número y no se listan. Turnos puede seguir informando "hepatologia" (es la HC general: [normalizarHistoriasClinicas]).
 */
fun medicosParaAsignar(medicos: List<Medico>, asignaciones: List<AsignacionPerfil>): List<MedicoAsignable> {
    val porNumero = asignaciones.associateBy { it.medicoIdTobb }
    return medicos.mapNotNull { m ->
        if (CodigosHc.GENERAL !in normalizarHistoriasClinicas(m.especialidadesHc)) return@mapNotNull null
        val numero = TobbIds.numero(m.id) ?: return@mapNotNull null
        val a = porNumero[numero]
        MedicoAsignable(m.id, numero, m.nombreCompleto, a?.perfilId, a?.vinculado ?: false)
    }
}
