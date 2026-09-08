package com.salud360.core.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/** Marca de tiempo actual en epoch millis, usada como `updated_at` de cada fila. */
fun ahoraMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Observa una consulta como lista de modelos. */
fun <R : Any, M> Query<R>.flujoLista(map: (R) -> M): Flow<List<M>> =
    asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map(map) }

/** Observa una consulta como un único modelo (o null). */
fun <R : Any, M> Query<R>.flujoUno(map: (R) -> M): Flow<M?> =
    asFlow().mapToOneOrNull(Dispatchers.Default).map { row -> row?.let(map) }

suspend fun <R : Any, M> Query<R>.lista(map: (R) -> M): List<M> = awaitAsList().map(map)
suspend fun <R : Any, M> Query<R>.uno(map: (R) -> M): M? = awaitAsOneOrNull()?.let(map)
