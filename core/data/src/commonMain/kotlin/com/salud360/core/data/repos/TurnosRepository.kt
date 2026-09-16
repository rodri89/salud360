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
import com.salud360.core.model.TobbIds
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
import com.salud360.core.model.pacientes.Paciente
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
    /** Error de comunicación o rechazo de turnosonlinebb que no encaja en los anteriores. */
    data class Error(val mensaje: String) : ResultadoTurno
}

/**
 * Agenda de turnos para médicos y secretarias.
 *
 * Hay dos clases de médicos:
 * - **Médicos de turnosonlinebb** (ids `tobb-m…`, importados al iniciar sesión): la agenda vive en la base MySQL
 *   de la web y cada operación se hace contra su API mediante [AgendaTurnosOnline]; la base local es solo caché.
 * - **Médicos propios de Salud 360**: la agenda es local y se sincroniza con el servidor. Para ellos este
 *   repositorio reimplementa `createJson`, `validarTurnoLibre`, `registrarTurno`, `registrarSobreturno`,
 *   `cancelarTurno` y `bloquarDiaAgendaSemanal` de turnosonlinebb en Kotlin.
 */
class TurnosRepository(private val db: Salud360Db, private val online: AgendaTurnosOnline? = null) {
    private val q get() = db.turnosQueries

    /** true si la agenda de este médico se gestiona en turnosonlinebb. */
    fun esRemota(medicoId: Id): Boolean = online != null && TobbIds.esTobb(medicoId)
    private fun remota(medicoId: Id): AgendaTurnosOnline? = online?.takeIf { TobbIds.esTobb(medicoId) }

    /**
     * Trae de turnosonlinebb la agenda del día y la deja en la caché local (los flujos `observar…` se actualizan solos).
     * Para médicos locales no hace nada y devuelve null.
     */
    suspend fun refrescarDia(medicoId: Id, consultorioId: Id, fecha: LocalDate): DiaAgendaResuelto? = remota(medicoId)?.dia(medicoId, consultorioId, fecha)

    /** Búsqueda de pacientes en turnosonlinebb (se guardan en la base local). Null si el médico es local. */
    suspend fun buscarPacientesRemotos(medicoId: Id, texto: String): List<Paciente>? = remota(medicoId)?.buscarPacientes(texto)

    /** Trae y vincula localmente todos los pacientes de este médico en turnosonlinebb. Null si el médico es local. */
    suspend fun sincronizarPacientesVinculados(medicoId: Id): List<Paciente>? = remota(medicoId)?.pacientesVinculados(medicoId)

    // ---- consultorios / catálogos ----

    fun observarConsultorios(): Flow<List<Consultorio>> = q.consultorios().flujoLista { it.toModel() }
    suspend fun consultorio(id: Id): Consultorio? = q.consultorioPorId(id).uno { it.toModel() }
    suspend fun consultorios(ids: List<Id>): List<Consultorio> = if (ids.isEmpty()) emptyList() else q.consultoriosPorIds(ids).lista { it.toModel() }
    suspend fun guardarConsultorio(c: Consultorio) = q.upsertConsultorio(c.toRow(ahoraMillis()))

    fun observarFeriados(): Flow<List<Feriado>> = q.feriados().flujoLista { it.toModel() }
    suspend fun esFeriado(fecha: LocalDate): Boolean = online?.esFeriadoCacheado(fecha) == true || q.feriadoEnFecha(fecha.toString()).uno { it } != null
    suspend fun guardarFeriado(f: Feriado) = q.upsertFeriado(f.toRow(ahoraMillis()))
    suspend fun eliminarFeriado(f: Feriado) = q.upsertFeriado(f.toRow(ahoraMillis(), deleted = true))

    fun observarObrasSociales(): Flow<List<ObraSocial>> = q.obrasSociales().flujoLista { it.toModel() }
    suspend fun guardarObraSocial(o: ObraSocial) = q.upsertObraSocial(o.copy(nombre = o.nombre.trim().uppercase()).toRow(ahoraMillis()))
    fun observarObrasSocialesDeMedico(medicoId: Id): Flow<List<ObraSocialMedico>> = q.obrasSocialesDeMedico(medicoId).flujoLista { it.toModel() }

    /** Trae de turnosonlinebb el catálogo de obras sociales y las del médico (caché local). Para médicos locales no hace nada. */
    suspend fun sincronizarObrasSociales(medicoId: Id) { remota(medicoId)?.sincronizarObrasSociales(medicoId) }

