package com.salud360.core.database

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker

actual class DriverFactory {
    actual suspend fun createDriver(): SqlDriver {
        val driver = WebWorkerDriver(crearWorkerSqlJs())
        Salud360Db.Schema.create(driver).await()
        return driver
    }
}

private fun crearWorkerSqlJs(): Worker =
    js("""new Worker(new URL("@cashapp/sqldelight-sqljs-worker/sqljs.worker.js", import.meta.url))""")
