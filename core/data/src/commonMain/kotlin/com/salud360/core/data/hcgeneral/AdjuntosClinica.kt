package com.salud360.core.data.hcgeneral

import com.salud360.core.data.files.extensionDe
import com.salud360.core.data.network.hc.RespuestaLimitesDeAdjuntosDto
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.EstadoConsulta

// Spec 005, T103 (RF-44, RF-46, RF-60): qué documento de la HC general viaja a la web con su consulta y el aviso del tope
// **antes** de subir. Puro: sin Compose, sin red, sin base y sin reloj. La sección `documentos` acepta PDF, JPG, JPEG y PNG
// (decisión del usuario del 2026-11-13); las fotos de exámenes quedan fuera de alcance (T100 y T105).

/** La sección de la HC general donde viven los documentos que E4 sube (la tabla `consulta_pdfs` de la web). */
const val SECCION_DOCUMENTOS = "documentos"

/**
 * Los cuatro tipos de archivo admitidos, con el `tipo` que devuelve la lista de la web (`pdf`, `jpeg`, `png`; T102) y su
 * `Content-Type` de descarga. `.jpg` y `.jpeg` son el mismo tipo (JPEG), como en el servidor (T96). La web valida el archivo
 * por su **firma** y no por el nombre: esto es solo lo que la app ya sabe del archivo antes de subirlo.
 */
enum class TipoDeAdjunto(val id: String, val mime: String, val extensiones: Set<String>) {
    PDF("pdf", "application/pdf", setOf("pdf")),
    JPEG("jpeg", "image/jpeg", setOf("jpg", "jpeg")),
    PNG("png", "image/png", setOf("png")),
}

/**
 * El tipo de un adjunto por su **nombre** y su `Content-Type` ([Archivo.nombre] y [Archivo.mime]); `null` si no es PDF, JPG,
 * JPEG ni PNG. La extensión declarada manda: si está y no es de los cuatro, no hay tipo aunque el `Content-Type` diga otra cosa
 * (un `.gif` no se cuela diciendo `image/png`). Sin extensión se mira el `Content-Type`, que es lo que informó el selector de
 * archivos y lo mismo que se sube a la web.
 */
fun tipoDeAdjunto(nombre: String, mime: String): TipoDeAdjunto? {
    val extension = extensionDe(nombre)
    if (extension.isNotEmpty()) return TipoDeAdjunto.entries.firstOrNull { extension in it.extensiones }
    val tipo = mime.substringBefore(';').trim().lowercase()
    return TipoDeAdjunto.entries.firstOrNull { it.mime == tipo }
}

/** Las firmas de los cuatro tipos, las mismas que valida la web por su contenido (T96): nunca el nombre ni el `Content-Type`. */
private val FIRMA_PDF = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // `%PDF-`
private val FIRMA_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // `FF D8 FF`
private val FIRMA_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) // `89 50 4E 47 0D 0A 1A 0A`

/**
 * El tipo de un adjunto por el **contenido** del archivo (su firma), o `null` si no es PDF, JPG, JPEG ni PNG. Son las mismas firmas que
 * valida la web (T96), así que lo que pasa acá no lo rechaza el servidor después. Con los bytes a mano, esto es lo que decide si el
 * archivo se acepta y con qué tipo se guarda. Un archivo vacío, o uno con otra firma, no tiene tipo.
 */
fun tipoPorContenido(bytes: ByteArray): TipoDeAdjunto? = when {
    bytes.empiezaCon(FIRMA_PDF) -> TipoDeAdjunto.PDF
    bytes.empiezaCon(FIRMA_JPEG) -> TipoDeAdjunto.JPEG
    bytes.empiezaCon(FIRMA_PNG) -> TipoDeAdjunto.PNG
    else -> null
}

private fun ByteArray.empiezaCon(firma: ByteArray): Boolean =
    size >= firma.size && firma.indices.all { this[it] == firma[it] }

/**
 * El tipo con el que se **guarda** un adjunto de [SECCION_DOCUMENTOS] (RF-44, segunda vuelta del reviewer): el que define el nombre o el
 * `Content-Type` ([tipoDeAdjunto]) y, cuando ninguno lo define (un archivo sin extensión conocida), el del **contenido** ([tipoPorContenido]).
 * `null` si no es PDF, JPG, JPEG ni PNG. Con ese tipo se guarda el `mime` de la fila, así el plan de envío (T104) también lo reconoce.
 */
fun tipoDelArchivo(nombre: String, mime: String, bytes: ByteArray? = null): TipoDeAdjunto? =
    tipoDeAdjunto(nombre, mime) ?: bytes?.let { tipoPorContenido(it) }

/**
 * El adjunto viaja con [consulta] (RF-44, RF-46): solo si cuelga de **esa** consulta propia de la HC general
 * ([Consulta.seEnviaALaWeb]), es de la sección [SECCION_DOCUMENTOS], es PDF, JPG, JPEG o PNG ([tipoDeAdjunto]) y está activo.
 *
 * **Nunca** uno sin consulta (`consulta_id` nulo, como el familigrama) ni uno de otra consulta del mismo paciente (el
 * histórico o la web, RF-46): la web lo listaría como de una consulta que no es. La foto de un examen (colgada de un registro)
 * tampoco: las fotos de exámenes quedan fuera de alcance (T105). Que la web ya lo tenga no cambia esto: si hay algo que subir
 * lo decide el plan de envío (T104), con el `remoto_id` y el `dirty` de la fila.
 */
