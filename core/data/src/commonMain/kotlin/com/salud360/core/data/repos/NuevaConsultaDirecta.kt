package com.salud360.core.data.repos

/** Qué hace la historia clínica al llegar desde el botón «Nueva consulta» de la ficha del paciente. */
enum class AlLlegarConNuevaConsulta {
    /** Las consultas todavía no se leyeron de la base: no se puede decidir (vacío no es lo mismo que «no cargado»). */
    ESPERAR,

    /** No hacer nada: ya hay una consulta en curso (se muestra con Continuar/Anular) o la especialidad no tiene tipos. */
    NADA,

    /** La especialidad tiene un solo tipo de consulta: se crea directamente. */
    CREAR_DEL_UNICO_TIPO,

    /** Hay varios tipos: se muestra la elección del tipo de consulta. */
    ELEGIR_TIPO,
}

/**
 * Decide qué hacer al llegar a la historia clínica con la señal «nueva consulta». Nunca se crea una segunda consulta si
 * ya hay una abierta: el médico decide si continúa o la anula. [hayAbierta] y [tiposDeConsulta] solo valen cuando
 * [consultasCargadas] es `true`.
 */
fun alLlegarConNuevaConsulta(consultasCargadas: Boolean, hayAbierta: Boolean, tiposDeConsulta: Int): AlLlegarConNuevaConsulta = when {
    !consultasCargadas -> AlLlegarConNuevaConsulta.ESPERAR
    hayAbierta || tiposDeConsulta <= 0 -> AlLlegarConNuevaConsulta.NADA
    tiposDeConsulta == 1 -> AlLlegarConNuevaConsulta.CREAR_DEL_UNICO_TIPO
    else -> AlLlegarConNuevaConsulta.ELEGIR_TIPO
}
