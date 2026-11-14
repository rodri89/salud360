package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id
import com.salud360.core.model.especialidad.SeccionesComunes
import com.salud360.core.model.hc.EstadoEnvio
import com.salud360.core.model.hc.Pendiente

// Cuántas consultas de la HC general tienen cambios sin enviar (spec 005, RF-31, RF-60, RF-67). **Puro**: el plan de cada consulta
// ([planDeEnvio]) y los estados guardados entran como parámetros; no hay base, red ni reloj. Cuenta **consultas**, no filas; solo las
// **propias** (el histórico es «Solo en este dispositivo» y lo de la web solo se lee: el plan de ambas es vacío); y solo lo que esta
// versión **va a enviar**: lo que la web no guarda, los antecedentes desactivados, lo que queda en reserva (una sección todavía no
// habilitada, una carga sin fecha útil, una baja que nunca llegó a la web) y las secciones que todavía no se envían no son un pendiente
// (RF-60, RF-4). Las partes salen del **plan** (pasos y retenciones), que es quien decide qué viaja; quien arma las entradas desde la base
// es `HcClinicaEnvio.contarPendientes`.

/** Los pendientes no pasan por el plan de envío (llegan con E3): esta es la clave de su estado. */
const val PARTE_PENDIENTES = "pendientes"

/** En qué punto está una consulta que tiene cambios sin enviar. */
enum class SituacionDeEnvio {
    /** Hay algo para enviar y todavía no salió (o está en camino): «Falta enviar». */
    FALTA_ENVIAR,

    /** Alguna parte quedó «En espera» por una causa ajena al dato (RF-6): se reintenta sola. */
    EN_ESPERA,

    /** Alguna parte quedó «No se pudo enviar» (RF-30): el médico la reintenta o la descarta. */
    NO_SE_PUDO_ENVIAR,
}

data class ConsultaPorEnviar(val consultaId: Id, val pacienteId: Id, val situacion: SituacionDeEnvio)

/** Las consultas con cambios sin enviar, en el orden de las entradas. */
data class ResumenDeEnvio(val consultas: List<ConsultaPorEnviar>) {
    /** Cuántas consultas tienen cambios sin enviar (la suma de las tres situaciones). */
    val total: Int get() = consultas.size
    val faltaEnviar: Int get() = consultas.count { it.situacion == SituacionDeEnvio.FALTA_ENVIAR }
    val enEspera: Int get() = consultas.count { it.situacion == SituacionDeEnvio.EN_ESPERA }
    val noSePudoEnviar: Int get() = consultas.count { it.situacion == SituacionDeEnvio.NO_SE_PUDO_ENVIAR }
}

/**
 * Una consulta de la HC general con todo lo que el contador necesita. Lo sucio de lo que viaja con la consulta (textos, examen, datos por
 * paciente, cargas y documentos) ya está dentro del [plan], que es de donde salen las partes que cuentan. [pendientes] es lo único que no
 * pasa por el plan (llega con E3).
 */
data class EntradaDelContador(
    val pacienteId: Id,
    val plan: PlanDeEnvio,
    /** El estado guardado de cada parte (T20); lo que no figura es «Falta enviar». */
    val estados: Map<String, EstadoEnvio> = emptyMap(),
    val pendientes: List<Pendiente> = emptyList(),
    /** Las secciones que envía esta versión ([SECCIONES_HABILITADAS], el candado por sección: RF-4). */
    val habilitadas: Set<String> = SECCIONES_HABILITADAS,
)

/**
 * Cuenta las consultas de [entradas] que tienen algo sin enviar. Una consulta cuenta si el plan tiene algo que enviar, algo retenido por un
 * rechazo (RF-56: hasta que el médico lo resuelva, la consulta no se cierra) o pendientes que esta versión envía; lo que el plan deja en
 * reserva (una carga sin fecha útil, una baja que nunca llegó a la web, lo de una consulta anulada) no cuenta. Su situación es la de sus
 * partes pendientes: «No se pudo enviar» gana a «En espera», y esta a «Falta enviar».
 */
fun resumenDeEnvio(entradas: List<EntradaDelContador>): ResumenDeEnvio = ResumenDeEnvio(entradas.mapNotNull(::consultaPorEnviar))

private fun consultaPorEnviar(e: EntradaDelContador): ConsultaPorEnviar? {
    val situacion = when (estadoPendienteDeLaConsulta(e)) {
        null -> return null
        is EstadoEnvio.NoSePudoEnviar -> SituacionDeEnvio.NO_SE_PUDO_ENVIAR
        is EstadoEnvio.EnEspera -> SituacionDeEnvio.EN_ESPERA
        else -> SituacionDeEnvio.FALTA_ENVIAR
    }
    return ConsultaPorEnviar(e.plan.consultaId, e.pacienteId, situacion)
}

/**
 * El estado de lo que le falta enviar a la consulta, o `null` si no le falta nada (o no es una propia): el más grave de sus partes
 * pendientes —«No se pudo enviar» gana a «En espera», y esta a «Falta enviar» ([EstadoEnvio.Pendiente])—. Es el que ve el médico en la
 * tarjeta y el banner de la consulta (T28) y el que cuenta [resumenDeEnvio]. Solo cuenta el estado de lo que realmente falta: el de una
 * parte que no tiene nada pendiente no cambia la situación.
 */
fun estadoPendienteDeLaConsulta(e: EntradaDelContador): EstadoEnvio? {
    val plan = e.plan
    if (plan.motivoVacio != null) return null // histórico o de la web: nada sale, nada se cuenta (RF-7, RF-67)

    val pendientes =
        if (seccionSeOfrece(SeccionesComunes.PENDIENTES, e.habilitadas)) e.pendientes.filter { it.consultaId == plan.consultaId } else emptyList()
    val partes = buildSet {
        plan.pasos.mapTo(this) { it.parte }
        plan.retenciones.mapTo(this) { it.parte }
        if (pendientes.isNotEmpty()) add(PARTE_PENDIENTES)
    }
    if (partes.isEmpty()) return null

    val estados = e.estados.filterKeys { it in partes }.toList().sortedBy { it.first }.map { it.second }
    return estados.firstOrNull { it is EstadoEnvio.NoSePudoEnviar } ?: estados.firstOrNull { it is EstadoEnvio.EnEspera } ?: EstadoEnvio.Pendiente
}