package com.salud360.core.data.files

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual class ArchivoStore(context: Context) {
    private val dir = File(context.filesDir, "adjuntos").apply { mkdirs() }

    actual suspend fun guardar(id: String, nombre: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val f = File(dir, "$id.${extensionDe(nombre).ifEmpty { "bin" }}")
        f.writeBytes(bytes)
        f.absolutePath
    }

    actual suspend fun leer(ruta: String): ByteArray? = withContext(Dispatchers.IO) { File(ruta).takeIf { it.exists() }?.readBytes() }
    actual suspend fun existe(ruta: String): Boolean = File(ruta).exists()
    actual suspend fun borrar(ruta: String) { File(ruta).delete() }
}
