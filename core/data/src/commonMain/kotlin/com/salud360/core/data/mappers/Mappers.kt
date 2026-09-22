package com.salud360.core.data.mappers

import com.salud360.core.database.Antecedente as AntecedenteRow
import com.salud360.core.database.Archivo as ArchivoRow
import com.salud360.core.database.Config_agenda as ConfigAgendaRow
import com.salud360.core.database.Consulta as ConsultaRow
import com.salud360.core.database.Consulta_diagnostico as ConsultaDiagnosticoRow
import com.salud360.core.database.Consultorio as ConsultorioRow
import com.salud360.core.database.Diagnostico as DiagnosticoRow
import com.salud360.core.database.Especialidad as EspecialidadRow
import com.salud360.core.database.Examen_fisico as ExamenFisicoRow
import com.salud360.core.database.Fecha_agregada as FechaAgregadaRow
import com.salud360.core.database.Feriado as FeriadoRow
import com.salud360.core.database.Horario as HorarioRow
import com.salud360.core.database.Horario_rango as HorarioRangoRow
import com.salud360.core.database.Interconsultor as InterconsultorRow
import com.salud360.core.database.Laboratorio as LaboratorioRow
import com.salud360.core.database.Licencia as LicenciaRow
import com.salud360.core.database.Medico as MedicoRow
import com.salud360.core.database.Medico_modulo as MedicoModuloRow
import com.salud360.core.database.Medico_paciente as MedicoPacienteRow
import com.salud360.core.database.Medico_preferencia as MedicoPreferenciaRow
import com.salud360.core.database.Mensaje_especial as MensajeEspecialRow
import com.salud360.core.database.Obra_social as ObraSocialRow
import com.salud360.core.database.Obra_social_medico as ObraSocialMedicoRow
import com.salud360.core.database.Paciente as PacienteRow
import com.salud360.core.database.Paciente_extra as PacienteExtraRow
import com.salud360.core.database.Pendiente as PendienteRow
import com.salud360.core.database.Receta as RecetaRow
import com.salud360.core.database.Registro_clinico as RegistroRow
import com.salud360.core.database.Seccion_valor as SeccionValorRow
import com.salud360.core.database.Secretaria as SecretariaRow
import com.salud360.core.database.Turno as TurnoRow
import com.salud360.core.database.Usuario as UsuarioRow
import com.salud360.core.database.Vacuna_aplicada as VacunaRow
import com.salud360.core.model.auth.Licencia
import com.salud360.core.model.auth.Medico
import com.salud360.core.model.auth.Rol
import com.salud360.core.model.auth.Secretaria
import com.salud360.core.model.auth.Usuario
import com.salud360.core.model.hc.Antecedente
import com.salud360.core.model.hc.Archivo
import com.salud360.core.model.hc.Consulta
import com.salud360.core.model.hc.ConsultaDiagnostico
import com.salud360.core.model.hc.Diagnostico
import com.salud360.core.model.hc.EstadoConsulta
import com.salud360.core.model.hc.ExamenFisico
import com.salud360.core.model.hc.Interconsultor
import com.salud360.core.model.hc.Laboratorio
import com.salud360.core.model.hc.MedicoPreferencia
import com.salud360.core.model.hc.Pendiente
import com.salud360.core.model.hc.RegistroClinico
import com.salud360.core.model.hc.SeccionValor
import com.salud360.core.model.hc.VacunaAplicada
import com.salud360.core.model.pacientes.MedicoPaciente
import com.salud360.core.model.pacientes.Paciente
import com.salud360.core.model.pacientes.PacienteExtra
import com.salud360.core.model.pacientes.Sexo
import com.salud360.core.model.turnos.Asistencia
import com.salud360.core.model.turnos.ConfigAgenda
import com.salud360.core.model.turnos.Consultorio
import com.salud360.core.model.turnos.EspecialidadTurnos
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
import com.salud360.core.model.turnos.TipoTurno
import com.salud360.core.model.turnos.Turno
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

// ---------- utilidades ----------

internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

