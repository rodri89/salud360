package com.salud360.core.model.turnos

/**
 * Tipos de turno configurables de turnosonlinebb (tabla propia en la web, no el enum cerrado [TipoTurno]): un catálogo
 * global que el administrador amplía y que habilita médico por médico. Los datos viven en turnos; la app no los guarda.
 */

/** Un tipo del catálogo global (incluye los inactivos). */
data class TipoTurnoCatalogo(val id: Int, val nombre: String, val activo: Boolean)

/**
 * Cuándo se ofrece un tipo de turno a un médico (`primer_control` en la web). Por ahora es solo configuración: la app
 * todavía no filtra nada con ella.
 */
enum class CondicionTipoTurno(
    /** Lo que viaja en `primer_control`: `null` = siempre, `1` = solo primera consulta, `0` = solo seguimiento. */
    val codigo: Int?,
    val etiqueta: String,
) {
    SIEMPRE(null, "Siempre"),
    SOLO_PRIMERA_CONSULTA(1, "Solo primera consulta"),
    SOLO_SEGUIMIENTO(0, "Solo consultas de seguimiento");

    companion object {
        /** La condición de un `primer_control`; un código que no se conoce se lee como [SIEMPRE]. */
        fun porCodigo(codigo: Int?): CondicionTipoTurno = entries.firstOrNull { it.codigo == codigo } ?: SIEMPRE
    }
}

/** Un subtipo que se elige al confirmar el turno (otro tipo del catálogo, elegido como opción de uno habilitado). */
data class OpcionTipoTurno(val id: Int, val nombre: String)

/** Un tipo habilitado para un médico, con su condición y las opciones que se eligen al confirmar. */
data class TipoTurnoDelMedico(
    val id: Int,
    val nombre: String,
    val condicion: CondicionTipoTurno,
    val opciones: List<OpcionTipoTurno>,
)
