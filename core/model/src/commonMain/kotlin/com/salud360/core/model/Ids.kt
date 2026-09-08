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
