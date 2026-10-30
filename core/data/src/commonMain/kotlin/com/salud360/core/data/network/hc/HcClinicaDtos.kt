package com.salud360.core.data.network.hc

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Las tres lecturas de la HC general en hclinica (`md/API_SALUD360_CLINICA.md`, «Consultas (solo lectura)»), tal como
 * las contesta el servidor: solo lectura, sin conversión al modelo (eso es de `LecturaClinica`) y con los valores
 * crudos: la alimentación `"0|detalle"` / `"1|detalle"`, las serologías `"SI"` / `"NO"`, los puntos de Child-Pugh
 * `"1"` a `"3"` y los analitos de laboratorio, como cadenas. Los ids son enteros y los de estudios, exámenes e
 * interconsultas **se repiten entre tablas**: el id local lo arma quien los guarda, con el tipo.
 *
 * Qué puede faltar (Q15 = nunca borrar; RF-55, RF-63): una ausencia nunca puede leerse como «la web ya no lo tiene».
 * Por eso lo que el servidor manda siempre y de lo que se deduce que algo desapareció —las listas, los mapas,
 * `hay_mas`, `antecedentes_familiares` y `pendientes` (que pueden ser `null`, pero la clave tiene que estar)— es
 * obligatorio: si falta, la respuesta es inesperada (`TobbException` `respuesta`) y no se aplica. Pueden faltar lo que
 * solo avisa (`omitidas`, `no_leidas`), lo que un servidor anterior no manda (`created_at`,
 * `antecedentes_familiares_perfil4`) y los campos que el contrato da por opcionales. Lo desconocido se ignora.
 */

