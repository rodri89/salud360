package com.salud360.core.data.repos

import com.salud360.core.data.ahoraMillis
import com.salud360.core.data.flujoLista
import com.salud360.core.data.flujoUno
import com.salud360.core.data.lista
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.data.uno
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.Id
import com.salud360.core.model.newId
import com.salud360.core.model.turnos.Asistencia
import com.salud360.core.model.turnos.ConfigAgenda
import com.salud360.core.model.turnos.Consultorio
import com.salud360.core.model.turnos.EstadoReceta
import com.salud360.core.model.turnos.EstadoTurno
import com.salud360.core.model.turnos.FechaAgregada
import com.salud360.core.model.turnos.Feriado
import com.salud360.core.model.turnos.HorarioMedico
import com.salud360.core.model.turnos.HorarioRango
import com.salud360.core.model.turnos.MedicoModulo
import com.salud360.core.model.turnos.MensajeEspecial
import com.salud360.core.model.turnos.ModuloTurnos
import com.salud360.core.model.turnos.ObraSocial
import com.salud360.core.model.turnos.ObraSocialMedico
import com.salud360.core.model.turnos.Receta
import com.salud360.core.model.turnos.SlotAgenda
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.Turno
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** Resultado de intentar registrar un turno (códigos `turnoRegistrado` de turnosonlinebb). */
sealed interface ResultadoTurno {
    data class Ok(val turno: Turno, val turnoDoble: Turno? = null) : ResultadoTurno
    data object HorarioOcupado : ResultadoTurno
    data object PacienteYaTieneTurnoEseDia : ResultadoTurno
    data object PacienteYaTieneTurnoEsteMes : ResultadoTurno
    data object CupoPrimerControlAgotado : ResultadoTurno
    data object Feriado : ResultadoTurno
    data object HorarioNoVisible : ResultadoTurno
}

/**
 * Agenda de turnos. Reimplementa `createJson`, `validarTurnoLibre`, `registrarTurno`,
 * `registrarSobreturno`, `cancelarTurno` y `bloquarDiaAgendaSemanal` de turnosonlinebb,
 * una sola vez y en Kotlin, para médicos y secretarias.
 */
class TurnosRepository(private val db: Salud360Db) {
    private val q get() = db.turnosQueries

    // ---- consultorios / catálogos ----

    fun observarConsultorios(): Flow<List<Consultorio>> = q.consultorios().flujoLista { it.toModel() }
    suspend fun consultorio(id: Id): Consultorio? = q.consultorioPorId(id).uno { it.toModel() }
    suspend fun consultorios(ids: List<Id>): List<Consultorio> = if (ids.isEmpty()) emptyList() else q.consultoriosPorIds(ids).lista { it.toModel() }
    suspend fun guardarConsultorio(c: Consultorio) = q.upsertConsultorio(c.toRow(ahoraMillis()))

    fun observarFeriados(): Flow<List<Feriado>> = q.feriados().flujoLista { it.toModel() }
    suspend fun esFeriado(fecha: LocalDate): Boolean = q.feriadoEnFecha(fecha.toString()).uno { it } != null
    suspend fun guardarFeriado(f: Feriado) = q.upsertFeriado(f.toRow(ahoraMillis()))
    suspend fun eliminarFeriado(f: Feriado) = q.upsertFeriado(f.toRow(ahoraMillis(), deleted = true))

    fun observarObrasSociales(): Flow<List<ObraSocial>> = q.obrasSociales().flujoLista { it.toModel() }
    suspend fun guardarObraSocial(o: ObraSocial) = q.upsertObraSocial(o.copy(nombre = o.nombre.trim().uppercase()).toRow(ahoraMillis()))
    fun observarObrasSocialesDeMedico(medicoId: Id): Flow<List<ObraSocialMedico>> = q.obrasSocialesDeMedico(medicoId).flujoLista { it.toModel() }
    suspend fun guardarObraSocialMedico(o: ObraSocialMedico) = q.upsertObraSocialMedico(o.toRow(ahoraMillis()))

