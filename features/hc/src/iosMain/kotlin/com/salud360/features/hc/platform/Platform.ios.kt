package com.salud360.features.hc.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication

/**
 * iOS: el selector de archivos y el grabador se conectan desde el proyecto Xcode
 * (`iosApp`) registrando implementaciones en [IosPlatformHooks]; por defecto quedan deshabilitados.
 */
object IosPlatformHooks {
    var filePicker: ((List<String>, (ArchivoElegido) -> Unit) -> Unit)? = null
    var audioRecorder: (() -> AudioRecorder)? = null
}

@Composable
actual fun rememberFilePicker(tipos: List<String>, onElegido: (ArchivoElegido) -> Unit): () -> Unit = {
    IosPlatformHooks.filePicker?.invoke(tipos, onElegido)
}

actual fun decodificarImagen(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

@OptIn(ExperimentalForeignApi::class)
actual suspend fun abrirArchivo(nombre: String, mime: String, bytes: ByteArray) {
    val ruta = NSTemporaryDirectory() + nombre.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
    data.writeToFile(ruta, true)
    NSURL.fileURLWithPath(ruta).let { url -> UIApplication.sharedApplication.openURL(url) }
}

private object SinGrabador : AudioRecorder {
    override val disponible = false
    override suspend fun iniciar() {}
    override fun pausar() {}
    override fun reanudar() {}
    override suspend fun detener(): ArchivoElegido? = null
}

@Composable
actual fun rememberAudioRecorder(): AudioRecorder = remember { IosPlatformHooks.audioRecorder?.invoke() ?: SinGrabador }
