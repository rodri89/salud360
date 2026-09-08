package com.salud360.core.model.especialidad

/**
 * Definición declarativa de una historia clínica por especialidad.
 *
 * Cada módulo `especialidades` construye una instancia describiendo sus tipos de consulta,
 * secciones y campos. El módulo `features:hc` renderiza todo genéricamente a partir de esta
 * definición, y las secciones que necesitan una pantalla especial (calendario de vacunas,
 * dibujo sobre silueta, escalas) se registran como [TipoSeccion.CUSTOM] con un `renderKey`.
 */
data class EspecialidadDefinition(
    /** Código estable, coincide con `especialidad` en la base de datos (ej. "pediatria"). */
    val codigo: String,
    val nombre: String,
    /** Nombre del producto original, para migración y referencia ("hcpediatria"). */
    val nombreLegacy: String,
    val tiposConsulta: List<TipoConsultaDef>,
    val secciones: List<SeccionDef>,
    /** Campos extra de la ficha del paciente (se guardan en `paciente_extras`). */
    val fichaExtra: List<CampoDef> = emptyList(),
    /** Antecedentes por categoría (personales, familiares, perinatales...). */
    val antecedentes: List<CategoriaAntecedentes> = emptyList(),
    /** Tablas longitudinales (laboratorios). */
    val laboratorios: List<LaboratorioDef> = emptyList(),
    /** Registros repetibles (interconsultas, estudios, internaciones...). */
    val registros: List<RegistroDef> = emptyList(),
    /** Campos extra del examen físico además de los comunes. */
    val examenFisicoExtra: List<CampoDef> = emptyList(),
    /** Campos comunes del examen físico que esta especialidad muestra. */
    val examenFisicoCampos: Set<CampoExamenFisico> = setOf(
        CampoExamenFisico.PESO, CampoExamenFisico.TALLA, CampoExamenFisico.IMC, CampoExamenFisico.TENSION_ARTERIAL,
    ),
    /** Muestra percentilos junto a peso/talla/PC/IMC (pediatría). */
    val conPercentilos: Boolean = false,
) {
    fun tipoConsulta(codigo: String): TipoConsultaDef? = tiposConsulta.firstOrNull { it.codigo == codigo }
    fun seccion(id: String): SeccionDef? = secciones.firstOrNull { it.id == id }
    fun laboratorio(tipo: String): LaboratorioDef? = laboratorios.firstOrNull { it.tipo == tipo }
    fun registro(tipo: String): RegistroDef? = registros.firstOrNull { it.tipo == tipo }
}

/** Tipo de consulta (control de salud, enfermedad, telemedicina, foto...). */
data class TipoConsultaDef(
    val codigo: String,
    val nombre: String,
    /** Nombre del ícono (se resuelve en la capa de UI). */
    val icono: String,
    /** Secciones que componen la pantalla, en orden. */
    val secciones: List<String>,
    /** Pestañas superiores (datos del paciente, antecedentes...). */
    val pestanias: List<String> = listOf(SeccionesComunes.DATOS_PACIENTE),
    val descripcion: String = "",
)

enum class TipoSeccion {
    /** Formulario de campos ([SeccionDef.campos]). */
    FORM,
    /** Un único texto largo. */
    TEXTO,
    /** Examen físico común + extras. */
    EXAMEN_FISICO,
    /** Tabla longitudinal fecha × analito ([SeccionDef.laboratorioTipo]). */
    LABORATORIO,
    /** Lista de registros repetibles ([SeccionDef.registroTipo]). */
    REGISTROS,
    /** Antecedentes por categoría ([SeccionDef.categoriaAntecedentes]). */
    ANTECEDENTES,
    /** Adjuntos (imágenes / PDF). */
    ARCHIVOS,
    /** Grabación de audio. */
    AUDIO,
    /** Diagnósticos del catálogo del médico. */
    DIAGNOSTICOS,
    /** Formulario que se guarda a nivel paciente (paciente_extras), no por consulta. */
    FORM_PACIENTE,
    /** Pantalla propia registrada por el módulo de especialidad. */
    CUSTOM,
}

