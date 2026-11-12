package com.salud360.core.data.network.hc

import com.salud360.core.data.hcgeneral.CATEGORIA_FAMILIARES
import com.salud360.core.data.hcgeneral.CLAVE_NOTA
import com.salud360.core.data.hcgeneral.CambioReal
import com.salud360.core.data.hcgeneral.TIPOS_ESTUDIO_WEB
import com.salud360.core.data.hcgeneral.TIPO_EXAMEN_COMPLEMENTARIO
import com.salud360.core.data.hcgeneral.TIPO_INTERCONSULTA
import com.salud360.core.data.hcgeneral.TIPO_LABORATORIO_GENERAL
import com.salud360.core.data.hcgeneral.TIPO_PESO
import com.salud360.core.data.hcgeneral.casillaSeEnvia
import com.salud360.core.data.hcgeneral.cambioReal
import com.salud360.core.data.hcgeneral.cambioRealDeAntecedente
import com.salud360.core.data.hcgeneral.esIdDeLaWeb
import com.salud360.core.data.hcgeneral.esVacio
import com.salud360.core.data.hcgeneral.esVacioDeMedida
import com.salud360.core.data.hcgeneral.valoresAEnviar
import com.salud360.core.model.Id
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.RegistroClinico
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * La escritura de la HC general en hclinica (spec 005, E1; `md/API_SALUD360_CLINICA.md` de hclinica, «Escritura (E1)»): lo que la
 * app manda ([PayloadsClinica]) y lo que el servidor contesta (los `Respuesta…Dto`). Nada de esto habla con la red ni con la base:
 * quien envía es [ClinicaEscrituraApi].
 */

/** `POST consultas`: `consulta` es la cabecera de la lectura; [creada] es `false` si el servidor ya la tenía (el envío se repitió, RF-15). */
@Serializable
data class RespuestaCrearConsultaDto(
    val consulta: ConsultaCabeceraDto,
    val creada: Boolean,
    /** Las consultas que el paciente ya tenía abiertas en la web (de cualquier médico): un aviso, la nueva se crea igual (RF-59). */
    @SerialName("otras_abiertas") val otrasAbiertas: List<ConsultaAbiertaDto> = emptyList(),
)

/** Una consulta abierta del paciente que no es la recién creada: el id de la web y el nombre de quien la abrió, si se resolvió. */
@Serializable
data class ConsultaAbiertaDto(
    val id: Long,
    @SerialName("medico_nombre") val medicoNombre: String? = null,
)

/**
 * `PUT consultas/{id}/secciones` y `/examen`: lo que se escribió (también lo vaciado) y lo que el servidor no conoce y no escribió.
 * [guardadas] es obligatoria: una respuesta que no la trae no es un éxito (no se sabe qué se escribió).
 */
@Serializable
data class RespuestaEscrituraDto(
    val guardadas: List<String>,
    val desconocidas: List<String> = emptyList(),
)

/** `POST consultas/{id}/cerrar`: la cabecera ya `CERRADA`. */
@Serializable
data class RespuestaCerrarDto(
    val consulta: ConsultaCabeceraDto,
    val cerrada: Boolean,
)

/** `DELETE consultas/{id}`: baja lógica; no trae la cabecera (la lectura ya no muestra una consulta dada de baja). */
@Serializable
data class RespuestaBajaDto(
    val baja: Boolean,
    @SerialName("consulta_id") val consultaId: Long,
)

/**
 * `PUT pacientes/{id}/antecedentes-familiares` (E2): [id] es la fila de la web que se escribió, [guardadas] las claves (también lo vaciado; `nota`
 * si se escribió) y [desconocidas] lo que el servidor no tradujo. [guardadas] es obligatoria: sin ella no se sabe qué se escribió y no es un éxito.
 */
@Serializable
data class RespuestaAntecedentesDto(
    val id: Long? = null,
    val guardadas: List<String>,
    val desconocidas: List<String> = emptyList(),
)

/**
 * `PUT consultas/{id}/registros` (E2): [ids] es `{ref: id de la web}` de todo lo aceptado (también lo que no se registra, como los estudios),
 * [guardadas] los `ref` escritos y [desconocidas] los `ref` de un `tipo` que el servidor no conoce. [ids] y [guardadas] son obligatorios: una
 * respuesta `200` sin ellos no es un éxito. [ids] es un [JsonElement] porque PHP codifica un mapa vacío como `[]`; [idsPorRef] lo lee de las dos formas
 * y tolera el id como número o como texto.
 */
