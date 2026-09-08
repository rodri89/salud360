package com.salud360.core.data.files

/**
 * Almacenamiento local de adjuntos (imágenes, PDF, audio).
 * - Android: directorio privado de la app (`filesDir/adjuntos`)
 * - iOS: `Documents/adjuntos`
 * - JVM: carpeta `adjuntos` junto a la base
 * - Web: memoria de la pestaña (los archivos se suben al servidor apenas hay conexión)
 */
expect class ArchivoStore {
    /** Guarda los bytes y devuelve la ruta/clave local. */
    suspend fun guardar(id: String, nombre: String, bytes: ByteArray): String
    suspend fun leer(ruta: String): ByteArray?
    suspend fun existe(ruta: String): Boolean
    suspend fun borrar(ruta: String)
}

fun extensionDe(nombre: String): String = nombre.substringAfterLast('.', "").lowercase()

fun mimeDe(nombre: String): String = when (extensionDe(nombre)) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "webm" -> "audio/webm"
    "m4a", "mp4" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "ogg" -> "audio/ogg"
    "wav" -> "audio/wav"
    else -> "application/octet-stream"
}
