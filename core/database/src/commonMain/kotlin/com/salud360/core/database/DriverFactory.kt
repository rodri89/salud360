package com.salud360.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Crea el driver de SQLite de cada plataforma:
 * - Android: AndroidSqliteDriver
 * - iOS: NativeSqliteDriver
 * - JVM (servidor y escritorio): JdbcSqliteDriver
 * - Web (wasmJs): WebWorkerDriver sobre sql.js
 *
 * En Android e iOS la base queda en el dispositivo, por eso la app funciona sin conexión.
 * En Web, sql.js mantiene la base en memoria mientras la pestaña esté abierta y se resincroniza al recargar.
 */
expect class DriverFactory {
    suspend fun createDriver(): SqlDriver
}

suspend fun createDatabase(factory: DriverFactory): Salud360Db = Salud360Db(factory.createDriver())
