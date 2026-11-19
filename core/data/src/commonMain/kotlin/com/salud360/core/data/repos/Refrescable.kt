package com.salud360.core.data.repos

import com.salud360.core.model.Id
import com.salud360.core.model.hc.Consulta

/**
 * Capacidad opcional de un [HcBackend] que lee de la web: traer las consultas del paciente **a pedido** (el botón «Actualizar»,
 * RF-57, RF-58), saltándose la espera de un minuto que tiene entre una lectura y la siguiente. `HcRepository` lo usa solo con
 * los backends que lo declaran: pediatría y las demás no cambian, y [HcBackend] tampoco.
 */
interface Refrescable {
    /**
     * Como [HcBackend.traerConsultas] pero con [forzar]: con `true` pide otra vez la lista y el historial aunque hayan pasado
     * menos de 60 segundos. Si falla lanza el error de la API, sin reintento propio.
     */
    suspend fun traerConsultas(pacienteId: Id, medicoId: Id, forzar: Boolean): List<Consulta>
}
