package com.salud360.core.data.network.hc

import com.salud360.core.data.hcgeneral.esVacio
import com.salud360.core.data.hcgeneral.esVacioDeMedida
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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

    private fun textoAMandar(texto: String): String = if (esVacio(texto)) "" else texto
}
