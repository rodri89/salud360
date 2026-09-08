package com.salud360.core.data.files

/**
 * En Web los adjuntos se mantienen en memoria hasta que se suben al servidor
 * (el navegador no ofrece un sistema de archivos persistente portable).
 */
actual class ArchivoStore {
    private val memoria = mutableMapOf<String, ByteArray>()

    actual suspend fun guardar(id: String, nombre: String, bytes: ByteArray): String {
        val clave = "mem:$id.${extensionDe(nombre).ifEmpty { "bin" }}"
        memoria[clave] = bytes
        return clave
    }

    actual suspend fun leer(ruta: String): ByteArray? = memoria[ruta]
    actual suspend fun existe(ruta: String): Boolean = memoria.containsKey(ruta)
    actual suspend fun borrar(ruta: String) { memoria.remove(ruta) }
}
