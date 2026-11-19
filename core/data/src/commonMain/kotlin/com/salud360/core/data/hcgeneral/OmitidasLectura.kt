package com.salud360.core.data.hcgeneral

import com.salud360.core.data.network.hc.OmitidasConsultasDto

// Cómo se guarda en `paciente_extra` lo que `GET consultas` no manda y cuenta (RF-43): la fila `hcc_omitidas` del paciente
// (especialidad `clinica`). `AvisosLectura` (E7.1) la lee con [omitidasDeTexto].

/** Clave de `paciente_extra` con las consultas de la web que la app no muestra. */
const val CLAVE_OMITIDAS = "hcc_omitidas"

/** `foto|otro|sin_fecha`: historia digitalizada, otro tipo de consulta y sin fecha, en ese orden. */
fun omitidasComoTexto(o: OmitidasConsultasDto): String = "${o.foto}|${o.otro}|${o.sinFecha}"

/** Lo que [omitidasComoTexto] guardó; `null` si no hay texto o no tiene esa forma (no se inventa ningún número). */
fun omitidasDeTexto(texto: String?): OmitidasConsultasDto? {
    val partes = texto?.split("|") ?: return null
    if (partes.size != 3) return null
    val numeros = partes.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    return OmitidasConsultasDto(numeros[0], numeros[1], numeros[2])
}

/** Hay algo que avisar: alguna de las tres cuentas es mayor que cero. */
fun OmitidasConsultasDto.hayAlgo(): Boolean = foto > 0 || otro > 0 || sinFecha > 0

// Lo mismo para el historial del paciente (E6.4). Son filas aparte para que la lista y el historial, que son pedidos
// independientes, no se pisen una cuenta con la otra, y para no cambiar el formato de `hcc_omitidas`.

/**
 * Clave de `paciente_extra` con cuántas cargas de laboratorio y de peso la web guarda sin fecha (D1): no llegan a la app y
 * se avisa cuántas son. Es otra cuenta que las consultas `sin_fecha` de [CLAVE_OMITIDAS].
 */
const val CLAVE_OMITIDAS_LABORATORIO = "hcc_omitidas_laboratorio"

fun omitidasLaboratorioComoTexto(cargas: Int): String = cargas.toString()

/** Lo que [omitidasLaboratorioComoTexto] guardó; `null` si no hay texto o no es un número entero desde cero. */
fun omitidasLaboratorioDeTexto(texto: String?): Int? = texto?.toIntOrNull()?.takeIf { it >= 0 }

/** Prefijo de la clave de `paciente_extra` con lo que una consulta de la web tiene y esta entrega no muestra. */
const val PREFIJO_CLAVE_NO_LEIDAS = "hcc_no_leidas_"

/** `hcc_no_leidas_<id de la consulta en la web>` (`documentos`, `audio`, `fotos`, `antecedentes_personales`; RF-43). */
fun claveNoLeidas(consultaWebId: Long): String = PREFIJO_CLAVE_NO_LEIDAS + consultaWebId

/** Los ids que informó el servidor, separados por coma y sin repetidos; vacío si ya no tiene nada. */
fun noLeidasComoTexto(noLeidas: List<String>): String = noLeidas.filter { it.isNotBlank() }.distinct().joinToString(",")

/** Lo que [noLeidasComoTexto] guardó; `null` si no hay fila (no se sabe nada), lista vacía si la fila está vacía. */
fun noLeidasDeTexto(texto: String?): List<String>? = texto?.let { if (it.isEmpty()) emptyList() else it.split(",") }
