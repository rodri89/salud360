package com.salud360.core.model.turnos

import com.salud360.core.model.Id
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

@Serializable
data class Consultorio(
    val id: Id,
    val nombre: String,
    val direccion: String = "",
    val telefono: String = "",
    val foto: String? = null,
    val colorPrimario: String? = null,
    val colorSecundario: String? = null,
    val activo: Boolean = true,
)

/** Especialidad para la agenda de turnos (catálogo público de turnosonlinebb). */
@Serializable
data class EspecialidadTurnos(
    val id: Id,
    val nombre: String,
    /** Código de la historia clínica asociada, si existe (ej. "pediatria"). */
    val codigoHc: String? = null,
    val color: String? = null,
    val activo: Boolean = true,
)

/** Tipos de turno de turnosonlinebb (`Util::getTipoTurno`). */
@Serializable
enum class TipoTurno(val codigo: Int, val etiqueta: String) {
    CONSULTA(1, "Consulta"),
    VIDEOLLAMADA(2, "Videollamada"),
    CONSULTA_ONLINE(22, "Consulta online"),
    ECOGRAFIA(23, "Ecografías"),
    DEPORTOLOGIA(24, "Deportología"),
    CONSULTA_ECO(25, "Consulta + Ecografía");

    companion object {
        fun porCodigo(codigo: Int): TipoTurno = entries.firstOrNull { it.codigo == codigo } ?: CONSULTA
    }
}

/**
 * Un slot de la plantilla semanal del médico (`horario_medicos`): un registro por horario y día.
 * La duración es implícita: la diferencia entre horarios consecutivos.
 */
@Serializable
data class HorarioMedico(
    val id: Id,
    val medicoId: Id,
    val consultorioId: Id,
    val dia: DayOfWeek,
    val horario: LocalTime,
    /** Puede unirse con el siguiente slot para un primer control doble (módulo 3). */
    val doble: Boolean = false,
    val tipoTurno: TipoTurno = TipoTurno.CONSULTA,
    val validoDesde: LocalDate? = null,
    val validoHasta: LocalDate? = null,
    val activo: Boolean = true,
) {
    fun vigenteEn(fecha: LocalDate): Boolean =
        activo && (validoDesde == null || validoDesde <= fecha) && (validoHasta == null || validoHasta >= fecha)
}

/** Rango "desde/hasta" que se muestra al público (`horario_medico_d_h_s`). */
@Serializable
data class HorarioRango(
    val id: Id,
    val medicoId: Id,
    val consultorioId: Id,
    val dia: DayOfWeek,
    val desde: LocalTime,
    val hasta: LocalTime,
    val tipoTurno: TipoTurno = TipoTurno.CONSULTA,
    val activo: Boolean = true,
)

/** Fecha puntual con horarios propios que reemplazan a la plantilla semanal (`fechas_agregadas`). */
@Serializable
data class FechaAgregada(
    val id: Id,
    val medicoId: Id,
    val consultorioId: Id,
    val fecha: LocalDate,
    val horarios: List<LocalTime>,
    val activo: Boolean = true,
)

@Serializable
enum class EstadoTurno { ACTIVO, CANCELADO, BLOQUEADO }

@Serializable
enum class Asistencia { SIN_MARCAR, ASISTIO, NO_ASISTIO }

/** Turno registrado (`turno_registrados`). */
@Serializable
data class Turno(
    val id: Id,
    val pacienteId: Id?,
    val medicoId: Id,
    val consultorioId: Id,
    val fecha: LocalDate,
    val horario: LocalTime,
    val tipoTurno: TipoTurno = TipoTurno.CONSULTA,
    val estado: EstadoTurno = EstadoTurno.ACTIVO,
    val asistencia: Asistencia = Asistencia.SIN_MARCAR,
    val sobreturno: Boolean = false,
    val primerControl: Boolean = false,
    /** Importe cobrado (módulo 2 "caja"). */
    val caja: Double = 0.0,
    val comentario: String = "",
    /** "Paciente" o nombre del médico/secretaria que lo otorgó. */
    val otorgadoPor: String = "",
    val canceladoPor: String = "",
    val recordatorioEnviado: Boolean = false,
    val pagado: Boolean = false,
    val importeReserva: Double = 0.0,
    val especialidad: String = "",
    /** Datos del paciente desnormalizados para la agenda (evita joins en listados). */
    val pacienteNombre: String = "",
    val pacienteDni: String = "",
    val pacienteTelefono: String = "",
    val pacienteObraSocial: String = "",
)