    /** Guarda el vínculo médico–obra social; para médicos de turnosonlinebb (y obras sociales de la web) se guarda en la web. */
    suspend fun guardarObraSocialMedico(o: ObraSocialMedico) {
        val r = remota(o.medicoId)?.takeIf { TobbIds.esTobb(o.obraSocialId) }
        if (r != null) r.guardarObraSocialMedico(o) else q.upsertObraSocialMedico(o.toRow(ahoraMillis()))
    }

    fun observarModulos(medicoId: Id): Flow<Set<ModuloTurnos>> =
        q.modulosDeMedico(medicoId).flujoLista { it.toModel() }.map { l -> l.filterNotNull().filter { it.activo }.map { it.modulo }.toSet() }
    suspend fun modulos(medicoId: Id): Set<ModuloTurnos> =
        q.modulosDeMedico(medicoId).lista { it.toModel() }.filterNotNull().filter { it.activo }.map { it.modulo }.toSet()
    suspend fun guardarModulo(medicoId: Id, modulo: ModuloTurnos, activo: Boolean) =
        q.upsertMedicoModulo(MedicoModulo(medicoId, modulo, activo).toRow(ahoraMillis()))

    fun observarConfig(medicoId: Id): Flow<ConfigAgenda> = q.configDeMedico(medicoId).flujoUno { it.toModel() }.map { it ?: ConfigAgenda(medicoId) }
    suspend fun config(medicoId: Id): ConfigAgenda = q.configDeMedico(medicoId).uno { it.toModel() } ?: ConfigAgenda(medicoId)

    /** Refresca desde turnosonlinebb cupo, ventana de días y mensajes del médico. Para médicos locales no hace nada. */
    suspend fun sincronizarConfig(medicoId: Id) { remota(medicoId)?.sincronizarConfig(medicoId) }

    /** Guarda la configuración; para médicos de turnosonlinebb la ventana y el cupo se guardan también en la web. */
    suspend fun guardarConfig(c: ConfigAgenda) {
        val r = remota(c.medicoId)
        if (r != null) r.guardarConfig(c) else q.upsertConfigAgenda(c.toRow(ahoraMillis()))
    }

    fun observarMensajes(medicoId: Id): Flow<List<MensajeEspecial>> = q.mensajesDeMedico(medicoId).flujoLista { it.toModel() }

    /** Crea o actualiza un mensaje para pacientes; para médicos de turnosonlinebb se guarda en la web. */
    suspend fun guardarMensaje(m: MensajeEspecial) {
        val r = remota(m.medicoId)
        if (r != null) r.guardarMensaje(m) else q.upsertMensajeEspecial(m.toRow(ahoraMillis()))
    }
    suspend fun eliminarMensaje(m: MensajeEspecial) {
        val r = remota(m.medicoId)
        if (r != null) r.eliminarMensaje(m) else q.upsertMensajeEspecial(m.toRow(ahoraMillis(), deleted = true))
    }

    // ---- horarios ----

    fun observarHorarios(medicoId: Id): Flow<List<HorarioMedico>> = q.horariosDeMedico(medicoId).flujoLista { it.toModel() }
    suspend fun horarios(medicoId: Id): List<HorarioMedico> = q.horariosDeMedico(medicoId).lista { it.toModel() }

    /**
     * Trae de turnosonlinebb la plantilla semanal y las fechas especiales del médico y las deja en la caché local
     * (los flujos `observar…` se actualizan solos). Para médicos locales no hace nada.
     */
    suspend fun sincronizarHorarios(medicoId: Id, consultorioId: Id) { remota(medicoId)?.sincronizarHorarios(medicoId, consultorioId) }

    /** Alta de un horario fijo; rechaza duplicados (día + horario + consultorio + tipo). En médicos de turnosonlinebb se crea en la web. */
    suspend fun agregarHorario(h: HorarioMedico): Boolean {
        val existe = q.horariosDeMedicoDia(h.medicoId, h.consultorioId, h.dia.ordinal + 1L).lista { it.toModel() }
            .any { it.horario == h.horario && it.tipoTurno == h.tipoTurno }
        if (existe) return false
        remota(h.medicoId)?.let { return it.agregarHorario(h) }
        q.upsertHorario(h.toRow(ahoraMillis()))
        return true
    }

