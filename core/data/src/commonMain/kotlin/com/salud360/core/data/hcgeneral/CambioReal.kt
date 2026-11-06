package com.salud360.core.data.hcgeneral

import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.RegistroClinico

// «Cambio real» al guardar (spec 005, RF-68, RF-69, D27). Puro. El autoguardado corre cada 600 ms y guarda todo lo que hay en
// pantalla, haya cambiado o no: si cada guardado marcara algo para enviar, se enviarían datos que el médico no tocó. Solo un
// cambio real marca. Dos casos distintos de «vacío» (constitución, punto 3):
// - un vacío sobre «nada» **no es un cambio**: el médico no escribió nada, no hay qué guardar ni qué enviar;
// - un vacío sobre algo ya cargado es un **vaciado a propósito**: se guarda y se envía vacío.

enum class CambioReal {
    /** No hay nada que guardar: es lo mismo que ya estaba, o un vacío sobre nada. */
    NINGUNO,

    /** Hay un valor nuevo o distinto. */
    ESCRIBIR,

    /** El médico dejó vacío algo que tenía contenido (RF-69): se guarda y se envía vacío. */
    VACIAR,
}

/** Texto sin contenido: vacío o en blanco. «Solo en blanco» solo decide si hay dato; los textos se guardan tal cual vinieron. */
fun esVacio(valor: String?): Boolean = valor.isNullOrBlank()

/**
 * Medida del examen físico (peso, talla, tensión arterial, IMC) sin cargar: vacía, en blanco o `0.00`. `0` y `0.0` son datos
 * (RF-21; la misma regla de la lectura).
 */
fun esVacioDeMedida(valor: String?): Boolean = esVacio(valor) || valor?.trim() == "0.00"

/** Qué es guardar [nuevo] donde estaba [anterior] (`null` = no había nada). Un texto distinto, aunque sea en un espacio, es otro texto. */
fun cambioReal(anterior: String?, nuevo: String, vacio: (String?) -> Boolean = ::esVacio): CambioReal = when {
    vacio(nuevo) -> if (vacio(anterior)) CambioReal.NINGUNO else CambioReal.VACIAR
    anterior == nuevo -> CambioReal.NINGUNO
    else -> CambioReal.ESCRIBIR
}

/** [cambioReal] para una medida del examen físico: `0.00` cuenta como vacío. */
fun cambioRealDeMedida(anterior: String?, nuevo: String): CambioReal = cambioReal(anterior, nuevo, ::esVacioDeMedida)

/** De los campos de una sección, solo los que cambian de verdad (`anteriores` es lo que hay en la base, `nuevos` lo que se guarda). */
fun cambiosRealesDeSeccion(anteriores: Map<String, String>, nuevos: Map<String, String>): Map<String, CambioReal> =
    nuevos.mapValues { (campo, valor) -> cambioReal(anteriores[campo], valor) }.filterValues { it != CambioReal.NINGUNO }

/**
 * El examen [nuevo] cambia algo respecto del [anterior] (`null` = no había fila). Peso, talla, tensión arterial e IMC usan la
 * regla de las medidas (`0.00` es vacío); el resto de los campos, la de los textos. Un examen vacío sobre nada no es un cambio.
 */
fun cambioRealDelExamen(anterior: ExamenFisico?, nuevo: ExamenFisico): Boolean {
    val base = anterior ?: ExamenFisico(nuevo.consultaId)
    val medidas = listOf(base.peso to nuevo.peso, base.talla to nuevo.talla, base.tensionArterial to nuevo.tensionArterial, base.imc to nuevo.imc)
    val textos = listOf(
        base.pesoPercentil to nuevo.pesoPercentil, base.tallaPercentil to nuevo.tallaPercentil, base.imcPercentil to nuevo.imcPercentil,
        base.perimetroCefalico to nuevo.perimetroCefalico, base.perimetroCefalicoPercentil to nuevo.perimetroCefalicoPercentil,
        base.circunferenciaAbdominal to nuevo.circunferenciaAbdominal, base.frecuenciaCardiaca to nuevo.frecuenciaCardiaca,
        base.temperatura to nuevo.temperatura, base.saturacion to nuevo.saturacion, base.ipd to nuevo.ipd, base.nota to nuevo.nota,
    )
    return medidas.any { (a, n) -> cambioRealDeMedida(a, n) != CambioReal.NINGUNO } ||
        textos.any { (a, n) -> cambioReal(a, n) != CambioReal.NINGUNO }
}

/**
 * Guardar la casilla ([flag], [detalle]) donde estaba [anterior] (`null` = no había fila). Una casilla sin marcar y sin detalle es
 * «vacía»: sobre nada no cambia nada; sobre algo marcado o con detalle es un vaciado a propósito.
 */
fun cambioRealDeAntecedente(anterior: Antecedente?, flag: Boolean, detalle: String): CambioReal {
    val nuevoVacio = !flag && esVacio(detalle)
    val anteriorVacio = anterior == null || (!anterior.flag && esVacio(anterior.detalle))
    return when {
        nuevoVacio -> if (anteriorVacio) CambioReal.NINGUNO else CambioReal.VACIAR
        anterior != null && anterior.flag == flag && anterior.detalle == detalle -> CambioReal.NINGUNO
        else -> CambioReal.ESCRIBIR
    }
}

/**
 * Guardar el registro [nuevo] donde estaba [anterior]. Una fila nueva (`anterior == null`) siempre se escribe: agregar es una
 * acción del médico. Si ya estaba, cuenta un cambio de fecha, de baja o de cualquier campo; un campo vacío es lo mismo que un
 * campo que no está, y vaciar uno que tenía texto sí es un cambio.
 */
fun cambioRealDeRegistro(anterior: RegistroClinico?, nuevo: RegistroClinico): Boolean {
    if (anterior == null) return true
    return anterior.fecha != nuevo.fecha || anterior.activo != nuevo.activo || anterior.consultaId != nuevo.consultaId ||
        conContenido(anterior.campos) != conContenido(nuevo.campos)
}

private fun conContenido(campos: Map<String, String>): Map<String, String> = campos.filterValues { !esVacio(it) }
