package com.salud360.core.database

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker

/**
 * Base en la web: sql.js dentro de un Web Worker con copia persistente en IndexedDB
 * (`sqljs-persistente.worker.js`, recurso estático de `composeApp` servido junto a `index.html`).
 *
 * Al arrancar, si la base guardada es de una versión anterior se aplican las migraciones de la carpeta
 * `migrations`, igual que en Android e iOS, para no perder lo cargado en el navegador. Solo si no es posible
 * —base de una versión más nueva, o migración que falla— se descarta y se vuelve a crear el esquema.
 */
actual class DriverFactory {
    actual suspend fun createDriver(): SqlDriver {
        val driver = WebWorkerDriver(Worker("sqljs-persistente.worker.js"))
        val esquema = Salud360Db.Schema
        val version = esquema.version
        val hayTablas = driver.consultarLong("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'usuario'") > 0
        if (!hayTablas) return driver.crearEsquema(version)

        // Las bases creadas antes de que se guardara el pragma quedaron en 0: son del esquema inicial.
        val guardada = driver.consultarLong("PRAGMA user_version").let { if (it <= 0L) 1L else it }
        if (guardada == version) return driver

        if (guardada < version) {
            val migrada = try {
                esquema.migrate(driver, guardada, version).await()
                true
            } catch (e: Throwable) {
                println("Salud 360 · no se pudo migrar la base del navegador ($guardada -> $version): ${e.message}. Se recrea vacía.")
                false
            }
            if (migrada) {
                driver.execute(null, "PRAGMA user_version = $version", 0).await()
                return driver
            }
        }

        // Esquema de otra versión o migración fallida: se descartan las tablas (y sus datos) y se crea el actual.
        val tablas = driver.consultarTexto("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
        tablas.forEach { driver.execute(null, "DROP TABLE IF EXISTS \"$it\"", 0).await() }
        return driver.crearEsquema(version)
    }
}

private suspend fun SqlDriver.crearEsquema(version: Long): SqlDriver {
    Salud360Db.Schema.create(this).await()
    execute(null, "PRAGMA user_version = $version", 0).await()
    return this
}

private suspend fun SqlDriver.consultarLong(sql: String): Long =
    executeQuery(null, sql, { cursor -> QueryResult.AsyncValue { if (cursor.next().await()) cursor.getLong(0) ?: 0L else 0L } }, 0).await()

private suspend fun SqlDriver.consultarTexto(sql: String): List<String> =
    executeQuery(null, sql, { cursor ->
        QueryResult.AsyncValue {
            buildList { while (cursor.next().await()) cursor.getString(0)?.let { add(it) } }
        }
    }, 0).await()
