package com.salud360.core.model.pacientes

import com.salud360.core.model.Id
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.serialization.Serializable

/**
 * Ficha unificada del paciente. Es la unión de las tablas `pacientes` de los siete proyectos:
 * datos básicos (todos), obra social opcional (hematología), padres/hermanos (pediatría y
 * desarrollo infantil), familiar de contacto (endocrinología), nacionalidad (desarrollo infantil)
 * y penalización por inasistencia (turnos).
 * Los datos propios de una sola especialidad que no están acá se guardan en [PacienteExtra].
 */
@Serializable
data class Paciente(
    val id: Id,
    val dni: String,
    val nombre: String,
    val apellido: String,
    val sexo: Sexo? = null,
    val fechaNacimiento: LocalDate? = null,
    val telefono: String = "",
    val mail: String = "",
    val domicilio: String = "",
    val localidad: String = "",
    val nacionalidad: String = "",
    // Obra social principal
    val obraSocial: String = "",
    val numeroAfiliado: String = "",
    val obraSocialPlan: String = "",
    val obraSocialFoto: String? = null,
    // Segunda obra social (HC hematología)
    val obraSocialOpcional: String = "",
    val numeroAfiliadoOpcional: String = "",
    val obraSocialPlanOpcional: String = "",
    // Familia (pediatría / desarrollo infantil / endocrinología)
    val nombreMadre: String = "",
    val telefonoMadre: String = "",
    val nombrePadre: String = "",
    val telefonoPadre: String = "",
    val cantidadHermanos: Int? = null,
    val nombreFamiliar: String = "",
    val telefonoFamiliar: String = "",
    // Turnos
    val fechaCastigo: LocalDate? = null,
    val afiliadoObligatorio: Boolean = false,
    /** false = pendiente de activación por el médico (módulo "Activar paciente" de turnos). */
    val activo: Boolean = true,
    /**
     * Nota libre de uso interno del consultorio, visible en la agenda y en el listado
     * (ej: "no cobrar, es familiar del médico"). Se guarda también en turnosonlinebb.
     */
    val nota: String = "",
) {
    val nombreCompleto: String get() = "$apellido, $nombre"

    /** Edad en años, meses y días a una fecha dada (misma lógica que `calcularEdad` de las apps). */
    fun edad(hoy: LocalDate): Edad? = fechaNacimiento?.let { Edad.entre(it, hoy) }
}

@Serializable
enum class Sexo(val etiqueta: String) { M("Masculino"), F("Femenino"), X("Otro") }

@Serializable
data class Edad(val anios: Int, val meses: Int, val dias: Int) {
    val totalMeses: Int get() = anios * 12 + meses

    override fun toString(): String = buildString {
        if (anios > 0) append("$anios ${if (anios == 1) "año" else "años"}")
        if (meses > 0) { if (isNotEmpty()) append(", "); append("$meses ${if (meses == 1) "mes" else "meses"}") }
        if (dias > 0 || isEmpty()) { if (isNotEmpty()) append(" y "); append("$dias ${if (dias == 1) "día" else "días"}") }
    }

    companion object {
        fun entre(desde: LocalDate, hasta: LocalDate): Edad {
            var anios = hasta.year - desde.year
            var meses = hasta.month.number - desde.month.number
            var dias = hasta.day - desde.day
            if (dias < 0) {
                meses -= 1
                // días del mes anterior a `hasta`
                val mesAnterior = if (hasta.month.number == 1) 12 else hasta.month.number - 1
                val anioAnterior = if (hasta.month.number == 1) hasta.year - 1 else hasta.year
                dias += diasEnMes(anioAnterior, mesAnterior)
            }
            if (meses < 0) { anios -= 1; meses += 12 }
            return Edad(maxOf(anios, 0), maxOf(meses, 0), maxOf(dias, 0))
        }

        private fun diasEnMes(anio: Int, mes: Int): Int = when (mes) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            else -> if ((anio % 4 == 0 && anio % 100 != 0) || anio % 400 == 0) 29 else 28
        }
    }
}

/** Dato adicional del paciente propio de una especialidad (ej. "convivientes" en desarrollo infantil). */
@Serializable
data class PacienteExtra(
    val pacienteId: Id,
    val especialidad: String,
    val clave: String,
    val valor: String,
)

/** Vínculo médico ↔ paciente (cartera de pacientes de cada médico). */
@Serializable
data class MedicoPaciente(
    val medicoId: Id,
    val pacienteId: Id,
    val bloqueado: Boolean = false,
    val activo: Boolean = true,
)