@Serializable
data class Feriado(val id: Id, val fecha: LocalDate, val descripcion: String = "")

@Serializable
data class ObraSocial(val id: Id, val nombre: String, val activo: Boolean = true)

/** Obra social atendida por un médico, con su diferencial e importe de reserva. */
@Serializable
data class ObraSocialMedico(
    val id: Id,
    val medicoId: Id,
    val obraSocialId: Id,
    val importe: Double = 0.0,
    val importeReserva: Double = 0.0,
    val activo: Boolean = true,
)

/** Módulos habilitables por médico (`modulos`). */
@Serializable
enum class ModuloTurnos(val codigo: Int, val descripcion: String) {
    ACTIVAR_PACIENTE(1, "Activar paciente"),
    CAJA_COMENTARIO(2, "Caja y comentario"),
    PRIMER_CONTROL_DOBLE(3, "Primer control doble"),
    UN_TURNO_POR_MES(4, "Solo un turno por mes"),
    RECETAS(5, "Recetas"),
    VIDEOLLAMADAS(6, "Videollamadas"),
    MERCADO_PAGO(7, "MercadoPago"),
    AFILIADO_OBLIGATORIO(8, "Afiliado obligatorio"),
    VENTANA_DIAS(9, "Ventana de días"),
    EXTRA_TURNO(10, "Extra turno"),
    MOSTRAR_DE_A_DOS(11, "Mostrar turnos de a dos"),
    COBRO_TURNOS_MP(12, "Cobro de turnos con MercadoPago");

    companion object {
        fun porCodigo(codigo: Int): ModuloTurnos? = entries.firstOrNull { it.codigo == codigo }
    }
}

@Serializable
data class MedicoModulo(val medicoId: Id, val modulo: ModuloTurnos, val activo: Boolean = true)

/** Configuración de agenda por médico (`medico_configs` + `medico_primer_controls`). */
@Serializable
data class ConfigAgenda(
    val medicoId: Id,
    /** Días hacia adelante en los que se pueden reservar turnos (default 180). */
    val ventanaDias: Int = 180,
    val valorConsulta: Double = 0.0,
    /** Cupo de primeros controles por día de la semana. */
    val cupoPrimerControl: Map<DayOfWeek, Int> = emptyMap(),
)

@Serializable
data class MensajeEspecial(
    val id: Id,
    val medicoId: Id,
    val titulo: String,
    val descripcion: String,
    val validoDesde: LocalDate? = null,
    val validoHasta: LocalDate? = null,
    val activo: Boolean = true,
)

@Serializable
enum class EstadoReceta(val codigo: Int, val etiqueta: String) {
    SOLICITADA(1, "Solicitada"), CONFIRMADA(2, "Confirmada"), COMPLETA(3, "Completa"),
    RECHAZADA(4, "Rechazada"), ENTREGADA(5, "Entregada"), CANCELADA(6, "Cancelada"), ENVIADA(7, "Enviada");

    companion object { fun porCodigo(c: Int) = entries.firstOrNull { it.codigo == c } ?: SOLICITADA }
}

@Serializable
data class Receta(
    val id: Id,
    val pacienteId: Id,
    val medicoId: Id,
    val consultorioId: Id?,
    val motivo: String,
    val estado: EstadoReceta = EstadoReceta.SOLICITADA,
    val retiraConsultorio: Boolean = false,
    val comentario: String = "",
    val archivos: List<String> = emptyList(),
    val activo: Boolean = true,
    val pacienteNombre: String = "",
    val pacienteDni: String = "",
)

/** Resultado del cálculo de disponibilidad de un día. */
@Serializable
data class SlotAgenda(
    val horario: LocalTime,
    val libre: Boolean,
    val turno: Turno? = null,
    /** Si el módulo "mostrar de a dos" está activo, solo el par vigente es reservable. */
    val reservableOnline: Boolean = true,
    val doble: Boolean = false,
)