@Serializable
data class RespuestaRegistrosDto(
    val ids: JsonElement,
    val guardadas: List<String>,
    val desconocidas: List<String> = emptyList(),
) {
    /** `ref` -> id de la fila en la web; un id que no es un número se ignora. */
    fun idsPorRef(): Map<String, Long> =
        (ids as? JsonObject)?.mapNotNull { (ref, id) -> (id as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let { ref to it } }?.toMap().orEmpty()
}

/**
 * `PUT pacientes/{id}/laboratorios` y `/pesos` (E3, T81): [id] es el id de la fila de la web (el servidor lo manda como texto),
 * [guardadas] los ids de la app escritos (también los vaciados) y [desconocidas] los que el servidor no tradujo. [id] y
 * [guardadas] son obligatorios: una respuesta `200` sin ellos no es un éxito (no se sabe qué fila ni qué se escribió). El id
 * vacío es el de una respuesta que no hizo pedido (no había nada que mandar).
 */
@Serializable
data class RespuestaCargaDto(
    val id: String,
    val guardadas: List<String>,
    val desconocidas: List<String> = emptyList(),
)

/** `DELETE pacientes/{id}/laboratorios/{cargaId}` y `/pesos/{cargaId}` (E3): la baja lógica y el id de la carga (texto). */
@Serializable
data class RespuestaBajaDeCargaDto(
    val baja: Boolean,
    val id: String,
)

/**
 * Los cuerpos de los pedidos. Puros: sin red ni reloj. Reglas (spec 005):
 * - **Solo lo tocado** (RF-34): una sección o un campo que no se pasa no sale; nunca un `null`.
 * - **El vaciado a propósito sale como `""`** (RF-69): un texto en blanco, y en las medidas del examen también `0.00`. `0` y `0.0`
 *   son datos y salen como vinieron; un texto con contenido sale **tal cual**, sin recortar. Todo va como texto: un número JSON
 *   da `422` y `0.00` llegaría como `0`.
 * - **La fecha y la edad son las que cargó el médico** (RF-14, RF-22), no las del momento del envío: entran como parámetro.
 * - Lo que la web no guarda o esta versión no envía no sale (tabaquismo, frecuencia cardíaca, circunferencia, audio...).
 */
object PayloadsClinica {
    /** Los tipos de consulta que la web crea. */
    private val TIPOS = setOf("control", "telemedicina")

    /** Las ocho secciones de texto de E1 (RF-20). El servidor informa en `desconocidas` cualquier otra, pero la app ni la manda. */
    val SECCIONES_DE_TEXTO = setOf(
        "motivo_consulta", "datos_subjetivos", "datos_objetivos", "conductas", "nota", "habitos", "diuresis_catarsis", "somnia",
    )

    /** Los campos del examen físico que la web guarda (RF-21), con el nombre del contrato. */
    val CAMPOS_DEL_EXAMEN = setOf("peso", "talla", "tension_arterial", "imc", "nota")

    private val MEDIDAS = setOf("peso", "talla", "tension_arterial", "imc")

    // Las claves de repetibilidad (RF-15). Cada operación usa la suya: mezclarlas da `422 datos_invalidos` (motivo `clave_en_uso`).
    // Estables entre reintentos: salen del id local de la consulta.
    fun claveDeCrear(idLocal: String) = "c:$idLocal"
    fun claveDeCierre(idLocal: String) = "c:$idLocal-cierre"
    fun claveDeBaja(idLocal: String) = "c:$idLocal-baja"

    /** `POST consultas`. [edadMostrar] sin contenido no se manda (la consulta queda con la edad vacía). */
    fun crear(clave: String, dispositivo: String, pacienteWebId: Long, tipo: String, fecha: LocalDate, edadMostrar: String?): JsonObject {
        require(tipo in TIPOS) { "La web solo crea consultas de tipo $TIPOS, no '$tipo'" }
        return buildJsonObject {
            put("clave", clave)
            put("dispositivo", dispositivo)
            put("paciente_id", pacienteWebId.toString())
            put("tipo", tipo)
            put("fecha", fecha.toString())
            if (!esVacio(edadMostrar)) put("edad_mostrar", edadMostrar!!)
        }
    }

    /** `PUT consultas/{id}/secciones` con [textos] (sección → texto). `null` si no queda ninguna sección que mandar. */
    fun secciones(textos: Map<String, String>): JsonObject? {
        val aMandar = textos.filterKeys { it in SECCIONES_DE_TEXTO }
        if (aMandar.isEmpty()) return null
        return buildJsonObject {
            put(
                "secciones",
                buildJsonObject {
                    aMandar.forEach { (seccion, texto) -> put(seccion, buildJsonObject { put("texto", textoAMandar(texto)) }) }
                },
            )
        }
    }

    /** `PUT consultas/{id}/examen` con [campos] (nombre del contrato → valor). `null` si no queda ningún campo que mandar. */
    fun examen(campos: Map<String, String>): JsonObject? {
        val aMandar = campos.filterKeys { it in CAMPOS_DEL_EXAMEN }
        if (aMandar.isEmpty()) return null
        return buildJsonObject {
            aMandar.forEach { (campo, valor) ->
                val vacio = if (campo in MEDIDAS) esVacioDeMedida(valor) else esVacio(valor)
                put(campo, if (vacio) "" else valor)
            }
        }
    }

    /** `POST consultas/{id}/cerrar`: la fecha clínica es obligatoria; [edadMostrar] sin contenido no se manda (conserva la que tenía). */
    fun cerrar(clave: String, dispositivo: String, fecha: LocalDate, edadMostrar: String?): JsonObject = buildJsonObject {
        put("clave", clave)
        put("dispositivo", dispositivo)
        put("fecha", fecha.toString())
        if (!esVacio(edadMostrar)) put("edad_mostrar", edadMostrar!!)
    }

    /** `DELETE consultas/{id}`: solo la clave y el dispositivo. */
    fun baja(clave: String, dispositivo: String): JsonObject = buildJsonObject {
        put("clave", clave)
        put("dispositivo", dispositivo)
    }

    // ---------- E2 (spec 005, T61): formularios, antecedentes familiares y registros ----------
    // Todo escalar sale como texto (el servidor lee el JSON crudo y no coacciona), salvo `borrado`, que es un booleano. Ningún tope de largo se aplica
    // acá (T64, a la espera de Q-E2-3): un texto largo sale entero y el servidor lo rechaza con `422 texto_demasiado_largo` si no entra. Quedan afuera,
    // a la espera de las respuestas del usuario: la nota del perfil 4 (Q-E2-1), la baja de un estudio (Q-E2-2), completar el resultado de una fila de
    // otra consulta (Q-E2-4) y `fechaSolicitud` / `numero` / `consulta_respuesta` (Q-E2-5).

    private const val SECCION_ALIMENTACION = "alimentacion"
    private const val SECCION_SEROLOGIAS = "serologias"
    private const val SECCION_CHILD_PUGH = "child_pugh"

    /** Las claves de los formularios de E2 que el servidor conoce, con el nombre de la app (`celiaco` y no la columna `seliaco`). */
    val SECCIONES_DE_FORMULARIOS = setOf(SECCION_ALIMENTACION, SECCION_SEROLOGIAS, SECCION_CHILD_PUGH)
    private val CLAVES_DE_ALIMENTACION = setOf("hiposodica", "diabetes", "celiaco", "otra")
    private val CLAVES_DE_SEROLOGIAS = setOf(
        "igm_hav", "ags_hbv", "ig_anti_hcv", "fan", "anti_lkm", "igg_hav", "ig_anti_core", "ig_anti_s_hbv", "ig_anti_hdv", "asma", "fibrotest", "hiv",
        "ag_e", "anti_ag_e", "ig_anti_hev", "ama",
    )
    private val CLAVES_DE_CHILD_PUGH = setOf("ascitis", "bilirrubina", "albumina", "tp", "encefalopatia")

    /**
     * Las quince casillas de los antecedentes familiares que la web guarda, con el nombre de la app (`dependiente_terceros`; la columna se llama
     * `dependientes_terceros`). Sin el tabaquismo (RF-3, D9) y sin `nota_perfil4` (Q-E2-1): lo que no figura acá no viaja.
     */
    val CASILLAS_DE_FAMILIARES = setOf(
        "hta", "dbt_nir", "dbt_ir", "acv", "dsl", "fa", "mp", "analfabeto", "postrado", "af_ca_mama", "ca_mama", "institucionalizado", "psicofarmacos",
        "dependiente_terceros", "otro",
    )

    private const val CAMPO_DETALLE = "detalle"
    private val CAMPOS_DEL_EXAMEN_COMPLEMENTARIO = listOf("solicito", "respuesta")
    private val CAMPOS_DE_LA_INTERCONSULTA = listOf("especialista", "solicito", "respuesta")
    private val ID_LOCAL_VALIDO = Regex("^[A-Za-z0-9._-]+\$")
    private const val CLAVE_MAXIMA = 191

    /**
     * La clave de repetibilidad de una fila de registro (RF-15, RF-61): `r:<id local>`, hasta 191 caracteres, solo letras, números, `.`, `_` y `-`
     * después del prefijo (la misma regla del servidor). Estable entre reintentos: sale del id local. Un id que el servidor rechazaría no se arma.
     */
    fun claveDeRegistro(idLocal: String): String {
        val clave = "r:$idLocal"
        require(ID_LOCAL_VALIDO.matches(idLocal) && clave.length <= CLAVE_MAXIMA) { "El id local '$idLocal' no sirve para una clave de registro" }
        return clave
    }

    /**
     * `PUT consultas/{id}/formularios`. [valores] es sección -> (clave -> valor de la app), solo lo tocado: alimentación `"<0|1>|<detalle>"`, serología
     * `SI`/`NO`, Child-Pugh `"1"`..`"3"`. En blanco es el vaciado a propósito y sale `""` (RF-69; el servidor escribe 0, «sin cargar»: nunca `NO`).
     * Lo que el servidor rechazaría (una clave que no es del contrato, un flag que no es 0 ni 1, un valor que no es SI/NO ni un puntaje) no sale.
     * `null` si no queda nada que mandar.
     */
    fun formularios(valores: Map<String, Map<String, String>>): JsonObject? {
        val aMandar = linkedMapOf<String, Map<String, String>>()
        for ((seccion, campos) in valores) {
            val traducidos = linkedMapOf<String, String>()
            for ((clave, valor) in campos) {
                val traducido = valorDeFormulario(seccion, clave, valor)
                if (traducido != null) traducidos[clave] = traducido
            }
            if (traducidos.isNotEmpty()) aMandar[seccion] = traducidos
        }
        if (aMandar.isEmpty()) return null
        return buildJsonObject {
            put(
                "formularios",
                buildJsonObject {
                    aMandar.forEach { (seccion, campos) -> put(seccion, buildJsonObject { campos.forEach { (clave, valor) -> put(clave, valor) } }) }
                },
            )
        }
    }

    /**
     * El valor **canónico** de [clave] de la sección [seccion] de los formularios: lo que `formularios` mandaría (un flag solo se completa con la barra,
     * en blanco es `""`), o `null` si la web no lo entiende (una clave que no es del contrato, un flag que no es 0 ni 1, un valor que no es SI/NO ni un
     * puntaje). Es lo que el plan de envío compara contra lo último que la web confirmó.
     */
    fun valorDeFormulario(seccion: String, clave: String, valor: String): String? = when (seccion) {
        SECCION_ALIMENTACION -> if (clave in CLAVES_DE_ALIMENTACION) alimentacionAMandar(valor) else null
        SECCION_SEROLOGIAS -> if (clave in CLAVES_DE_SEROLOGIAS) serologiaAMandar(valor) else null
        SECCION_CHILD_PUGH -> if (clave in CLAVES_DE_CHILD_PUGH) puntajeAMandar(valor) else null
        else -> null
    }

    /**
     * [valor] (canónico, [valorDeFormulario]) no tiene nada cargado: en blanco o, en alimentación, la casilla sin marcar y sin detalle (`"0|"`). Un vacío
     * sobre nada no es un cambio; sobre algo ya enviado es un vaciado a propósito (RF-69).
     */
    fun esFormularioVacio(seccion: String, valor: String?): Boolean {
        if (esVacio(valor)) return true
        return seccion == SECCION_ALIMENTACION && valor!!.substringBefore("|") == "0" && esVacio(valor.substringAfter("|", ""))
    }

    /** [a] es una casilla de los antecedentes familiares que el contrato escribe: una de las quince o la `nota`. Nunca el tabaquismo, lo personal ni `nota_perfil4`. */
    fun esCasillaDeFamiliares(a: Antecedente): Boolean =
        a.categoria == CATEGORIA_FAMILIARES && (a.clave in CASILLAS_DE_FAMILIARES || a.clave == CLAVE_NOTA)

    /** `"<0|1>|<detalle>"`: el detalle va tal cual. Un flag solo se completa con la barra; un flag que no es 0 ni 1 no se manda. En blanco: `""`. */
    private fun alimentacionAMandar(valor: String): String? {
        if (esVacio(valor)) return ""
        val flag = valor.substringBefore("|")
        if (flag != "0" && flag != "1") return null
        return if ('|' in valor) valor else "$flag|"
    }

    private fun serologiaAMandar(valor: String): String? = when {
        esVacio(valor) -> ""
        valor == "SI" || valor == "NO" -> valor
        else -> null
    }

    private fun puntajeAMandar(valor: String): String? = when {
        esVacio(valor) -> ""
        valor == "1" || valor == "2" || valor == "3" -> valor
        else -> null
    }

    /**
     * `PUT pacientes/{id}/antecedentes-familiares`. De [antecedentes] salen las casillas activas de los familiares que el contrato conoce y la `nota`, solo
     * si **cambian de verdad** contra [enviados] (clave -> lo último que la web confirmó; `cambioRealDeAntecedente`): una casilla vacía sobre nada no es
     * un cambio y una vaciada a propósito sale `flag 0` y detalle `""` (RF-69). Nunca el tabaquismo, los personales, los desactivados (RF-9) ni la
     * `nota_perfil4` (Q-E2-1). [consultaWebId] es la consulta a la que se asocian (RF-68). `null` si no queda nada que mandar.
     */
    fun antecedentes(consultaWebId: Long, antecedentes: List<Antecedente>, enviados: Map<String, Antecedente> = emptyMap()): JsonObject? {
        val casillas = linkedMapOf<String, Pair<String, String>>()
        var nota: String? = null
        for (a in antecedentes) {
            if (!a.activo || a.categoria != CATEGORIA_FAMILIARES || !casillaSeEnvia(a.categoria, a.clave)) continue
            val anterior = enviados[a.clave]
            if (a.clave == CLAVE_NOTA) {
                when (cambioReal(anterior?.detalle, a.detalle)) {
                    CambioReal.NINGUNO -> Unit
                    CambioReal.ESCRIBIR -> nota = a.detalle
                    CambioReal.VACIAR -> nota = ""
                }
                continue
            }
            if (a.clave !in CASILLAS_DE_FAMILIARES) continue
            when (cambioRealDeAntecedente(anterior, a.flag, a.detalle)) {
                CambioReal.NINGUNO -> Unit
                CambioReal.ESCRIBIR -> casillas[a.clave] = (if (a.flag) "1" else "0") to a.detalle
                CambioReal.VACIAR -> casillas[a.clave] = "0" to ""
            }
        }
        if (casillas.isEmpty() && nota == null) return null
        return buildJsonObject {
            put("consulta_id", consultaWebId.toString())
            put(
                "casillas",
                buildJsonObject {
                    casillas.forEach { (clave, c) -> put(clave, buildJsonObject { put("flag", c.first); put(CAMPO_DETALLE, c.second) }) }
                },
            )
            if (nota != null) put("nota", nota)
        }
    }

    /**
     * `PUT consultas/{id}/registros`: `{items: [{ref, tipo, id?, fecha?, campos, borrado?}]}`, atómico en el servidor. De [registros] salen, contra
     * [enviados] (id local -> lo último que la web confirmó), solo los que se escriben o se dan de baja:
     * - **Estudios** (los cinco): con `fecha` obligatoria y `campos.detalle`; en blanco es el vaciado a propósito (`""`, Q-E2-2) y nunca hay baja de un
     *   estudio, tampoco desactivado. Sin fecha, o en blanco sin nada enviado, no sale.
     * - **Exámenes e interconsultas**: solo los campos de texto que cambiaron (sin fecha ni número: Q-E2-5); la **baja** (`borrado`) solo si la web ya
     *   tiene la fila (`remotoId`): si no, no hay nada que dar de baja (RF-70).
     * Lo que no es de E2 y lo que viene de la web (`hcc-`) se descarta. `null` si no queda nada que mandar.
     */
    fun registros(registros: List<RegistroClinico>, enviados: Map<Id, RegistroClinico> = emptyMap()): JsonObject? {
        val items = registros.mapNotNull { itemDeRegistro(it, enviados[it.id]) }
        if (items.isEmpty()) return null
        return buildJsonObject { put("items", JsonArray(items)) }
    }

    private fun itemDeRegistro(r: RegistroClinico, anterior: RegistroClinico?): JsonObject? {
        if (esIdDeLaWeb(r.id)) return null
        val idWeb = r.remotoId.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        return when (r.tipo) {
            in TIPOS_ESTUDIO_WEB -> itemDeEstudio(r, anterior, idWeb)
            TIPO_EXAMEN_COMPLEMENTARIO -> itemDeLista(r, anterior, idWeb, CAMPOS_DEL_EXAMEN_COMPLEMENTARIO)
            TIPO_INTERCONSULTA -> itemDeLista(r, anterior, idWeb, CAMPOS_DE_LA_INTERCONSULTA)
            else -> null
        }
    }

    private fun itemDeEstudio(r: RegistroClinico, anterior: RegistroClinico?, idWeb: String?): JsonObject? {
        if (!r.activo) return null
        val fecha = r.fecha ?: return null
        val detalle = r.campos[CAMPO_DETALLE].orEmpty()
        val cambioDelTexto = cambioReal(anterior?.campos?.get(CAMPO_DETALLE), detalle)
        val cambioDeFecha = anterior != null && anterior.fecha != r.fecha
        if (cambioDelTexto == CambioReal.NINGUNO && !cambioDeFecha) return null
        return buildJsonObject {
            put("ref", claveDeRegistro(r.id))
            put("tipo", r.tipo)
            if (idWeb != null) put("id", idWeb)
            put("fecha", fecha.toString())
            put("campos", buildJsonObject { put(CAMPO_DETALLE, if (esVacio(detalle)) "" else detalle) })
        }
    }

    private fun itemDeLista(r: RegistroClinico, anterior: RegistroClinico?, idWeb: String?, permitidos: List<String>): JsonObject? {
        if (!r.activo) {
            if (r.remotoId.isBlank()) return null
            return buildJsonObject {
                put("ref", claveDeRegistro(r.id))
                put("tipo", r.tipo)
                if (idWeb != null) put("id", idWeb)
                put("campos", buildJsonObject { })
                put("borrado", true)
            }
        }
        val campos = linkedMapOf<String, String>()
        for (campo in permitidos) {
            val nuevo = r.campos[campo].orEmpty()
            when (cambioReal(anterior?.campos?.get(campo), nuevo)) {
                CambioReal.NINGUNO -> Unit
                CambioReal.ESCRIBIR -> campos[campo] = nuevo
                CambioReal.VACIAR -> campos[campo] = ""
            }
        }
        if (campos.isEmpty()) return null
        return buildJsonObject {
            put("ref", claveDeRegistro(r.id))
            put("tipo", r.tipo)
            if (idWeb != null) put("id", idWeb)
            put("campos", buildJsonObject { campos.forEach { (campo, valor) -> put(campo, valor) } })
        }
    }

    // ---------- E3 (spec 005, T81): cargas de laboratorio general y peso ----------
    // Solo `laboratorio_general` y `peso`: el reducido (`laboratorio_reducido`) y los pendientes quedaron fuera de alcance
    // (decisión del usuario, T73). Las claves de `valores` son los ids de la app, tal como los devuelve la lectura (el servidor
    // traduce las columnas de la web con `MapaClinica`): acá no se re-mapea nada y lo que el servidor no conozca va en
    // `desconocidas`, sin escribirse.

    /** Los dos tipos de carga que la API de E3 escribe; cualquier otro (el reducido) no se manda. */
    val TIPOS_DE_CARGA = setOf(TIPO_LABORATORIO_GENERAL, TIPO_PESO)

    /**
     * Las fechas con las que la web dice «sin fecha» y con las que una carga nunca sale (el servidor contesta `422 fecha_invalida`).
     * `0000-00-00` no es una fecha y no puede llegar acá; la app resuelve la fecha de la consulta antes de enviar (T82). Es la misma
     * regla que usa el plan de envío: la constante vive en `hcgeneral/FECHAS_SIN_DATO` (T82-a) y acá queda el alias de T81.
     */
    val FECHAS_SIN_DATO = com.salud360.core.data.hcgeneral.FECHAS_SIN_DATO

    /**
     * La clave de repetibilidad de una carga (RF-15): `l:<id local>`, hasta 191 caracteres, solo letras, números, `.`, `_` y `-` después
     * del prefijo. Estable entre reintentos: sale del id local de la carga. Un id que el servidor rechazaría no se arma.
     */
    fun claveDeCarga(idLocal: String): String = claveDeCargaConSufijo(idLocal, sufijo = "")

    /** La clave de la baja de una carga: `l:<id local>-baja` (el servidor la registra con tipo `baja_carga`). */
    fun claveDeBajaDeCarga(idLocal: String): String = claveDeCargaConSufijo(idLocal, sufijo = "-baja")

    private fun claveDeCargaConSufijo(idLocal: String, sufijo: String): String {
        val clave = "l:$idLocal$sufijo"
        require(ID_LOCAL_VALIDO.matches(idLocal) && clave.length <= CLAVE_MAXIMA) { "El id local '$idLocal' no sirve para una clave de carga" }
        return clave
    }

    /**
     * `PUT pacientes/{id}/laboratorios` ([tipo] `laboratorio_general`) y `PUT pacientes/{id}/pesos` ([tipo] `peso`): una carga de la
     * consulta [consultaWebId] con la [fecha] clínica (siempre presente y nunca el marcador de «sin fecha») y sus valores contra
     * [confirmada] (lo último que la web confirmó de esa carga):
     * - **alta** ([idWeb] nulo): todos los valores con contenido; un vacío sobre nada no viaja y un alta sin ninguno no se arma;
     * - **actualización** ([idWeb] presente, va como `id`): solo los valores tocados (RF-34), y una columna que ya no está es un
     *   vaciado; si no cambia ningún valor y la fecha es la misma, no hay nada que mandar;
     * - el vaciado a propósito sale `""` (RF-69); el texto va tal cual, sin recortar (un valor con contenido no se toca). «En blanco» es
     *   vacío para la app (`esVacio`), así que el servidor recibe `""`, como en E1 y E2.
     * `null` si no queda nada que mandar, si la carga viene de la web (`hcc-`) o si su tipo no es el del pedido. El peso viaja como
     * `valores: {peso}` (el id de la app, no la columna `valor` de la web) y sin `tipo`.
     */
    fun carga(
        clave: String,
        dispositivo: String,
        consultaWebId: Long,
        fecha: LocalDate,
        carga: Laboratorio,
        idWeb: Long? = null,
        confirmada: Laboratorio? = null,
        tipo: String = TIPO_LABORATORIO_GENERAL,
    ): JsonObject? {
        if (tipo !in TIPOS_DE_CARGA || carga.tipo != tipo || esIdDeLaWeb(carga.id)) return null
        require(fecha !in FECHAS_SIN_DATO) { "La carga no se manda con una fecha de «sin fecha» ($fecha): la app resuelve la de la consulta" }
        val valores = valoresAEnviar(carga.valores, confirmada?.valores)
        if (valores.isEmpty() && (idWeb == null || confirmada?.fecha == fecha)) return null
        return buildJsonObject {
            put("clave", clave)
            put("dispositivo", dispositivo)
            put("consulta_id", consultaWebId.toString())
            if (tipo == TIPO_LABORATORIO_GENERAL) put("tipo", tipo)
            if (idWeb != null) put("id", idWeb.toString())
            put("fecha", fecha.toString())
            if (valores.isNotEmpty()) put("valores", buildJsonObject { valores.forEach { (claveValor, valor) -> put(claveValor, valor) } })
        }
    }

    /** `DELETE pacientes/{id}/laboratorios/{cargaId}` y `/pesos/{cargaId}`: la clave de la baja, el dispositivo y la consulta. */
    fun bajaDeCarga(clave: String, dispositivo: String, consultaWebId: Long): JsonObject = buildJsonObject {
        put("clave", clave)
        put("dispositivo", dispositivo)
        put("consulta_id", consultaWebId.toString())
    }

    private fun textoAMandar(texto: String): String = if (esVacio(texto)) "" else texto
}
