package com.salud360.core.database

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual suspend fun createDriver(): SqlDriver =
        AndroidSqliteDriver(Salud360Db.Schema.synchronous(), context, "salud360.db")
}
