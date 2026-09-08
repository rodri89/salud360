package com.salud360.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory {
    actual suspend fun createDriver(): SqlDriver =
        NativeSqliteDriver(Salud360Db.Schema.synchronous(), "salud360.db")
}
