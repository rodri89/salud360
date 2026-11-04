package com.salud360.core.model.hc

import com.salud360.core.model.Id
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Cabecera de una consulta / evolución. Es común a todas las especialidades.
 * El contenido clínico se guarda en secciones ([SeccionValor], [ExamenFisico], [Laboratorio],
 * [RegistroClinico], [Archivo]) para que cada especialidad defina sus propios campos sin
 * necesitar tablas nuevas.
 */
@Serializable
data class Consulta(
    val id: Id,
    val pacienteId: Id,
    val medicoId: Id,
    /** Código de la especialidad de la historia clínica (ej. "pediatria"). */
    val especialidad: String,
    /** Código del tipo de consulta definido por la especialidad (ej. "control", "enfermedad", "telemedicina", "foto"). */
    val tipo: String,
    val fecha: LocalDate,
    val estado: EstadoConsulta = EstadoConsulta.ABIERTA,
    /** Edad del paciente al momento de la consulta, en texto ("3 años, 2 meses y 5 días"). */
    val edadMostrar: String = "",
    val updatedAt: Instant? = null,
    /**
     * Id de esta consulta en la API de la especialidad, vacío mientras todavía no se pudo enviar.
     * La consulta se abre en el dispositivo aunque no haya señal; cuando el envío funciona, la API
     * devuelve su id y se guarda acá. El id local no cambia nunca.
     */
    val remotoId: String = "",
)

@Serializable
enum class EstadoConsulta { ABIERTA, CERRADA, ANULADA }

/**
 * Valor de un campo de una sección de la consulta. Reemplaza a las decenas de tablas
 * `descripcion`/`flag + detalle` de los proyectos originales con un esquema clave-valor.
 * Ej.: seccion = "motivo_consulta", campo = "descripcion", valor = "Control de salud".
 */
@Serializable
data class SeccionValor(
    val consultaId: Id,
    val seccion: String,
    val campo: String,
    val valor: String,
)

/**
 * Examen físico con los campos antropométricos comunes a todas las HC.
 * Los campos específicos (ej. "tiroides" en endocrinología) van en [SeccionValor] con seccion = "examen_fisico".
 */
@Serializable
data class ExamenFisico(
    val consultaId: Id,
    val peso: String = "",
    val pesoPercentil: String = "",
    val talla: String = "",
    val tallaPercentil: String = "",
    val imc: String = "",
    val imcPercentil: String = "",
    val perimetroCefalico: String = "",
    val perimetroCefalicoPercentil: String = "",
    val circunferenciaAbdominal: String = "",
    val tensionArterial: String = "",
    val frecuenciaCardiaca: String = "",
    val temperatura: String = "",
    val saturacion: String = "",
    /** Incremento ponderal diario (pediatría). */
    val ipd: String = "",
    val nota: String = "",
) {
    /** IMC = peso / talla² (talla en metros), truncado a 2 decimales como en `calcularIMC()`. */
    fun calcularImc(): String? {
        val p = peso.replace(',', '.').toDoubleOrNull() ?: return null
        var t = talla.replace(',', '.').toDoubleOrNull() ?: return null
        if (t > 3) t /= 100 // cargada en cm
        if (t <= 0) return null
        val imc = p / (t * t)
        val truncado = kotlin.math.floor(imc * 100) / 100
        return truncado.toString()
    }
}

/**
 * Antecedente del paciente (personal, familiar, perinatal, etc.). Se guarda por paciente y no por
 * consulta, porque en la práctica se acumula a lo largo del tiempo.
 * Ej.: categoria = "familiares", clave = "hta", flag = true, detalle = "Padre".
 */
@Serializable
data class Antecedente(
    val id: Id,
    val pacienteId: Id,
    val especialidad: String,
    val categoria: String,
    val clave: String,
    val flag: Boolean = false,
    val detalle: String = "",
    /** Consulta en la que se cargó o modificó por última vez. */
    val consultaId: Id? = null,
    val activo: Boolean = true,
)

