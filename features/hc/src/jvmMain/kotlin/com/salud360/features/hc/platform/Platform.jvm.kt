package com.salud360.features.hc.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.imageio.ImageIO

@Composable
actual fun rememberFilePicker(tipos: List<String>, onElegido: (ArchivoElegido) -> Unit): () -> Unit = {
    val dialog = FileDialog(null as Frame?, "Elegir archivo", FileDialog.LOAD)
    dialog.isVisible = true
    val f = dialog.file?.let { File(dialog.directory, it) }
    if (f != null && f.exists()) onElegido(ArchivoElegido(f.name, f.readBytes()))
}

actual fun decodificarImagen(bytes: ByteArray): ImageBitmap? =
    runCatching { ImageIO.read(bytes.inputStream())?.toComposeImageBitmap() }.getOrNull()

actual suspend fun abrirArchivo(nombre: String, mime: String, bytes: ByteArray) {
    val f = File.createTempFile("salud360_", "_" + nombre.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    f.writeBytes(bytes)
    runCatching { Desktop.getDesktop().open(f) }
}

private object SinGrabador : AudioRecorder {
    override val disponible = false
    override suspend fun iniciar() {}
    override fun pausar() {}
    override fun reanudar() {}
    override suspend fun detener(): ArchivoElegido? = null
}

@Composable
actual fun rememberAudioRecorder(): AudioRecorder = remember { SinGrabador }
