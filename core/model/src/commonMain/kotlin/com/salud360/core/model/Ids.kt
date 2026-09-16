package com.salud360.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Los identificadores son UUID en texto para que cualquier dispositivo pueda crear
 * registros sin conexión y sincronizarlos después sin colisiones.
 */
typealias Id = String

fun newId(): Id = Uuid.random().toString()

fun now(): Instant = Clock.System.now()

/** Metadatos comunes a todo registro sincronizable. */
data class SyncMeta(
    val createdAt: Instant = now(),
    val updatedAt: Instant = now(),
    val deleted: Boolean = false,
    /** true cuando el cambio local todavía no fue enviado al servidor */
    val pendiente: Boolean = true,
)

/**
 * Identificadores derivados de turnosonlinebb. El servidor de Salud 360 importa usuarios, médicos,
 * consultorios, pacientes y turnos de la web de turnos con ids estables `tobb-<letra><número>`
 * para que la app sepa que ese registro vive en la base de turnosonlinebb.
 */
object TobbIds {
    const val PREFIJO = "tobb-"

    fun usuario(n: Long): Id = "${PREFIJO}u$n"
    fun medico(n: Long): Id = "${PREFIJO}m$n"
    fun secretaria(n: Long): Id = "${PREFIJO}s$n"
    fun consultorio(n: Long): Id = "${PREFIJO}c$n"
    fun especialidad(n: Long): Id = "${PREFIJO}e$n"
    fun paciente(n: Long): Id = "${PREFIJO}p$n"
    fun turno(n: Long): Id = "${PREFIJO}t$n"
    fun horario(n: Long): Id = "${PREFIJO}h$n"
    fun fechaAgregada(n: Long): Id = "${PREFIJO}f$n"
    fun obraSocial(n: Long): Id = "${PREFIJO}o$n"
    /** Mensaje especial para pacientes (`n` porque la `m` es del médico). */
    fun mensaje(n: Long): Id = "${PREFIJO}n$n"
    /** Vínculo médico–obra social (`obra_social_medicos`). */
    fun obraSocialMedico(n: Long): Id = "${PREFIJO}v$n"

    /** true si el id fue importado de turnosonlinebb. */
    fun esTobb(id: Id?): Boolean = id != null && id.startsWith(PREFIJO)

    /** Número de turnosonlinebb contenido en el id (`tobb-m12` → 12), o null si no es un id importado. */
    fun numero(id: Id?): Long? {
        if (id == null || !id.startsWith(PREFIJO) || id.length <= PREFIJO.length + 1) return null
        return id.substring(PREFIJO.length + 1).toLongOrNull()
    }
}