    suspend fun guardarHorario(h: HorarioMedico) {
        val r = remota(h.medicoId)?.takeIf { TobbIds.esTobb(h.id) }
        if (r != null) r.guardarHorario(h) else q.upsertHorario(h.toRow(ahoraMillis()))
    }
    suspend fun eliminarHorario(h: HorarioMedico) {
        val r = remota(h.medicoId)?.takeIf { TobbIds.esTobb(h.id) }
        if (r != null) r.eliminarHorario(h) else q.upsertHorario(h.copy(activo = false).toRow(ahoraMillis()))
    }

    /** Genera slots cada `intervaloMinutos` entre `desde` y `hasta` para un día (`crear_turnos_dia_dh`). */
    suspend fun generarHorarios(
        medicoId: Id, consultorioId: Id, dia: kotlinx.datetime.DayOfWeek, desde: LocalTime, hasta: LocalTime, intervaloMinutos: Int,
        tipo: TipoTurno = TipoTurno.CONSULTA, quincenal: Boolean = false, validoDesde: LocalDate? = null, validoHasta: LocalDate? = null,
    ): Int {
        var minutos = desde.hour * 60 + desde.minute
        val fin = hasta.hour * 60 + hasta.minute
        var creados = 0
        while (minutos < fin) {
            val h = LocalTime(minutos / 60, minutos % 60)
            if (agregarHorario(HorarioMedico(newId(), medicoId, consultorioId, dia, h, tipoTurno = tipo, validoDesde = validoDesde, validoHasta = validoHasta, quincenal = quincenal))) creados++
            minutos += intervaloMinutos
        }
        return creados
    }

    fun observarRangos(medicoId: Id): Flow<List<HorarioRango>> = q.rangosDeMedico(medicoId).flujoLista { it.toModel() }
    suspend fun guardarRango(r: HorarioRango) = q.upsertHorarioRango(r.toRow(ahoraMillis()))

    fun observarFechasAgregadas(medicoId: Id, desde: LocalDate): Flow<List<FechaAgregada>> =
        q.fechasAgregadasDeMedico(medicoId, desde.toString()).flujoLista { it.toModel() }
    suspend fun guardarFechaAgregada(f: FechaAgregada) {
        val r = remota(f.medicoId)
        if (r != null) r.agregarFechaEspecial(f) else q.upsertFechaAgregada(f.toRow(ahoraMillis()))
    }
    suspend fun eliminarFechaAgregada(f: FechaAgregada) {
        val r = remota(f.medicoId)?.takeIf { TobbIds.esTobb(f.id) }
        if (r != null) r.eliminarFechaEspecial(f) else q.upsertFechaAgregada(f.copy(activo = false).toRow(ahoraMillis()))
    }

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
        remota(medicoId)?.let { return it.dia(medicoId, consultorioId, fecha, tipo).slots }
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
        remota(medicoId)?.let { return it.proximasFechas(medicoId, consultorioId, desde, cantidad) }
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

    /**
     * Próximos `cantidad` días con horarios cargados a partir de `desde` (agenda semanal, `obtener5Dias`).
     * Para médicos de turnosonlinebb se pide en una sola llamada a la API.
     */
    suspend fun semana(medicoId: Id, consultorioId: Id, desde: LocalDate, cantidad: Int = 5, maxDias: Int = 60): List<DiaAgendaResuelto> {
        remota(medicoId)?.let { return it.semana(medicoId, consultorioId, desde, cantidad) }
        val dias = mutableListOf<DiaAgendaResuelto>()
        var f = desde
        var i = 0
        while (dias.size < cantidad && i < maxDias) {
            val feriado = esFeriado(f)
            val slots = if (feriado) emptyList() else slotsDelDia(medicoId, consultorioId, f)
            if (slots.isNotEmpty()) dias += DiaAgendaResuelto(f, slots, feriado, slots.mapNotNull { it.turno })
            f = f.plus(1, DateTimeUnit.DAY); i++
        }
        return dias
    }

    /** Registra un turno validando disponibilidad, feriado, mismo día, un turno por mes y cupo de primer control. */
    suspend fun registrarTurno(turno: Turno, validarMismoDia: Boolean = true): ResultadoTurno {
        remota(turno.medicoId)?.let { return it.registrar(turno) }
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
        remota(turno.medicoId)?.let { return it.registrar(turno.copy(primerControl = true), segundoHorario) }
        val primero = registrarTurno(turno.copy(primerControl = true))
        if (primero !is ResultadoTurno.Ok) return primero
        val segundo = registrarTurno(turno.copy(id = newId(), horario = segundoHorario, primerControl = true), validarMismoDia = false)
        return if (segundo is ResultadoTurno.Ok) ResultadoTurno.Ok(primero.turno, segundo.turno) else segundo
    }