fun adjuntoViaja(archivo: Archivo, consulta: Consulta): Boolean =
    consulta.seEnviaALaWeb() &&
        archivo.consultaId == consulta.id &&
        archivo.pacienteId == consulta.pacienteId &&
        archivo.seccion == SECCION_DOCUMENTOS &&
        archivo.registroId == null &&
        archivo.activo &&
        tipoDeAdjunto(archivo.nombre, archivo.mime) != null

/** Un MB de los de la web y de hclinica (`15 MB` = 15.728.640 bytes, T95). */
private const val BYTES_POR_MB = 1024L * 1024

/**
 * El aviso del tope **antes** de subir (RF-44, RF-60), o `null` si el archivo entra: «El archivo pesa X MB y el máximo es Y MB».
 * El máximo que manda es [RespuestaLimitesDeAdjuntosDto.maximoBytes], el **efectivo** que calcula la web (el menor entre el
 * suyo y el del hosting, D18); el de la web solo no decide.
 *
 * **No marca nada**: devuelve el texto y nada más, así que el archivo no figura enviado ni se sube. Lo que hace T104 con la
 * respuesta es dejarlo pendiente y avisar. Los MB son de 1.048.576 bytes; el peso se redondea hacia arriba y el máximo hacia
 * abajo, para no decir que el archivo pesa menos ni que el máximo es más de lo que es.
 */
fun limiteExcedido(tamanio: Long, limites: RespuestaLimitesDeAdjuntosDto): String? {
    val maximo = limites.maximoBytes
    if (tamanio <= maximo) return null
    return "El archivo pesa ${enMb(tamanio, haciaArriba = true)} MB y el máximo es ${enMb(maximo, haciaArriba = false)} MB"
}

/** Los MB de [bytes] con un decimal como mucho, con coma (el texto de la app va en español) y sin decimal si es cero. */
private fun enMb(bytes: Long, haciaArriba: Boolean): String {
    val decimas = if (haciaArriba) (bytes * 10 + BYTES_POR_MB - 1) / BYTES_POR_MB else bytes * 10 / BYTES_POR_MB
    val entero = decimas / 10
    val decimal = decimas % 10
    return if (decimal == 0L) "$entero" else "$entero,$decimal"
}

// ---- La pantalla de la sección «Documentos» (spec 005, T107; Q-E4-1 revisada) ----

/** El aviso de un archivo elegido que no es PDF, JPG, JPEG ni PNG (RF-44): el mismo texto para el médico, en un solo lugar. */
const val AVISO_TIPO_DE_ADJUNTO = "Solo se aceptan PDF, JPG, JPEG o PNG"

/**
 * Lo que el selector de archivos ofrece en [SECCION_DOCUMENTOS] (Q-E4-1 revisada): PDF, JPG, JPEG y PNG. Son los MIME de [TipoDeAdjunto],
 * así que `.jpg` y `.jpeg` los dos son `image/jpeg`; sale del mismo lugar que lo que [adjuntoViaja].
 */
val TIPOS_MIME_DE_ADJUNTO: List<String> = TipoDeAdjunto.entries.map { it.mime }

/**
 * El aviso de un archivo elegido para [SECCION_DOCUMENTOS] **antes** de guardarlo, o `null` si es PDF, JPG, JPEG o PNG. Con los [bytes] a mano
 * **decide el contenido** (RF-44): un PDF sin extensión se acepta (y se guarda como PDF, [tipoDelArchivo]) y, si además el nombre o el
 * `Content-Type` ([tipoDeAdjunto]) dan un tipo, tienen que **coincidir** (un PNG con nombre `.pdf` se rechaza). Un archivo vacío, o uno con otra
 * firma, se rechaza acá, sin crear la fila, en vez de guardarse y volver después como «No se pudo enviar». Sin [bytes] deciden el nombre y el
 * `Content-Type`, como siempre.
 */
fun avisoDelTipoDeAdjunto(nombre: String, mime: String, bytes: ByteArray? = null): String? {
    val porNombre = tipoDeAdjunto(nombre, mime)
    if (bytes == null) return if (porNombre == null) AVISO_TIPO_DE_ADJUNTO else null
    val porContenido = tipoPorContenido(bytes) ?: return AVISO_TIPO_DE_ADJUNTO
    return if (porNombre == null || porNombre == porContenido) null else AVISO_TIPO_DE_ADJUNTO
}

/**
 * La sección [SECCION_DOCUMENTOS] de [consulta] se lista con **todos** los documentos del paciente (Q-E4-3, T106, T107): solo cuando es una
 * consulta **nueva y en curso** —propia de la HC general y abierta, la que se está cargando—. Una **previa** (cerrada, del histórico o de la
 * web) lista solo los documentos de esa consulta.
 */
fun Consulta.documentosDeTodoElPaciente(): Boolean = seEnviaALaWeb() && estado == EstadoConsulta.ABIERTA
