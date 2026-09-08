package com.salud360.server

import app.cash.sqldelight.db.QueryResult
import com.salud360.core.database.Salud360Db
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Contraseñas de usuarios (solo en el servidor): PBKDF2-HMAC-SHA256 con sal por usuario. */
class Credenciales_(private val db: Salud360Db) {
    private val driver get() = db.driverInterno()
    private val random = SecureRandom()

    fun inicializar() {
        driver.execute(null, "CREATE TABLE IF NOT EXISTS credenciales (usuario_id TEXT PRIMARY KEY, hash TEXT NOT NULL, sal TEXT NOT NULL, actualizado_en INTEGER NOT NULL)", 0)
    }

    fun guardar(usuarioId: String, password: String) {
        val sal = ByteArray(16).also { random.nextBytes(it) }
        val hash = derivar(password, sal)
        driver.execute(null, "INSERT OR REPLACE INTO credenciales(usuario_id, hash, sal, actualizado_en) VALUES (?, ?, ?, ?)", 4) {
            bindString(0, usuarioId); bindString(1, hash); bindString(2, Base64.getEncoder().encodeToString(sal)); bindLong(3, System.currentTimeMillis())
        }
    }

    fun verificar(usuarioId: String, password: String): Boolean {
        var hash: String? = null
        var sal: String? = null
        driver.executeQuery(null, "SELECT hash, sal FROM credenciales WHERE usuario_id = ?", { c ->
            if (c.next().value) { hash = c.getString(0); sal = c.getString(1) }
            QueryResult.Unit
        }, 1) { bindString(0, usuarioId) }
        val h = hash ?: return false
        val s = sal ?: return false
        return derivar(password, Base64.getDecoder().decode(s)) == h
    }

    private fun derivar(password: String, sal: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), sal, 120_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(key)
    }
}