    fun observarModulos(medicoId: Id): Flow<Set<ModuloTurnos>> =
        q.modulosDeMedico(medicoId).flujoLista { it.toModel() }.map { l -> l.filterNotNull().filter { it.activo }.map { it.modulo }.toSet() }
    suspend fun modulos(medicoId: Id): Set<ModuloTurnos> =
        q.modulosDeMedico(medicoId).lista { it.toModel() }.filterNotNull().filter { it.activo }.map { it.modulo }.toSet()
    suspend fun guardarModulo(medicoId: Id, modulo: ModuloTurnos, activo: Boolean) =
        q.upsertMedicoModulo(MedicoModulo(medicoId, modulo, activo).toRow(ahoraMillis()))

    fun observarConfig(medicoId: Id): Flow<ConfigAgenda> = q.configDeMedico(medicoId).flujoUno { it.toModel() }.map { it ?: ConfigAgenda(medicoId) }
    suspend fun config(medicoId: Id): ConfigAgenda = q.configDeMedico(medicoId).uno { it.toModel() } ?: ConfigAgenda(medicoId)
    suspend fun guardarConfig(c: ConfigAgenda) = q.upsertConfigAgenda(c.toRow(ahoraMillis()))

    fun observarMensajes(medicoId: Id): Flow<List<MensajeEspecial>> = q.mensajesDeMedico(medicoId).flujoLista { it.toModel() }
    suspend fun guardarMensaje(m: MensajeEspecial) = q.upsertMensajeEspecial(m.toRow(ahoraMillis()))

    // ---- horarios ----

    fun observarHorarios(medicoId: Id): Flow<List<HorarioMedico>> = q.horariosDeMedico(medicoId).flujoLista { it.toModel() }
    suspend fun horarios(medicoId: Id): List<HorarioMedico> = q.horariosDeMedico(medicoId).lista { it.toModel() }

    /** Alta de un horario fijo; rechaza duplicados (día + horario + consultorio + tipo). */
    suspend fun agregarHorario(h: HorarioMedico): Boolean {
        val existe = q.horariosDeMedicoDia(h.medicoId, h.consultorioId, h.dia.ordinal + 1L).lista { it.toModel() }
            .any { it.horario == h.horario && it.tipoTurno == h.tipoTurno }
        if (existe) return false
        q.upsertHorario(h.toRow(ahoraMillis()))
        return true
    }

    suspend fun guardarHorario(h: HorarioMedico) = q.upsertHorario(h.toRow(ahoraMillis()))
    suspend fun eliminarHorario(h: HorarioMedico) = q.upsertHorario(h.copy(activo = false).toRow(ahoraMillis()))

    /** Genera slots cada `intervaloMinutos` entre `desde` y `hasta` para un día (`crear_turnos_dia_dh`). */
    suspend fun generarHorarios(medicoId: Id, consultorioId: Id, dia: kotlinx.datetime.DayOfWeek, desde: LocalTime, hasta: LocalTime, intervaloMinutos: Int, tipo: TipoTurno = TipoTurno.CONSULTA): Int {
        var minutos = desde.hour * 60 + desde.minute
        val fin = hasta.hour * 60 + hasta.minute
        var creados = 0
        while (minutos < fin) {
            val h = LocalTime(minutos / 60, minutos % 60)
            if (agregarHorario(HorarioMedico(newId(), medicoId, consultorioId, dia, h, tipoTurno = tipo))) creados++
            minutos += intervaloMinutos
        }
        return creados
    }

    fun observarRangos(medicoId: Id): Flow<List<HorarioRango>> = q.rangosDeMedico(medicoId).flujoLista { it.toModel() }
    suspend fun guardarRango(r: HorarioRango) = q.upsertHorarioRango(r.toRow(ahoraMillis()))