fun Long.b(): Boolean = this != 0L
fun Boolean.l(): Long = if (this) 1L else 0L
fun String?.toLocalDateOrNull(): LocalDate? = this?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
fun String.toLocalTime(): LocalTime = LocalTime.parse(if (length == 5) "$this:00" else this)
fun LocalTime.hhmm(): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
fun Long.toDayOfWeek(): DayOfWeek = DayOfWeek.entries[(toInt() - 1).coerceIn(0, 6)]
fun DayOfWeek.toNumero(): Long = (ordinal + 1).toLong()
fun String.splitCsv(): List<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }
fun List<String>.joinCsv(): String = joinToString(",")
fun Map<String, String>.toJsonText(): String = json.encodeToString(mapSerializer, this)
fun String.toStringMap(): Map<String, String> = runCatching { json.decodeFromString(mapSerializer, this) }.getOrDefault(emptyMap())

// ---------- auth ----------

fun UsuarioRow.toModel() = Usuario(id, email, nombre, apellido, Rol.valueOf(rol), foto, activo.b())
fun Usuario.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    UsuarioRow(id, email, nombre, apellido, rol.name, foto, activo.l(), updatedAt, deleted.l(), dirty.l())

fun MedicoRow.toModel() = Medico(
    id, usuario_id, nombre, apellido, telefono, mail, sexo, foto, consultorio_id, especialidad_id,
    especialidades_hc.splitCsv(), tiene_turnos.b(), visible_en_turnos.b(), activo.b(),
)
fun Medico.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) = MedicoRow(
    id, usuarioId, nombre, apellido, telefono, mail, sexo, foto, consultorioId, especialidadId,
    especialidadesHc.joinCsv(), tieneTurnos.l(), visibleEnTurnos.l(), activo.l(), updatedAt, deleted.l(), dirty.l(),
)

fun SecretariaRow.toModel() = Secretaria(id, usuario_id, nombre, apellido, consultorio_ids.splitCsv(), medico_ids.splitCsv(), activo.b())
fun Secretaria.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    SecretariaRow(id, usuarioId, nombre, apellido, consultorioIds.joinCsv(), medicoIds.joinCsv(), activo.l(), updatedAt, deleted.l(), dirty.l())

fun EspecialidadRow.toModel() = EspecialidadTurnos(id, nombre, codigo_hc, color, activo.b())
fun EspecialidadTurnos.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    EspecialidadRow(id, nombre, codigoHc, color, activo.l(), updatedAt, deleted.l(), dirty.l())

fun LicenciaRow.toModel() = Licencia(medico_id, fecha_expiracion, fecha_aviso, importe, activo.b())
fun Licencia.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    LicenciaRow(medicoId, fechaExpiracion, fechaAviso, importe, activo.l(), updatedAt, deleted.l(), dirty.l())

// ---------- pacientes ----------

fun PacienteRow.toModel() = Paciente(
    id = id, dni = dni, nombre = nombre, apellido = apellido,
    sexo = sexo?.let { s -> Sexo.entries.firstOrNull { it.name == s } },
    fechaNacimiento = fecha_nacimiento.toLocalDateOrNull(),
    telefono = telefono, mail = mail, domicilio = domicilio, localidad = localidad, nacionalidad = nacionalidad,
    obraSocial = obra_social, numeroAfiliado = numero_afiliado, obraSocialPlan = obra_social_plan, obraSocialFoto = obra_social_foto,
    obraSocialOpcional = obra_social_opcional, numeroAfiliadoOpcional = numero_afiliado_opcional, obraSocialPlanOpcional = obra_social_plan_opcional,
    nombreMadre = nombre_madre, telefonoMadre = telefono_madre, nombrePadre = nombre_padre, telefonoPadre = telefono_padre,
    cantidadHermanos = cantidad_hermanos?.toInt(), nombreFamiliar = nombre_familiar, telefonoFamiliar = telefono_familiar,
    fechaCastigo = fecha_castigo.toLocalDateOrNull(), afiliadoObligatorio = afiliado_obligatorio.b(), activo = activo.b(),
    nota = nota,
)
// Los argumentos son POSICIONALES: `nota` va última porque es la última columna de la tabla (ver Pacientes.sq).
fun Paciente.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) = PacienteRow(
    id, dni, nombre, apellido, sexo?.name, fechaNacimiento?.toString(), telefono, mail, domicilio, localidad, nacionalidad,
    obraSocial, numeroAfiliado, obraSocialPlan, obraSocialFoto, obraSocialOpcional, numeroAfiliadoOpcional, obraSocialPlanOpcional,
    nombreMadre, telefonoMadre, nombrePadre, telefonoPadre, cantidadHermanos?.toLong(), nombreFamiliar, telefonoFamiliar,
    fechaCastigo?.toString(), afiliadoObligatorio.l(), activo.l(), updatedAt, deleted.l(), dirty.l(), nota,
)

