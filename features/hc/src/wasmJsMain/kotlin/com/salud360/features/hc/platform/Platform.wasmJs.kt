package com.salud360.features.hc.platform

import androidx.compose.runtime.Composable
import kotlin.io.encoding.ExperimentalEncodingApi
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.skia.Image
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.files.Blob

// ---- utilidades JS (los cuerpos js() deben ser la única sentencia de la función) ----

private fun abrirSelector(accept: String, callback: (JsAny, String) -> Unit): Unit = js(
    """{
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = accept;
        input.onchange = () => {
            const f = input.files && input.files[0];
            if (!f) return;
            f.arrayBuffer().then(buf => callback(buf, f.name));
        };
        input.click();
    }"""
)

private fun descargar(nombre: String, mime: String, base64: String): Unit = js(
    """{
        const bin = atob(base64);
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        const blob = new Blob([bytes], { type: mime });
        const url = URL.createObjectURL(blob);
        if (mime.startsWith('image/') || mime === 'application/pdf' || mime.startsWith('audio/')) {
            window.open(url, '_blank');
        } else {
            const a = document.createElement('a'); a.href = url; a.download = nombre; a.click();
        }
        setTimeout(() => URL.revokeObjectURL(url), 60000);
    }"""
)

private fun grabadorSoportado(): Boolean = js("!!(navigator.mediaDevices && window.MediaRecorder)")

private fun iniciarGrabacion(): Unit = js(
    """{
        window.__s360rec = null;
        navigator.mediaDevices.getUserMedia({ audio: true }).then(stream => {
            const types = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4'];
            const type = types.find(t => MediaRecorder.isTypeSupported(t)) || '';
            const rec = new MediaRecorder(stream, type ? { mimeType: type, audioBitsPerSecond: 32000 } : {});
            const chunks = [];
            rec.ondataavailable = e => { if (e.data.size > 0) chunks.push(e.data); };
            rec.start(1000);
            window.__s360rec = { rec, chunks, stream, type };
        });
    }"""
)

private fun pausarGrabacion(): Unit = js("{ const r = window.__s360rec; if (r && r.rec.state === 'recording') r.rec.pause(); }")
private fun reanudarGrabacion(): Unit = js("{ const r = window.__s360rec; if (r && r.rec.state === 'paused') r.rec.resume(); }")

private fun detenerGrabacion(callback: (JsAny, String) -> Unit): Unit = js(
    """{
        const r = window.__s360rec;
        if (!r) { return; }
        r.rec.onstop = () => {
            const blob = new Blob(r.chunks, { type: r.type || 'audio/webm' });
            r.stream.getTracks().forEach(t => t.stop());
            blob.arrayBuffer().then(buf => callback(buf, r.type.startsWith('audio/mp4') ? 'm4a' : (r.type.startsWith('audio/ogg') ? 'ogg' : 'webm')));
            window.__s360rec = null;
        };
        r.rec.stop();
    }"""
)

private fun ArrayBuffer.toByteArray(): ByteArray {
    val arr = Int8Array(this)
    return ByteArray(arr.length) { arr[it] }
}


@Composable
actual fun rememberFilePicker(tipos: List<String>, onElegido: (ArchivoElegido) -> Unit): () -> Unit = {
    abrirSelector(tipos.joinToString(",")) { buf, nombre -> onElegido(ArchivoElegido(nombre, (buf as ArrayBuffer).toByteArray())) }
}

actual fun decodificarImagen(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

@OptIn(ExperimentalEncodingApi::class)
actual suspend fun abrirArchivo(nombre: String, mime: String, bytes: ByteArray) {
    descargar(nombre, mime, kotlin.io.encoding.Base64.encode(bytes))
}

private class WebAudioRecorder : AudioRecorder {
    override val disponible: Boolean get() = grabadorSoportado()
    override suspend fun iniciar() = iniciarGrabacion()
    override fun pausar() = pausarGrabacion()
    override fun reanudar() = reanudarGrabacion()
    override suspend fun detener(): ArchivoElegido? {
        val d = CompletableDeferred<ArchivoElegido?>()
        detenerGrabacion { buf, ext -> d.complete(ArchivoElegido("grabacion.$ext", (buf as ArrayBuffer).toByteArray())) }
        return d.await()
    }
}

@Composable
actual fun rememberAudioRecorder(): AudioRecorder = remember { WebAudioRecorder() }
