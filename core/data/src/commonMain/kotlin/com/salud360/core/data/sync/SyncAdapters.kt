package com.salud360.core.data.sync

import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.salud360.core.data.mappers.json
import com.salud360.core.data.mappers.toModel
import com.salud360.core.data.mappers.toRow
import com.salud360.core.database.Salud360Db
import com.salud360.core.model.sync.CambioLocal
import com.salud360.core.model.sync.CambioRemoto
import com.salud360.core.model.sync.Operacion
import com.salud360.core.model.sync.Tablas
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Adaptador genérico: serializa el modelo de dominio (que es `@Serializable`) como payload,
 * y la fila local aporta `updated_at` (versión) y `deleted` (operación).
 *
 * @param R tipo de fila generada por SQLDelight
 * @param M modelo de dominio
 */
class TablaSync<R : Any, M : Any>(
    override val tabla: String,
    private val serializer: KSerializer<M>,
    private val dirtyRows: () -> Query<R>,
    private val rowId: (R) -> String,
    private val rowUpdatedAt: (R) -> Long,
    private val rowDeleted: (R) -> Long,
    private val rowToModel: (R) -> M?,
    private val existente: suspend (String) -> R?,
    private val upsert: suspend (M, Long, Boolean) -> Unit,
    private val limpiar: suspend (String) -> Unit,
) : SyncAdapter {
    override suspend fun dirty(): List<CambioLocal> = dirtyRows().awaitAsList().mapNotNull { r ->
        val m = rowToModel(r) ?: return@mapNotNull null
        CambioLocal(
            tabla = tabla,
            registroId = rowId(r),
            operacion = operacionDe(rowDeleted(r)),
            payload = json.encodeToJsonElement(serializer, m).jsonObject,
            creadoEn = rowUpdatedAt(r),
        )
    }

    override suspend fun aplicar(cambio: CambioRemoto) {
        val local = existente(cambio.registroId)
        // Última escritura gana: si el cambio local es más nuevo y todavía no se envió, se conserva.
        if (local != null && rowUpdatedAt(local) > cambio.version) return
        val modelo = json.decodeFromJsonElement(serializer, cambio.payload)
        upsert(modelo, cambio.version, cambio.operacion == Operacion.DELETE)
    }

    override suspend fun marcarLimpio(registroId: String) = limpiar(registroId)
}

/** Claves compuestas: se unen con "|" en `registroId`. */
private fun clave(vararg partes: String) = partes.joinToString("|")
private fun partes(id: String) = id.split("|")