fun PacienteExtraRow.toModel() = PacienteExtra(paciente_id, especialidad, clave, valor)
fun PacienteExtra.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    PacienteExtraRow(pacienteId, especialidad, clave, valor, updatedAt, deleted.l(), dirty.l())

fun MedicoPacienteRow.toModel() = MedicoPaciente(medico_id, paciente_id, bloqueado.b(), activo.b())
fun MedicoPaciente.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    MedicoPacienteRow(medicoId, pacienteId, bloqueado.l(), activo.l(), updatedAt, deleted.l(), dirty.l())

// ---------- historia clínica ----------

fun ConsultaRow.toModel() = Consulta(
    id, paciente_id, medico_id, especialidad, tipo, LocalDate.parse(fecha), EstadoConsulta.valueOf(estado), edad_mostrar,
    Instant.fromEpochMilliseconds(updated_at),
)
fun Consulta.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    ConsultaRow(id, pacienteId, medicoId, especialidad, tipo, fecha.toString(), estado.name, edadMostrar, updatedAt, deleted.l(), dirty.l())

fun SeccionValorRow.toModel() = SeccionValor(consulta_id, seccion, campo, valor)
fun SeccionValor.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    SeccionValorRow(consultaId, seccion, campo, valor, updatedAt, deleted.l(), dirty.l())

fun ExamenFisicoRow.toModel() = ExamenFisico(
    consulta_id, peso, peso_percentil, talla, talla_percentil, imc, imc_percentil, perimetro_cefalico, perimetro_cefalico_percentil,
    circunferencia_abdominal, tension_arterial, frecuencia_cardiaca, temperatura, saturacion, ipd, nota,
)
fun ExamenFisico.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) = ExamenFisicoRow(
    consultaId, peso, pesoPercentil, talla, tallaPercentil, imc, imcPercentil, perimetroCefalico, perimetroCefalicoPercentil,
    circunferenciaAbdominal, tensionArterial, frecuenciaCardiaca, temperatura, saturacion, ipd, nota, updatedAt, deleted.l(), dirty.l(),
)

fun AntecedenteRow.toModel() = Antecedente(id, paciente_id, especialidad, categoria, clave, flag.b(), detalle, consulta_id, activo.b())
fun Antecedente.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    AntecedenteRow(id, pacienteId, especialidad, categoria, clave, flag.l(), detalle, consultaId, activo.l(), updatedAt, deleted.l(), dirty.l())

fun RegistroRow.toModel() = RegistroClinico(id, paciente_id, consulta_id, especialidad, tipo, fecha.toLocalDateOrNull(), campos_json.toStringMap(), activo.b())
fun RegistroClinico.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    RegistroRow(id, pacienteId, consultaId, especialidad, tipo, fecha?.toString(), campos.toJsonText(), activo.l(), updatedAt, deleted.l(), dirty.l())

fun LaboratorioRow.toModel() = Laboratorio(id, paciente_id, consulta_id, especialidad, tipo, LocalDate.parse(fecha), valores_json.toStringMap(), activo.b())
fun Laboratorio.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    LaboratorioRow(id, pacienteId, consultaId, especialidad, tipo, fecha.toString(), valores.toJsonText(), activo.l(), updatedAt, deleted.l(), dirty.l())

fun ArchivoRow.toModel() = Archivo(id, paciente_id, consulta_id, registro_id, seccion, nombre, mime, tamanio_bytes, duracion_ms, ruta_local, url_remota, subido.b(), activo.b())
fun Archivo.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    ArchivoRow(id, pacienteId, consultaId, registroId, seccion, nombre, mime, tamanioBytes, duracionMs, rutaLocal, urlRemota, subido.l(), activo.l(), updatedAt, deleted.l(), dirty.l())

