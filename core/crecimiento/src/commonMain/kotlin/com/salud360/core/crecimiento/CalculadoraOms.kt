package com.salud360.core.crecimiento

import com.salud360.core.crecimiento.tablas.ImcEdadMujeres
import com.salud360.core.crecimiento.tablas.ImcEdadVarones
import com.salud360.core.crecimiento.tablas.Lms
import com.salud360.core.crecimiento.tablas.PcEdadMujeres
import com.salud360.core.crecimiento.tablas.PcEdadVarones
import com.salud360.core.crecimiento.tablas.PesoEdadMujeres
import com.salud360.core.crecimiento.tablas.PesoEdadVarones
import com.salud360.core.crecimiento.tablas.TablaLms
import com.salud360.core.crecimiento.tablas.TallaEdadMujeres
import com.salud360.core.crecimiento.tablas.TallaEdadVarones
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Medidas antropométricas con curva de referencia OMS. Unidades: kg, cm, cm, kg/m². */
enum class Medida(val etiqueta: String, val unidad: String) {
    PESO("Peso", "kg"), TALLA("Talla", "cm"), PERIMETRO_CEFALICO("Perímetro cefálico", "cm"), IMC("IMC", "kg/m²"),
}

enum class SexoOms { M, F }

/** Resultado de ubicar una medida en la curva: puntaje z y percentil (0 a 100). */
data class ResultadoPercentil(val z: Double, val percentil: Double) {
    val etiqueta: String get() = CalculadoraOms.etiquetaPercentil(percentil)
}

/**
 * Percentilos y puntajes z según los patrones de crecimiento OMS 2006 (0-5 años) y la referencia OMS 2007
 * (5-19 años), con el método LMS: z = ((x/M)^L − 1) / (L·S) (o ln(x/M)/S si L = 0).
 *
 * Nota: la OMS acota los z extremos (|z| > 3) de peso e IMC con una fórmula especial para estudios
 * poblacionales; para mostrar el percentilo de un paciente no cambia nada (queda por encima de P99 o por
 * debajo de P1), así que acá se usa la fórmula directa.
 */
object CalculadoraOms {
    /** Días promedio por mes según la OMS (365.25 / 12). */
    const val DIAS_POR_MES = 30.4375

    /** Z de las curvas de referencia que se dibujan: P3, P15, P50, P85 y P97. */
    val curvasReferencia: List<Pair<String, Double>> = listOf("P3" to -1.881, "P15" to -1.036, "P50" to 0.0, "P85" to 1.036, "P97" to 1.881)

    fun tabla(medida: Medida, sexo: SexoOms): TablaLms = when (medida) {
        Medida.PESO -> if (sexo == SexoOms.M) PesoEdadVarones.tabla else PesoEdadMujeres.tabla
        Medida.TALLA -> if (sexo == SexoOms.M) TallaEdadVarones.tabla else TallaEdadMujeres.tabla
        Medida.PERIMETRO_CEFALICO -> if (sexo == SexoOms.M) PcEdadVarones.tabla else PcEdadMujeres.tabla
        Medida.IMC -> if (sexo == SexoOms.M) ImcEdadVarones.tabla else ImcEdadMujeres.tabla
    }

    /** Edad máxima (meses) que cubre la referencia de una medida. */
    fun edadMaximaMeses(medida: Medida): Int = tabla(medida, SexoOms.M).ultimoMes

    /** Parámetros LMS interpolados a una edad en meses, o null fuera de rango. */
    fun lms(medida: Medida, sexo: SexoOms, edadMeses: Double): Lms? = tabla(medida, sexo).en(edadMeses)

    /** Puntaje z y percentil de una medida a una edad en días. Null si la edad está fuera de la referencia o el valor no es válido. */
    fun zScore(medida: Medida, sexo: SexoOms, edadDias: Int, valor: Double): ResultadoPercentil? =
        zScoreMeses(medida, sexo, edadDias / DIAS_POR_MES, valor)

    fun zScoreMeses(medida: Medida, sexo: SexoOms, edadMeses: Double, valor: Double): ResultadoPercentil? {
        if (valor.isNaN() || valor <= 0) return null
        val p = lms(medida, sexo, edadMeses) ?: return null
        val z = if (abs(p.l) < 1e-9) ln(valor / p.m) / p.s else ((valor / p.m).pow(p.l) - 1) / (p.l * p.s)
        if (z.isNaN() || z.isInfinite()) return null
        return ResultadoPercentil(z, percentil(z))
    }

    /** Valor de la medida que corresponde a un z dado a esa edad (para dibujar las curvas de referencia). */
    fun valorEnZ(medida: Medida, sexo: SexoOms, edadMeses: Double, z: Double): Double? {
        val p = lms(medida, sexo, edadMeses) ?: return null
        return if (abs(p.l) < 1e-9) p.m * exp(p.s * z) else p.m * (1 + p.l * p.s * z).pow(1 / p.l)
    }

    /** Percentil (0-100) de un puntaje z: Φ(z)·100. */
    fun percentil(z: Double): Double = 50.0 * (1 + erf(z / sqrt(2.0)))

    /** "P45"; fuera de 1-99 se muestra "<P1" o ">P99" para no dar una precisión que la referencia no tiene. */
    fun etiquetaPercentil(p: Double): String = when {
        p < 1 -> "<P1"
        p > 99 -> ">P99"
        else -> "P${p.roundToInt()}"
    }

    /** IMC = peso / talla² (talla en cm), o null si faltan datos. */
    fun imc(pesoKg: Double, tallaCm: Double): Double? = if (pesoKg > 0 && tallaCm > 0) pesoKg / (tallaCm / 100).pow(2) else null

    /** Función error, aproximación de Abramowitz y Stegun 7.1.26 (error máximo 1.5e-7): suficiente para percentilos. */
    internal fun erf(x: Double): Double {
        val signo = if (x < 0) -1.0 else 1.0
        val a = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * a)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-a * a)
        return signo * y
    }
}