/** Una consulta en la lista, y la cabecera de `GET consultas/{id}`. [estado] es `ABIERTA` o `CERRADA`; [propia], si la cargó quien pide. */
@Serializable
data class ConsultaCabeceraDto(
    val id: Long,
    @SerialName("paciente_id") val pacienteId: Long,
    @SerialName("medico_id") val medicoId: Long? = null,
    /** `users.name` de quien la cargó; `null` si el servidor no pudo resolverlo (no se inventa). */
    @SerialName("medico_nombre") val medicoNombre: String? = null,
    /** `control` o `telemedicina`. */
    val tipo: String? = null,
    @SerialName("tipo_numero") val tipoNumero: Int? = null,
    /** `AAAA-MM-DD`: el día de [createdAt], la fecha clínica. */
    val fecha: String,
    /** `AAAA-MM-DD HH:MM:SS`. Lo agrega E4.4: un servidor anterior no lo manda. */
    @SerialName("created_at") val createdAt: String? = null,
    val estado: String,
    /** El texto que guardó la web (`"45 años"`); vacío si no lo tiene. */
    @SerialName("edad_mostrar") val edadMostrar: String = "",
    val propia: Boolean,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Lo que `GET consultas` no manda y cuenta: historia digitalizada, otro tipo y sin fecha. Las dadas de baja no figuran. */
@Serializable
data class OmitidasConsultasDto(
    val foto: Int = 0,
    val otro: Int = 0,
    @SerialName("sin_fecha") val sinFecha: Int = 0,
)

/** `GET consultas?paciente_id=&limite=&desde=`. [hayMas] es el número `0` o `1`, como en la cartera. */
@Serializable
data class RespuestaListaConsultasDto(
    val consultas: List<ConsultaCabeceraDto>,
    val total: Int,
    val desde: Int,
    @SerialName("hay_mas") val hayMas: Int,
    val omitidas: OmitidasConsultasDto = OmitidasConsultasDto(),
) {
    val quedanMas: Boolean get() = hayMas != 0
}

/** Una sección de texto: la `descripcion` de la fila activa de la consulta. */
@Serializable
data class TextoDto(val texto: String)

/**
 * `secciones` de `GET consultas/{id}`: solo viene lo que tiene contenido. Los textos son `{texto}`; los tres formularios
 * son mapas de campo a valor crudo (ver arriba).
 */
@Serializable
data class SeccionesConsultaDto(
    @SerialName("motivo_consulta") val motivoConsulta: TextoDto? = null,
    @SerialName("datos_subjetivos") val datosSubjetivos: TextoDto? = null,
    @SerialName("datos_objetivos") val datosObjetivos: TextoDto? = null,
    val conductas: TextoDto? = null,
    val nota: TextoDto? = null,
    val habitos: TextoDto? = null,
    @SerialName("diuresis_catarsis") val diuresisCatarsis: TextoDto? = null,
    val somnia: TextoDto? = null,
    /** `hiposodica`, `diabetes`, `celiaco`, `otra`: `"<0|1>|<detalle>"`. */
    val alimentacion: Map<String, String> = emptyMap(),
    /** Las 16 de la web: `"SI"` o `"NO"`; la que no se cargó no viene. */
    val serologias: Map<String, String> = emptyMap(),
    /** `ascitis`, `bilirrubina`, `albumina`, `tp`, `encefalopatia`: `"1"` a `"3"`. */
    @SerialName("child_pugh") val childPugh: Map<String, String> = emptyMap(),
) {
    /** Los textos que vinieron, por id de sección del catálogo (`motivo_consulta`, `nota`, ...). */
    fun textos(): Map<String, String> = buildMap {
        motivoConsulta?.let { put("motivo_consulta", it.texto) }
        datosSubjetivos?.let { put("datos_subjetivos", it.texto) }
        datosObjetivos?.let { put("datos_objetivos", it.texto) }
        conductas?.let { put("conductas", it.texto) }
        nota?.let { put("nota", it.texto) }
        habitos?.let { put("habitos", it.texto) }
        diuresisCatarsis?.let { put("diuresis_catarsis", it.texto) }
        somnia?.let { put("somnia", it.texto) }
    }
}

/** `examen_fisico`: texto tal cual (la regla de «sin cargar» es del servidor); `{}` si no hay examen. */
@Serializable
data class ExamenFisicoDto(
    val peso: String? = null,
    val talla: String? = null,
    @SerialName("tension_arterial") val tensionArterial: String? = null,
    val imc: String? = null,
    val nota: String? = null,
)

/** `GET consultas/{id}`. [noLeidas]: lo que la consulta tiene y esta API no devuelve (`documentos`, `audio`, `fotos`, `antecedentes_personales`). */
@Serializable
data class RespuestaConsultaDto(
    val consulta: ConsultaCabeceraDto,
    val secciones: SeccionesConsultaDto,
    @SerialName("examen_fisico") val examenFisico: ExamenFisicoDto,
    @SerialName("no_leidas") val noLeidas: List<String> = emptyList(),
)

/** Una casilla de los antecedentes familiares: [flag] `1` (marcada) o `0`; [detalle] solo si la web lo guardó. */
@Serializable
data class CasillaAntecedenteDto(val flag: Int = 0, val detalle: String? = null)

/**
 * `antecedentes_familiares`: un objeto con una casilla por clave (`hta`, `dbt_ir`, `otro`, ...) y la `nota`, que es
 * texto. Las claves que la app no conoce se guardan igual en [casillas]; un valor que no es ni una casilla ni la nota se
 * ignora.
 */
@Serializable(with = AntecedentesFamiliaresSerializer::class)
data class AntecedentesFamiliaresDto(val casillas: Map<String, CasillaAntecedenteDto>, val nota: String? = null)

/** Lee `antecedentes_familiares`, que mezcla casillas (objetos) y `nota` (texto) en el mismo objeto. Solo lee. */
object AntecedentesFamiliaresSerializer : KSerializer<AntecedentesFamiliaresDto> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AntecedentesFamiliaresDto")

    override fun deserialize(decoder: Decoder): AntecedentesFamiliaresDto {
        val json = decoder as? JsonDecoder ?: throw SerializationException("antecedentes_familiares solo se lee de JSON")
        val obj = json.decodeJsonElement().jsonObject
        val nota = (obj["nota"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val casillas = buildMap {
            for ((clave, valor) in obj) {
                if (clave == "nota" || valor !is JsonObject) continue
                put(clave, json.json.decodeFromJsonElement(CasillaAntecedenteDto.serializer(), valor))
            }
        }
        return AntecedentesFamiliaresDto(casillas, nota)
    }

    override fun serialize(encoder: Encoder, value: AntecedentesFamiliaresDto) {
        throw SerializationException("antecedentes_familiares solo se lee")
    }
}

/** `antecedentes_familiares_perfil4`: la nota de la pestaña del perfil 4 de la web, distinta de la nota de [AntecedentesFamiliaresDto]. */
@Serializable
data class NotaPerfil4Dto(val nota: String)

/** Una carga de laboratorio o de peso: [valores] por id de la app (el servidor traduce las columnas de la web). */
@Serializable
data class CargaLaboratorioDto(
    val id: Long,
    @SerialName("consulta_id") val consultaId: Long? = null,
    val fecha: String? = null,
    val valores: Map<String, String>,
)

@Serializable
data class LaboratoriosDto(
    @SerialName("laboratorio_general") val laboratorioGeneral: List<CargaLaboratorioDto>,
    @SerialName("laboratorio_reducido") val laboratorioReducido: List<CargaLaboratorioDto>,
    val peso: List<CargaLaboratorioDto>,
)

/** Una fila de estudio: [fecha] `null` si la web la guardó vacía; el [id] se repite entre las cinco tablas. */
@Serializable
data class EstudioDto(
    val id: Long,
    @SerialName("consulta_id") val consultaId: Long? = null,
    val fecha: String? = null,
    val detalle: String,
)

@Serializable
data class EstudiosDto(
    @SerialName("eco_abdominal") val ecoAbdominal: List<EstudioDto>,
    val veda: List<EstudioDto>,
    @SerialName("biopsia_hepatica") val biopsiaHepatica: List<EstudioDto>,
    @SerialName("estudios_cardiologicos") val estudiosCardiologicos: List<EstudioDto>,
    @SerialName("tc_rmn") val tcRmn: List<EstudioDto>,
)

/** El motivo de una consulta del paciente, de todas (también las que la lista omite). */
@Serializable
data class MotivoDto(
    val id: Long,
    @SerialName("consulta_id") val consultaId: Long? = null,
    val fecha: String? = null,
    val texto: String,
)

@Serializable
data class ExamenComplementarioDto(
    val id: Long,
    @SerialName("consulta_id") val consultaId: Long? = null,
    val fecha: String? = null,
    val solicito: String? = null,
    /** Solo si la hay. */
    val respuesta: String? = null,
    val numero: Int? = null,
)

@Serializable
data class InterconsultaDto(
    val id: Long,
    @SerialName("consulta_id") val consultaId: Long? = null,
    val fecha: String? = null,
    val especialista: String? = null,
    val solicito: String? = null,
    val respuesta: String? = null,
    val numero: Int? = null,
)

/** Los pendientes del paciente: la última fila activa. */
@Serializable
data class PendienteDto(val id: Long, val texto: String)

/** Cargas de laboratorio o de peso que el servidor no mandó por no tener fecha. */
@Serializable
data class OmitidasHistoriaDto(@SerialName("laboratorio_sin_fecha") val laboratorioSinFecha: Int = 0)

/**
 * `GET pacientes/{id}/historia`: lo que la web guarda por paciente y fecha. [antecedentesFamiliares] y [pendientes] son
 * `null` cuando no hay (la clave siempre está); [antecedentesFamiliaresPerfil4] puede faltar, y es `null` si la tabla del
 * perfil 4 no existe en esa base o no tiene nota.
 */
@Serializable
data class RespuestaHistoriaDto(
    @SerialName("paciente_id") val pacienteId: Long,
    @SerialName("antecedentes_familiares") val antecedentesFamiliares: AntecedentesFamiliaresDto?,
    @SerialName("antecedentes_familiares_perfil4") val antecedentesFamiliaresPerfil4: NotaPerfil4Dto? = null,
    val laboratorios: LaboratoriosDto,
    val estudios: EstudiosDto,
    val motivos: List<MotivoDto>,
    @SerialName("examenes_complementarios") val examenesComplementarios: List<ExamenComplementarioDto>,
    val interconsultas: List<InterconsultaDto>,
    val pendientes: PendienteDto?,
    val omitidas: OmitidasHistoriaDto = OmitidasHistoriaDto(),
)
