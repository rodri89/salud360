package com.salud360.core.data.repos

import com.salud360.core.model.Id
import com.salud360.core.model.pacientes.MedicoPaciente
import com.salud360.core.model.pacientes.Paciente

/**
 * Reglas de la fusión entre la ficha que el médico escribió en el dispositivo y la que ya tenía turnos
 * (un paciente es único por DNI). Todo puro: ni red, ni base (constitución, principio 4).
 */

/** Qué hacer con la ficha local una vez que turnos contestó el alta (`POST pacientes`). */
enum class AccionTrasAlta {
    /** Turnos lo creó con este alta: la ficha local se queda y solo se anota el id de turnos en `paciente_extra`. */
    ANOTAR_ID_REMOTO,

    /** La ficha local ya es la de turnos (mismo id): solo se actualiza con lo que informó turnos. */
    ACTUALIZAR_LOCAL,

    /** La ficha local sobra: se reemplaza por la de turnos y todo lo que la referenciaba se repunta. */
    REEMPLAZAR_LOCAL,
}

/** @property yaExistia el paciente ya estaba en el sistema (turnos dijo `creado = false`): se asoció en vez de crearlo. */
data class DecisionDeAlta(val accion: AccionTrasAlta, val yaExistia: Boolean)

/**
 * @param idLocal id de la ficha que se dio de alta (UUID local, o `tobb-p…` si ya se conocía).
 * @param idRemoto id que devolvió turnos (`tobb-p<N>`).
 * @param creado lo que informó turnos: true = no existía y se creó con estos datos.
 * @param remotaYaEnElDispositivo ya hay una fila local con el id de turnos (por una sincronización anterior).
 */
fun decidirTrasAlta(idLocal: Id, idRemoto: Id, creado: Boolean, remotaYaEnElDispositivo: Boolean): DecisionDeAlta {
    val yaExistia = !creado
    return when {
        idLocal == idRemoto -> DecisionDeAlta(AccionTrasAlta.ACTUALIZAR_LOCAL, yaExistia)
        !creado || remotaYaEnElDispositivo -> DecisionDeAlta(AccionTrasAlta.REEMPLAZAR_LOCAL, yaExistia)
        else -> DecisionDeAlta(AccionTrasAlta.ANOTAR_ID_REMOTO, yaExistia = false)
    }
}

/**
 * Ficha resultante de aplicar lo que informa turnos sobre la que ya hay en el dispositivo: los datos de turnos mandan,
 * pero un dato que turnos trae vacío no pisa uno válido que ya estaba (constitución, principio 3). Lo que turnos no
 * conoce (sexo, familia, nacionalidad, obra social opcional, etc.) se conserva. Sin ficha previa queda la de turnos.
 * El id es siempre el de [nuevo] (la ficha previa puede ser la local, con otro id).
 */
fun fusionarFicha(existente: Paciente?, nuevo: Paciente): Paciente = if (existente == null) nuevo else existente.copy(
    id = nuevo.id,
    dni = nuevo.dni.ifBlank { existente.dni }, nombre = nuevo.nombre.ifBlank { existente.nombre }, apellido = nuevo.apellido.ifBlank { existente.apellido },
    telefono = nuevo.telefono.ifBlank { existente.telefono }, mail = nuevo.mail.ifBlank { existente.mail },
    domicilio = nuevo.domicilio.ifBlank { existente.domicilio }, localidad = nuevo.localidad.ifBlank { existente.localidad },
    fechaNacimiento = nuevo.fechaNacimiento ?: existente.fechaNacimiento,
    obraSocial = nuevo.obraSocial.ifBlank { existente.obraSocial }, numeroAfiliado = nuevo.numeroAfiliado.ifBlank { existente.numeroAfiliado },
    obraSocialPlan = nuevo.obraSocialPlan.ifBlank { existente.obraSocialPlan },
    // La nota llega vacía mientras la web no exponga la columna: en ese caso se conserva la local.
    nota = nuevo.nota.ifBlank { existente.nota },
)

/**
 * Vínculos con médicos que quedan para el paciente de turnos al absorber a la ficha local: los que ya tenía con turnos
 * más los de la ficha local (repuntados a [idRemoto]). Un médico con vínculo en ambos lados queda con uno solo: el de
 * turnos (con su bloqueo), y activo si lo estaba cualquiera de los dos —el médico acaba de dar de alta al paciente.
 */
fun fusionarVinculos(locales: List<MedicoPaciente>, remotos: List<MedicoPaciente>, idRemoto: Id): List<MedicoPaciente> {
    val porMedico = remotos.associateBy { it.medicoId }.toMutableMap()
    locales.forEach { local ->
        val remoto = porMedico[local.medicoId]
        porMedico[local.medicoId] = if (remoto == null) local else remoto.copy(activo = remoto.activo || local.activo)
    }
    return porMedico.values.map { it.copy(pacienteId = idRemoto) }
}

/** Qué pasó al guardar la ficha del paciente en turnos: la ficha que quedó en el dispositivo y si ya existía en el sistema. */
data class PacienteGuardado(val paciente: Paciente, val yaExistia: Boolean) {
    val id: Id get() = paciente.id
}

/** Aviso al médico cuando el DNI que dio de alta ya estaba en el sistema. */
fun avisoDePacienteYaExistia(): String = "Ese paciente ya existía en el sistema: se asoció a tu lista."
