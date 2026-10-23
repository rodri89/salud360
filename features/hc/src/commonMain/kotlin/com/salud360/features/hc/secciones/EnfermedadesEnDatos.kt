package com.salud360.features.hc.secciones

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.salud360.core.data.hcgeneral.TITULO_ENFERMEDADES_EN_DATOS
import com.salud360.core.data.hcgeneral.enfermedadesDelPaciente
import com.salud360.core.ui.components.SectionCard
import com.salud360.features.hc.EnfermedadesViewModel
import com.salud360.features.hc.SeccionContext
import com.salud360.features.hc.iconoSeccion
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Las enfermedades del paciente en "Datos", solo para leer, cuando la sección no se muestra (spec 003, RF-6, RF-7). La
 * pantalla lo dibuja solo si `ConsultaViewModel.enfermedadesEnDatos` lo dice (HC general y médico propio); acá no se
 * decide nada de eso.
 *
 * Usa el mismo [EnfermedadesViewModel] que la sección (`key = "enfermedades-$consultaId"`): `abrir()` es idempotente, así
 * que cambiar de pestaña no repite el pedido. Como pide la spec (RF-8), `abrir()` resuelve el vínculo y puede crear la
 * ficha del paciente en la web de clínica (P7-A). Funciona con la consulta cerrada: no hay casillas ni "Administrar".
 *
 * Sin motivos ni "Elegí la ficha" (PD6): con cualquier motivo, sin copia, cargando o sin enfermedades no dibuja nada
 * (`vista` es `null` con un motivo o sin copia). Nada pasa por `ctx` ni por el autoguardado.
 */
@Composable
fun EnfermedadesEnDatos(ctx: SeccionContext) {
    val vm = koinViewModel<EnfermedadesViewModel>(key = "enfermedades-${ctx.consultaId}") { parametersOf(ctx.pacienteId) }
    val controlador = vm.controlador ?: return
    LaunchedEffect(Unit) { controlador.abrir() }

    val ui by controlador.estado.collectAsState()
    val enfermedades = ui.vista?.let(::enfermedadesDelPaciente).orEmpty()
    if (enfermedades.isEmpty()) return

    SectionCard(TITULO_ENFERMEDADES_EN_DATOS, icon = iconoSeccion("enfermedades"), collapsible = false) {
        enfermedades.forEach { Text(it.nombre) }
    }
}
