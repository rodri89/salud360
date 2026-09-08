package com.salud360.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

/** @param ruta ruta del archivo SQLite; por defecto `salud360.db` en el directorio de trabajo. */
actual class DriverFactory(private val ruta: String = "salud360.db") {
    actual suspend fun createDriver(): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:$ruta", Properties(), Salud360Db.Schema.synchronous())
}
