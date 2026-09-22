package com.salud360.core.crecimiento.tablas

/**
 * Tabla de parámetros LMS (Box-Cox: L potencia, M mediana, S coeficiente de variación) por mes cumplido.
 * Las filas son mensuales y consecutivas; se interpola linealmente entre meses.
 */
class TablaLms(val meses: IntArray, val l: DoubleArray, val m: DoubleArray, val s: DoubleArray) {
    init {
        require(meses.size == l.size && meses.size == m.size && meses.size == s.size) { "Tabla LMS inconsistente" }
        require(meses.isNotEmpty()) { "Tabla LMS vacía" }
    }

    val primerMes: Int get() = meses.first()
    val ultimoMes: Int get() = meses.last()

    /** Parámetros interpolados para una edad en meses (decimal), o null fuera del rango de la tabla. */
    fun en(edadMeses: Double): Lms? {
        if (edadMeses.isNaN() || edadMeses < primerMes || edadMeses > ultimoMes) return null
        val pos = edadMeses - primerMes
        val i = pos.toInt().coerceIn(0, meses.lastIndex)
        if (i == meses.lastIndex) return Lms(l[i], m[i], s[i])
        val f = pos - i
        return Lms(l[i] + (l[i + 1] - l[i]) * f, m[i] + (m[i + 1] - m[i]) * f, s[i] + (s[i + 1] - s[i]) * f)
    }
}

/** Parámetros LMS de una edad puntual. */
data class Lms(val l: Double, val m: Double, val s: Double)
