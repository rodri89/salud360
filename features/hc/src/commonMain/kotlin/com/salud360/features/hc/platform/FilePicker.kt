package com.salud360.features.hc.platform

import androidx.compose.runtime.Composable

/** Archivo elegido por el usuario (imagen, PDF o audio). */
data class ArchivoElegido(val nombre: String, val bytes: ByteArray)

/**
 * Selector de archivos multiplataforma. Devuelve una función que abre el diálogo del sistema.
 * @param tipos MIME aceptados (ej. imágenes o PDF)
 */
@Composable
expect fun rememberFilePicker(tipos: List<String>, onElegido: (ArchivoElegido) -> Unit): () -> Unit

/**
 * Grabador de audio (MediaRecorder en Android/Web, AVAudioRecorder en iOS).
 * Reemplaza al componente `grabar_microfono.js` / `grabacion_audio.js` de las apps originales.
 */
interface AudioRecorder {
    val disponible: Boolean
    suspend fun iniciar()
    fun pausar()
    fun reanudar()
    /** Detiene y devuelve el audio (nombre con extensión + bytes) o null si falló. */
    suspend fun detener(): ArchivoElegido?
}

@Composable
expect fun rememberAudioRecorder(): AudioRecorder

/** Abre un archivo con el visor del sistema (PDF/imagen) o lo descarga en web. */
expect suspend fun abrirArchivo(nombre: String, mime: String, bytes: ByteArray)
