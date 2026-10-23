package com.salud360.features.hc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salud360.core.data.hcgeneral.medicoParaEnfermedades
import com.salud360.core.data.repos.AuthRepository
import com.salud360.core.data.repos.EnfermedadesControlador
import com.salud360.core.data.repos.EnfermedadesRepository
import com.salud360.core.data.repos.PacientesHcRepository
import com.salud360.core.model.Id
import kotlin.concurrent.Volatile

/**
 * Sostiene el [EnfermedadesControlador] de una consulta (spec 003): lo comparten la sección "Enfermedades" y el bloque de
 * "Datos", así cambiar de pestaña no repite el pedido (RF-8, RF-15). Se pide con `key = "enfermedades-$consultaId"`.
 *
 * El médico es el de la **sesión** ([medicoParaEnfermedades]), no el que recibe `ConsultaScreen`: para la secretaria ese
 * es el médico que eligió (RF-4). Sin médico propio no hay [controlador] y la sección no se dibuja. Sin lógica propia:
 * las reglas están en `core/data`. Nada pasa por el autoguardado ni por la sincronización diferida.
 */
class EnfermedadesViewModel(
    repo: EnfermedadesRepository,
    pacientesHc: PacientesHcRepository,
    auth: AuthRepository,
    pacienteId: Id,
) : ViewModel() {
    val medicoId: Id? = auth.sesion.value?.let(::medicoParaEnfermedades)

    /**
     * Si la consulta se puede editar (RF-35). No es observable: la pantalla lo actualiza con `SideEffect` y el controlador
     * lo lee en cada uso; por eso la pantalla recalcula `puedeAdministrar` junto con el estado del controlador. Hasta
     * que la pantalla lo diga, solo lectura.
     */
    @Volatile
    var editable: Boolean = false

    /** El último contador de "Guardado" ya mostrado, para que cambiar de pestaña no lo repita (RF-24). */
    var guardadoVisto: Int = 0

    /** Con el `viewModelScope`: los pedidos viven mientras viva la pantalla. `null` si la sesión no es de un médico. */
    val controlador: EnfermedadesControlador? = medicoId?.let {
        EnfermedadesControlador(repo, pacientesHc, viewModelScope, it, pacienteId, editable = { editable })
    }
}
