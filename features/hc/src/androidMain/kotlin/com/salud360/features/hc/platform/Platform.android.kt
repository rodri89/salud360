package com.salud360.features.hc.platform

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun rememberFilePicker(tipos: List<String>, onElegido: (ArchivoElegido) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val nombre = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "archivo"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        onElegido(ArchivoElegido(nombre, bytes))
    }
    return { launcher.launch(tipos.toTypedArray()) }
}

actual fun decodificarImagen(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

actual suspend fun abrirArchivo(nombre: String, mime: String, bytes: ByteArray) {
    val context = contextGlobal ?: return
    withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "compartidos").apply { mkdirs() }
        val f = File(dir, nombre.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

/** Contexto de aplicación registrado por `MainActivity` para abrir archivos fuera de composición. */
var contextGlobal: Context? = null

private class AndroidAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var archivo: File? = null
    override val disponible: Boolean = true

    override suspend fun iniciar() {
        val f = File(context.cacheDir, "grabacion_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(32_000)
        r.setAudioSamplingRate(22_050)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        recorder = r; archivo = f
    }

    override fun pausar() { if (Build.VERSION.SDK_INT >= 24) recorder?.pause() }
    override fun reanudar() { if (Build.VERSION.SDK_INT >= 24) recorder?.resume() }

    override suspend fun detener(): ArchivoElegido? {
        val r = recorder ?: return null
        runCatching { r.stop() }; r.release(); recorder = null
        val f = archivo ?: return null
        val bytes = withContext(Dispatchers.IO) { f.readBytes() }
        f.delete()
        return ArchivoElegido(f.name, bytes)
    }
}

@Composable
actual fun rememberAudioRecorder(): AudioRecorder {
    val context = LocalContext.current.applicationContext
    return remember { AndroidAudioRecorder(context) }
}
