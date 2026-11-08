package com.salud360.core.data.repos

import com.salud360.core.model.turnos.EstadoTurno
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.Turno
import kotlinx.datetime.LocalTime

/**
 * Reglas puras de los tipos de turno en la agenda (sin red ni pantallas).
 *
 * La agenda se lee con `todos_los_tipos=1`: cada horario y cada turno trae el código real de su tipo (el de turnos, que
 * incluye los que crea el administrador y el enum cerrado [TipoTurno] no conoce). Acá se decide cómo se rotula un tipo,
 * cuándo se muestra la etiqueta y con qué tipo se registra un turno sobre un horario.
 */

/** Código del tipo «Consulta»: el de siempre, el que turnos usa cuando no se le dice otro. */
const val CODIGO_TIPO_CONSULTA = 1

/**
 * Etiqueta de un tipo: el [nombre] que manda turnos si vino (y no está en blanco); si no, el del enum para los códigos
 * que conoce; si no, «Tipo <código>».
 */
fun etiquetaDelTipo(codigo: Int, nombre: String? = null): String =
    nombre?.trim()?.takeIf { it.isNotEmpty() }
        ?: TipoTurno.entries.firstOrNull { it.codigo == codigo }?.etiqueta
        ?: "Tipo $codigo"

/**
 * Etiqueta a mostrar en un horario o turno de ese [codigo], o `null` si no corresponde mostrar ninguna.
 *
 * Se muestra cuando el tipo no es «Consulta» (hay que ver que es otra cosa: deportología, ecografías…) o cuando el día
 * mezcla tipos (entonces hasta la consulta se rotula, para distinguirla). Un médico que solo atiende consultas no ve
 * etiquetas: la agenda queda como siempre.
 */
fun etiquetaVisibleDelTipo(codigo: Int, codigosDelDia: Collection<Int>, nombre: String? = null): String? {
    val mezcla = (codigosDelDia + codigo).toSet().size > 1
    return if (codigo != CODIGO_TIPO_CONSULTA || mezcla) etiquetaDelTipo(codigo, nombre) else null
}

/** Recorta una etiqueta para que entre en un espacio chico (el círculo de la semana): el último carácter pasa a «…». */
fun etiquetaCorta(etiqueta: String, maximo: Int = 12): String =
    if (etiqueta.length <= maximo) etiqueta else etiqueta.take(maximo - 1) + "…"

/** Códigos de tipo que hay en un día: los de sus horarios y los de sus turnos que no están cancelados. */
fun codigosDelDia(slots: List<SlotAgenda>, turnos: List<Turno>): Set<Int> =
    slots.map { it.codigoDeTipo }.toSet() + turnos.filter { it.estado != EstadoTurno.CANCELADO }.map { it.codigoDeTipo }

/** Código con el que se registra o bloquea un turno sobre [slot]: el del horario (sin horario conocido, «Consulta» como siempre). */
fun codigoParaRegistrar(slot: SlotAgenda?): Int = slot?.codigoDeTipo ?: CODIGO_TIPO_CONSULTA

/** El turno con el tipo del horario sobre el que se registra (si no hay horario, queda como estaba). */
fun Turno.sobreElSlot(slot: SlotAgenda?): Turno =
    if (slot == null) this else copy(tipoTurno = slot.tipoTurno, tipoCodigo = slot.tipoCodigo)

/** El horario del día que empieza a [horario], para tomar su tipo (un sobreturno fuera de la plantilla no tiene uno). */
fun slotDelHorario(slots: List<SlotAgenda>, horario: LocalTime): SlotAgenda? = slots.firstOrNull { it.horario == horario }