    fun observarFechasAgregadas(medicoId: Id, desde: LocalDate): Flow<List<FechaAgregada>> =
        q.fechasAgregadasDeMedico(medicoId, desde.toString()).flujoLista { it.toModel() }
    suspend fun guardarFechaAgregada(f: FechaAgregada) = q.upsertFechaAgregada(f.toRow(ahoraMillis()))
    suspend fun eliminarFechaAgregada(f: FechaAgregada) = q.upsertFechaAgregada(f.copy(activo = false).toRow(ahoraMillis()))

    // ---- turnos ----

    fun observarTurnosDelDia(medicoId: Id, consultorioId: Id, fecha: LocalDate): Flow<List<Turno>> =
        q.turnosDelDia(medicoId, consultorioId, fecha.toString()).flujoLista { it.toModel() }

    fun observarTurnosEntre(medicoId: Id, desde: LocalDate, hasta: LocalDate): Flow<List<Turno>> =
        q.turnosEntreFechas(medicoId, desde.toString(), hasta.toString()).flujoLista { it.toModel() }

    fun observarTurnosDePaciente(pacienteId: Id): Flow<List<Turno>> = q.turnosDePaciente(pacienteId).flujoLista { it.toModel() }
    suspend fun turno(id: Id): Turno? = q.turnoPorId(id).uno { it.toModel() }

    suspend fun sobreturnosDelDia(medicoId: Id, consultorioId: Id, fecha: LocalDate): Long =
        q.sobreturnosDelDia(medicoId, consultorioId, fecha.toString()).uno { it } ?: 0

    /**
     * Slots del día (equivalente a `createJson`):
     * 1. horarios de la plantilla semanal vigentes en la fecha (o los de la fecha agregada si existe)
     * 2. cruzados con turnos activos/bloqueados
     * 3. feriado → sin turnos
     * 4. módulo "mostrar de a dos" → solo el primer par no ocupado es reservable online
     */
    suspend fun slotsDelDia(medicoId: Id, consultorioId: Id, fecha: LocalDate, tipo: TipoTurno = TipoTurno.CONSULTA): List<SlotAgenda> {
        if (esFeriado(fecha)) return emptyList()
        val modulos = modulos(medicoId)
        val agregada = q.fechaAgregada(medicoId, consultorioId, fecha.toString()).uno { it.toModel() }
        val plantilla = q.horariosDeMedicoDia(medicoId, consultorioId, fecha.dayOfWeek.ordinal + 1L).lista { it.toModel() }
            .filter { it.tipoTurno == tipo && it.vigenteEn(fecha) }
        val horarios: List<Pair<LocalTime, Boolean>> = agregada?.horarios?.map { it to false }
            ?: plantilla.map { it.horario to it.doble }
        val ocupados = q.turnosOcupados(medicoId, consultorioId, fecha.toString()).lista { it.toModel() }.associateBy { it.horario }
        var slots = horarios.sortedBy { it.first }.map { (h, doble) ->
            val t = ocupados[h]
            SlotAgenda(horario = h, libre = t == null, turno = t, doble = doble)
        }
        if (ModuloTurnos.MOSTRAR_DE_A_DOS in modulos) {
            val parActivo = slots.chunked(2).indexOfFirst { par -> par.any { it.libre } }
            slots = slots.mapIndexed { i, s -> s.copy(reservableOnline = parActivo >= 0 && i / 2 == parActivo) }
        }
        return slots
    }

    /** Próximas fechas con al menos un turno libre (máximo `dias` días hacia adelante). */
    suspend fun proximasFechasLibres(medicoId: Id, consultorioId: Id, desde: LocalDate, cantidad: Int = 3, dias: Int = 180): List<LocalDate> {
        val res = mutableListOf<LocalDate>()
        var f = desde
        var i = 0
        while (res.size < cantidad && i < dias) {
            if (slotsDelDia(medicoId, consultorioId, f).any { it.libre }) res += f
            f = f.plus(1, DateTimeUnit.DAY)
            i++
        }
        return res
    }

