package com.salud360.core.ui.components

/**
 * Valor de un marcador SI / NO al tocar [tocado] ("SI" o "NO") con [actual] cargado: tocar el elegido lo deja sin
 * cargar (`""`); si no, queda lo tocado, también sobre un valor desconocido (RF-67, RF-68).
 */
fun alternarSiNo(actual: String, tocado: String): String = if (tocado == actual) "" else tocado

/** Tarjetas por fila de la grilla SI / NO según el ancho de la sección: 2 hasta 600 dp, 4 desde ahí (RF-66). */
fun columnasPara(anchoDp: Float): Int = if (anchoDp < 600f) 2 else 4
