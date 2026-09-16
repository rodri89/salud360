package com.salud360.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.salud360.core.data.network.TurnosOnlineClient
import com.salud360.core.model.TobbIds
import com.salud360.core.ui.components.InitialsAvatar
import com.salud360.core.ui.theme.Salud360Colors
import com.salud360.features.hc.platform.decodificarImagen
import org.koin.compose.koinInject

/**
 * Avatar circular del médico: su foto de turnosonlinebb (`GET medicos/{id}/foto`) o, mientras carga o si no tiene,
 * las iniciales. Solo los médicos importados de la web (`tobb-m…`) tienen foto.
 */
@Composable
fun FotoMedico(medicoId: String?, nombre: String, size: Int = 40, colorIniciales: Color = Salud360Colors.TealStart, modifier: Modifier = Modifier) {
    val numero = TobbIds.numero(medicoId)
    val cliente = koinInject<TurnosOnlineClient>()
    var bitmap by remember(medicoId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(numero) {
        bitmap = numero?.let { cliente.fotoMedico(it) }?.let { decodificarImagen(it) }
    }
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = nombre, contentScale = ContentScale.Crop, modifier = modifier.size(size.dp).clip(CircleShape))
    } else {
        InitialsAvatar(nombre, modifier = modifier, size = size, color = colorIniciales)
    }
}