fun PendienteRow.toModel() = Pendiente(id, paciente_id, medico_id, texto, consulta_id, resuelto.b())
fun Pendiente.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    PendienteRow(id, pacienteId, medicoId, texto, consultaId, resuelto.l(), updatedAt, deleted.l(), dirty.l())

fun DiagnosticoRow.toModel() = Diagnostico(id, medico_id, nombre, activo.b())
fun Diagnostico.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    DiagnosticoRow(id, medicoId, nombre, activo.l(), updatedAt, deleted.l(), dirty.l())

fun ConsultaDiagnosticoRow.toModel() = ConsultaDiagnostico(consulta_id, diagnostico_id)
fun ConsultaDiagnostico.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    ConsultaDiagnosticoRow(consultaId, diagnosticoId, updatedAt, deleted.l(), dirty.l())

fun InterconsultorRow.toModel() = Interconsultor(id, medico_id, nombre, apellido, especialidad, direccion, telefono_particular, telefono_consultorio, activo.b())
fun Interconsultor.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    InterconsultorRow(id, medicoId, nombre, apellido, especialidad, direccion, telefonoParticular, telefonoConsultorio, activo.l(), updatedAt, deleted.l(), dirty.l())

fun VacunaRow.toModel() = VacunaAplicada(id, paciente_id, vacuna, edad_meses?.toInt(), aplicada.b(), fecha.toLocalDateOrNull(), dosis?.toInt(), detalle, consulta_id)
fun VacunaAplicada.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    VacunaRow(id, pacienteId, vacuna, edadMeses?.toLong(), aplicada.l(), fecha?.toString(), dosis?.toLong(), detalle, consultaId, updatedAt, deleted.l(), dirty.l())

fun MedicoPreferenciaRow.toModel() = MedicoPreferencia(medico_id, clave, valor)
fun MedicoPreferencia.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    MedicoPreferenciaRow(medicoId, clave, valor, updatedAt, deleted.l(), dirty.l())

// ---------- turnos ----------

fun ConsultorioRow.toModel() = Consultorio(id, nombre, direccion, telefono, foto, color_primario, color_secundario, activo.b())
fun Consultorio.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    ConsultorioRow(id, nombre, direccion, telefono, foto, colorPrimario, colorSecundario, activo.l(), updatedAt, deleted.l(), dirty.l())

fun HorarioRow.toModel() = HorarioMedico(
    id, medico_id, consultorio_id, dia.toDayOfWeek(), horario.toLocalTime(), doble.b(), TipoTurno.porCodigo(tipo_turno.toInt()),
    valido_desde.toLocalDateOrNull(), valido_hasta.toLocalDateOrNull(), activo.b(), quincenal.b(),
)
fun HorarioMedico.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) = HorarioRow(
    id, medicoId, consultorioId, dia.toNumero(), horario.hhmm(), doble.l(), tipoTurno.codigo.toLong(),
    validoDesde?.toString(), validoHasta?.toString(), activo.l(), updatedAt, deleted.l(), dirty.l(), quincenal.l(),
)

fun HorarioRangoRow.toModel() = HorarioRango(id, medico_id, consultorio_id, dia.toDayOfWeek(), desde.toLocalTime(), hasta.toLocalTime(), TipoTurno.porCodigo(tipo_turno.toInt()), activo.b())
fun HorarioRango.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    HorarioRangoRow(id, medicoId, consultorioId, dia.toNumero(), desde.hhmm(), hasta.hhmm(), tipoTurno.codigo.toLong(), activo.l(), updatedAt, deleted.l(), dirty.l())

fun FechaAgregadaRow.toModel() = FechaAgregada(id, medico_id, consultorio_id, LocalDate.parse(fecha), horarios.splitCsv().map { it.toLocalTime() }, activo.b())
fun FechaAgregada.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    FechaAgregadaRow(id, medicoId, consultorioId, fecha.toString(), horarios.map { it.hhmm() }.joinCsv(), activo.l(), updatedAt, deleted.l(), dirty.l())