/**
 * Registro repetible dentro de la HC: interconsultas, exámenes complementarios, internaciones,
 * estudios, screenings, hermanos, vacunas antigripales, etc. Los campos varían según el tipo,
 * por eso se guardan como mapa.
 */
@Serializable
data class RegistroClinico(
    val id: Id,
    val pacienteId: Id,
    val consultaId: Id? = null,
    val especialidad: String,
    /** Código del tipo de registro definido por la especialidad (ej. "interconsulta"). */
    val tipo: String,
    val fecha: LocalDate? = null,
    val campos: Map<String, String> = emptyMap(),
    val activo: Boolean = true,
    /**
     * Id de esta fila en la API de la especialidad, vacío mientras todavía no se pudo enviar. Sin él,
     * el segundo envío la duplicaría del otro lado. El id local no cambia nunca.
     */
    val remotoId: String = "",
)

/**
 * Carga de laboratorio o tabla longitudinal (una columna por fecha, una fila por analito).
 * Unifica `labs`, `valores_glucosas`, `laboratorio_generals`, `trombofilias`, etc.
 */
@Serializable
data class Laboratorio(
    val id: Id,
    val pacienteId: Id,
    val consultaId: Id? = null,
    val especialidad: String,
    /** Tipo de tabla (ej. "laboratorio_general", "trombofilia", "glucosa"). */
    val tipo: String,
    val fecha: LocalDate,
    val valores: Map<String, String> = emptyMap(),
    val activo: Boolean = true,
)

/** Archivo adjunto: imagen, PDF o audio. Puede estar en el dispositivo (offline) y/o en el servidor. */
@Serializable
data class Archivo(
    val id: Id,
    val pacienteId: Id,
    val consultaId: Id? = null,
    val registroId: Id? = null,
    val seccion: String,
    val nombre: String,
    val mime: String,
    val tamanioBytes: Long = 0,
    /** Duración en milisegundos para audios. */
    val duracionMs: Long = 0,
    val rutaLocal: String? = null,
    val urlRemota: String? = null,
    val subido: Boolean = false,
    val activo: Boolean = true,
    /** Id de este adjunto en la API de la especialidad. Vacío mientras no se subió. */
    val remotoId: String = "",
) {
    val esImagen: Boolean get() = mime.startsWith("image/")
    val esAudio: Boolean get() = mime.startsWith("audio/")
    val esPdf: Boolean get() = mime == "application/pdf"
}

/** Recordatorio que reaparece al abrir la próxima consulta del paciente. */
@Serializable
data class Pendiente(
    val id: Id,
    val pacienteId: Id,
    val medicoId: Id,
    val texto: String,
    val consultaId: Id? = null,
    val resuelto: Boolean = false,
)

/** Diagnóstico del catálogo del médico (HC hematología) asociado a una consulta. */
@Serializable
data class Diagnostico(val id: Id, val medicoId: Id?, val nombre: String, val activo: Boolean = true)

@Serializable
data class ConsultaDiagnostico(val consultaId: Id, val diagnosticoId: Id)

/** Agenda de especialistas para interconsultas (HC pediatría). */
@Serializable
data class Interconsultor(
    val id: Id,
    val medicoId: Id,
    val nombre: String,
    val apellido: String,
    val especialidad: String,
    val direccion: String = "",
    val telefonoParticular: String = "",
    val telefonoConsultorio: String = "",
    val activo: Boolean = true,
)

/** Vacuna aplicada (calendario nacional o antigripal). */
@Serializable
data class VacunaAplicada(
    val id: Id,
    val pacienteId: Id,
    /** Código de la vacuna del calendario o "antigripal" / "otras". */
    val vacuna: String,
    /** Fila del calendario (edad en meses) o null para antigripal/otras. */
    val edadMeses: Int? = null,
    val aplicada: Boolean = true,
    val fecha: LocalDate? = null,
    val dosis: Int? = null,
    val detalle: String = "",
    val consultaId: Id? = null,
)

/** Preferencia de configuración por médico (ej. secciones visibles, modo de vacunas). */
@Serializable
data class MedicoPreferencia(val medicoId: Id, val clave: String, val valor: String)