    /** Registra un turno validando disponibilidad, feriado, mismo día, un turno por mes y cupo de primer control. */
    suspend fun registrarTurno(turno: Turno, validarMismoDia: Boolean = true): ResultadoTurno {
        if (esFeriado(turno.fecha)) return ResultadoTurno.Feriado
        val modulos = modulos(turno.medicoId)
        val ocupados = q.turnosOcupados(turno.medicoId, turno.consultorioId, turno.fecha.toString()).lista { it.toModel() }
        if (ocupados.any { it.horario == turno.horario && it.id != turno.id }) return ResultadoTurno.HorarioOcupado
        val pacienteId = turno.pacienteId
        if (pacienteId != null && validarMismoDia && turno.tipoTurno == TipoTurno.CONSULTA) {
            val mismoDia = q.turnosDePacienteEnFecha(pacienteId, turno.medicoId, turno.fecha.toString()).lista { it.toModel() }
            if (mismoDia.any { it.id != turno.id }) return ResultadoTurno.PacienteYaTieneTurnoEseDia
        }
        if (pacienteId != null && ModuloTurnos.UN_TURNO_POR_MES in modulos) {
            val inicio = LocalDate(turno.fecha.year, turno.fecha.month, 1)
            val fin = inicio.plus(1, DateTimeUnit.MONTH).plus(-1, DateTimeUnit.DAY)
            val enMes = q.turnosDePacienteEnMes(pacienteId, turno.medicoId, inicio.toString(), fin.toString()).uno { it } ?: 0
            if (enMes > 0) return ResultadoTurno.PacienteYaTieneTurnoEsteMes
        }
        if (turno.primerControl) {
            val cupo = config(turno.medicoId).cupoPrimerControl[turno.fecha.dayOfWeek]
            if (cupo != null) {
                val dados = q.primerosControlesDelDia(turno.medicoId, turno.consultorioId, turno.fecha.toString()).uno { it } ?: 0
                if (dados >= cupo) return ResultadoTurno.CupoPrimerControlAgotado
            }
        }
        q.upsertTurno(turno.toRow(ahoraMillis()))
        return ResultadoTurno.Ok(turno)
    }

    /** Primer control doble: reserva dos horarios consecutivos (módulo 3). */
    suspend fun registrarTurnoDoble(turno: Turno, segundoHorario: LocalTime): ResultadoTurno {
        val primero = registrarTurno(turno.copy(primerControl = true))
        if (primero !is ResultadoTurno.Ok) return primero
        val segundo = registrarTurno(turno.copy(id = newId(), horario = segundoHorario, primerControl = true), validarMismoDia = false)
        return if (segundo is ResultadoTurno.Ok) ResultadoTurno.Ok(primero.turno, segundo.turno) else segundo
    }

    /** Sobreturno: no requiere que exista el horario en la plantilla, solo que no esté ocupado. */
    suspend fun registrarSobreturno(turno: Turno): ResultadoTurno {
        val ocupados = q.turnosOcupados(turno.medicoId, turno.consultorioId, turno.fecha.toString()).lista { it.toModel() }
        if (ocupados.any { it.horario == turno.horario }) return ResultadoTurno.HorarioOcupado
        val t = turno.copy(sobreturno = true, primerControl = false)
        q.upsertTurno(t.toRow(ahoraMillis()))
        return ResultadoTurno.Ok(t)
    }

    /** Bloquea un horario sin paciente (`estado = BLOQUEADO`, equivalente a `activo = 2`). */
    suspend fun bloquearHorario(medicoId: Id, consultorioId: Id, fecha: LocalDate, horario: LocalTime, por: String): Turno {
        val t = Turno(newId(), null, medicoId, consultorioId, fecha, horario, estado = EstadoTurno.BLOQUEADO, otorgadoPor = por, comentario = "Bloqueado")
        q.upsertTurno(t.toRow(ahoraMillis()))
        return t
    }

