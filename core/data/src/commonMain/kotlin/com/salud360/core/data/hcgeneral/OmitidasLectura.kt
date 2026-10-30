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
