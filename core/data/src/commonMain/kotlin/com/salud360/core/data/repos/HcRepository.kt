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
import com.salud360.core.model.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** Un valor histórico de un campo de sección (para "consultas previas"). */
data class ValorHistorico(val fecha: LocalDate, val consultaId: Id, val valor: String)

/** Examen físico con la fecha de la consulta (para curvas de crecimiento y tabla de peso). */
data class ExamenFisicoFechado(val fecha: LocalDate, val examen: ExamenFisico)

/**
 * Historia clínica genérica: consultas, secciones clave-valor, examen físico, antecedentes,
 * registros repetibles, laboratorios, archivos, pendientes, diagnósticos, interconsultores y vacunas.
 * Todas las especialidades usan este mismo repositorio; lo que cambia es la definición de secciones.
 */
class HcRepository(
    private val db: Salud360Db,
    /**
     * Especialidades con API propia (hoy pediatría). El repositorio no habla con la red: solo sabe si
     * hay backend para decidir si además de guardar en el dispositivo hay que traer lo que está en el
     * servidor. Una especialidad sin backend funciona exactamente como antes, solo local.
     */
    private val backends: Map<String, HcBackend> = emptyMap(),
) {
    private val q get() = db.historiaClinicaQueries

    /** true si esa especialidad guarda también en su propio sistema. */
    fun tieneApi(especialidad: String): Boolean = backends.containsKey(especialidad)

    /** Trae del servidor las consultas del paciente y las deja en el dispositivo. Null si no hay API. */
    suspend fun traerConsultasRemotas(pacienteId: Id, medicoId: Id, especialidad: String): List<Consulta>? =
        backends[especialidad]?.traerConsultas(pacienteId, medicoId)

    /** Trae del servidor el contenido de una consulta, sin pisar lo que esté pendiente de enviar. */
    suspend fun traerConsultaRemota(consulta: Consulta) {
        val backend = backends[consulta.especialidad] ?: return
        if (consulta.remotoId.isBlank()) return
        backend.traerConsulta(consulta.id, consulta.remotoId)
    }

    // ---- consultas ----

    fun observarConsultas(pacienteId: Id, especialidad: String): Flow<List<Consulta>> =
        q.consultasDePaciente(pacienteId, especialidad).flujoLista { it.toModel() }

    fun observarConsultasTodas(pacienteId: Id): Flow<List<Consulta>> =
        q.consultasDePacienteTodas(pacienteId).flujoLista { it.toModel() }

    fun observarConsulta(id: Id): Flow<Consulta?> = q.consultaPorId(id).flujoUno { it.toModel() }
    suspend fun consulta(id: Id): Consulta? = q.consultaPorId(id).uno { it.toModel() }
    suspend fun ultimaConsulta(pacienteId: Id, especialidad: String): Consulta? = q.ultimaConsulta(pacienteId, especialidad).uno { it.toModel() }

    /** Devuelve la consulta abierta del médico para ese paciente o crea una nueva (`activo = 2` en las apps originales). */
    suspend fun abrirConsulta(pacienteId: Id, medicoId: Id, especialidad: String, tipo: String, fecha: LocalDate, edadMostrar: String): Consulta {
        q.consultaAbierta(pacienteId, medicoId, especialidad).uno { it.toModel() }?.let { if (it.tipo == tipo) return it }
        val nueva = Consulta(newId(), pacienteId, medicoId, especialidad, tipo, fecha, EstadoConsulta.ABIERTA, edadMostrar)
        q.upsertConsulta(nueva.toRow(ahoraMillis()))
        return nueva
    }

    suspend fun guardarConsulta(consulta: Consulta) = q.upsertConsulta(consulta.toRow(ahoraMillis()))

    /** Cierra la consulta (`establecer_activo`). */
    suspend fun cerrarConsulta(id: Id, fecha: LocalDate? = null, edadMostrar: String? = null) {
        val c = consulta(id) ?: return
        q.upsertConsulta(c.copy(estado = EstadoConsulta.CERRADA, fecha = fecha ?: c.fecha, edadMostrar = edadMostrar ?: c.edadMostrar).toRow(ahoraMillis()))
    }

    suspend fun reabrirConsulta(id: Id) {
        val c = consulta(id) ?: return
        q.upsertConsulta(c.copy(estado = EstadoConsulta.ABIERTA).toRow(ahoraMillis()))
    }

    suspend fun anularConsulta(id: Id) {
        val c = consulta(id) ?: return
        q.upsertConsulta(c.copy(estado = EstadoConsulta.ANULADA).toRow(ahoraMillis()))
    }

    suspend fun contarConsultas(medicoId: Id, desde: LocalDate, hasta: LocalDate): Long =
        q.contarConsultasMedico(medicoId, desde.toString(), hasta.toString()).uno { it } ?: 0

    // ---- secciones clave-valor ----

    fun observarValores(consultaId: Id): Flow<Map<String, Map<String, String>>> =
        q.valoresDeConsulta(consultaId).flujoLista { it.toModel() }.map { lista ->
            lista.groupBy { it.seccion }.mapValues { (_, v) -> v.associate { it.campo to it.valor } }
        }

    fun observarSeccion(consultaId: Id, seccion: String): Flow<Map<String, String>> =
        q.valoresDeSeccion(consultaId, seccion).flujoLista { it.toModel() }.map { l -> l.associate { it.campo to it.valor } }

    suspend fun valoresDeSeccion(consultaId: Id, seccion: String): Map<String, String> =
        q.valoresDeSeccion(consultaId, seccion).lista { it.toModel() }.associate { it.campo to it.valor }

    suspend fun guardarValor(consultaId: Id, seccion: String, campo: String, valor: String) =
        q.upsertSeccionValor(SeccionValor(consultaId, seccion, campo, valor).toRow(ahoraMillis()))

    suspend fun guardarSeccion(consultaId: Id, seccion: String, valores: Map<String, String>) {
        val ahora = ahoraMillis()
        db.transaction {
            valores.forEach { (campo, valor) -> q.upsertSeccionValor(SeccionValor(consultaId, seccion, campo, valor).toRow(ahora)) }
        }
    }

    /** Historial de un campo en consultas anteriores (ej. "Consultas previas" del motivo de consulta). */
    fun observarHistorial(pacienteId: Id, especialidad: String, seccion: String, campo: String): Flow<List<ValorHistorico>> =
        q.historialCampo(pacienteId, especialidad, seccion, campo).flujoLista { ValorHistorico(LocalDate.parse(it.fecha), it.consulta_id, it.valor) }

    /**
     * Último valor conocido de cada campo de una sección en las demás consultas del paciente (se excluye
     * `consultaIdActual`). Sirve para lo que pertenece al paciente y se arrastra entre consultas, como los hitos
     * del desarrollo madurativo.
     */
    fun observarUltimosValores(pacienteId: Id, especialidad: String, seccion: String, consultaIdActual: Id): Flow<Map<String, String>> =
        q.ultimosValoresDeSeccion(pacienteId, especialidad, seccion, consultaIdActual).flujoLista { it.campo to it.valor }
            .map { lista -> lista.distinctBy { it.first }.toMap() }

    // ---- examen físico ----

    fun observarExamenFisico(consultaId: Id): Flow<ExamenFisico?> = q.examenFisicoDeConsulta(consultaId).flujoUno { it.toModel() }
    suspend fun guardarExamenFisico(examen: ExamenFisico) = q.upsertExamenFisico(examen.toRow(ahoraMillis()))

    fun observarExamenesFisicos(pacienteId: Id): Flow<List<ExamenFisicoFechado>> =
        q.examenesFisicosDePaciente(pacienteId).flujoLista {
            ExamenFisicoFechado(
                LocalDate.parse(it.fecha),
                ExamenFisico(
                    it.consulta_id, it.peso, it.peso_percentil, it.talla, it.talla_percentil, it.imc, it.imc_percentil,
                    it.perimetro_cefalico, it.perimetro_cefalico_percentil, it.circunferencia_abdominal, it.tension_arterial,
                    it.frecuencia_cardiaca, it.temperatura, it.saturacion, it.ipd, it.nota,
                ),
            )
        }

    // ---- antecedentes ----

    fun observarAntecedentes(pacienteId: Id, especialidad: String): Flow<List<Antecedente>> =
        q.antecedentesDePaciente(pacienteId, especialidad).flujoLista { it.toModel() }

    fun observarAntecedentes(pacienteId: Id, especialidad: String, categoria: String): Flow<List<Antecedente>> =
        q.antecedentesDeCategoria(pacienteId, especialidad, categoria).flujoLista { it.toModel() }

    suspend fun guardarAntecedente(pacienteId: Id, especialidad: String, categoria: String, clave: String, flag: Boolean, detalle: String, consultaId: Id?) {
        val existente = q.antecedentesDeCategoria(pacienteId, especialidad, categoria).lista { it.toModel() }.firstOrNull { it.clave == clave }
        val a = (existente ?: Antecedente(newId(), pacienteId, especialidad, categoria, clave)).copy(flag = flag, detalle = detalle, consultaId = consultaId)
        q.upsertAntecedente(a.toRow(ahoraMillis()))
    }

    // ---- registros repetibles ----

    fun observarRegistros(pacienteId: Id, especialidad: String, tipo: String): Flow<List<RegistroClinico>> =
        q.registrosDePaciente(pacienteId, especialidad, tipo).flujoLista { it.toModel() }

    fun observarRegistrosDeConsulta(consultaId: Id, tipo: String): Flow<List<RegistroClinico>> =
        q.registrosDeConsulta(consultaId, tipo).flujoLista { it.toModel() }

    /**
     * El vínculo con la API de la especialidad se conserva aunque el que llama arme el registro de
     * cero: la pantalla no lo conoce, y perderlo duplicaría la fila del otro lado en el próximo envío.
     */
    suspend fun guardarRegistro(registro: RegistroClinico) {
        val remoto = registro.remotoId.ifBlank { q.registroPorId(registro.id).uno { it.remoto_id }.orEmpty() }
        q.upsertRegistro(registro.copy(remotoId = remoto).toRow(ahoraMillis()))
    }

    suspend fun eliminarRegistro(id: Id) {
        val r = q.registroPorId(id).uno { it.toModel() } ?: return
        q.upsertRegistro(r.copy(activo = false).toRow(ahoraMillis()))
    }

    // ---- laboratorios ----

    fun observarLaboratorios(pacienteId: Id, especialidad: String, tipo: String): Flow<List<Laboratorio>> =
        q.laboratoriosDePaciente(pacienteId, especialidad, tipo).flujoLista { it.toModel() }

    suspend fun guardarLaboratorio(lab: Laboratorio) = q.upsertLaboratorio(lab.toRow(ahoraMillis()))

    suspend fun eliminarLaboratorio(id: Id) {
        val l = q.laboratorioPorId(id).uno { it.toModel() } ?: return
        q.upsertLaboratorio(l.copy(activo = false).toRow(ahoraMillis()))
    }

    // ---- archivos ----

    fun observarArchivos(pacienteId: Id, seccion: String): Flow<List<Archivo>> = q.archivosDePaciente(pacienteId, seccion).flujoLista { it.toModel() }
    fun observarArchivosDeConsulta(consultaId: Id, seccion: String): Flow<List<Archivo>> = q.archivosDeConsulta(consultaId, seccion).flujoLista { it.toModel() }
    fun observarArchivosDeRegistro(registroId: Id): Flow<List<Archivo>> = q.archivosDeRegistro(registroId).flujoLista { it.toModel() }
    suspend fun archivosPendientesDeSubir(): List<Archivo> = q.archivosPendientesDeSubir().lista { it.toModel() }
    suspend fun archivo(id: Id): Archivo? = q.archivoPorId(id).uno { it.toModel() }
    suspend fun guardarArchivo(archivo: Archivo) = q.upsertArchivo(archivo.toRow(ahoraMillis()))
    suspend fun eliminarArchivo(id: Id) {
        val a = archivo(id) ?: return
        q.upsertArchivo(a.copy(activo = false).toRow(ahoraMillis()))
    }

    // ---- pendientes ----

    fun observarPendientes(pacienteId: Id, medicoId: Id): Flow<List<Pendiente>> = q.pendientesDePaciente(pacienteId, medicoId).flujoLista { it.toModel() }
    fun observarCantidadPendientes(medicoId: Id): Flow<Long> = q.contarPendientesDeMedico(medicoId).flujoUno { it }.map { it ?: 0L }
    suspend fun guardarPendiente(p: Pendiente) = q.upsertPendiente(p.toRow(ahoraMillis()))

    // ---- diagnósticos ----

    fun observarDiagnosticos(medicoId: Id): Flow<List<Diagnostico>> = q.diagnosticosDeMedico(medicoId).flujoLista { it.toModel() }
    fun observarDiagnosticosDeConsulta(consultaId: Id): Flow<List<Diagnostico>> = q.diagnosticosDeConsulta(consultaId).flujoLista { it.toModel() }
    suspend fun guardarDiagnostico(d: Diagnostico) = q.upsertDiagnostico(d.toRow(ahoraMillis()))
    suspend fun asignarDiagnostico(consultaId: Id, diagnosticoId: Id, asignado: Boolean) =
        q.upsertConsultaDiagnostico(ConsultaDiagnostico(consultaId, diagnosticoId).toRow(ahoraMillis(), deleted = !asignado))

    /** Copia los diagnósticos de la última consulta cerrada a la consulta nueva (HC hematología). */
    suspend fun copiarUltimosDiagnosticos(pacienteId: Id, especialidad: String, consultaId: Id) {
        val ultima = ultimaConsulta(pacienteId, especialidad) ?: return
        val ids = q.consultaDiagnosticosDe(ultima.id).lista { it.diagnostico_id }
        ids.forEach { asignarDiagnostico(consultaId, it, true) }
    }

    fun observarPacientesPorDiagnostico(diagnosticoId: Id) = q.pacientesPorDiagnostico(diagnosticoId).flujoLista { it.toModel() }

    // ---- interconsultores ----

    fun observarInterconsultores(medicoId: Id): Flow<List<Interconsultor>> = q.interconsultoresDeMedico(medicoId).flujoLista { it.toModel() }
    suspend fun guardarInterconsultor(i: Interconsultor) = q.upsertInterconsultor(i.toRow(ahoraMillis()))

    // ---- vacunas ----

    fun observarVacunas(pacienteId: Id): Flow<List<VacunaAplicada>> = q.vacunasDePaciente(pacienteId).flujoLista { it.toModel() }
    suspend fun guardarVacuna(v: VacunaAplicada) = q.upsertVacuna(v.toRow(ahoraMillis()))
    suspend fun vacunas(pacienteId: Id): List<VacunaAplicada> = q.vacunasDePaciente(pacienteId).lista { it.toModel() }

    // ---- preferencias del médico ----

    fun observarPreferencias(medicoId: Id): Flow<Map<String, String>> =
        q.preferenciasDeMedico(medicoId).flujoLista { it.toModel() }.map { l -> l.associate { it.clave to it.valor } }
    suspend fun guardarPreferencia(medicoId: Id, clave: String, valor: String) =
        q.upsertPreferencia(MedicoPreferencia(medicoId, clave, valor).toRow(ahoraMillis()))
}