data class SeccionDef(
    val id: String,
    val titulo: String,
    val tipo: TipoSeccion,
    val campos: List<CampoDef> = emptyList(),
    val laboratorioTipo: String? = null,
    val registroTipo: String? = null,
    val categoriaAntecedentes: String? = null,
    /** Clave del composable registrado para secciones CUSTOM. */
    val renderKey: String? = null,
    /** Permite adjuntar imágenes dentro de la sección. */
    val conArchivos: Boolean = false,
    val icono: String? = null,
    /** Solo se muestra si se cumple la condición (ej. sexo femenino). */
    val condicion: CondicionSeccion? = null,
    val inicialmenteExpandida: Boolean = true,
)

sealed interface CondicionSeccion {
    data object SoloFemenino : CondicionSeccion
    data object SoloMasculino : CondicionSeccion
    data class EdadMaximaMeses(val meses: Int) : CondicionSeccion
    data class EdadMinimaMeses(val meses: Int) : CondicionSeccion
}

enum class TipoCampo { TEXTO, TEXTO_LARGO, NUMERO, FECHA, SELECT, CHECK, CHECK_DETALLE, SI_NO, SI_NO_DETALLE, RADIO, HORA, ETIQUETA }

data class CampoDef(
    val clave: String,
    val etiqueta: String,
    val tipo: TipoCampo = TipoCampo.TEXTO,
    val opciones: List<String> = emptyList(),
    val unidad: String? = null,
    val placeholder: String? = null,
    val obligatorio: Boolean = false,
    /** Agrupa visualmente campos en una misma fila. */
    val grupo: String? = null,
    /** Ancho relativo en la fila (1 = normal, 2 = doble). */
    val peso: Float = 1f,
)

data class CategoriaAntecedentes(
    val categoria: String,
    val titulo: String,
    val items: List<ItemAntecedente>,
    /** Modo de carga: casillas con detalle, o texto libre acumulativo. */
    val modo: ModoAntecedentes = ModoAntecedentes.CASILLAS,
    val notaLibre: Boolean = true,
)

enum class ModoAntecedentes { CASILLAS, TEXTO_ACUMULATIVO }

data class ItemAntecedente(val clave: String, val etiqueta: String, val soloDetalle: Boolean = false)

data class LaboratorioDef(
    val tipo: String,
    val titulo: String,
    val analitos: List<AnalitoDef>,
)

data class AnalitoDef(val clave: String, val etiqueta: String, val unidad: String? = null, val grupo: String? = null)

data class RegistroDef(
    val tipo: String,
    val titulo: String,
    val tituloSingular: String,
    val campos: List<CampoDef>,
    val conFecha: Boolean = true,
    val conArchivos: Boolean = false,
    /** El registro se asocia al paciente y no a una consulta puntual (ej. screenings). */
    val porPaciente: Boolean = false,
)

/** Identificadores de secciones y tipos de consulta compartidos por varias especialidades. */
object SeccionesComunes {
    const val DATOS_PACIENTE = "datos_paciente"
    const val ANTECEDENTES_PERSONALES = "antecedentes_personales"
    const val ANTECEDENTES_FAMILIARES = "antecedentes_familiares"
    const val MOTIVO_CONSULTA = "motivo_consulta"
    const val EXAMEN_FISICO = "examen_fisico"
    const val EXAMENES_COMPLEMENTARIOS = "examenes_complementarios"
    const val INTERCONSULTA = "interconsulta"
    const val EVOLUCION = "evolucion"
    const val PLAN = "plan"
    const val CONDUCTAS = "conductas"
    const val OBSERVACIONES = "observaciones"
    const val NOTA = "nota"
    const val DATOS_SUBJETIVOS = "datos_subjetivos"
    const val DATOS_OBJETIVOS = "datos_objetivos"
    const val MEDICACION_ACTUAL = "medicacion_actual"
    const val DIAGNOSTICO = "diagnostico"
    const val RESUMEN = "resumen"
    const val AUDIO = "audio"
    const val FOTOS = "fotos"
    const val PENDIENTES = "pendientes"

    const val TIPO_CONTROL = "control"
    const val TIPO_ENFERMEDAD = "enfermedad"
    const val TIPO_TELEMEDICINA = "telemedicina"
    const val TIPO_FOTO = "foto"
}

/** Campos comunes del examen físico que una especialidad puede mostrar. */
enum class CampoExamenFisico {
    PESO, TALLA, IMC, TENSION_ARTERIAL, PERIMETRO_CEFALICO, CIRCUNFERENCIA_ABDOMINAL,
    FRECUENCIA_CARDIACA, TEMPERATURA, SATURACION, IPD, NOTA,
}
