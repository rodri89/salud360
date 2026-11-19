package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.Laboratorio
import kotlinx.datetime.LocalDate

// Spec 005, T82-a (RF-41, RF-42, RF-69): qué manda una carga de laboratorio general o de peso y con qué fecha. Puro: sin Compose,
// sin red, sin base y sin reloj. Son las dos preguntas que el plan de envío (T84) y la cola (T85) necesitan responder igual que
// `PayloadsClinica.carga` (T81), que delega en `valoresAEnviar` para que el cuerpo y el plan no puedan divergir.

/**
 * Las fechas con las que la web dice «sin fecha» (002 RF-20, RF-21) y con las que hclinica contesta `422 fecha_invalida` (E3,
 * `FECHAS_VACIAS_CARGA`): ninguna de las dos viaja. `0000-00-00` no puede llegar acá porque no es un `LocalDate` (la lectura lo
 * descarta, `LecturaClinica.fechaDeLaWeb`); si alguna vez se guardara una fecha así, la regla es la misma: no hay fecha útil.
 */
val FECHAS_SIN_DATO: Set<LocalDate> = setOf(LocalDate(2000, 1, 1), LocalDate(1000, 1, 1))

/** De dónde sale la fecha que viaja con una carga. */
enum class OrigenDeFecha {
    DE_LA_CARGA,
    DE_LA_CONSULTA,
}

/** Lo que una carga manda como fecha. */
sealed interface FechaAEnviar {
    /** Hay una fecha de calendario real; [origen] dice de dónde salió (para el aviso de la pantalla, T87). */
    data class Valida(val fecha: LocalDate, val origen: OrigenDeFecha) : FechaAEnviar

    /**
     * Ni la carga ni la consulta tienen una fecha que la web acepte. No se inventa nada (un marcador de «sin fecha» o la fecha de
     * hoy): el plan deja la carga en reserva (T84) con el dato intacto, como un estudio sin fecha.
     */
    data object SinFecha : FechaAEnviar
}

/**
 * La fecha con la que viaja [carga] (RF-41): la suya o, si no tiene una que la web acepte, la de [consulta] («si la carga no tiene
 * fecha, usa la fecha de la consulta»). Nunca un marcador de «sin fecha»; si tampoco la consulta tiene una, [FechaAEnviar.SinFecha].
 * El peso usa la misma regla (RF-42).
 */
fun fechaAEnviar(carga: Laboratorio, consulta: Consulta): FechaAEnviar = fechaAEnviar(carga.fecha, consulta.fecha)

/** [fechaAEnviar] sin los modelos: la fecha de la carga, después la de la consulta, y si ninguna sirve, [FechaAEnviar.SinFecha]. */
fun fechaAEnviar(fechaDeLaCarga: LocalDate, fechaDeLaConsulta: LocalDate): FechaAEnviar = when {
    fechaDeLaCarga !in FECHAS_SIN_DATO -> FechaAEnviar.Valida(fechaDeLaCarga, OrigenDeFecha.DE_LA_CARGA)
    fechaDeLaConsulta !in FECHAS_SIN_DATO -> FechaAEnviar.Valida(fechaDeLaConsulta, OrigenDeFecha.DE_LA_CONSULTA)
    else -> FechaAEnviar.SinFecha
}

/**
 * Qué valores de la carga viajan (RF-41, RF-69), contra [confirmada] (los valores de esa misma carga que la web confirmó la última
 * vez, `LoEnviado.cargas[remoto_id]`, T84; `null` o vacío = alta):
 * - **alta:** todos los que tienen contenido; un valor en blanco o vacío no viaja;
 * - **actualización:** solo los que cambian de verdad ([cambioReal]); un valor que se vacía a propósito sale `""` y una columna que
 *   lo confirmado tenía y la carga ya no está es un vaciado (`""`), porque la web solo se puede vaciar;
 * - el texto va tal cual, sin recortar; `0` y `0.00` son datos (en una carga no hay medidas del examen), no vacíos.
 * El orden es el de la carga y después los vaciados que solo estaban en [confirmada]. `PayloadsClinica.carga` arma el cuerpo con
 * esta misma función.
 */
fun valoresAEnviar(valores: Map<String, String>, confirmada: Map<String, String>? = null): Map<String, String> {
    val confirmados = confirmada.orEmpty()
    val salida = linkedMapOf<String, String>()
    for (clave in valores.keys + (confirmados.keys - valores.keys)) {
        val anterior = confirmados[clave]
        val nuevo = valores[clave].orEmpty()
        when (cambioReal(anterior, nuevo)) {
            CambioReal.NINGUNO -> Unit
            CambioReal.ESCRIBIR -> salida[clave] = nuevo
            CambioReal.VACIAR -> salida[clave] = ""
        }
    }
    return salida
}
