package com.salud360.features.hc.platform

import androidx.compose.ui.graphics.ImageBitmap

/** Decodifica una imagen (JPEG/PNG) a bitmap de Compose, o null si falla. */
expect fun decodificarImagen(bytes: ByteArray): ImageBitmap?