    /** Bloquea el día completo; solo si no hay pacientes con turno ese día. */
    suspend fun bloquearDia(medicoId: Id, consultorioId: Id, fecha: LocalDate, por: String): Boolean {
        val conPacientes = q.turnosDelDia(medicoId, consultorioId, fecha.toString()).lista { it.toModel() }.any { it.pacienteId != null && it.estado == EstadoTurno.ACTIVO }
        if (conPacientes) return false
        val slots = slotsDelDia(medicoId, consultorioId, fecha)
        db.transaction { slots.filter { it.libre }.forEach { s -> q.upsertTurno(Turno(newId(), null, medicoId, consultorioId, fecha, s.horario, estado = EstadoTurno.BLOQUEADO, otorgadoPor = por, comentario = "Día bloqueado").toRow(ahoraMillis())) } }
        return true
    }

    suspend fun cancelarTurno(id: Id, por: String) {
        val t = turno(id) ?: return
        q.upsertTurno(t.copy(estado = EstadoTurno.CANCELADO, canceladoPor = por, comentario = listOf(t.comentario, "Cancelado por $por").filter { it.isNotBlank() }.joinToString(" - ")).toRow(ahoraMillis()))
        // si era primer control doble, cancelar el par del mismo día
        if (t.primerControl && t.pacienteId != null) {
            q.turnosDePacienteEnFecha(t.pacienteId, t.medicoId, t.fecha.toString()).lista { it.toModel() }
                .filter { it.id != t.id && it.primerControl }
                .forEach { par -> q.upsertTurno(par.copy(estado = EstadoTurno.CANCELADO, canceladoPor = por).toRow(ahoraMillis())) }
        }
    }

    suspend fun liberarBloqueo(id: Id) {
        val t = turno(id) ?: return
        if (t.estado == EstadoTurno.BLOQUEADO) q.upsertTurno(t.toRow(ahoraMillis(), deleted = true))
    }

    suspend fun marcarAsistencia(id: Id, asistencia: Asistencia) {
        val t = turno(id) ?: return
        q.upsertTurno(t.copy(asistencia = asistencia).toRow(ahoraMillis()))
    }

    suspend fun actualizarCaja(id: Id, caja: Double) {
        val t = turno(id) ?: return
        q.upsertTurno(t.copy(caja = caja).toRow(ahoraMillis()))
    }

    suspend fun actualizarComentario(id: Id, comentario: String) {
        val t = turno(id) ?: return
        q.upsertTurno(t.copy(comentario = comentario).toRow(ahoraMillis()))
    }

    /** Días hábiles de la semana con al menos un horario vigente en la ventana de reserva (para el calendario). */
    suspend fun diasConAtencion(medicoId: Id, consultorioId: Id, desde: LocalDate): Set<kotlinx.datetime.DayOfWeek> {
        val ventana = config(medicoId).ventanaDias
        val horarios = horarios(medicoId).filter { it.consultorioId == consultorioId }
        val dias = mutableSetOf<kotlinx.datetime.DayOfWeek>()
        var f = desde
        repeat(minOf(ventana, 366)) {
            if (dias.size == 7) return dias
            if (horarios.any { it.dia == f.dayOfWeek && it.vigenteEn(f) }) dias += f.dayOfWeek
            f = f.plus(1, DateTimeUnit.DAY)
        }
        return dias
    }

    // ---- recetas ----

    fun observarRecetasDeMedico(medicoId: Id): Flow<List<Receta>> = q.recetasDeMedico(medicoId).flujoLista { it.toModel() }
    fun observarRecetasDeMedicos(medicoIds: List<Id>): Flow<List<Receta>> =
        if (medicoIds.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList()) else q.recetasDeMedicos(medicoIds).flujoLista { it.toModel() }
    fun observarRecetasPendientes(medicoId: Id): Flow<Long> = q.recetasPendientesDeMedico(medicoId).flujoUno { it }.map { it ?: 0L }
    suspend fun guardarReceta(r: Receta) = q.upsertReceta(r.toRow(ahoraMillis()))
    suspend fun cambiarEstadoReceta(id: Id, estado: EstadoReceta) {
        val r = q.recetaPorId(id).uno { it.toModel() } ?: return
        q.upsertReceta(r.copy(estado = estado).toRow(ahoraMillis()))
    }
}