object SyncAdapters {
    fun todos(db: Salud360Db): List<SyncAdapter> {
        val a = db.authQueries
        val p = db.pacientesQueries
        val h = db.historiaClinicaQueries
        val t = db.turnosQueries
        return listOf(
            // ---- catálogos y usuarios (primero, porque el resto depende de ellos) ----
            TablaSync(Tablas.USUARIOS, com.salud360.core.model.auth.Usuario.serializer(), { a.usuariosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { a.usuarioPorId(it).awaitAsOneOrNull() }, { m, v, d -> a.upsertUsuario(m.toRow(v, d, dirty = false)) }, { a.limpiarUsuario(it) }),
            TablaSync(Tablas.ESPECIALIDADES, com.salud360.core.model.turnos.EspecialidadTurnos.serializer(), { a.especialidadesDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { a.especialidadPorId(it).awaitAsOneOrNull() }, { m, v, d -> a.upsertEspecialidad(m.toRow(v, d, dirty = false)) }, { a.limpiarEspecialidad(it) }),
            TablaSync(Tablas.CONSULTORIOS, com.salud360.core.model.turnos.Consultorio.serializer(), { t.consultoriosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { t.consultorioPorId(it).awaitAsOneOrNull() }, { m, v, d -> t.upsertConsultorio(m.toRow(v, d, dirty = false)) }, { t.limpiarConsultorio(it) }),
            TablaSync(Tablas.MEDICOS, com.salud360.core.model.auth.Medico.serializer(), { a.medicosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { a.medicoPorId(it).awaitAsOneOrNull() }, { m, v, d -> a.upsertMedico(m.toRow(v, d, dirty = false)) }, { a.limpiarMedico(it) }),
            TablaSync(Tablas.SECRETARIAS, com.salud360.core.model.auth.Secretaria.serializer(), { a.secretariasDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { a.secretariaPorId(it).awaitAsOneOrNull() }, { m, v, d -> a.upsertSecretaria(m.toRow(v, d, dirty = false)) }, { a.limpiarSecretaria(it) }),
            TablaSync(Tablas.LICENCIAS, com.salud360.core.model.auth.Licencia.serializer(), { a.licenciasDirty() }, { it.medico_id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { a.licenciaPorMedico(it).awaitAsOneOrNull() }, { m, v, d -> a.upsertLicencia(m.toRow(v, d, dirty = false)) }, { a.limpiarLicencia(it) }),
            // ---- pacientes ----
            TablaSync(Tablas.PACIENTES, com.salud360.core.model.pacientes.Paciente.serializer(), { p.pacientesDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { p.pacientePorId(it).awaitAsOneOrNull() }, { m, v, d -> p.upsertPaciente(m.toRow(v, d, dirty = false)) }, { p.limpiarPaciente(it) }),
            TablaSync(Tablas.PACIENTE_EXTRAS, com.salud360.core.model.pacientes.PacienteExtra.serializer(), { p.pacienteExtrasDirty() }, { clave(it.paciente_id, it.especialidad, it.clave) }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { id -> partes(id).let { k -> p.extrasDePaciente(k[0], k[1]).awaitAsList().firstOrNull { it.clave == k[2] } } },
                { m, v, d -> p.upsertPacienteExtra(m.toRow(v, d, dirty = false)) }, { id -> partes(id).let { k -> p.limpiarPacienteExtra(k[0], k[1], k[2]) } }),
            TablaSync(Tablas.MEDICO_PACIENTES, com.salud360.core.model.pacientes.MedicoPaciente.serializer(), { p.medicoPacientesDirty() }, { clave(it.medico_id, it.paciente_id) }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { id -> partes(id).let { k -> p.vinculo(k[0], k[1]).awaitAsOneOrNull() } },
                { m, v, d -> p.upsertMedicoPaciente(m.toRow(v, d, dirty = false)) }, { id -> partes(id).let { k -> p.limpiarMedicoPaciente(k[0], k[1]) } }),
            // ---- historia clínica ----
            TablaSync(Tablas.CONSULTAS, com.salud360.core.model.hc.Consulta.serializer(), { h.consultasDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { h.consultaPorId(it).awaitAsOneOrNull() }, { m, v, d -> h.upsertConsulta(m.toRow(v, d, dirty = false)) }, { h.limpiarConsulta(it) }),
            TablaSync(Tablas.SECCION_VALORES, com.salud360.core.model.hc.SeccionValor.serializer(), { h.seccionValoresDirty() }, { clave(it.consulta_id, it.seccion, it.campo) }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { id -> partes(id).let { k -> h.valoresDeSeccion(k[0], k[1]).awaitAsList().firstOrNull { it.campo == k[2] } } },
                { m, v, d -> h.upsertSeccionValor(m.toRow(v, d, dirty = false)) }, { id -> partes(id).let { k -> h.limpiarSeccionValor(k[0], k[1], k[2]) } }),
            TablaSync(Tablas.EXAMEN_FISICO, com.salud360.core.model.hc.ExamenFisico.serializer(), { h.examenesFisicosDirty() }, { it.consulta_id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { h.examenFisicoDeConsulta(it).awaitAsOneOrNull() }, { m, v, d -> h.upsertExamenFisico(m.toRow(v, d, dirty = false)) }, { h.limpiarExamenFisico(it) }),
            TablaSync(Tablas.ANTECEDENTES, com.salud360.core.model.hc.Antecedente.serializer(), { h.antecedentesDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> h.upsertAntecedente(m.toRow(v, d, dirty = false)) }, { h.limpiarAntecedente(it) }),
            TablaSync(Tablas.REGISTROS, com.salud360.core.model.hc.RegistroClinico.serializer(), { h.registrosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { h.registroPorId(it).awaitAsOneOrNull() }, { m, v, d -> h.upsertRegistro(m.toRow(v, d, dirty = false)) }, { h.limpiarRegistro(it) }),
            TablaSync(Tablas.LABORATORIOS, com.salud360.core.model.hc.Laboratorio.serializer(), { h.laboratoriosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { h.laboratorioPorId(it).awaitAsOneOrNull() }, { m, v, d -> h.upsertLaboratorio(m.toRow(v, d, dirty = false)) }, { h.limpiarLaboratorio(it) }),
            TablaSync(Tablas.ARCHIVOS, com.salud360.core.model.hc.Archivo.serializer(), { h.archivosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { h.archivoPorId(it).awaitAsOneOrNull() },
                { m, v, d ->
                    // conserva la ruta local si el archivo ya está en el dispositivo
                    val local = h.archivoPorId(m.id).awaitAsOneOrNull()
                    h.upsertArchivo(m.copy(rutaLocal = local?.ruta_local ?: m.rutaLocal).toRow(v, d, dirty = false))
                },
                { h.limpiarArchivo(it) }),
            TablaSync(Tablas.PENDIENTES, com.salud360.core.model.hc.Pendiente.serializer(), { h.pendientesDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> h.upsertPendiente(m.toRow(v, d, dirty = false)) }, { h.limpiarPendiente(it) }),
            TablaSync(Tablas.DIAGNOSTICOS, com.salud360.core.model.hc.Diagnostico.serializer(), { h.diagnosticosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> h.upsertDiagnostico(m.toRow(v, d, dirty = false)) }, { h.limpiarDiagnostico(it) }),
            TablaSync(Tablas.CONSULTA_DIAGNOSTICOS, com.salud360.core.model.hc.ConsultaDiagnostico.serializer(), { h.consultaDiagnosticosDirty() }, { clave(it.consulta_id, it.diagnostico_id) }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { id -> partes(id).let { k -> h.consultaDiagnosticosDe(k[0]).awaitAsList().firstOrNull { it.diagnostico_id == k[1] } } },
                { m, v, d -> h.upsertConsultaDiagnostico(m.toRow(v, d, dirty = false)) }, { id -> partes(id).let { k -> h.limpiarConsultaDiagnostico(k[0], k[1]) } }),
            TablaSync(Tablas.INTERCONSULTORES, com.salud360.core.model.hc.Interconsultor.serializer(), { h.interconsultoresDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> h.upsertInterconsultor(m.toRow(v, d, dirty = false)) }, { h.limpiarInterconsultor(it) }),
            TablaSync(Tablas.VACUNAS, com.salud360.core.model.hc.VacunaAplicada.serializer(), { h.vacunasDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> h.upsertVacuna(m.toRow(v, d, dirty = false)) }, { h.limpiarVacuna(it) }),
            TablaSync(Tablas.PREFERENCIAS, com.salud360.core.model.hc.MedicoPreferencia.serializer(), { h.preferenciasDirty() }, { clave(it.medico_id, it.clave) }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { id -> partes(id).let { k -> h.preferenciasDeMedico(k[0]).awaitAsList().firstOrNull { it.clave == k[1] } } },
                { m, v, d -> h.upsertPreferencia(m.toRow(v, d, dirty = false)) }, { id -> partes(id).let { k -> h.limpiarPreferencia(k[0], k[1]) } }),
            // ---- turnos ----
            TablaSync(Tablas.HORARIOS, com.salud360.core.model.turnos.HorarioMedico.serializer(), { t.horariosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { t.horarioPorId(it).awaitAsOneOrNull() }, { m, v, d -> t.upsertHorario(m.toRow(v, d, dirty = false)) }, { t.limpiarHorario(it) }),
            TablaSync(Tablas.HORARIO_RANGOS, com.salud360.core.model.turnos.HorarioRango.serializer(), { t.horarioRangosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> t.upsertHorarioRango(m.toRow(v, d, dirty = false)) }, { t.limpiarHorarioRango(it) }),
            TablaSync(Tablas.FECHAS_AGREGADAS, com.salud360.core.model.turnos.FechaAgregada.serializer(), { t.fechasAgregadasDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> t.upsertFechaAgregada(m.toRow(v, d, dirty = false)) }, { t.limpiarFechaAgregada(it) }),
            TablaSync(Tablas.TURNOS, com.salud360.core.model.turnos.Turno.serializer(), { t.turnosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { t.turnoPorId(it).awaitAsOneOrNull() }, { m, v, d -> t.upsertTurno(m.toRow(v, d, dirty = false)) }, { t.limpiarTurno(it) }),
            TablaSync(Tablas.FERIADOS, com.salud360.core.model.turnos.Feriado.serializer(), { t.feriadosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> t.upsertFeriado(m.toRow(v, d, dirty = false)) }, { t.limpiarFeriado(it) }),
            TablaSync(Tablas.OBRAS_SOCIALES, com.salud360.core.model.turnos.ObraSocial.serializer(), { t.obrasSocialesDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> t.upsertObraSocial(m.toRow(v, d, dirty = false)) }, { t.limpiarObraSocial(it) }),
            TablaSync(Tablas.OBRA_SOCIAL_MEDICOS, com.salud360.core.model.turnos.ObraSocialMedico.serializer(), { t.obraSocialMedicosDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> t.upsertObraSocialMedico(m.toRow(v, d, dirty = false)) }, { t.limpiarObraSocialMedico(it) }),
            TablaSync(Tablas.MEDICO_MODULOS, com.salud360.core.model.turnos.MedicoModulo.serializer(), { t.medicoModulosDirty() }, { clave(it.medico_id, it.modulo.toString()) }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { id -> partes(id).let { k -> t.modulosDeMedico(k[0]).awaitAsList().firstOrNull { it.modulo.toString() == k[1] } } },
                { m, v, d -> t.upsertMedicoModulo(m.toRow(v, d, dirty = false)) }, { id -> partes(id).let { k -> t.limpiarMedicoModulo(k[0], k[1].toLong()) } }),
            TablaSync(Tablas.CONFIG_AGENDA, com.salud360.core.model.turnos.ConfigAgenda.serializer(), { t.configAgendaDirty() }, { it.medico_id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { t.configDeMedico(it).awaitAsOneOrNull() }, { m, v, d -> t.upsertConfigAgenda(m.toRow(v, d, dirty = false)) }, { t.limpiarConfigAgenda(it) }),
            TablaSync(Tablas.MENSAJES_ESPECIALES, com.salud360.core.model.turnos.MensajeEspecial.serializer(), { t.mensajesDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { null }, { m, v, d -> t.upsertMensajeEspecial(m.toRow(v, d, dirty = false)) }, { t.limpiarMensaje(it) }),
            TablaSync(Tablas.RECETAS, com.salud360.core.model.turnos.Receta.serializer(), { t.recetasDirty() }, { it.id }, { it.updated_at }, { it.deleted }, { it.toModel() },
                { t.recetaPorId(it).awaitAsOneOrNull() }, { m, v, d -> t.upsertReceta(m.toRow(v, d, dirty = false)) }, { t.limpiarReceta(it) }),
        )
    }
}