fun TurnoRow.toModel() = Turno(
    id, paciente_id, medico_id, consultorio_id, LocalDate.parse(fecha), horario.toLocalTime(), TipoTurno.porCodigo(tipo_turno.toInt()),
    EstadoTurno.valueOf(estado), Asistencia.valueOf(asistencia), sobreturno.b(), primer_control.b(), caja, comentario, otorgado_por,
    cancelado_por, recordatorio_enviado.b(), pagado.b(), importe_reserva, especialidad, paciente_nombre, paciente_dni, paciente_telefono, paciente_obra_social,
)
fun Turno.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) = TurnoRow(
    id, pacienteId, medicoId, consultorioId, fecha.toString(), horario.hhmm(), tipoTurno.codigo.toLong(), estado.name, asistencia.name,
    sobreturno.l(), primerControl.l(), caja, comentario, otorgadoPor, canceladoPor, recordatorioEnviado.l(), pagado.l(), importeReserva,
    especialidad, pacienteNombre, pacienteDni, pacienteTelefono, pacienteObraSocial, updatedAt, deleted.l(), dirty.l(),
)

fun FeriadoRow.toModel() = Feriado(id, LocalDate.parse(fecha), descripcion)
fun Feriado.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    FeriadoRow(id, fecha.toString(), descripcion, updatedAt, deleted.l(), dirty.l())

fun ObraSocialRow.toModel() = ObraSocial(id, nombre, activo.b())
fun ObraSocial.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    ObraSocialRow(id, nombre, activo.l(), updatedAt, deleted.l(), dirty.l())

fun ObraSocialMedicoRow.toModel() = ObraSocialMedico(id, medico_id, obra_social_id, importe, importe_reserva, activo.b())
fun ObraSocialMedico.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    ObraSocialMedicoRow(id, medicoId, obraSocialId, importe, importeReserva, activo.l(), updatedAt, deleted.l(), dirty.l())

fun MedicoModuloRow.toModel(): MedicoModulo? = ModuloTurnos.porCodigo(modulo.toInt())?.let { MedicoModulo(medico_id, it, activo.b()) }
fun MedicoModulo.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    MedicoModuloRow(medicoId, modulo.codigo.toLong(), activo.l(), updatedAt, deleted.l(), dirty.l())

fun ConfigAgendaRow.toModel() = ConfigAgenda(
    medico_id, ventana_dias.toInt(), valor_consulta,
    cupo_primer_control_json.toStringMap().mapNotNull { (k, v) -> runCatching { DayOfWeek.valueOf(k) to v.toInt() }.getOrNull() }.toMap(),
)
fun ConfigAgenda.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) = ConfigAgendaRow(
    medicoId, ventanaDias.toLong(), valorConsulta, cupoPrimerControl.map { (k, v) -> k.name to v.toString() }.toMap().toJsonText(),
    updatedAt, deleted.l(), dirty.l(),
)

fun MensajeEspecialRow.toModel() = MensajeEspecial(id, medico_id, titulo, descripcion, valido_desde.toLocalDateOrNull(), valido_hasta.toLocalDateOrNull(), activo.b())
fun MensajeEspecial.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) =
    MensajeEspecialRow(id, medicoId, titulo, descripcion, validoDesde?.toString(), validoHasta?.toString(), activo.l(), updatedAt, deleted.l(), dirty.l())

fun RecetaRow.toModel() = Receta(
    id, paciente_id, medico_id, consultorio_id, motivo, EstadoReceta.porCodigo(estado.toInt()), retira_consultorio.b(), comentario,
    archivos.splitCsv(), activo.b(), paciente_nombre, paciente_dni,
)
fun Receta.toRow(updatedAt: Long, deleted: Boolean = false, dirty: Boolean = true) = RecetaRow(
    id, pacienteId, medicoId, consultorioId, motivo, estado.codigo.toLong(), retiraConsultorio.l(), comentario, archivos.joinCsv(),
    activo.l(), pacienteNombre, pacienteDni, updatedAt, deleted.l(), dirty.l(),
)