    /** Sobreturno: no requiere que exista el horario en la plantilla, solo que no esté ocupado. */
    suspend fun registrarSobreturno(turno: Turno): ResultadoTurno {
        remota(turno.medicoId)?.let { return it.sobreturno(turno) }
        val ocupados = q.turnosOcupados(turno.medicoId, turno.consultorioId, turno.fecha.toString()).lista { it.toModel() }
        if (ocupados.any { it.horario == turno.horario }) return ResultadoTurno.HorarioOcupado
        val t = turno.copy(sobreturno = true, primerControl = false)
        q.upsertTurno(t.toRow(ahoraMillis()))
        return ResultadoTurno.Ok(t)
    }

    /** Bloquea un horario sin paciente (`estado = BLOQUEADO`, equivalente a `activo = 2`). */
    suspend fun bloquearHorario(medicoId: Id, consultorioId: Id, fecha: LocalDate, horario: LocalTime, por: String): ResultadoTurno {
        remota(medicoId)?.let { return it.bloquearHorario(medicoId, consultorioId, fecha, horario) }
        val ocupados = q.turnosOcupados(medicoId, consultorioId, fecha.toString()).lista { it.toModel() }
        if (ocupados.any { it.horario == horario }) return ResultadoTurno.HorarioOcupado
        val t = Turno(newId(), null, medicoId, consultorioId, fecha, horario, estado = EstadoTurno.BLOQUEADO, otorgadoPor = por, comentario = "Bloqueado")
        q.upsertTurno(t.toRow(ahoraMillis()))
        return ResultadoTurno.Ok(t)
    }

    /** Bloquea el día completo; solo si no hay pacientes con turno ese día. Devuelve null si salió bien o el motivo del rechazo. */
    suspend fun bloquearDia(medicoId: Id, consultorioId: Id, fecha: LocalDate, por: String): String? {
        remota(medicoId)?.let { return it.bloquearDia(medicoId, consultorioId, fecha) }
        val conPacientes = q.turnosDelDia(medicoId, consultorioId, fecha.toString()).lista { it.toModel() }.any { it.pacienteId != null && it.estado == EstadoTurno.ACTIVO }
        if (conPacientes) return "No se puede bloquear: hay pacientes con turno ese día"
        val slots = slotsDelDia(medicoId, consultorioId, fecha)
        db.transaction { slots.filter { it.libre }.forEach { s -> q.upsertTurno(Turno(newId(), null, medicoId, consultorioId, fecha, s.horario, estado = EstadoTurno.BLOQUEADO, otorgadoPor = por, comentario = "Día bloqueado").toRow(ahoraMillis())) } }
        return null
    }

    suspend fun cancelarTurno(id: Id, por: String) {
        val t = turno(id) ?: return
        remota(t.medicoId)?.let { it.cancelar(id, t.medicoId); return }
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
        remota(t.medicoId)?.let { it.cancelar(id, t.medicoId); return }
        if (t.estado == EstadoTurno.BLOQUEADO) q.upsertTurno(t.toRow(ahoraMillis(), deleted = true))
    }

    suspend fun marcarAsistencia(id: Id, asistencia: Asistencia) {
        val t = turno(id) ?: return
        remota(t.medicoId)?.let { it.asistencia(id, t.medicoId, asistencia); return }
        q.upsertTurno(t.copy(asistencia = asistencia).toRow(ahoraMillis()))
    }

    suspend fun actualizarCaja(id: Id, caja: Double) {
        val t = turno(id) ?: return
        remota(t.medicoId)?.let { it.caja(id, t.medicoId, caja); return }
        q.upsertTurno(t.copy(caja = caja).toRow(ahoraMillis()))
    }

    suspend fun actualizarComentario(id: Id, comentario: String) {
        val t = turno(id) ?: return
        remota(t.medicoId)?.let { it.comentario(id, t.medicoId, comentario); return }
        q.upsertTurno(t.copy(comentario = comentario).toRow(ahoraMillis()))
    }

    /** Días hábiles de la semana con al menos un horario vigente en la ventana de reserva (para el calendario). */
    suspend fun diasConAtencion(medicoId: Id, consultorioId: Id, desde: LocalDate): Set<kotlinx.datetime.DayOfWeek> {
        remota(medicoId)?.let { return it.diasAtencion(medicoId, consultorioId) }
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
