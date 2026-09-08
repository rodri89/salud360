package com.salud360.core.data.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual class ArchivoStore {
    private val dir: String by lazy {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val d = "$docs/adjuntos"
        NSFileManager.defaultManager.createDirectoryAtPath(d, true, null, null)
        d
    }

    actual suspend fun guardar(id: String, nombre: String, bytes: ByteArray): String {
        val ruta = "$dir/$id.${extensionDe(nombre).ifEmpty { "bin" }}"
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        data.writeToFile(ruta, true)
        return ruta
    }

    actual suspend fun leer(ruta: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(ruta) ?: return null
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }

    actual suspend fun existe(ruta: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(ruta)
    actual suspend fun borrar(ruta: String) { NSFileManager.defaultManager.removeItemAtPath(ruta, null) }
}
