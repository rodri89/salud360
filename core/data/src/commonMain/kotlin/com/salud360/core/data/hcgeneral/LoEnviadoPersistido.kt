package com.salud360.core.data.hcgeneral

import com.salud360.core.model.Id
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Cómo se guarda `LoEnviado` (lo último que la web confirmó de una consulta propia, spec 005, T21 decisión a y T25) para que sobreviva a
// cerrar la app **sin migración**: en `paciente_extra` del paciente, especialidad `clinica`, una fila `hce_enviado_<consulta>` con un
// JSON `{"v": {"<sección>/<campo>": texto}, "e": {"<campo del examen>": valor}, "f": {"<sección>/<campo>": valor del formulario}, "a": {"<clave>": "<0|1>|<detalle>"}, "r": {"<id de la web>": {"fecha": fecha, "detalle": detalle}}}`
// (`f` y `a`, de T62-a; `r`, de T62-b; cada uno solo si hay algo: un texto guardado antes se sigue leyendo y uno sin ellos se escribe igual que antes), siempre `dirty = 0` (es un dato local: nunca se
// manda a turnos ni cuenta como pendiente). Sin él, el plan no distinguiría un texto vaciado a propósito de un vacío que el médico no
// tocó (RF-69, D27). Puro: de `LoEnviado` a texto y de texto a `LoEnviado`; quien lo lee y lo escribe es `LoEnviadoGuardado`.

/** Prefijo de la clave de `paciente_extra` con lo último que la web confirmó de una consulta (el del estado de envío es [PREFIJO_ESTADO_ENVIO]). */
const val PREFIJO_LO_ENVIADO = "hce_enviado_"

/** `hce_enviado_<consulta>`. */
fun claveDeLoEnviado(consultaId: Id): String = "$PREFIJO_LO_ENVIADO$consultaId"

/** Lo que se guarda de [lo], o `null` si no hay nada confirmado (no se guarda una fila vacía). El orden de las claves es fijo. */
fun loEnviadoComoTexto(lo: LoEnviado): String? {
    if (lo.valores.isEmpty() && lo.examen.isEmpty() && lo.formularios.isEmpty() && lo.antecedentes.isEmpty() && lo.registros.isEmpty()) return null
    return buildJsonObject {
        put("v", textos(lo.valores))
        put("e", textos(lo.examen))
        // T62-a: solo si hay algo, así lo que no tiene formularios ni antecedentes se guarda igual que antes y una versión vieja lo lee.
        if (lo.formularios.isNotEmpty()) put("f", textos(lo.formularios))
        if (lo.antecedentes.isNotEmpty()) put("a", textos(lo.antecedentes))
        // T62-b: los estudios, por id de la web y con sus campos; también solo si hay algo.
        if (lo.registros.isNotEmpty()) put("r", registros(lo.registros))
    }.toString()
}

/**
 * Lo que [loEnviadoComoTexto] guardó. Un texto ausente o que no se entiende es «no sé qué tiene la web» (un [LoEnviado] vacío): no
 * se inventa nada. Lo que no es un texto dentro del JSON se ignora sin romper el resto.
 */
fun loEnviadoDeTexto(texto: String?): LoEnviado {
    if (texto.isNullOrBlank()) return LoEnviado()
    val raiz = runCatching { Json.parseToJsonElement(texto) }.getOrNull() as? JsonObject ?: return LoEnviado()
    return LoEnviado(
        valores = raiz.textos("v"),
        examen = raiz.textos("e"),
        formularios = raiz.textos("f"),
        antecedentes = raiz.textos("a"),
        registros = raiz.registros("r"),
    )
}

private fun textos(mapa: Map<String, String>): JsonObject = buildJsonObject {
    mapa.toList().sortedBy { it.first }.forEach { (clave, valor) -> put(clave, valor) }
}

/** Los estudios confirmados: `{<id de la web>: {<campo>: valor}}`, con el orden de las claves fijo. */
private fun registros(mapa: Map<String, Map<String, String>>): JsonObject = buildJsonObject {
    mapa.toList().sortedBy { it.first }.forEach { (remoto, campos) -> put(remoto, textos(campos)) }
}

private fun JsonObject.textos(clave: String): Map<String, String> {
    val objeto = this[clave] as? JsonObject ?: return emptyMap()
    return objeto.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap()
}

/** Los estudios guardados: `{<id de la web>: {<campo>: valor}}`; lo que no es un objeto se ignora sin romper el resto (como [textos]). */
private fun JsonObject.registros(clave: String): Map<String, Map<String, String>> {
    val objeto = this[clave] as? JsonObject ?: return emptyMap()
    return objeto.mapNotNull { (remoto, v) -> (v as? JsonObject)?.let { remoto to it.camposDeEstudio() } }.toMap()
}

private fun JsonObject.camposDeEstudio(): Map<String, String> =
    entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap()
